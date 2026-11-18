#!/usr/bin/env python3
"""OLAP benchmark: Postgres vs pgrust (heap and pgrcolumnar) vs ClickHouse.

    python3 bench.py gen --rows 100000000                  # -> data/s100m
    python3 bench.py run --rows 100000000 [--engines ...] [--runs 3] [--no-cold]
    python3 bench.py report                                # results/*.json -> results/report.md

Each engine runs alone, in Docker, with the same CPU/memory limits. Per engine:
fresh server, load the CSVs (timed), measure on-disk size, then for each query
one cold run (server restarted and the OS page cache dropped first) and --runs
hot runs. Every engine's answers are checked against the first engine's.
"""
import argparse, datetime, decimal, glob, json, os, platform, re, subprocess, sys, time
import urllib.request, urllib.parse

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "data")
RESULTS = os.path.join(HERE, "results")

PG_PORT, CH_PORT = 55432, 8123
MEMORY = "12g"          # container memory limit (host has 15 GiB)
SHM = "4g"

PG_ARGS = [  # tuned for a 4-core, 12 GiB box, applied to Postgres and pgrust alike
    "shared_buffers=4GB", "effective_cache_size=10GB", "work_mem=512MB",
    "maintenance_work_mem=1GB", "max_parallel_workers_per_gather=4",
    "max_parallel_workers=4", "max_worker_processes=8", "max_wal_size=16GB",
    "wal_level=minimal", "max_wal_senders=0", "synchronous_commit=off",
    "checkpoint_timeout=30min", "random_page_cost=1.1",
]

ENGINES = {
    "postgres": dict(kind="pg", image="postgres:18", fact_using="", args=PG_ARGS),
    "pgrust-heap": dict(kind="pg", image="malisper/pgrust:v0.4-beta-amd64", fact_using="",
                        args=PG_ARGS, pgrust=True),
    "pgrust-columnar": dict(kind="pg", image="malisper/pgrust:v0.4-beta-amd64",
                            fact_using="USING pgrcolumnar", args=PG_ARGS, pgrust=True,
                            pre=["CREATE ACCESS METHOD pgrcolumnar TYPE TABLE HANDLER heap_tableam_handler"]),
    "clickhouse": dict(kind="ch", image="clickhouse/clickhouse-server:latest"),
}
TABLES = ["org", "service", "product", "fact"]
TIMEOUT_S = 1800


def sh(*cmd, check=True, capture=True, merge=False):
    if merge:
        r = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        return r.stdout.strip()
    r = subprocess.run(cmd, capture_output=capture, text=True)
    if check and r.returncode:
        raise RuntimeError(f"{' '.join(cmd)}: {r.stderr.strip() or r.stdout.strip()}")
    return r.stdout.strip() if capture else ""


def log(*a):
    print(f"[{datetime.datetime.now():%H:%M:%S}]", *a, flush=True)


def scale_name(rows):
    for div, suf in ((10**9, "b"), (10**6, "m"), (10**3, "k")):
        if rows % div == 0:
            return f"s{rows // div}{suf}"
    return f"s{rows}"


def drop_caches():
    os.sync()
    try:
        with open("/proc/sys/vm/drop_caches", "w") as f:
            f.write("3\n")
        return True
    except OSError:
        return False


# --------------------------------------------------------------------- engines

class Engine:
    def __init__(self, name, data_dir):
        self.name, self.cfg, self.data_dir = name, ENGINES[name], data_dir
        self.container = f"olap-{name}"
        self.volume = f"olap-{name}-data"

    def docker_run(self, image, args, env=(), extra=()):
        sh("docker", "rm", "-f", self.container, check=False)
        cmd = ["docker", "run", "-d", "--name", self.container, "--network", "host",
               "--memory", MEMORY, "--memory-swap", MEMORY, "--shm-size", SHM,
               "-v", f"{self.volume}:{self.mount}",
               "-v", f"{self.data_dir}:/data:ro"]
        for e in env:
            cmd += ["-e", e]
        cmd += list(extra) + [image] + list(args)
        sh(*cmd)

    def destroy(self):
        sh("docker", "rm", "-f", self.container, check=False)
        sh("docker", "volume", "rm", "-f", self.volume, check=False)

    def restart(self):
        sh("docker", "stop", "-t", "120", self.container)
        drop_caches()
        sh("docker", "start", self.container)
        self.wait_ready()

    def wait_ready(self, timeout=300):
        t0 = time.time()
        while True:
            try:
                self.query("SELECT 1")
                return
            except Exception as e:  # noqa: BLE001
                if time.time() - t0 > timeout:
                    logs = sh("docker", "logs", "--tail", "40", self.container, check=False)
                    raise RuntimeError(f"{self.name} not ready: {e}\n{logs}")
                time.sleep(1)


