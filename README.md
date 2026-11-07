# go-brrrr

A [jank](https://jank-lang.org) HTTPS server whose `GET /` returns `(+ 20 3)` as `text/plain`,
over HTTP/1.1, HTTP/2 and HTTP/3, via [h2o](https://github.com/h2o/h2o) used as a library.

## Install jank (Ubuntu 24.04+)

```bash
curl -s https://ppa.jank-lang.org/KEY.gpg | gpg --dearmor | sudo tee /etc/apt/trusted.gpg.d/jank.gpg >/dev/null
echo "deb [signed-by=/etc/apt/trusted.gpg.d/jank.gpg] https://ppa.jank-lang.org $(lsb_release -cs) main" \
  | sudo tee /etc/apt/sources.list.d/jank.list >/dev/null
sudo apt update && sudo apt install -y jank
```

Build deps (already in the cloud image): `clang cmake ninja-build libssl-dev zlib1g-dev`.

## Use

```bash
make run    # JIT; serves https://0.0.0.0:8443 (self-signed cert generated in certs/)
make build  # AOT native binary at target/go-brrrr
make test   # starts the binary and checks GET / over h1, h2 and h3
```

curl in Ubuntu has no HTTP/3, so `make test` uses h2o's own `h2o-httpclient -3`.

## Layout

- `src/go_brrrr/main.jank` – the `handle` fn (method, path → body or nil for 404), plus a
  `cpp/raw` bridge that lets the C callback call back into jank.
- `native/brrrr.{h,c}` – tiny C facade over libh2o: TLS/TCP listener (h1+h2, OpenSSL) and
  QUIC/UDP listener (h3, quicly+picotls on OpenSSL 3.0 primitives) on the same port. One handler.
- `vendor/h2o` – h2o submodule (master), built as a static `libh2o-evloop` with mruby,
  brotli, zstd, io_uring etc. switched off.

## Notes

- jank ignores `-main`'s return value, so exit codes go through `cpp/std.exit`.
- Single-threaded event loop; the jank handler runs on it, so don't block in `handle`.
- Spike-grade: no QUIC retry/address validation, no connection limits, no graceful shutdown,
  IPv4 only.
