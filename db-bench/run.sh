#!/usr/bin/env bash
# Run the benchmark against each database in turn, fresh data dir each time, one server
# up at a time. Usage: ./run.sh [durable|relaxed ...] [-- harness flags]
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
DBS=${DBS:-$here/.dbs}
ARCADE_HOME=${ARCADE_HOME:-$(ls -d "$DBS"/arcadedb-* | tail -1)}
SURREAL=${SURREAL:-$DBS/surreal}
DATA=${DATA:-$DBS/data}
OUT=${OUT:-$here/results}
HEAP=${HEAP:-4G}
export ARCADE_PASS=benchpass1

modes=(); while [ $# -gt 0 ] && [ "$1" != -- ]; do modes+=("$1"); shift; done
[ "${1:-}" = -- ] && shift
[ ${#modes[@]} -eq 0 ] && modes=(durable relaxed)

mkdir -p "$OUT"
(cd "$here" && go build -o "$DBS/db-bench" .)
unset _JAVA_OPTIONS   # this sandbox sets one; it's not part of ArcadeDB's config
sv=$("$SURREAL" version | awk '{print $1}')
av=$(basename "$ARCADE_HOME" | sed 's/arcadedb-//')
stamp=$(date +%Y%m%d-%H%M%S)

wait_http() { for _ in $(seq 120); do curl -sf -o /dev/null "$1" && return 0; sleep 0.5; done; echo "server didn't come up: $1" >&2; return 1; }
stats() { # pid, data dir -> "peak_rss_mb disk_mb"
  local hwm; hwm=$(awk '/VmHWM/{print int($2/1024)}' /proc/$1/status)
  echo "$hwm $(du -sm "$2" | cut -f1)"
}

for mode in "${modes[@]}"; do
  # durable: fsync the log on every commit (SurrealDB's default; ArcadeDB txWalFlush=2)
  # relaxed: leave flushing to the OS (ArcadeDB's default; SurrealDB ?sync=never)
  if [ "$mode" = durable ]; then wal=2; ssync=every; else wal=0; ssync=never; fi

  for db in ${DB_LIST:-arcadedb surrealdb surrealdb-ws}; do
    pkill -f com.arcadedb.server.ArcadeDBServer || true; pkill -x surreal || true; sleep 1
    rm -rf "$DATA"; mkdir -p "$DATA"
    if [ $db = arcadedb ]; then
      rm -rf "$ARCADE_HOME/databases/bench"
      (cd "$ARCADE_HOME" && ARCADEDB_OPTS_MEMORY="-Xms$HEAP -Xmx$HEAP" nohup bin/server.sh \
        -Darcadedb.server.rootPassword=$ARCADE_PASS -Darcadedb.txWalFlush=$wal \
        -Darcadedb.server.databaseDirectory="$DATA" > "$DATA/../arcadedb.log" 2>&1 &)
      url=http://127.0.0.1:2480; wait_http $url/api/v1/ready; ver=$av
      pid=$(pgrep -f com.arcadedb.server.ArcadeDBServer)
    else
      nohup "$SURREAL" start -u root -p root -b 127.0.0.1:8000 "surrealkv://$DATA/surreal?sync=$ssync" \
        > "$DATA/../surrealdb.log" 2>&1 &
      url=http://127.0.0.1:8000; wait_http $url/health; ver=$sv
      pid=$(pgrep -x surreal)
    fi
    echo "=== $db $ver ($mode)"
    f="$OUT/$db-$ver-$mode-$stamp.json"
    "$DBS/db-bench" -db $db -url $url -version "$ver" -mode $mode -out "$f" "$@"
    read -r rss disk < <(stats "$pid" "$DATA")
    echo "server peak RSS ${rss}MB, data on disk ${disk}MB"
    python3 - "$f" "$rss" "$disk" <<'PY'
import json,sys; p=sys.argv[1]; r=json.load(open(p)); r["server"]={"peak_rss_mb":int(sys.argv[2]),"disk_mb":int(sys.argv[3])}; json.dump(r,open(p,"w"),indent=2)
PY
  done
done
pkill -f com.arcadedb.server.ArcadeDBServer || true; pkill -x surreal || true
