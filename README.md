# go-brrrr

A [jank](https://jank-lang.org) Ring app served over HTTP/1.1, HTTP/2 and HTTP/3 by
[h2o](https://github.com/h2o/h2o), used as a library through a new `ring.adapter.h2o`.

- `GET /` → `23` (`text/plain`)
- `GET|POST /add` with `a` and `b` (query string or form) → their sum

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
make test   # starts the binary and checks the endpoints over h1, h2 and h3
```

curl in Ubuntu has no HTTP/3, so `make test` uses h2o's own `h2o-httpclient -3`.

## Layout

- `src/go_brrrr/main.jank` – the app: a plain Ring handler plus middleware.
- `src/ring/adapter/h2o.jank` – `run-h2o`. Builds Ring request maps in jank; a small
  `cpp/raw` bridge marshals between them and the C shim.
- `src/ring/...` – the parts of ring-core (and ring-codec) ported to jank so far. Each file
  says what changed from upstream. MIT, see `src/ring/LICENSE`.
  - `ring.util.codec`, `.parsing`, `.request`, `.response` (minus file/resource responses),
    `.mime-type`
  - `ring.middleware.params`, `.nested-params`, `.keyword-params`, `.head`, `.content-type`
- `native/brrrr.{h,c}` – tiny C facade over libh2o: TLS/TCP listener (h1+h2, OpenSSL) and
  QUIC/UDP listener (h3, quicly+picotls on OpenSSL 3.0 primitives) on the same port.
- `vendor/h2o` – h2o submodule (master), built as a static `libh2o-evloop` with mruby,
  brotli, zstd, io_uring etc. switched off.

## Deviations from the Ring spec

Until jank has protocols and streams:

- Request `:body` is a string holding the whole buffered body (or nil), not an `InputStream`.
- Response `:body` may be nil, a string, or a seq of strings. No files or streams yet.
- Sync (1-arity) handlers only. One server per process; `run-h2o` blocks.
- Codec functions are UTF-8 only; the encoding argument is accepted and ignored.
- `:protocol` is `HTTP/1.1`, `HTTP/2.0` or `HTTP/3.0`, as Jetty reports them.

## jank gotchas found so far

- No protocols, records, `deftype`, `reify` or `import` (documented upstream).
- Only `.jank`/`.cljc` are loaded, never `.clj`; the reader feature is `:jank`.
- `-main`'s return value is ignored, so exit codes go through `cpp/std.exit`.
- Regexes are C++ ECMAScript-style: no `(?i)`, `(?s)` or lookbehind (parse errors), and
  `\p{L}` inside a character class silently means the literal characters `p{L}`.
- `(catch :default e ...)` binds `e` to nil; catch `cpp/jank.runtime.object_ref` and
  `cpp/std.exception` instead.
- Non-ASCII characters in string literals fail to lex (`"é"` is an unterminated string).

## Spike-grade

No QUIC retry/address validation, no connection limits, no graceful shutdown, IPv4 only,
self-signed cert. Single-threaded event loop; the handler runs on it, so don't block.
