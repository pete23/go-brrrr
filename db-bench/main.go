// db-bench: ArcadeDB vs SurrealDB over their HTTP APIs, same data, same client.
//
// Workloads: bulk load (documents, graph edges, vectors), point read/update/insert,
// composite-index lookup, full-table aggregation, 1/2/3-hop graph traversal and
// vector kNN. Every query's answer is first checked against ground truth computed
// here from the generated data, so a fast wrong answer can't win.
package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"math"
	"math/rand"
	"net/http"
	"os"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
)

// ---------------------------------------------------------------- data

type Person struct {
	ID    int64   `json:"id"`
	Name  string  `json:"name"`
	Age   int     `json:"age"`
	City  int     `json:"city"`
	Score float64 `json:"score"`
}

type Edge struct {
	A int64 `json:"a"`
	B int64 `json:"b"`
}

type Doc struct {
	ID  int64     `json:"id"`
	Emb []float32 `json:"emb"`
}

type Dataset struct {
	Persons []Person
	Edges   []Edge
	Adj     [][]int64
	Docs    []Doc
	Queries [][]float32
	Cities  int
}

func generate(n, k, docs, dim, cities int, seed int64) *Dataset {
	r := rand.New(rand.NewSource(seed))
	d := &Dataset{Cities: cities, Adj: make([][]int64, n)}
	for i := 0; i < n; i++ {
		d.Persons = append(d.Persons, Person{ID: int64(i), Name: "user" + strconv.Itoa(i),
			Age: 18 + r.Intn(63), City: r.Intn(cities), Score: math.Round(r.Float64()*1e4) / 100})
	}
	// Half the follows are uniform, half go to Zipf-distributed hubs (in-degree skew).
	perm := r.Perm(n)
	z := rand.NewZipf(r, 1.2, 1, uint64(n-1))
	for i := 0; i < n; i++ {
		seen := map[int64]bool{}
		for len(seen) < k {
			var t int64
			if r.Intn(2) == 0 {
				t = int64(r.Intn(n))
			} else {
				t = int64(perm[z.Uint64()])
			}
			if t == int64(i) || seen[t] {
				continue
			}
			seen[t] = true
			d.Edges = append(d.Edges, Edge{int64(i), t})
			d.Adj[i] = append(d.Adj[i], t)
		}
	}
	// Vectors: Gaussian clusters, so kNN has structure to find.
	centroids := make([][]float32, 50)
	for c := range centroids {
		centroids[c] = randVec(r, dim, 1)
	}
	noisy := func() []float32 {
		c := centroids[r.Intn(len(centroids))]
		v := randVec(r, dim, 0.35)
		for j := range v {
			v[j] += c[j]
		}
		return v
	}
	for i := 0; i < docs; i++ {
		d.Docs = append(d.Docs, Doc{ID: int64(i), Emb: noisy()})
	}
	for i := 0; i < 200; i++ {
		d.Queries = append(d.Queries, noisy())
	}
	return d
}

func randVec(r *rand.Rand, dim int, s float64) []float32 {
	v := make([]float32, dim)
	for j := range v {
		v[j] = float32(math.Round(r.NormFloat64()*s*1e4) / 1e4)
	}
	return v
}

func (d *Dataset) hops(id int64, depth int) int {
	cur := map[int64]bool{id: true}
	for h := 0; h < depth; h++ {
		next := map[int64]bool{}
		for v := range cur {
			for _, t := range d.Adj[v] {
				next[t] = true
			}
		}
		cur = next
	}
	return len(cur)
}

func cosDist(a, b []float32) float64 {
	var dot, na, nb float64
	for i := range a {
		dot += float64(a[i]) * float64(b[i])
		na += float64(a[i]) * float64(a[i])
		nb += float64(b[i]) * float64(b[i])
	}
	return 1 - dot/math.Sqrt(na*nb)
}

func (d *Dataset) knn(q []float32, k int) []int64 {
	type sd struct {
		id int64
		d  float64
	}
	all := make([]sd, len(d.Docs))
	for i, doc := range d.Docs {
		all[i] = sd{doc.ID, cosDist(q, doc.Emb)}
	}
	sort.Slice(all, func(i, j int) bool { return all[i].d < all[j].d })
	out := make([]int64, k)
	for i := range out {
		out[i] = all[i].id
	}
	return out
}

