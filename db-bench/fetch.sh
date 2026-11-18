#!/usr/bin/env bash
# Fetch ArcadeDB (Maven Central) and SurrealDB (binary lifted out of the Docker Hub image,
# no Docker daemon needed) into $DBS (default ./.dbs).
set -euo pipefail
DBS=${DBS:-$(dirname "$0")/.dbs}
ARCADEDB_VERSION=${ARCADEDB_VERSION:-26.11.1}
SURREAL_TAG=${SURREAL_TAG:-latest}
mkdir -p "$DBS"; cd "$DBS"

if [ ! -d "arcadedb-$ARCADEDB_VERSION" ]; then
  curl -sSfL https://repo1.maven.org/maven2/com/arcadedb/arcadedb-package/$ARCADEDB_VERSION/arcadedb-package-$ARCADEDB_VERSION.tar.gz | tar xz
fi

if [ ! -x surreal ]; then
  repo=surrealdb/surrealdb
  tok=$(curl -sS "https://auth.docker.io/token?service=registry.docker.io&scope=repository:$repo:pull" | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
  reg=https://registry-1.docker.io/v2/$repo
  digest=$(curl -sS -H "Authorization: Bearer $tok" -H "Accept: application/vnd.oci.image.index.v1+json" "$reg/manifests/$SURREAL_TAG" \
    | python3 -c 'import sys,json;print([m["digest"] for m in json.load(sys.stdin)["manifests"] if m["platform"]["architecture"]=="amd64" and m["platform"]["os"]=="linux"][0])')
  mkdir -p surreal-root
  for layer in $(curl -sS -H "Authorization: Bearer $tok" -H "Accept: application/vnd.oci.image.manifest.v1+json" "$reg/manifests/$digest" \
      | python3 -c 'import sys,json;[print(l["digest"]) for l in json.load(sys.stdin)["layers"]]'); do
    curl -sSL -H "Authorization: Bearer $tok" "$reg/blobs/$layer" | tar xz -C surreal-root 2>/dev/null || true
  done
  cp surreal-root/surreal . && rm -rf surreal-root
fi
./surreal version
ls -d arcadedb-*