class PgEngine(Engine):
    mount = "/var/lib/postgresql"   # PG 18 image: PGDATA=/var/lib/postgresql/18/docker

    def start(self):
        args = []
        for a in self.cfg["args"]:
            args += ["-c", a]
        # The port goes in PGPORT, not -c: both images' entrypoints append our
        # args after their own, so -c port/listen_addresses would override the
        # socket-only settings of the temporary server they run initdb scripts
        # against, and we would connect to (and load into) that one.
        extra = []
        if self.cfg.get("pgrust"):
            extra = ["--ulimit", "stack=67108864:67108864", "-e", "RUST_MIN_STACK=33554432"]
            args += ["-c", "max_stack_depth=60000"]
        self.docker_run(self.cfg["image"], ["postgres"] + args,
                        env=["POSTGRES_PASSWORD=bench", f"PGPORT={PG_PORT}",
                             "POSTGRES_INITDB_ARGS=--no-locale --encoding=UTF8"],
                        extra=extra)
        self.wait_ready()

    def connect(self):
        import psycopg
        return psycopg.connect(host="127.0.0.1", port=PG_PORT, user="postgres",
                               password="bench", dbname="postgres", autocommit=True,
                               options=f"-c statement_timeout={TIMEOUT_S * 1000}")

    def query(self, sql):
        with self.connect() as c, c.cursor() as cur:
            cur.execute(sql)
            return cur.fetchall() if cur.description else []

    def version(self):
        return self.query("SELECT version()")[0][0]

    def load(self):
        schema = open(os.path.join(HERE, "schema", "postgres.sql")).read()
        schema = schema.replace("{{FACT_USING}}", self.cfg["fact_using"])
        steps = {}
        with self.connect() as c:
            for s in self.cfg.get("pre", []):
                c.execute(s)
            c.execute(schema)
            for t in TABLES:
                t0 = time.time()
                c.execute(f"COPY {t} FROM '/data/{t}.csv' WITH (FORMAT csv)")
                steps[f"copy_{t}"] = time.time() - t0
            t0 = time.time()
            c.execute("VACUUM (ANALYZE)")
            c.execute("CHECKPOINT")
            steps["vacuum_analyze_checkpoint"] = time.time() - t0
        return steps

    def sizes(self):
        q = """SELECT relname, pg_total_relation_size(oid) FROM pg_class
               WHERE relname IN ('org','service','product','fact') AND relkind = 'r'"""
        return {r[0]: int(r[1]) for r in self.query(q)}

    def run(self, sql):
        t0 = time.perf_counter()
        rows = self.query(sql)
        return time.perf_counter() - t0, rows


