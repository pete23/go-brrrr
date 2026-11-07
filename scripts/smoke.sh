#!/usr/bin/env bash
# Usage: smoke.sh <server-binary> <h2o-httpclient> <port>
set -u
bin=$1 h3client=$2 port=$3
base="https://127.0.0.1:$port"

"$bin" >/dev/null 2>&1 &
pid=$!
trap 'kill $pid 2>/dev/null' EXIT
for _ in $(seq 50); do curl -sk -o /dev/null "$base/" && break; sleep 0.1; done

fail=0
check() { # name expected actual
  if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: want '$2' got '$3'"; fail=1; fi
}
# body, status, content-type
get() { curl -sk -w ' %{http_code} %{content_type}' "$@"; }

check "h1 GET /"               "23 200 text/plain"   "$(get --http1.1 "$base/")"
check "h2 GET /"               "23 200 text/plain"   "$(get --http2 "$base/")"
check "h3 GET /"               "23"                  "$("$h3client" -3 100 -k "$base/" 2>/dev/null)"
check "h2 HEAD /"              " 200 text/plain"     "$(get --http2 -I -o /dev/null "$base/")"
check "GET /add query params"  "23 200 text/plain"   "$(get "$base/add?a=20&b=3")"
check "POST /add form params"  "42 200 text/plain"   "$(get -d 'a=40&b=2' "$base/add")"
check "POST /add bad params"   "need integer a and b 400 " "$(get -d 'a=x' "$base/add")"
check "GET /nope"              "not found 404 "      "$(get "$base/nope")"
exit $fail
