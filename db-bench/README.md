# db-bench: ArcadeDB vs SurrealDB

A quick, same-client, same-data comparison of [ArcadeDB](https://arcadedb.com) and
[SurrealDB](https://surrealdb.com) on the workloads both claim: documents with secondary
indexes, a property graph, and vector search. Nothing to do with the HTTP server in the rest
of this repo except the habit of measuring things.

```bash
./fetch.sh                     # ArcadeDB from Maven Central; SurrealDB's binary lifted out of its Docker image
./run.sh                       # both durability modes, every db, one server up at a time, fresh data each run
./run.sh durable -- -d 30s     # flags after -- go to the harness (see `go run . -h`)
DB_LIST=arcadedb ./run.sh relaxed
./summarize.py                 # markdown tables from results/*.json
```

Needs Go, a JDK 21+ and Python 3. `fetch.sh` doesn't need a Docker daemon; it pulls the image
layers from the registry API and takes `/surreal` out of them.

## What it does

`main.go` (stdlib, plus gorilla/websocket) generates a deterministic dataset (seed 42):

- **100k people** `{id, name, age, city, score}`: unique index on `id` (SurrealDB: the record
  id), composite index on `(city, age)`.
- **1M `follows` edges**, 10 per person: half to uniform targets, half to Zipf-chosen hubs, so
  in-degree is heavily skewed.
- **100k 128-dim vectors** in 50 Gaussian clusters, cosine HNSW-style index (`HNSW` in SurrealDB,
  `LSM_VECTOR` in ArcadeDB), plus 200 held-out query vectors.

It bulk loads all of that (batches of 1000 rows, 250 for vectors, 4 parallel loaders, with
retries on write conflicts, which are counted). Then it **checks answers against ground truth
computed in Go**: row and edge counts; for 30 random people their point read, exact
1-hop set, distinct 2-hop and 3-hop counts and a `(city, age)` lookup; per-city
`GROUP BY` counts; and recall@10 over 100 kNN queries against brute force. Then each op runs
closed-loop for 10s (1s warm-up discarded) at 16 concurrent clients, or 4 for the heavy ones,
and reports throughput and latency percentiles.

| op | SurrealDB | ArcadeDB |
|---|---|---|
| `noop` | `RETURN 1` | `SELECT 1` |
| `point_read` | `SELECT * FROM type::record("person", $id)` | `SELECT FROM Person WHERE id = :id` |
| `point_update` | `UPDATE … SET score += 1` | `UPDATE Person SET score = score + 1 WHERE id = :id` |
| `insert_one` | `CREATE type::record("person", $id) CONTENT $doc` | `INSERT INTO Person CONTENT :doc` |
| `index_lookup` | `… WHERE city = $city AND age = $age` | same |
| `agg_group` | `SELECT city, count(), math::mean(score) … GROUP BY city` (full scan) | same, `avg` |
| `graph_1hop` | `SELECT VALUE ->follows->person FROM ONLY …` | `SELECT out("Follows").id …` |
| `graph_2hop`/`3hop` | `array::len(array::distinct(->follows->person->follows->person…))` | `out("Follows").out("Follows")….asSet().size()` |
| `graph_*_cypher` | – | `MATCH (p:Person {id: $id})-[:Follows]->()-[:Follows]->(f) RETURN count(DISTINCT f)` |
| `vector_knn` | `WHERE emb <\|10,100\|> $q ORDER BY d` | `vectorNeighbors("Doc[emb]", :q, 10)` |

Transport: ArcadeDB over its HTTP JSON API (what its own remote driver uses). SurrealDB over
HTTP `/rpc` (`surrealdb`) and over WebSocket RPC (`surrealdb-ws`, what its SDKs use, one
connection per in-flight request). Both use token auth: SurrealDB with basic auth runs argon2
on **every request** (~48ms each here), which would turn this into a password-hashing benchmark.

Durability modes:

- `durable`: fsync on every commit. SurrealDB's default (`surrealkv://…?sync=every`);
  ArcadeDB `-Darcadedb.txWalFlush=2` (its default is 0, no flush).
- `relaxed`: leave flushing to the OS. ArcadeDB's default; SurrealDB `?sync=never`.

## Results

ArcadeDB 26.11.1 (4GB heap, pinned) and SurrealDB 4.0.1 (SurrealKV, defaults), 18 Nov 2026.
Intel Xeon @ 2.10GHz, 4 vCPU (KVM), 16GB RAM, Ubuntu 24.04, OpenJDK 21, client on the same
host. Each cell is ops/s · p50 / p99 latency. Raw JSON in `results/`.

#### durable

| | arcadedb 26.11.1 | surrealdb 4.0.1 | surrealdb-ws 4.0.1 |
|---|---|---|---|
| load persons (rows/s) | 42,819 | 16,772 | 25,270 |
| load edges (rows/s) | 25,396 (610 retries) | 52,254 | 57,249 |
| load docs (rows/s) | 31,202 | 4,462 | 4,997 |
| noop (c=16) | 12,780/s · 0.8 / 6.9ms | 4,044/s · 3.3 / 12.0ms | 6,380/s · 2.1 / 7.2ms |
| point_read (c=16) | 13,430/s · 0.9 / 6.2ms | 5,310/s · 2.6 / 8.5ms | 7,957/s · 1.7 / 5.9ms |
| point_update (c=16) | 3,302/s · 4.0 / 13.6ms | 1,299/s · 10.4 / 36.7ms | 1,299/s · 10.6 / 35.1ms |
| insert_one (c=16) | 2,082/s · 6.7 / 37.1ms | 1,041/s · 10.1 / 96.8ms | 1,177/s · 9.6 / 96.8ms |
| index_lookup (c=16) | 7,015/s · 1.9 / 8.6ms | 3,246/s · 4.5 / 11.5ms | 3,597/s · 4.1 / 10.7ms |
| agg_group (c=4) | 70/s · 55.3 / 113.6ms | 48/s · 75.6 / 129.9ms | 46/s · 79.3 / 139.5ms |
| graph_1hop (c=16) | 11,177/s · 1.2 / 6.1ms | 6,013/s · 2.5 / 6.0ms | 9,952/s · 1.5 / 3.7ms |
| graph_2hop (c=16) | 8,072/s · 1.6 / 7.4ms | 4,487/s · 3.2 / 11.2ms | 6,736/s · 2.1 / 7.3ms |
| graph_3hop (c=4) | 2,036/s · 1.5 / 7.3ms | 1,582/s · 2.3 / 5.7ms | 1,848/s · 1.9 / 4.5ms |
| graph_2hop_cypher (c=16) | 5,750/s · 2.2 / 11.4ms | – | – |
| graph_3hop_cypher (c=4) | 1,204/s · 2.7 / 9.4ms | – | – |
| vector_knn (c=4) | 1,514/s · 2.0 / 8.1ms | 1,490/s · 2.5 / 5.2ms | 1,705/s · 2.2 / 4.6ms |
| vector recall@10 | 0.985 | 0.996 | 0.997 |
| first kNN query after load | 29.3s | 0.8s | 0.1s |
| correctness checks failed | 0/213 | 0/153 | 0/153 |
| server peak RSS / disk | 3,644MB / 206MB | 908MB / 508MB | 934MB / 508MB |

#### relaxed

| | arcadedb 26.11.1 | surrealdb 4.0.1 | surrealdb-ws 4.0.1 |
|---|---|---|---|
| load persons (rows/s) | 38,381 | 27,277 | 20,071 |
| load edges (rows/s) | 24,947 (638 retries) | 72,480 | 66,141 |
| load docs (rows/s) | 28,293 | 5,910 | 5,468 |
| noop (c=16) | 17,707/s · 0.6 / 5.4ms | 4,274/s · 3.2 / 11.6ms | 6,819/s · 2.0 / 7.3ms |
| point_read (c=16) | 15,159/s · 0.8 / 5.7ms | 4,683/s · 2.9 / 9.9ms | 8,022/s · 1.7 / 6.2ms |
| point_update (c=16) | 4,972/s · 2.5 / 10.3ms | 1,625/s · 8.0 / 33.4ms | 1,534/s · 8.7 / 33.8ms |
| insert_one (c=16) | 2,436/s · 6.0 / 13.6ms | 1,709/s · 7.9 / 28.6ms | 1,450/s · 8.8 / 35.7ms |
| index_lookup (c=16) | 7,422/s · 1.7 / 8.5ms | 2,942/s · 5.0 / 12.7ms | 2,599/s · 5.5 / 17.4ms |
| agg_group (c=4) | 82/s · 47.0 / 82.6ms | 46/s · 79.4 / 146.9ms | 37/s · 97.8 / 200.1ms |
| graph_1hop (c=16) | 13,424/s · 0.9 / 5.8ms | 6,040/s · 2.5 / 6.0ms | 8,671/s · 1.7 / 4.4ms |
| graph_2hop (c=16) | 10,090/s · 1.2 / 6.9ms | 4,134/s · 3.4 / 12.8ms | 5,758/s · 2.5 / 8.3ms |
| graph_3hop (c=4) | 2,527/s · 1.2 / 6.7ms | 1,472/s · 2.5 / 5.8ms | 1,594/s · 2.4 / 5.5ms |
| graph_2hop_cypher (c=16) | 5,829/s · 2.2 / 10.4ms | – | – |
| graph_3hop_cypher (c=4) | 1,253/s · 2.6 / 9.0ms | – | – |
| vector_knn (c=4) | 1,605/s · 1.9 / 8.2ms | 1,535/s · 2.4 / 5.1ms | 1,517/s · 2.5 / 5.5ms |
| vector recall@10 | 0.938 | 0.996 | 0.996 |
| first kNN query after load | 27.9s | 0.5s | 0.1s |
| correctness checks failed | 0/213 | 0/153 | 0/153 |
| server peak RSS / disk | 3,622MB / 207MB | 907MB / 510MB | 917MB / 510MB |

### Reading it

**One run per cell on a shared 4-vCPU box, so treat ±20% as noise.** A pilot run (20k
64-dim vectors, not kept) had ArcadeDB's point reads at ~5k/s instead of ~14k/s. My guess is
that its vector index was still building in the background then, but I haven't confirmed it.
The client used ~0.8 of a core during ArcadeDB's `noop` and ~0.2 during SurrealDB's; the
servers were at 300–350%, so the server is the limit, not the harness.

- **Request overhead decides most of the small-op numbers.** `noop` is the floor: ArcadeDB
  turns round a trivial HTTP query at 13–18k/s, SurrealDB at ~4k/s over HTTP and ~6.5k/s over
  WebSocket, with the server CPU-bound either way. Point reads, 1-hop and index lookups all
  sit close to those ceilings. If you talk to SurrealDB, use the WebSocket/SDK path. (Its
  `RETURN 1` is slower than a point read, oddly.)
- **Graph traversal: ArcadeDB is ahead,** by 1.1–2.4×, mostly from cheaper requests.
  SurrealDB-over-WebSocket has the tighter p99 on 1- and 3-hop. ArcadeDB's Cypher is 1.5–2×
  slower than its SQL for the same traversal. Its `MATCH` also never reuses an edge within a
  path (standard Cypher), so 3-hop counts can be 1 lower than plain reachability on cycles
  like a→b→a→b. The pilot run hit exactly that. Also, ArcadeDB's chained `out().out()`
  doesn't deduplicate: without `.asSet()` it returned 100 for a node with 86 distinct
  friends-of-friends.
- **Single-row writes: ArcadeDB 1.4–3.2× faster.** SurrealDB lands at ~1.3–1.7k/s whether or
  not it fsyncs, so its commit path rather than the disk is the limit. Its `insert_one` p99
  (~97ms durable) is the worst tail in the table.
- **Bulk graph load: SurrealDB 2–3× faster, with zero conflicts.** ArcadeDB keeps edge lists
  on the vertices, so concurrent batches touching the same Zipf hubs hit optimistic-MVCC
  conflicts (~600 batch retries per 1M edges). Load serially, or partition by target, if this
  matters to you. Plain document and vector inserts go the other way: ArcadeDB is 1.4–7×
  faster, since SurrealDB maintains its HNSW graph on insert.
- **Vectors: similar query throughput (~1.5–1.7k/s at c=4), but different trade-offs.**
  ArcadeDB builds the index lazily, so the **first kNN query after loading 100k vectors took
  28–29s**, and recall@10 came out 0.94–0.985 with defaults (it varied between builds).
  SurrealDB pays at insert time, answers its first query in well under a second and hit
  0.996 recall at `ef=100`.
- **Full-scan aggregation (100k rows): ArcadeDB 1.5–1.8× faster** (70–82/s vs 37–48/s).
  Neither is an analytics engine: at 50–100ms per 100k-row `GROUP BY` (c=4), use
  something columnar if that's the job.
- **Footprint:** ArcadeDB used 3.6GB RSS, but that's the pinned 4GB heap, not need. It's JVM,
  so size the heap deliberately. SurrealDB used ~0.9GB. On disk, ArcadeDB is ~2.5× smaller
  (207MB vs 510MB).

### What each one suits, on this evidence

- **ArcadeDB**: read-heavy graph + document workloads on one node, where it wins most ops by
  1.1–3×. Also anywhere you want SQL, Cypher, Gremlin, or Postgres/Mongo/Redis wire protocols
  over the same data, or to embed it in a JVM app (which skips HTTP entirely, so it should do
  far better than this; not measured). Watch out for write conflicts on hub vertices, the
  lazy vector-index build (warm it before taking traffic), and heap sizing.
- **SurrealDB**: an application backend that wants one store with record links, graph edges,
  vectors, auth/permissions and live queries, where a few thousand requests per second per
  node is plenty. It is better at bulk-loading graph edges, gets vector recall right out of
  the box, and needs a small memory footprint. Use the WebSocket/SDK path, and expect writes
  and the per-request cost to be the bottlenecks, not fsync.

Not tested: embedded mode for either (ArcadeDB in-JVM, SurrealDB via the Rust SDK), HA or
clustering (ArcadeDB Raft, SurrealDB on TiKV), larger-than-memory data, long runs, or the
Postgres-wire endpoints both now expose.