// ---------------------------------------------------------------- HTTP

var client = &http.Client{Timeout: 10 * time.Minute, Transport: &http.Transport{
	Proxy: nil, MaxIdleConns: 1024, MaxIdleConnsPerHost: 1024, DisableCompression: true,
	IdleConnTimeout: 90 * time.Second,
}}

func post(url string, hdr map[string]string, body any) ([]byte, int, error) {
	b, err := json.Marshal(body)
	if err != nil {
		return nil, 0, err
	}
	req, _ := http.NewRequest("POST", url, bytes.NewReader(b))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	for k, v := range hdr {
		req.Header.Set(k, v)
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, 0, err
	}
	defer resp.Body.Close()
	out, err := io.ReadAll(resp.Body)
	return out, resp.StatusCode, err
}

// ---------------------------------------------------------------- drivers

// A DB runs a named query with params and returns the decoded result rows.
type DB interface {
	Name() string
	Setup(dim int) error
	Exec(q Query, params map[string]any) (json.RawMessage, error)
	Queries() map[string]Query
}

type Query struct {
	Lang string // arcade: sql|cypher; surreal: ignored
	Text string
	Read bool // arcade: /query vs /command
}

var errConflict = errors.New("conflict")

type Surreal struct {
	url, ns, db string
	hdr         map[string]string
	ef          int
	ws          bool                 // WebSocket RPC (what the SDKs use) instead of HTTP /rpc
	pool        chan *websocket.Conn // one in-flight request per connection
}

func (s *Surreal) Name() string {
	if s.ws {
		return "surrealdb-ws"
	}
	return "surrealdb"
}

func (s *Surreal) dialPool(n int) error {
	s.pool = make(chan *websocket.Conn, n)
	d := websocket.Dialer{Subprotocols: []string{"json"}}
	for i := 0; i < n; i++ {
		c, _, err := d.Dial(strings.Replace(s.url, "http", "ws", 1)+"/rpc", nil)
		if err != nil {
			return err
		}
		for _, m := range []map[string]any{
			{"id": 1, "method": "signin", "params": []any{map[string]string{"user": "root", "pass": "root"}}},
			{"id": 2, "method": "use", "params": []any{s.ns, s.db}},
		} {
			if err := c.WriteJSON(m); err != nil {
				return err
			}
			var resp struct{ Error *struct{ Message string } }
			if err := c.ReadJSON(&resp); err != nil || resp.Error != nil {
				return fmt.Errorf("ws %v: %v %v", m["method"], err, resp.Error)
			}
		}
		s.pool <- c
	}
	return nil
}

func (s *Surreal) Setup(dim int) error {
	b, _, err := post(s.url+"/signin", nil, map[string]string{"user": "root", "pass": "root"})
	if err != nil {
		return err
	}
	var tok struct{ Token string }
	if err := json.Unmarshal(b, &tok); err != nil || tok.Token == "" {
		return fmt.Errorf("signin: %s", b)
	}
	s.hdr = map[string]string{"Authorization": "Bearer " + tok.Token}
	if _, err := s.Exec(Query{Text: fmt.Sprintf("DEFINE NAMESPACE IF NOT EXISTS %s", s.ns)}, nil); err != nil {
		return err
	}
	s.hdr["Surreal-NS"] = s.ns
	_, err = s.Exec(Query{Text: fmt.Sprintf("DEFINE DATABASE IF NOT EXISTS %s", s.db)}, nil)
	if err != nil {
		return err
	}
	s.hdr["Surreal-DB"] = s.db
	if s.ws {
		if err := s.dialPool(32); err != nil {
			return err
		}
	}
	_, err = s.Exec(Query{Text: strings.Join([]string{
		"REMOVE TABLE IF EXISTS person", "REMOVE TABLE IF EXISTS follows", "REMOVE TABLE IF EXISTS doc",
		"DEFINE TABLE person SCHEMAFULL",
		"DEFINE FIELD name ON person TYPE string", "DEFINE FIELD age ON person TYPE int",
		"DEFINE FIELD city ON person TYPE int", "DEFINE FIELD score ON person TYPE float",
		"DEFINE INDEX person_city_age ON person FIELDS city, age",
		"DEFINE TABLE follows TYPE RELATION FROM person TO person SCHEMAFULL",
		"DEFINE TABLE doc SCHEMAFULL", "DEFINE FIELD emb ON doc TYPE array<float>",
		fmt.Sprintf("DEFINE INDEX doc_emb ON doc FIELDS emb HNSW DIMENSION %d DIST COSINE", dim),
	}, ";\n")}, nil)
	return err
}

