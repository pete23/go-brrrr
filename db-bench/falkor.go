package main

import (
	"context"
	"encoding/json"
	"fmt"
	"reflect"
	"strconv"
	"strings"

	"github.com/redis/go-redis/v9"
)

// Falkor talks to FalkorDB (a Redis module) with GRAPH.QUERY / GRAPH.RO_QUERY over RESP.
// Parameters travel as a "CYPHER k=v ..." prefix of Cypher literals; results come back in
// --compact form and are turned into the same JSON rows the other drivers return.
type Falkor struct {
	addr, graph string
	ef          int // efRuntime; FalkorDB's default is 10
	rdb         *redis.Client
}

func (f *Falkor) Name() string { return "falkordb" }

func (f *Falkor) Setup(dim int) error {
	f.rdb = redis.NewClient(&redis.Options{Addr: f.addr, Protocol: 2, PoolSize: 64, ReadTimeout: -1, WriteTimeout: -1})
	ctx := context.Background()
	f.rdb.Do(ctx, "GRAPH.DELETE", f.graph) // fine if it doesn't exist
	for _, q := range []string{
		"CREATE INDEX FOR (p:Person) ON (p.id)",
		"CREATE INDEX FOR (p:Person) ON (p.city, p.age)",
		"CREATE INDEX FOR (d:Doc) ON (d.id)",
		fmt.Sprintf("CREATE VECTOR INDEX FOR (d:Doc) ON (d.emb) OPTIONS {dimension: %d, similarityFunction: 'cosine', efRuntime: %d}", dim, f.ef),
	} {
		if _, err := f.Exec(Query{Text: q}, nil); err != nil {
			return fmt.Errorf("%s: %w", q, err)
		}
	}
	return nil
}

func (f *Falkor) Exec(q Query, params map[string]any) (json.RawMessage, error) {
	var sb strings.Builder
	if len(params) > 0 {
		sb.WriteString("CYPHER ")
		for k, v := range params {
			sb.WriteString(k)
			sb.WriteByte('=')
			cypherLit(&sb, reflect.ValueOf(v))
			sb.WriteByte(' ')
		}
	}
	sb.WriteString(q.Text)
	cmd := "GRAPH.QUERY"
	if q.Read {
		cmd = "GRAPH.RO_QUERY"
	}
	res, err := f.rdb.Do(context.Background(), cmd, f.graph, sb.String(), "--compact").Result()
	if err != nil {
		return nil, err
	}
	// compact reply: [header, rows, stats] for queries that return something, else [stats]
	parts, _ := res.([]any)
	if len(parts) < 3 {
		return json.RawMessage("[]"), nil
	}
	var names []string
	for _, h := range parts[0].([]any) {
		names = append(names, fmt.Sprint(h.([]any)[1]))
	}
	var out []map[string]any
	for _, r := range parts[1].([]any) {
		row := map[string]any{}
		for i, cell := range r.([]any) {
			row[names[i]] = compactValue(cell)
		}
		out = append(out, row)
	}
	b, err := json.Marshal(out)
	return b, err
}

// compactValue decodes a [type, value] pair from FalkorDB's compact result format.
func compactValue(cell any) any {
	c, ok := cell.([]any)
	if !ok || len(c) != 2 {
		return cell
	}
	t, _ := c[0].(int64)
	switch t {
	case 1: // null
		return nil
	case 4: // bool
		return c[1] == "true"
	case 5: // double, as a string
		f, _ := strconv.ParseFloat(fmt.Sprint(c[1]), 64)
		return f
	case 6: // array of [type, value]
		var arr []any
		for _, e := range c[1].([]any) {
			arr = append(arr, compactValue(e))
		}
		return arr
	}
	return c[1] // string, integer
}

