#!/usr/bin/env python3
"""Markdown tables from results/*.json (newest file per db+mode)."""
import json, glob, sys, os
d = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "results")
latest = {}
for f in sorted(glob.glob(os.path.join(d, "*.json"))):
    r = json.load(open(f)); latest[(r["db"], r["mode"])] = r
dbs = ["arcadedb", "surrealdb", "surrealdb-ws"]
for mode in ["durable", "relaxed"]:
    cols = [db for db in dbs if (db, mode) in latest]
    if not cols: continue
    R = {db: latest[(db, mode)] for db in cols}
    print(f"\n#### {mode}\n")
    print("| | " + " | ".join(f"{db} {R[db]['version']}" for db in cols) + " |")
    print("|---|" + "---|" * len(cols))
    for what in ["persons", "edges", "docs"]:
        print(f"| load {what} (rows/s) | " + " | ".join(
            f"{R[db]['load'][what]['rows_per_s']:,.0f}" + (f" ({R[db]['load'][what]['conflict_retries']} retries)" if R[db]['load'][what]['conflict_retries'] else "")
            for db in cols) + " |")
    ops = []
    for db in cols:
        for r in R[db]["runs"]:
            if r["op"] not in ops: ops.append(r["op"])
    for op in ops:
        cells = []
        for db in cols:
            r = next((r for r in R[db]["runs"] if r["op"] == op), None)
            cells.append("–" if r is None else f"{r['ops_per_s']:,.0f}/s · {r['p50_ms']:.1f} / {r['p99_ms']:.1f}ms")
        c = next(r["concurrency"] for db in cols for r in R[db]["runs"] if r["op"] == op)
        print(f"| {op} (c={c}) | " + " | ".join(cells) + " |")
    print("| vector recall@10 | " + " | ".join(f"{R[db]['verify']['vector_recall_at_10']:.3f}" for db in cols) + " |")
    print("| first kNN query after load | " + " | ".join(f"{R[db]['verify']['first_knn_ms']/1000:.1f}s" for db in cols) + " |")
    print("| correctness checks failed | " + " | ".join(f"{len(R[db]['verify'].get('failures') or [])}/{R[db]['verify']['checks']}" for db in cols) + " |")
    print("| server peak RSS / disk | " + " | ".join(f"{R[db]['server']['peak_rss_mb']:,}MB / {R[db]['server']['disk_mb']}MB" for db in cols) + " |")
