# db-bench: ArcadeDB vs SurrealDB vs FalkorDB

A quick, same-client, same-data comparison of [ArcadeDB](https://arcadedb.com),
[SurrealDB](https://surrealdb.com) and [FalkorDB](https://www.falkordb.com) on the workloads
all three claim: documents/properties with secondary indexes, a property graph, and vector
search. Nothing to do with the HTTP server in the rest of this repo except the habit of
measuring things.

```bash
./fetch.sh                     # see below for where each binary comes from
./run.sh                       # both durability modes, every db, one server up at a time, fresh data each run
./run.sh durable -- -d 30s     # flags after -- go to the harness (see `go run . -h`)
DB_LIST="arcadedb falkordb" ./run.sh relaxed
./summarize.py                 # markdown tables from results/*.json
```

Needs Go, a JDK 21+ and Python 3; no Docker daemon. `fetch.sh` takes ArcadeDB from Maven
Central. It pulls SurrealDB's Docker image layers from the registry API and takes `/surreal`
out of them. It takes FalkorDB's `redis-server` and `falkordb.so` out of the `falkordblite`
wheel on PyPI, since Docker Hub rate-limits anonymous pulls. That wheel's Redis is built with
libc malloc rather than jemalloc, which the official image may not match.

## What it does

`main.go` and `falkor.go` (Go, plus gorilla/websocket and go-redis) generate a deterministic
dataset (seed 42):

- **100k people** `{id, name, age, city, score}`: index on `id` (SurrealDB: the record id),
  composite index on `(city, age)` (FalkorDB: range indexes on both).
- **1M `follows` edges**, 10 per person: half to uniform targets, half to Zipf-chosen hubs, so
  in-degree is heavily skewed.
- **100k 128-dim vectors** in 50 Gaussian clusters, cosine HNSW index (`HNSW` in SurrealDB,
  `LSM_VECTOR` in ArcadeDB, `VECTOR INDEX` in FalkorDB), plus 200 held-out query vectors.

It bulk loads all of that (batches of 1000 rows, 250 for vectors, 4 parallel loaders, with
retries on write conflicts, which are counted). Then it **checks answers against ground truth
computed in Go**: row and edge counts; for 30 random people their point read, exact
1-hop set, distinct 2-hop and 3-hop counts and a `(city, age)` lookup; per-city
`GROUP BY` counts; and recall@10 over 100 kNN queries against brute force. Then each op runs
closed-loop for 10s (1s warm-up discarded) at 16 concurrent clients, or 4 for the heavy ones,
and reports throughput and latency percentiles.

| op | SurrealDB | ArcadeDB | FalkorDB (Cypher) |
|---|---|---|---|
| `noop` | `RETURN 1` | `SELECT 1` | `RETURN 1` |
| `point_read` | `SELECT * FROM type::record("person", $id)` | `SELECT FROM Person WHERE id = :id` | `MATCH (p:Person {id: $id}) RETURN p.id, …` |
| `point_update` | `UPDATE … SET score += 1` | `UPDATE Person SET score = score + 1 WHERE id = :id` | `MATCH … SET p.score = p.score + 1` |
| `insert_one` | `CREATE type::record("person", $id) CONTENT $doc` | `INSERT INTO Person CONTENT :doc` | `CREATE (:Person {id: $id, name: $doc.name, …})` |
| `index_lookup` | `… WHERE city = $city AND age = $age` | same | same |
| `agg_group` | `SELECT city, count(), math::mean(score) … GROUP BY city` (full scan) | same, `avg` | `MATCH (p:Person) RETURN p.city, count(p), avg(p.score)` |
| `graph_1hop` | `SELECT VALUE ->follows->person FROM ONLY …` | `SELECT out("Follows").id …` | `MATCH (p)-[:Follows]->(f) RETURN f.id` |
| `graph_2hop`/`3hop` | `array::len(array::distinct(->follows->person->follows->person…))` | `out("Follows").out("Follows")….asSet().size()` | `MATCH (p)-[:Follows]->(a) WITH DISTINCT a MATCH (a)-[:Follows]->(f) RETURN count(DISTINCT f)` |
| `graph_*_cypher` | – | `MATCH (p:Person {id: $id})-[:Follows]->()-[:Follows]->(f) RETURN count(DISTINCT f)` | – |
| `vector_knn` | `WHERE emb <\|10,100\|> $q ORDER BY d` | `vectorNeighbors("Doc[emb]", :q, 10)` | `CALL db.idx.vector.queryNodes('Doc', 'emb', 10, vecf32($q))` |

Transport:

- **ArcadeDB**: its HTTP JSON API, which is what its own remote driver uses.
- **SurrealDB**: HTTP `/rpc` (`surrealdb`) and WebSocket RPC (`surrealdb-ws`, what its SDKs
  use), with one connection per in-flight request.
- **FalkorDB**: RESP (`GRAPH.QUERY`, or `GRAPH.RO_QUERY` for reads, `--compact`), through a
  pooled go-redis client. Parameters go in its `CYPHER k=v …` prefix as Cypher literals.

ArcadeDB and SurrealDB use token auth, because SurrealDB with basic auth runs argon2 on
**every request** (~48ms each here). That would turn this into a password-hashing benchmark.
FalkorDB runs without a password.

Settings that differ from the defaults:

- **SurrealDB's vector search uses `ef=100`.** FalkorDB's `efRuntime` is set to the same 100;
  its default of 10 gave **recall@10 of 0.56** here.
- **FalkorDB's single-row insert is `CREATE (:Person {id: $id, …})`, not `CREATE (p) SET p = $doc`.**
  The map-assignment form cost ~2ms more per insert.

Durability modes:

- `durable`: fsync on every commit.
  - SurrealDB: its default (`surrealkv://…?sync=every`).
  - ArcadeDB: `-Darcadedb.txWalFlush=2` (its default is 0, no flush).
  - FalkorDB: Redis AOF with `appendfsync always`, which Redis group-commits per event-loop
    turn.
- `relaxed`: leave flushing to the OS.
  - ArcadeDB: its default.
  - SurrealDB: `?sync=never`.
  - FalkorDB: AOF with `appendfsync no`.

  RDB snapshots are off throughout.

## Results

ArcadeDB 26.11.1 (4GB heap, pinned), SurrealDB 4.0.1 (SurrealKV, defaults) and FalkorDB
4.22.1 (Redis 8.10.2, defaults apart from the above), 18 Nov 2026. Intel Xeon @ 2.10GHz, 4
vCPU (KVM), 16GB RAM, Ubuntu 24.04, OpenJDK 21, client on the same host. Each cell is
ops/s · p50 / p99 latency. Raw JSON in `results/`.

#### durable

| | arcadedb 26.11.1 | surrealdb 4.0.1 | surrealdb-ws 4.0.1 | falkordb 4.22.1 |
|---|---|---|---|---|
| load persons (rows/s) | 42,819 | 16,772 | 25,270 | 70,733 |
| load edges (rows/s) | 25,396 (610 retries) | 52,254 | 57,249 | 13,809 |
| load docs (rows/s) | 31,202 | 4,462 | 4,997 | 2,488 |
| noop (c=16) | 12,780/s · 0.8 / 6.9ms | 4,044/s · 3.3 / 12.0ms | 6,380/s · 2.1 / 7.2ms | 38,414/s · 0.3 / 1.4ms |
| point_read (c=16) | 13,430/s · 0.9 / 6.2ms | 5,310/s · 2.6 / 8.5ms | 7,957/s · 1.7 / 5.9ms | 22,631/s · 0.6 / 2.1ms |
| point_update (c=16) | 3,302/s · 4.0 / 13.6ms | 1,299/s · 10.4 / 36.7ms | 1,299/s · 10.6 / 35.1ms | 1,852/s · 8.2 / 16.9ms |
| insert_one (c=16) | 2,082/s · 6.7 / 37.1ms | 1,041/s · 10.1 / 96.8ms | 1,177/s · 9.6 / 96.8ms | 2,116/s · 7.3 / 15.6ms |
| index_lookup (c=16) | 7,015/s · 1.9 / 8.6ms | 3,246/s · 4.5 / 11.5ms | 3,597/s · 4.1 / 10.7ms | 5,335/s · 2.9 / 6.5ms |
| agg_group (c=4) | 70/s · 55.3 / 113.6ms | 48/s · 75.6 / 129.9ms | 46/s · 79.3 / 139.5ms | 92/s · 41.0 / 66.4ms |
| graph_1hop (c=16) | 11,177/s · 1.2 / 6.1ms | 6,013/s · 2.5 / 6.0ms | 9,952/s · 1.5 / 3.7ms | 16,519/s · 0.9 / 2.7ms |
| graph_2hop (c=16) | 8,072/s · 1.6 / 7.4ms | 4,487/s · 3.2 / 11.2ms | 6,736/s · 2.1 / 7.3ms | 12,042/s · 1.3 / 2.9ms |
| graph_3hop (c=4) | 2,036/s · 1.5 / 7.3ms | 1,582/s · 2.3 / 5.7ms | 1,848/s · 1.9 / 4.5ms | 2,805/s · 1.3 / 3.5ms |
| graph_2hop_cypher (c=16) | 5,750/s · 2.2 / 11.4ms | – | – | – |
| graph_3hop_cypher (c=4) | 1,204/s · 2.7 / 9.4ms | – | – | – |
| vector_knn (c=4) | 1,514/s · 2.0 / 8.1ms | 1,490/s · 2.5 / 5.2ms | 1,705/s · 2.2 / 4.6ms | 5,440/s · 0.7 / 1.9ms |
| vector recall@10 | 0.985 | 0.996 | 0.997 | 0.984 |
| first kNN query after load | 29.3s | 0.8s | 0.1s | 0.0s |
| correctness checks failed | 0/213 | 0/153 | 0/153 | 0/153 |
| server peak RSS / disk | 3,644MB / 206MB | 908MB / 508MB | 934MB / 508MB | 354MB / 78MB |

#### relaxed

| | arcadedb 26.11.1 | surrealdb 4.0.1 | surrealdb-ws 4.0.1 | falkordb 4.22.1 |
|---|---|---|---|---|
| load persons (rows/s) | 38,381 | 27,277 | 20,071 | 116,894 |
| load edges (rows/s) | 24,947 (638 retries) | 72,480 | 66,141 | 17,357 |
| load docs (rows/s) | 28,293 | 5,910 | 5,468 | 3,049 |
| noop (c=16) | 17,707/s · 0.6 / 5.4ms | 4,274/s · 3.2 / 11.6ms | 6,819/s · 2.0 / 7.3ms | 39,990/s · 0.3 / 1.4ms |
| point_read (c=16) | 15,159/s · 0.8 / 5.7ms | 4,683/s · 2.9 / 9.9ms | 8,022/s · 1.7 / 6.2ms | 23,913/s · 0.6 / 2.1ms |
| point_update (c=16) | 4,972/s · 2.5 / 10.3ms | 1,625/s · 8.0 / 33.4ms | 1,534/s · 8.7 / 33.8ms | 6,774/s · 2.3 / 4.2ms |
| insert_one (c=16) | 2,436/s · 6.0 / 13.6ms | 1,709/s · 7.9 / 28.6ms | 1,450/s · 8.8 / 35.7ms | 8,673/s · 1.7 / 4.1ms |
| index_lookup (c=16) | 7,422/s · 1.7 / 8.5ms | 2,942/s · 5.0 / 12.7ms | 2,599/s · 5.5 / 17.4ms | 4,681/s · 3.2 / 7.6ms |
| agg_group (c=4) | 82/s · 47.0 / 82.6ms | 46/s · 79.4 / 146.9ms | 37/s · 97.8 / 200.1ms | 48/s · 78.5 / 119.8ms |
| graph_1hop (c=16) | 13,424/s · 0.9 / 5.8ms | 6,040/s · 2.5 / 6.0ms | 8,671/s · 1.7 / 4.4ms | 16,792/s · 0.9 / 2.7ms |
| graph_2hop (c=16) | 10,090/s · 1.2 / 6.9ms | 4,134/s · 3.4 / 12.8ms | 5,758/s · 2.5 / 8.3ms | 9,129/s · 1.6 / 4.2ms |
| graph_3hop (c=4) | 2,527/s · 1.2 / 6.7ms | 1,472/s · 2.5 / 5.8ms | 1,594/s · 2.4 / 5.5ms | 2,505/s · 1.4 / 4.1ms |
| graph_2hop_cypher (c=16) | 5,829/s · 2.2 / 10.4ms | – | – | – |
| graph_3hop_cypher (c=4) | 1,253/s · 2.6 / 9.0ms | – | – | – |
| vector_knn (c=4) | 1,605/s · 1.9 / 8.2ms | 1,535/s · 2.4 / 5.1ms | 1,517/s · 2.5 / 5.5ms | 4,917/s · 0.7 / 2.1ms |
| vector recall@10 | 0.938 | 0.996 | 0.996 | 0.984 |
| first kNN query after load | 27.9s | 0.5s | 0.1s | 0.0s |
| correctness checks failed | 0/213 | 0/153 | 0/153 | 0/153 |
| server peak RSS / disk | 3,622MB / 207MB | 907MB / 510MB | 917MB / 510MB | 354MB / 91MB |

### Reading it

**One run per cell on a shared 4-vCPU box, so treat ±20% as noise.** FalkorDB's
`agg_group`, for one, came out at 92/s in one mode and 48/s in the other. A pilot run (20k
64-dim vectors, not kept) had ArcadeDB's point reads at ~5k/s instead of ~14k/s. My guess is
that its vector index was still building in the background then, but I haven't confirmed it.
The servers ran at 300–350% CPU and the client at ≤0.8 of a core, so the server is the limit,
not the harness.

- **Request overhead decides most of the small-op numbers.** `noop` is the floor:
  - FalkorDB: ~40k/s over RESP.
  - ArcadeDB: 13–18k/s over HTTP.
  - SurrealDB: ~4k/s over HTTP and ~6.5k/s over WebSocket, with its server CPU-bound either
    way. If you talk to SurrealDB, use the WebSocket/SDK path. (Its `RETURN 1` is slower than
    a point read, oddly.)
- **FalkorDB: fastest reads by a distance.** Point reads run at ~23k/s with p99 around 2ms;
  1–3-hop traversal ranges from level with ArcadeDB's SQL to 1.5× ahead, and is 1.5–1.9×
  SurrealDB-over-WebSocket, with the tightest tails. Vector kNN runs at ~5k/s, 3× the others, with recall 0.984 once
  `efRuntime` is raised. It's an in-memory graph (sparse matrices), so the whole dataset sat
  in 354MB of RSS, and its AOF on disk was the smallest at 78–91MB. The `(city, age)` lookup is
  the one read where ArcadeDB beats it, and neither of the other two is far behind there.
- **FalkorDB is slow where writes are batched.** Writes to a graph are serialized, so the 4
  parallel loaders don't help: 1M edges loaded at 14–17k rows/s (slowest of the three) and
  vectors at 2.5–3k/s (slowest; HNSW maintained on insert). Single-row writes depend on fsync
  more than they do elsewhere: 1.9–2.1k/s with `appendfsync always`, 6.8–8.7k/s without.
  That's the best of the three relaxed, and level with ArcadeDB durable.
- **Graph traversal: ArcadeDB is ahead of SurrealDB,** by 1.1–2.4×, mostly from cheaper
  requests.
  - SurrealDB-over-WebSocket has the tighter p99 on 1- and 3-hop.
  - ArcadeDB's Cypher is 1.5–2× slower than its SQL for the same traversal.
  - Its single-pattern `MATCH` never reuses an edge within a path (standard Cypher), so 3-hop
    counts can be 1 lower than plain reachability on cycles like a→b→a→b. The pilot run hit
    exactly that. FalkorDB's queries avoid it by splitting the pattern into `WITH DISTINCT`
    stages, which is also cheaper.
  - ArcadeDB's chained `out().out()` doesn't deduplicate: without `.asSet()` it returned 100
    for a node with 86 distinct friends-of-friends.
- **Single-row writes, durable: ArcadeDB 1.4–3.2× faster than SurrealDB,** with FalkorDB in
  between on updates and level with ArcadeDB on inserts. SurrealDB lands at ~1.3–1.7k/s
  whether or not it fsyncs, so its commit path rather than the disk is the limit. Its
  `insert_one` p99 (~97ms durable) is the worst tail in the table.
- **Bulk graph load: SurrealDB is 2–5× faster than the others, with zero conflicts.**
  ArcadeDB keeps edge lists on the vertices, so concurrent batches touching the same Zipf hubs
  hit optimistic-MVCC conflicts (~600 batch retries per 1M edges). Load serially, or
  partition by target, if this matters to you. FalkorDB doesn't conflict, it just queues. For
  plain node/document inserts, FalkorDB (70–117k rows/s) and ArcadeDB lead. For vectors,
  ArcadeDB is ~5–12× faster than the other two, because it defers building the index.
- **Vectors: ArcadeDB's lazy build is the trap.** The **first kNN query after loading 100k
  vectors took 28–29s**, and recall@10 came out 0.94–0.985 with defaults (it varied between
  builds). SurrealDB and FalkorDB pay at insert time and answer straight away (recall 0.996 and
  0.984 at ef 100).
- **Full-scan aggregation (100k rows): all within ~2.5× of each other** (37–92/s at c=4). None is
  an analytics engine: at 40–100ms per 100k-row `GROUP BY`, use something columnar if that's
  the job.
- **Footprint:**
  - FalkorDB: 354MB RSS, 78–91MB on disk.
  - SurrealDB: ~0.9GB RSS, 510MB on disk.
  - ArcadeDB: 3.6GB RSS, 207MB on disk. The RSS is the pinned 4GB heap rather than need, but
    it's JVM, so size the heap deliberately.

### What each one suits, on this evidence

- **FalkorDB**: low-latency graph reads and vector search (GraphRAG-style lookups, recommendations, fraud
  hops) on a graph that fits in RAM, if you're happy running Redis. It's the fastest reader
  here by a wide margin and the smallest footprint. Expect single-writer throughput,
  the slowest bulk edge and vector loads, and fsync-bound durable writes. Raise `efRuntime`,
  or vector recall is poor. Cypher only; it's a graph store, not a document database.
- **ArcadeDB**: read-heavy graph + document workloads on one node that may outgrow RAM, where
  it beats SurrealDB on most ops by 1.1–3×. Also anywhere you want SQL, Cypher, Gremlin, or
  Postgres/Mongo/Redis wire protocols over the same data, or to embed it in a JVM app (which
  skips HTTP entirely, so it should do far better than this; not measured). Watch out for
  write conflicts on hub vertices, the lazy vector-index build (warm it before taking
  traffic), and heap sizing.
- **SurrealDB**: an application backend that wants one store with record links, graph edges,
  vectors, auth/permissions and live queries, where a few thousand requests per second per
  node is plenty. It's the best at bulk-loading graph edges, gets vector recall right out of
  the box, and has a modest footprint. Use the WebSocket/SDK path, and expect writes and the
  per-request cost to be the bottlenecks, not fsync.

Not tested: embedded mode (ArcadeDB in-JVM, SurrealDB via the Rust SDK, FalkorDB via
falkordblite), HA or clustering (ArcadeDB Raft, SurrealDB on TiKV, FalkorDB replication),
larger-than-memory data (which FalkorDB can't do by design), long runs, or the Postgres-wire
endpoints of ArcadeDB and SurrealDB.