func (s *Surreal) Exec(q Query, params map[string]any) (json.RawMessage, error) {
	if params == nil {
		params = map[string]any{}
	}
	msg := map[string]any{"id": 1, "method": "query", "params": []any{q.Text, params}}
	var b []byte
	var code int
	var err error
	if s.pool != nil {
		c := <-s.pool
		if err = c.WriteJSON(msg); err == nil {
			_, b, err = c.ReadMessage()
		}
		s.pool <- c
		code = 101
	} else {
		b, code, err = post(s.url+"/rpc", s.hdr, msg)
	}
	if err != nil {
		return nil, err
	}
	var resp struct {
		Error  *struct{ Message string }
		Result []struct {
			Status string
			Result json.RawMessage
		}
	}
	if err := json.Unmarshal(b, &resp); err != nil {
		return nil, fmt.Errorf("http %d: %.300s", code, b)
	}
	if resp.Error != nil {
		return nil, classify(resp.Error.Message)
	}
	for _, r := range resp.Result {
		if r.Status != "OK" {
			return nil, classify(string(r.Result))
		}
	}
	if len(resp.Result) == 0 {
		return nil, nil
	}
	return resp.Result[len(resp.Result)-1].Result, nil
}

func classify(msg string) error {
	l := strings.ToLower(msg)
	if strings.Contains(l, "conflict") || strings.Contains(l, "concurrentmodification") || strings.Contains(l, "retry") {
		return fmt.Errorf("%w: %.200s", errConflict, msg)
	}
	return errors.New(msg)
}

func (s *Surreal) Queries() map[string]Query {
	rec := `type::record("person", $id)`
	return map[string]Query{
		"load_persons":  {Text: "INSERT INTO person $rows RETURN NONE"},
		"load_edges":    {Text: `INSERT RELATION INTO follows $rows.map(|$r| {in: type::record("person", $r.a), out: type::record("person", $r.b)}) RETURN NONE`},
		"load_docs":     {Text: "INSERT INTO doc $rows RETURN NONE"},
		"count_persons": {Text: "SELECT VALUE count() FROM person GROUP ALL"},
		"count_edges":   {Text: "SELECT VALUE count() FROM follows GROUP ALL"},
		"noop":          {Text: "RETURN 1"},
		"point_read":    {Text: "SELECT * FROM " + rec},
		"point_update":  {Text: "UPDATE " + rec + " SET score += 1 RETURN NONE"},
		"insert_one":    {Text: "CREATE " + rec + " CONTENT $doc RETURN NONE"},
		"index_lookup":  {Text: "SELECT * FROM person WHERE city = $city AND age = $age"},
		"agg_group":     {Text: "SELECT city, count() AS n, math::mean(score) AS s FROM person GROUP BY city"},
		"graph_1hop":    {Text: "SELECT VALUE ->follows->person FROM ONLY " + rec},
		"graph_2hop":    {Text: "SELECT VALUE array::len(array::distinct(->follows->person->follows->person)) FROM ONLY " + rec},
		"graph_3hop":    {Text: "SELECT VALUE array::len(array::distinct(->follows->person->follows->person->follows->person)) FROM ONLY " + rec},
		"vector_knn":    {Text: fmt.Sprintf("SELECT id, vector::distance::knn() AS d FROM doc WHERE emb <|10,%d|> $q ORDER BY d", s.ef)},
	}
}

type Arcade struct {
	url, db string
	hdr     map[string]string
}

func (a *Arcade) Name() string { return "arcadedb" }