class ChEngine(Engine):
    mount = "/var/lib/clickhouse"

    def start(self):
        self.docker_run(self.cfg["image"], [],
                        env=["CLICKHOUSE_PASSWORD=bench"])
        self.wait_ready()

    def query(self, sql, fmt="TabSeparated"):
        params = {"default_format": fmt, "max_execution_time": TIMEOUT_S}
        req = urllib.request.Request(
            f"http://127.0.0.1:{CH_PORT}/?" + urllib.parse.urlencode(params),
            data=sql.encode(), headers={"X-ClickHouse-User": "default",
                                        "X-ClickHouse-Key": "bench"})
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        try:
            with opener.open(req, timeout=TIMEOUT_S + 60) as r:
                body = r.read().decode()
        except urllib.error.HTTPError as e:
            raise RuntimeError(e.read().decode()[:2000]) from None
        return [line.split("\t") for line in body.splitlines()]

    def version(self):
        return "ClickHouse " + self.query("SELECT version()")[0][0]

    def load(self):
        schema = open(os.path.join(HERE, "schema", "clickhouse.sql")).read()
        for stmt in split_sql(schema):
            self.query(stmt)
        steps = {}
        for t in TABLES:
            t0 = time.time()
            # clickhouse-client inside the container reads the CSV directly
            sh("docker", "exec", self.container, "clickhouse-client", "--password", "bench",
               "--query", f"INSERT INTO {t} FROM INFILE '/data/{t}.csv' FORMAT CSV")
            steps[f"insert_{t}"] = time.time() - t0
        return steps

    def sizes(self):
        q = """SELECT table, sum(bytes_on_disk) FROM system.parts
               WHERE active AND database = currentDatabase() GROUP BY table"""
        return {r[0]: int(r[1]) for r in self.query(q)}

    def run(self, sql):
        t0 = time.perf_counter()
        rows = self.query(sql)
        return time.perf_counter() - t0, rows


def make_engine(name, data_dir):
    return {"pg": PgEngine, "ch": ChEngine}[ENGINES[name]["kind"]](name, data_dir)


# --------------------------------------------------------------------- queries

def split_sql(text):
    text = re.sub(r"--[^\n]*", "", text)
    return [s.strip() for s in text.split(";") if s.strip()]


def load_queries(kind, only=None):
    qs = []
    for path in sorted(glob.glob(os.path.join(HERE, "queries", "q*.sql"))):
        name = os.path.basename(path)[:-4]
        if only and not any(name.startswith(o) for o in only):
            continue
        override = os.path.join(HERE, "queries", kind, os.path.basename(path))
        qs.append((name, open(override if os.path.exists(override) else path).read()))
    return qs


def norm_cell(v):
    if v is None:
        return None
    s = str(v)
    try:
        d = decimal.Decimal(s)
        return float(d) if d.is_finite() else s
    except decimal.InvalidOperation:
        return s


def same(a, b):
    if len(a) != len(b):
        return False
    for ra, rb in zip(a, b):
        if len(ra) != len(rb):
            return False
        for x, y in zip(ra, rb):
            if isinstance(x, float) and isinstance(y, float):
                if abs(x - y) > 1e-6 * max(1.0, abs(x), abs(y)):
                    return False
            elif x != y:
                return False
    return True


# --------------------------------------------------------------------- commands

def cmd_gen(a):
    exe = os.path.join(HERE, "build", "gen")
    src = os.path.join(HERE, "gen", "gen.c")
    if not os.path.exists(exe) or os.path.getmtime(exe) < os.path.getmtime(src):
        os.makedirs(os.path.dirname(exe), exist_ok=True)
        sh("cc", "-O2", "-o", exe, src, "-lm")
    out = os.path.join(DATA, scale_name(a.rows))
    t0 = time.time()
    sh(exe, out, str(a.rows), str(a.seed), capture=False)
    log(f"generated {out} in {time.time() - t0:.1f}s")


def machine_info():
    cpu = ""
    try:
        cpu = next(l.split(":", 1)[1].strip() for l in open("/proc/cpuinfo") if l.startswith("model name"))
    except (OSError, StopIteration):
        pass
    mem = os.sysconf("SC_PAGE_SIZE") * os.sysconf("SC_PHYS_PAGES")
    return {"cpu": cpu, "cores": os.cpu_count(), "mem_gib": round(mem / 2**30, 1),
            "kernel": platform.release(), "container_memory_limit": MEMORY}


