#!/usr/bin/env bash
# Usage: smoke.sh <server-binary> <h2o-httpclient> <port>
set -u
bin=$1 h3client=$2 port=$3
url="https://127.0.0.1:$port/"

"$bin" >/dev/null 2>&1 &
pid=$!
trap 'kill $pid 2>/dev/null' EXIT
for _ in $(seq 50); do curl -sk -o /dev/null "$url" && break; sleep 0.1; done

fail=0
check() { # name expected actual
  if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: want '$2' got '$3'"; fail=1; fi
}
get() { curl -sk "$1" -w ' %{http_code} %{content_type}' "$url"; }
check "h1 GET /" "23 200 text/plain" "$(get --http1.1)"
check "h2 GET /" "23 200 text/plain" "$(get --http2)"
check "h3 GET /" "23"                "$("$h3client" -3 100 -k "$url" 2>/dev/null)"
check "h2 GET /nope" "404"           "$(curl -sk -o /dev/null -w '%{http_code}' "$url"nope)"
exit $fail