func (a *Arcade) Setup(dim int) error {
	req, _ := http.NewRequest("POST", a.url+"/api/v1/login", nil)
	req.SetBasicAuth("root", os.Getenv("ARCADE_PASS"))
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	b, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	var tok struct{ Token string }
	if err := json.Unmarshal(b, &tok); err != nil || tok.Token == "" {
		return fmt.Errorf("login: %s", b)
	}
	a.hdr = map[string]string{"Authorization": "Bearer " + tok.Token}
	post(a.url+"/api/v1/server", a.hdr, map[string]string{"command": "drop database " + a.db})
	if b, code, err := post(a.url+"/api/v1/server", a.hdr, map[string]string{"command": "create database " + a.db}); err != nil || code != 200 {
		return fmt.Errorf("create db: %v %s", err, b)
	}
	_, err = a.Exec(Query{Lang: "sqlscript", Text: strings.Join([]string{
		"CREATE VERTEX TYPE Person",
		"CREATE PROPERTY Person.id LONG", "CREATE PROPERTY Person.name STRING", "CREATE PROPERTY Person.age INTEGER",
		"CREATE PROPERTY Person.city INTEGER", "CREATE PROPERTY Person.score DOUBLE",
		"CREATE INDEX ON Person (id) UNIQUE", "CREATE INDEX ON Person (city, age) NOTUNIQUE",
		"CREATE EDGE TYPE Follows",
		"CREATE DOCUMENT TYPE Doc", "CREATE PROPERTY Doc.id LONG", "CREATE PROPERTY Doc.emb ARRAY_OF_FLOATS",
		"CREATE INDEX ON Doc (id) UNIQUE",
		fmt.Sprintf(`CREATE INDEX ON Doc (emb) LSM_VECTOR METADATA {"dimensions": %d, "similarity": "COSINE"}`, dim),
	}, ";\n")}, nil)
	return err
}

func (a *Arcade) Exec(q Query, params map[string]any) (json.RawMessage, error) {
	ep := "command"
	if q.Read {
		ep = "query"
	}
	body := map[string]any{"language": q.Lang, "command": q.Text}
	if params != nil {
		body["params"] = params
	}
	b, code, err := post(a.url+"/api/v1/"+ep+"/"+a.db, a.hdr, body)
	if err != nil {
		return nil, err
	}
	if code != 200 {
		return nil, classify(fmt.Sprintf("http %d: %.400s", code, b))
	}
	var resp struct{ Result json.RawMessage }
	if err := json.Unmarshal(b, &resp); err != nil {
		return nil, err
	}
	return resp.Result, nil
}

func (a *Arcade) Queries() map[string]Query {
	return map[string]Query{
		"load_persons":      {Lang: "sql", Text: "INSERT INTO Person CONTENT :rows RETURN count(*)"},
		"load_edges":        {Lang: "cypher", Text: "UNWIND $rows AS r MATCH (a:Person {id: r.a}), (b:Person {id: r.b}) CREATE (a)-[:Follows]->(b)"},
		"load_docs":         {Lang: "sql", Text: "INSERT INTO Doc CONTENT :rows RETURN count(*)"},
		"count_persons":     {Lang: "sql", Read: true, Text: "SELECT count(*) AS n FROM Person"},
		"count_edges":       {Lang: "sql", Read: true, Text: "SELECT count(*) AS n FROM Follows"},
		"noop":              {Lang: "sql", Read: true, Text: "SELECT 1 AS one"},
		"point_read":        {Lang: "sql", Read: true, Text: "SELECT FROM Person WHERE id = :id"},
		"point_update":      {Lang: "sql", Text: "UPDATE Person SET score = score + 1 WHERE id = :id"},
		"insert_one":        {Lang: "sql", Text: "INSERT INTO Person CONTENT :doc"},
		"index_lookup":      {Lang: "sql", Read: true, Text: "SELECT FROM Person WHERE city = :city AND age = :age"},
		"agg_group":         {Lang: "sql", Read: true, Text: "SELECT city, count(*) AS n, avg(score) AS s FROM Person GROUP BY city"},
		"graph_1hop":        {Lang: "sql", Read: true, Text: `SELECT out("Follows").id AS ids FROM Person WHERE id = :id`},
		"graph_2hop":        {Lang: "sql", Read: true, Text: `SELECT out("Follows").out("Follows").asSet().size() AS n FROM Person WHERE id = :id`},
		"graph_2hop_cypher": {Lang: "cypher", Read: true, Text: "MATCH (p:Person {id: $id})-[:Follows]->()-[:Follows]->(f) RETURN count(DISTINCT f) AS n"},
		"graph_3hop_cypher": {Lang: "cypher", Read: true, Text: "MATCH (p:Person {id: $id})-[:Follows]->()-[:Follows]->()-[:Follows]->(f) RETURN count(DISTINCT f) AS n"},
		"graph_3hop":        {Lang: "sql", Read: true, Text: `SELECT out("Follows").out("Follows").out("Follows").asSet().size() AS n FROM Person WHERE id = :id`},
		"vector_knn":        {Lang: "sql", Read: true, Text: `SELECT id, distance FROM (SELECT expand(vectorNeighbors("Doc[emb]", :q, 10)))`},
	}
}