def cmd_run(a):
    scale = scale_name(a.rows)
    data_dir = os.path.join(DATA, scale)
    if not os.path.exists(os.path.join(data_dir, "fact.csv")):
        sys.exit(f"no data at {data_dir}; run: bench.py gen --rows {a.rows}")
    os.makedirs(RESULTS, exist_ok=True)
    expected_path = os.path.join(RESULTS, f"expected-{scale}.json")
    expected = json.load(open(expected_path)) if os.path.exists(expected_path) else {}
    can_drop = drop_caches()
    if a.cold and not can_drop:
        log("warning: cannot drop the page cache; cold runs only restart the server")

    for name in a.engines:
        eng = make_engine(name, data_dir)
        log(f"== {name}: starting")
        eng.destroy()
        res = {"engine": name, "scale": scale, "rows": a.rows, "label": a.label,
               "started": datetime.datetime.now().isoformat(timespec="seconds"),
               "machine": machine_info(), "cold_runs": a.cold, "page_cache_dropped": can_drop,
               "queries": {}}
        try:
            eng.start()
            res["version"] = eng.version()
            log(f"{name}: {res['version']}")
            t0 = time.time()
            res["load_steps"] = eng.load()
            res["load_s"] = time.time() - t0
            log(f"{name}: loaded in {res['load_s']:.1f}s {fmt_steps(res['load_steps'])}")
            res["sizes"] = eng.sizes()
            log(f"{name}: fact {res['sizes'].get('fact', 0) / 2**30:.2f} GiB")
            for qname, sql in load_queries(ENGINES[name]["kind"], a.only):
                q = res["queries"][qname] = {}
                try:
                    if a.cold:
                        eng.restart()
                        q["cold_s"], rows = eng.run(sql)
                    hot = []
                    for _ in range(a.runs):
                        t, rows = eng.run(sql)
                        hot.append(t)
                    q["hot_s"] = hot
                    norm = [[norm_cell(v) for v in r] for r in rows]
                    q["rows"] = len(norm)
                    if qname in expected:
                        q["correct"] = same(norm, expected[qname]["result"])
                        if not q["correct"]:
                            q["result"] = norm
                    else:
                        expected[qname] = {"engine": name, "result": norm}
                        q["correct"] = None  # this engine is the reference
                    log(f"{name} {qname}: "
                        + (f"cold {q['cold_s']:.3f}s " if "cold_s" in q else "")
                        + f"hot min {min(hot):.3f}s  rows {len(norm)}"
                        + ("" if q["correct"] is not False else "  ** RESULT MISMATCH **"))
                except Exception as e:  # noqa: BLE001
                    q["error"] = str(e)[:2000]
                    log(f"{name} {qname}: ERROR {q['error'][:300]}")
                    try:
                        eng.wait_ready(timeout=120)
                    except Exception:  # noqa: BLE001
                        eng.restart()
        except Exception as e:  # noqa: BLE001
            res["error"] = str(e)[:4000]
            log(f"{name}: FAILED {res['error'][:1000]}")
        finally:
            res["finished"] = datetime.datetime.now().isoformat(timespec="seconds")
            label = f"{a.label}-" if a.label else ""
            path = os.path.join(RESULTS, f"{label}{scale}-{name}.json")
            json.dump(res, open(path, "w"), indent=1)
            json.dump(expected, open(expected_path, "w"), indent=1)
            log(f"{name}: wrote {os.path.relpath(path, HERE)}")
            if "error" in res or any("error" in q for q in res["queries"].values()):
                logs = sh("docker", "logs", "--tail", "300", eng.container, check=False, merge=True)
                state = sh("docker", "inspect", "-f", "{{.State.Status}} exit={{.State.ExitCode}} "
                           "oom={{.State.OOMKilled}}", eng.container, check=False, merge=True)
                lpath = path[:-5] + ".log"
                open(lpath, "w").write(f"container: {state}\n\n{logs}\n")
                log(f"{name}: container {state}; server log in {os.path.relpath(lpath, HERE)}")
            if not a.keep:
                eng.destroy()
    cmd_report(a)


def fmt_steps(steps):
    return "(" + ", ".join(f"{k} {v:.1f}s" for k, v in steps.items()) + ")"


