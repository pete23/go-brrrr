#!/usr/bin/env bash
# Builds h3load: nghttp2's h2load with HTTP/3, patched (h3load.patch) to time a response
# when it ends rather than when its stream closes. Installs it as ~/bin/h3load.
#
# macOS: brew install libngtcp2 libnghttp3 openssl@4 libev c-ares jemalloc pkgconf
# (libnghttp3 >= 1.17). bb bench uses ~/bin/h3load for h3 when it exists.
set -euo pipefail
version=1.71.0
here="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cd "$work"
curl -sfL -o nghttp2.tar.xz "https://github.com/nghttp2/nghttp2/releases/download/v$version/nghttp2-$version.tar.xz"
tar xf nghttp2.tar.xz
cd "nghttp2-$version"
patch -p1 < "$here/h3load.patch"
if command -v brew >/dev/null; then
  b="$(brew --prefix)/opt"
  export PKG_CONFIG_PATH="$b/libngtcp2/lib/pkgconfig:$b/libnghttp3/lib/pkgconfig:$b/openssl@4/lib/pkgconfig:$b/libev/lib/pkgconfig:$b/c-ares/lib/pkgconfig:$b/jemalloc/lib/pkgconfig"
  export LIBEV_CFLAGS="-I$b/libev/include" LIBEV_LIBS="-L$b/libev/lib -lev"
fi
./configure --enable-app --enable-http3 --disable-shared --enable-static >/dev/null
make -j"$(getconf _NPROCESSORS_ONLN)" -C lib >/dev/null
make -j"$(getconf _NPROCESSORS_ONLN)" -C third-party >/dev/null
make -j"$(getconf _NPROCESSORS_ONLN)" -C src h2load >/dev/null
mkdir -p "$HOME/bin"
cp src/h2load "$HOME/bin/h3load"
echo "installed $HOME/bin/h3load"