// ---------------------------------------------------------------- result decoding

// Normalise both APIs' answers to comparable Go values.

func asInt(raw json.RawMessage) (int64, error) {
	// surreal: 5 | [5] ; arcade: [{"n":5}]
	var v any
	if err := json.Unmarshal(raw, &v); err != nil {
		return 0, err
	}
	for {
		switch x := v.(type) {
		case float64:
			return int64(x), nil
		case []any:
			if len(x) != 1 {
				return 0, fmt.Errorf("want 1 value, got %.200s", raw)
			}
			v = x[0]
		case map[string]any:
			if len(x) != 1 {
				return 0, fmt.Errorf("want 1 field, got %.200s", raw)
			}
			for _, f := range x {
				v = f
			}
		default:
			return 0, fmt.Errorf("not a number: %.200s", raw)
		}
	}
}

// ids pulls integer ids out of "person:5" strings, {"id": 5} rows or {"ids":[...]} rows.
func ids(raw json.RawMessage) ([]int64, error) {
	var v any
	if err := json.Unmarshal(raw, &v); err != nil {
		return nil, err
	}
	var out []int64
	var walk func(any) error
	walk = func(v any) error {
		switch x := v.(type) {
		case float64:
			out = append(out, int64(x))
		case string:
			i := strings.LastIndexByte(x, ':')
			n, err := strconv.ParseInt(x[i+1:], 10, 64)
			if err != nil {
				return fmt.Errorf("bad id %q", x)
			}
			out = append(out, n)
		case []any:
			for _, e := range x {
				if err := walk(e); err != nil {
					return err
				}
			}
		case map[string]any:
			if id, ok := x["id"]; ok {
				return walk(id)
			}
			if id, ok := x["ids"]; ok {
				return walk(id)
			}
			return fmt.Errorf("row without id: %v", x)
		}
		return nil
	}
	return out, walk(v)
}

func rows(raw json.RawMessage) []map[string]any {
	var r []map[string]any
	json.Unmarshal(raw, &r)
	return r
}

// ---------------------------------------------------------------- load

type loadStat struct {
	Rows      int     `json:"rows"`
	Seconds   float64 `json:"seconds"`
	RowsPerS  float64 `json:"rows_per_s"`
	Conflicts int64   `json:"conflict_retries"`
}

func load[T any](db DB, q Query, items []T, batch, workers int) (loadStat, error) {
	var next, conflicts int64
	var firstErr error
	var mu sync.Mutex
	var wg sync.WaitGroup
	start := time.Now()
	for w := 0; w < workers; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for {
				i := int(atomic.AddInt64(&next, int64(batch))) - batch
				if i >= len(items) {
					return
				}
				chunk := items[i:min(i+batch, len(items))]
				for attempt := 0; ; attempt++ {
					_, err := db.Exec(q, map[string]any{"rows": chunk})
					if err == nil {
						break
					}
					if errors.Is(err, errConflict) && attempt < 50 {
						atomic.AddInt64(&conflicts, 1)
						time.Sleep(time.Duration(rand.Intn(5*(attempt+1))) * time.Millisecond)
						continue
					}
					mu.Lock()
					if firstErr == nil {
						firstErr = err
					}
					mu.Unlock()
					return
				}
			}
		}()
	}
	wg.Wait()
	el := time.Since(start).Seconds()
	return loadStat{len(items), el, float64(len(items)) / el, conflicts}, firstErr
}

// ---------------------------------------------------------------- verify

type verifyStat struct {
	Checks   int      `json:"checks"`
	Failures []string `json:"failures,omitempty"`
	Recall10 float64  `json:"vector_recall_at_10"`
	// first kNN query after load (some vector indexes build lazily)
	FirstKnnMs float64 `json:"first_knn_ms"`
}