def cmd_report(a):
    files = sorted(glob.glob(os.path.join(RESULTS, "*.json")))
    runs = [json.load(open(f)) for f in files if not os.path.basename(f).startswith("expected-")]
    by_scale = {}
    for r in runs:
        by_scale.setdefault((r.get("label") or "", r["scale"]), {})[r["engine"]] = r
    order = list(ENGINES)
    out = ["# OLAP benchmark results", "",
           "Generated by `bench.py report`. Times are seconds, client wall clock. "
           "*cold*: server restarted and OS page cache dropped before the query; "
           "*hot*: best of the hot runs. ✗ = result differs from the reference engine.", ""]
    for (label, scale), engines in sorted(by_scale.items(), key=lambda kv: (kv[0][0], -next(iter(kv[1].values()))["rows"])):
        names = [n for n in order if n in engines]
        any_r = engines[names[0]]
        m = any_r["machine"]
        out += [f"## {scale}{' — ' + label if label else ''}: {any_r['rows']:,} fact rows", "",
                f"{m['cpu']}, {m['cores']} cores, {m['mem_gib']} GiB RAM; containers limited to "
                f"{m['container_memory_limit']}.", ""]
        out += ["| | " + " | ".join(names) + " |", "|---|" + "---|" * len(names)]
        out.append("| version | " + " | ".join(short_version(engines[n].get("version", "?")) for n in names) + " |")
        out.append("| load (s) | " + " | ".join(f"{engines[n].get('load_s', float('nan')):.0f}" for n in names) + " |")
        out.append("| fact size (GiB) | " + " | ".join(
            f"{engines[n].get('sizes', {}).get('fact', 0) / 2**30:.2f}" for n in names) + " |")
        out.append("")
        qnames = sorted({q for n in names for q in engines[n]["queries"]})
        for mode in ("hot", "cold"):
            if mode == "cold" and not any(engines[n].get("cold_runs") for n in names):
                continue
            out += [f"### {mode}", "", "| query | " + " | ".join(names) + " |",
                    "|---|" + "---:|" * len(names)]
            geo = {n: [] for n in names}
            for qn in qnames:
                cells = []
                for n in names:
                    q = engines[n]["queries"].get(qn, {})
                    t = (min(q["hot_s"]) if q.get("hot_s") else None) if mode == "hot" else q.get("cold_s")
                    if "error" in q:
                        cells.append("error")
                    elif t is None:
                        cells.append("–")
                    else:
                        geo[n].append(t)
                        cells.append(f"{t:.3f}" + (" ✗" if q.get("correct") is False else ""))
                out.append(f"| {qn} | " + " | ".join(cells) + " |")
            out.append("| **geomean** | " + " | ".join(
                f"**{geomean(geo[n]):.3f}**" if len(geo[n]) == len(qnames) else "n/a" for n in names) + " |")
            out.append("")
        errs = [(n, qn, q["error"]) for n in names for qn, q in engines[n]["queries"].items() if "error" in q]
        if errs:
            out += ["Errors:", ""] + [f"- {n} {qn}: `{e.splitlines()[0][:200]}`" for n, qn, e in errs] + [""]
    path = os.path.join(RESULTS, "report.md")
    open(path, "w").write("\n".join(out))
    log(f"wrote {os.path.relpath(path, HERE)}")


def short_version(v):
    m = re.search(r"pgrust [\w.-]+", v) or re.search(r"PostgreSQL [\d.]+", v) or re.search(r"ClickHouse [\d.]+", v)
    return m.group(0) if m else v[:40]


def geomean(xs):
    import math
    return math.exp(sum(math.log(max(x, 1e-6)) for x in xs) / len(xs)) if xs else float("nan")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    g = sub.add_parser("gen")
    g.add_argument("--rows", type=int, default=100_000_000)
    g.add_argument("--seed", type=int, default=42)
    r = sub.add_parser("run")
    r.add_argument("--rows", type=int, default=100_000_000)
    r.add_argument("--engines", type=lambda s: s.split(","), default=list(ENGINES))
    r.add_argument("--runs", type=int, default=3, help="hot runs per query")
    r.add_argument("--no-cold", dest="cold", action="store_false")
    r.add_argument("--only", type=lambda s: s.split(","), help="query name prefixes, e.g. q01,q06")
    r.add_argument("--label", default="", help="prefix for results files")
    r.add_argument("--keep", action="store_true", help="leave the container and data volume")
    sub.add_parser("report")
    a = p.parse_args()
    {"gen": cmd_gen, "run": cmd_run, "report": cmd_report}[a.cmd](a)


if __name__ == "__main__":
    main()