// cypherLit writes v as a Cypher literal: numbers, strings, lists, maps, and structs by
// their json tags. Floats always carry a '.' or exponent so they stay floats.
func cypherLit(sb *strings.Builder, v reflect.Value) {
	switch v.Kind() {
	case reflect.Interface, reflect.Pointer:
		if v.IsNil() {
			sb.WriteString("null")
			return
		}
		cypherLit(sb, v.Elem())
	case reflect.Bool:
		sb.WriteString(strconv.FormatBool(v.Bool()))
	case reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64:
		sb.WriteString(strconv.FormatInt(v.Int(), 10))
	case reflect.Float32, reflect.Float64:
		s := strconv.FormatFloat(v.Float(), 'g', -1, v.Type().Bits())
		if !strings.ContainsAny(s, ".eEn") {
			s += ".0"
		}
		sb.WriteString(s)
	case reflect.String:
		sb.WriteByte('"')
		sb.WriteString(strings.NewReplacer(`\`, `\\`, `"`, `\"`).Replace(v.String()))
		sb.WriteByte('"')
	case reflect.Slice, reflect.Array:
		sb.WriteByte('[')
		for i := 0; i < v.Len(); i++ {
			if i > 0 {
				sb.WriteByte(',')
			}
			cypherLit(sb, v.Index(i))
		}
		sb.WriteByte(']')
	case reflect.Map:
		sb.WriteByte('{')
		for i, k := range v.MapKeys() {
			if i > 0 {
				sb.WriteByte(',')
			}
			sb.WriteString(k.String())
			sb.WriteByte(':')
			cypherLit(sb, v.MapIndex(k))
		}
		sb.WriteByte('}')
	case reflect.Struct:
		sb.WriteByte('{')
		t := v.Type()
		for i := 0; i < t.NumField(); i++ {
			if i > 0 {
				sb.WriteByte(',')
			}
			name, _, _ := strings.Cut(t.Field(i).Tag.Get("json"), ",")
			sb.WriteString(name)
			sb.WriteByte(':')
			cypherLit(sb, v.Field(i))
		}
		sb.WriteByte('}')
	default:
		panic("cypherLit: unsupported " + v.Kind().String())
	}
}

func (f *Falkor) Queries() map[string]Query {
	me := "MATCH (p:Person {id: $id}) "
	return map[string]Query{
		"load_persons":  {Text: "UNWIND $rows AS r CREATE (:Person {id: r.id, name: r.name, age: r.age, city: r.city, score: r.score})"},
		"load_edges":    {Text: "UNWIND $rows AS r MATCH (a:Person {id: r.a}), (b:Person {id: r.b}) CREATE (a)-[:Follows]->(b)"},
		"load_docs":     {Text: "UNWIND $rows AS r CREATE (:Doc {id: r.id, emb: vecf32(r.emb)})"},
		"count_persons": {Read: true, Text: "MATCH (p:Person) RETURN count(p) AS n"},
		"count_edges":   {Read: true, Text: "MATCH ()-[r:Follows]->() RETURN count(r) AS n"},
		"noop":          {Read: true, Text: "RETURN 1 AS one"},
		"point_read":    {Read: true, Text: me + "RETURN p.id AS id, p.name AS name, p.age AS age, p.city AS city, p.score AS score"},
		"point_update":  {Text: me + "SET p.score = p.score + 1"},
		"insert_one":    {Text: "CREATE (:Person {id: $id, name: $doc.name, age: $doc.age, city: $doc.city, score: $doc.score})"},
		"index_lookup":  {Read: true, Text: "MATCH (p:Person) WHERE p.city = $city AND p.age = $age RETURN p.id AS id, p.name AS name, p.score AS score"},
		"agg_group":     {Read: true, Text: "MATCH (p:Person) RETURN p.city AS city, count(p) AS n, avg(p.score) AS s"},
		"graph_1hop":    {Read: true, Text: me + "-[:Follows]->(f) RETURN f.id AS id"},
		// Separate MATCHes with DISTINCT frontiers: plain reachability, as the ground truth
		// counts it (one MATCH pattern would forbid reusing an edge within a path).
		"graph_2hop": {Read: true, Text: me + "-[:Follows]->(a) WITH DISTINCT a MATCH (a)-[:Follows]->(f) RETURN count(DISTINCT f) AS n"},
		"graph_3hop": {Read: true, Text: me + "-[:Follows]->(a) WITH DISTINCT a MATCH (a)-[:Follows]->(b) WITH DISTINCT b MATCH (b)-[:Follows]->(f) RETURN count(DISTINCT f) AS n"},
		"vector_knn": {Read: true, Text: "CALL db.idx.vector.queryNodes('Doc', 'emb', 10, vecf32($q)) YIELD node RETURN node.id AS id"},
	}
}