func verify(db DB, d *Dataset, r *rand.Rand) verifyStat {
	q := db.Queries()
	var v verifyStat
	fail := func(f string, a ...any) {
		if len(v.Failures) < 20 {
			v.Failures = append(v.Failures, fmt.Sprintf(f, a...))
		}
	}
	check := func(name string, want int64, raw json.RawMessage, err error) {
		v.Checks++
		if err != nil {
			fail("%s: %v", name, err)
			return
		}
		got, err := asInt(raw)
		if err != nil || got != want {
			fail("%s: want %d got %d (%v)", name, want, got, err)
		}
	}
	raw, err := db.Exec(q["count_persons"], nil)
	check("count_persons", int64(len(d.Persons)), raw, err)
	raw, err = db.Exec(q["count_edges"], nil)
	check("count_edges", int64(len(d.Edges)), raw, err)

	for i := 0; i < 30; i++ {
		id := int64(r.Intn(len(d.Persons)))
		p := map[string]any{"id": id}
		raw, err := db.Exec(q["point_read"], p)
		got, _ := ids(raw)
		v.Checks++
		if err != nil || len(got) != 1 || got[0] != id {
			fail("point_read %d: %v %v", id, got, err)
		}
		raw, err = db.Exec(q["graph_1hop"], p)
		got, _ = ids(raw)
		sort.Slice(got, func(i, j int) bool { return got[i] < got[j] })
		want := append([]int64(nil), d.Adj[id]...)
		sort.Slice(want, func(i, j int) bool { return want[i] < want[j] })
		v.Checks++
		if err != nil || fmt.Sprint(got) != fmt.Sprint(want) {
			fail("graph_1hop %d: want %v got %v %v", id, want, got, err)
		}
		raw, err = db.Exec(q["graph_2hop"], p)
		check(fmt.Sprintf("graph_2hop %d", id), int64(d.hops(id, 2)), raw, err)
		raw, err = db.Exec(q["graph_3hop"], p)
		check(fmt.Sprintf("graph_3hop %d", id), int64(d.hops(id, 3)), raw, err)
		for h, name := range map[int]string{2: "graph_2hop_cypher", 3: "graph_3hop_cypher"} {
			if cq, ok := q[name]; ok {
				raw, err = db.Exec(cq, p)
				check(fmt.Sprintf("%s %d", name, id), int64(d.hops(id, h)), raw, err)
			}
		}

		city, age := r.Intn(d.Cities), 18+r.Intn(63)
		n := 0
		for _, p := range d.Persons {
			if p.City == city && p.Age == age {
				n++
			}
		}
		raw, err = db.Exec(q["index_lookup"], map[string]any{"city": city, "age": age})
		got, _ = ids(raw)
		v.Checks++
		if err != nil || len(got) != n {
			fail("index_lookup %d/%d: want %d got %d %v", city, age, n, len(got), err)
		}
	}

	raw, err = db.Exec(q["agg_group"], nil)
	v.Checks++
	perCity := map[int]int{}
	for _, p := range d.Persons {
		perCity[p.City]++
	}
	rs := rows(raw)
	if err != nil || len(rs) != d.Cities {
		fail("agg_group: %d groups, err %v", len(rs), err)
	} else {
		for _, row := range rs {
			c, _ := row["city"].(float64)
			n, _ := row["n"].(float64)
			if perCity[int(c)] != int(n) {
				fail("agg_group city %v: want %d got %v", c, perCity[int(c)], n)
				break
			}
		}
	}

	if len(d.Docs) > 0 {
		hits, total := 0, 0
		for i, qv := range d.Queries[:100] {
			t := time.Now()
			raw, err := db.Exec(q["vector_knn"], map[string]any{"q": qv})
			if i == 0 {
				v.FirstKnnMs = float64(time.Since(t).Microseconds()) / 1000
			}
			if err != nil {
				fail("vector_knn: %v", err)
				break
			}
			got, _ := ids(raw)
			want := map[int64]bool{}
			for _, id := range d.knn(qv, 10) {
				want[id] = true
			}
			for _, id := range got {
				if want[id] {
					hits++
				}
			}
			total += 10
		}
		if total > 0 {
			v.Recall10 = float64(hits) / float64(total)
		}
	}
	return v
}

// ---------------------------------------------------------------- run

type runStat struct {
	Op          string  `json:"op"`
	Concurrency int     `json:"concurrency"`
	Ops         int     `json:"ops"`
	Errors      int     `json:"errors"`
	Conflicts   int     `json:"conflicts"`
	OpsPerS     float64 `json:"ops_per_s"`
	P50         float64 `json:"p50_ms"`
	P90         float64 `json:"p90_ms"`
	P99         float64 `json:"p99_ms"`
	Max         float64 `json:"max_ms"`
	FirstError  string  `json:"first_error,omitempty"`
}

func run(db DB, d *Dataset, op string, conc int, dur time.Duration, nextID *int64) runStat {
	q := db.Queries()[op]
	n := int64(len(d.Persons))
	params := func(r *rand.Rand) map[string]any {
		switch op {
		case "index_lookup":
			return map[string]any{"city": r.Intn(d.Cities), "age": 18 + r.Intn(63)}
		case "agg_group", "noop":
			return nil
		case "vector_knn":
			return map[string]any{"q": d.Queries[r.Intn(len(d.Queries))]}
		case "insert_one":
			id := atomic.AddInt64(nextID, 1)
			return map[string]any{"id": id, "doc": Person{ID: id, Name: "new" + strconv.FormatInt(id, 10),
				Age: 18 + r.Intn(63), City: r.Intn(d.Cities), Score: 1}}
		}
		return map[string]any{"id": r.Int63n(n)}
	}
	var mu sync.Mutex
	var lat []time.Duration
	var errs, conflicts int
	var firstErr string
	var wg sync.WaitGroup
	start := time.Now()
	deadline := start.Add(dur)
	for w := 0; w < conc; w++ {
		wg.Add(1)
		go func(seed int64) {
			defer wg.Done()
			r := rand.New(rand.NewSource(seed))
			var mine []time.Duration
			var e, c int
			var fe string
			for time.Now().Before(deadline) {
				t := time.Now()
				_, err := db.Exec(q, params(r))
				if err != nil {
					if errors.Is(err, errConflict) {
						c++
						continue
					}
					e++
					if fe == "" {
						fe = err.Error()
					}
					continue
				}
				mine = append(mine, time.Since(t))
			}
			mu.Lock()
			lat = append(lat, mine...)
			errs += e
			conflicts += c
			if firstErr == "" {
				firstErr = fe
			}
			mu.Unlock()
		}(int64(w) + 1000)
	}
	wg.Wait()
	el := time.Since(start).Seconds()
	sort.Slice(lat, func(i, j int) bool { return lat[i] < lat[j] })
	pct := func(p float64) float64 {
		if len(lat) == 0 {
			return 0
		}
		return float64(lat[min(len(lat)-1, int(p*float64(len(lat))))].Microseconds()) / 1000
	}
	if len(firstErr) > 300 {
		firstErr = firstErr[:300]
	}
	return runStat{op, conc, len(lat), errs, conflicts, float64(len(lat)) / el,
		pct(.5), pct(.9), pct(.99), pct(1), firstErr}
}

type report struct {
	DB      string              `json:"db"`
	Version string              `json:"version"`
	Mode    string              `json:"mode"`
	Host    map[string]any      `json:"host"`
	Config  map[string]any      `json:"config"`
	Load    map[string]loadStat `json:"load"`
	Verify  verifyStat          `json:"verify"`
	Runs    []runStat           `json:"runs"`
}

func main() {
	which := flag.String("db", "", "arcadedb | surrealdb | surrealdb-ws | falkordb")
	url := flag.String("url", "", "server URL")
	version := flag.String("version", "", "server version, for the report")
	mode := flag.String("mode", "durable", "label: durable | relaxed")
	n := flag.Int("persons", 100_000, "people")
	k := flag.Int("follows", 10, "follows per person")
	docs := flag.Int("docs", 100_000, "vectors")
	dim := flag.Int("dim", 128, "vector dimensions")
	batch := flag.Int("batch", 1000, "rows per load batch")
	loadWorkers := flag.Int("load-workers", 4, "parallel load batches")
	conc := flag.Int("c", 16, "concurrency for point/graph ops")
	heavyConc := flag.Int("heavy-c", 4, "concurrency for agg/3hop/vector")
	dur := flag.Duration("d", 10*time.Second, "duration per op")
	ops := flag.String("ops", "noop,point_read,point_update,insert_one,index_lookup,agg_group,graph_1hop,graph_2hop,graph_3hop,graph_2hop_cypher,graph_3hop_cypher,vector_knn", "ops to run")
	ef := flag.Int("ef", 100, "HNSW search ef (SurrealDB, FalkorDB efRuntime)")
	out := flag.String("out", "", "write JSON report here")
	flag.Parse()

	var db DB
	switch *which {
	case "surrealdb", "surrealdb-ws":
		db = &Surreal{url: *url, ns: "bench", db: "bench", ef: *ef, ws: *which == "surrealdb-ws"}
	case "arcadedb":
		db = &Arcade{url: *url, db: "bench"}
	case "falkordb":
		db = &Falkor{addr: strings.TrimPrefix(*url, "redis://"), graph: "bench", ef: *ef}
	default:
		fmt.Fprintln(os.Stderr, "-db arcadedb|surrealdb|surrealdb-ws|falkordb")
		os.Exit(2)
	}
	t := time.Now()
	d := generate(*n, *k, *docs, *dim, 100, 42)
	fmt.Printf("generated %d persons, %d edges, %d docs in %v\n", len(d.Persons), len(d.Edges), len(d.Docs), time.Since(t).Round(time.Millisecond))

	must := func(err error) {
		if err != nil {
			fmt.Fprintln(os.Stderr, "FATAL:", err)
			os.Exit(1)
		}
	}
	must(db.Setup(*dim))
	rep := report{DB: db.Name(), Version: *version, Mode: *mode, Load: map[string]loadStat{},
		Host: map[string]any{"cpus": runtime.NumCPU(), "os": runtime.GOOS},
		Config: map[string]any{"persons": *n, "follows": *k, "docs": *docs, "dim": *dim, "batch": *batch,
			"load_workers": *loadWorkers, "c": *conc, "heavy_c": *heavyConc, "seconds": dur.Seconds(), "surreal_ef": *ef}}
	q := db.Queries()
	var err error
	st, err := load(db, q["load_persons"], d.Persons, *batch, *loadWorkers)
	must(err)
	rep.Load["persons"] = st
	fmt.Printf("load persons: %8.0f rows/s  (%.1fs, %d conflict retries)\n", st.RowsPerS, st.Seconds, st.Conflicts)
	st, err = load(db, q["load_edges"], d.Edges, *batch, *loadWorkers)
	must(err)
	rep.Load["edges"] = st
	fmt.Printf("load edges:   %8.0f rows/s  (%.1fs, %d conflict retries)\n", st.RowsPerS, st.Seconds, st.Conflicts)
	if *docs > 0 {
		st, err = load(db, q["load_docs"], d.Docs, *batch/4, *loadWorkers)
		must(err)
		rep.Load["docs"] = st
		fmt.Printf("load vectors: %8.0f rows/s  (%.1fs, %d conflict retries)\n", st.RowsPerS, st.Seconds, st.Conflicts)
	}

	rep.Verify = verify(db, d, rand.New(rand.NewSource(7)))
	fmt.Printf("verify: %d checks, %d failures, recall@10 %.3f, first kNN %.0fms\n",
		rep.Verify.Checks, len(rep.Verify.Failures), rep.Verify.Recall10, rep.Verify.FirstKnnMs)
	for _, f := range rep.Verify.Failures {
		fmt.Println("  FAIL", f)
	}

	nextID := int64(*n) + 1_000_000
	fmt.Printf("%-13s %4s %9s %8s %8s %8s %8s %6s %6s\n", "op", "c", "ops/s", "p50ms", "p90ms", "p99ms", "maxms", "errs", "confl")
	for _, op := range strings.Split(*ops, ",") {
		if _, ok := q[op]; !ok || (op == "vector_knn" && *docs == 0) {
			continue
		}
		c := *conc
		if op == "agg_group" || strings.HasPrefix(op, "graph_3hop") || op == "vector_knn" {
			c = *heavyConc
		}
		run(db, d, op, c, time.Second, &nextID) // 1s warmup, discarded
		r := run(db, d, op, c, *dur, &nextID)
		rep.Runs = append(rep.Runs, r)
		fmt.Printf("%-13s %4d %9.0f %8.2f %8.2f %8.2f %8.1f %6d %6d\n", r.Op, r.Concurrency, r.OpsPerS, r.P50, r.P90, r.P99, r.Max, r.Errors, r.Conflicts)
		if r.FirstError != "" {
			fmt.Println("  first error:", r.FirstError)
		}
	}
	if *out != "" {
		b, _ := json.MarshalIndent(rep, "", "  ")
		must(os.WriteFile(*out, b, 0o644))
	}
}
