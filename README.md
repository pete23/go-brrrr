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

Plus [Leiningen](https://leiningen.org/) (jank's project tool, via the
[lein-jank](https://book.jank-lang.org/getting-started/03-hello-leiningen.html) plugin),
[Babashka](https://babashka.org) (tasks, jank build scripts, integration tests) and
bubblewrap (jank-build's sandbox), and the native toolchain:

```bash
sudo apt install -y leiningen bubblewrap clang cmake ninja-build libssl-dev zlib1g-dev curl
bash <(curl -s https://raw.githubusercontent.com/babashka/babashka/master/install)
```

## Use

```bash
bb run [port]          # JIT via `lein run`; https://0.0.0.0:8443 by default
bb build               # release binary at target/release/go-brrrr (statically linked)
bb test                # everything below
bb test:unit           # `lein test`: ported ring tests plus the adapter's pure helpers
bb test:integration    # the adapter against test/server/echo.jank, over h1, h2 and h3
bb test:app            # the app's endpoints, as the release binary and under the JIT
bb tasks               # the rest
```

`lein run`, `lein test` and `lein compile` work directly too; the bb tasks add the
submodule check, the dev cert and the release-build workaround below.

## How it builds

`project.clj` uses lein-jank, which runs the top-level `jank-build.bb` (sandboxed, with
Babashka) before every run, test or compile. That script builds `vendor/h2o` with CMake and
the C shim in `native/`, installs them into the jank-build output dir under
`target/<profile>/_cache/`, and prints the include/link directives lein-jank passes to jank.
It only re-runs when `native/`, `vendor/h2o` or the script itself change.

jank's JIT can't link h2o's thread-local-storage relocations from a static archive (no
initial-exec or local-dynamic support), so linking depends on the profile:

- default (`run`, `test`, debug `compile`): `libbrrrr.so`, the shim with h2o inside. Its
  SONAME is its absolute path, so debug binaries find it without an rpath.
- `release`: fully static. jank's compile-time JIT loads previously cached module objects
  and would then pull the archive into the JIT, so `bb build` clears jank's module cache for
  the project first (the native build output is kept).

## Tests

- **Unit** (`test/ring/**`, run by `lein test`, ~70 tests): upstream ring-core and
  ring-codec tests for every ported namespace. Where we deviate (UTF-8 only, no files or
  InputStreams, ASCII-only keyword params), the upstream case is replaced by one asserting
  what we actually do, so deviations are pinned rather than silently untested.
- **Integration** (`test/integration/*_test.clj`, Babashka `clojure.test` driving `curl`
  and `h2o-httpclient`): the Ring request map on every protocol; exact, binary (NUL
  bytes), large, chunked and streamed request bodies; status codes, headers, HEAD,
  content-length; incremental response delivery (time-to-first-byte vs total); handler
  errors before and during streaming; client disconnects mid-upload and mid-download;
  worker recovery; parallel and multiplexed load; unread bodies on keep-alive; body size
  limits; and RSS staying flat while 400MB streams through. Each namespace starts its own
  server on a free port, and every test fails if the server died.
- **App** (`test/integration/app_test.clj`): every endpoint over h1/h2/h3, as the release
  binary and under `lein run`.

curl in Ubuntu has no HTTP/3, so h3 tests use h2o's own `h2o-httpclient`, which
`jank-build.bb` builds alongside h2o.

## Layout

- `src/go_brrrr/main.jank` – the app: a plain Ring handler plus middleware.
- `src/ring/adapter/h2o.jank` – `run-h2o`. Builds Ring request maps in jank and streams
  bodies both ways; a small `cpp/raw` bridge marshals between them and the C shim.
- `src/ring/...` – the parts of ring-core (and ring-codec) ported to jank so far. Each file
  says what changed from upstream. MIT, see `src/ring/LICENSE`.
  - `ring.util.codec`, `.parsing`, `.request`, `.response` (minus file/resource responses),
    `.mime-type`
  - `ring.middleware.params`, `.nested-params`, `.keyword-params`, `.head`, `.content-type`
- `native/brrrr.{h,c}` – small C facade over libh2o: TLS/TCP listener (h1+h2, OpenSSL) and
  QUIC/UDP listener (h3, quicly+picotls on OpenSSL 3.0 primitives) on the same port, a
  worker thread pool, and per-request streaming with backpressure (see below).
- `project.clj`, `jank-build.bb`, `bb.edn` – lein-jank project, native build script, tasks.
- `test/` – unit tests (jank), the echo test server (jank), integration suites (bb).
- `vendor/h2o` – h2o submodule (master), built as a static `libh2o-evloop` with mruby,
  brotli, zstd, io_uring etc. switched off.

## Streaming and threads

h2o's event loop runs on the thread that calls `run-h2o`; handlers run on a pool of worker
threads (`:threads`, default 8), so they may block. Each request is an *exchange* shared by
the two sides: request body chunks flow loop → worker and response chunks worker → loop,
each direction buffered up to 1MB. Past that, h2o stops reading from the client, or
`write!` blocks until the client catches up, so memory stays bounded whatever the body size.
Workers never touch h2o; they poke the loop, which does all h2o calls.

```clojure
(defn upload [{:keys [body]}]          ; body: reader fn (nil if no body); returns nil at end
  (loop [n 0]
    (if-let [chunk (and body (body))]
      (recur (+ n (count chunk)))
      {:status 200 :body (str n)})))

(cpp/raw "#include <unistd.h>")

(defn ticker [_]                       ; one line a second, delivered as written
  {:status 200
   :body (fn [write!] (dotimes [i 10] (write! (str i "\n")) (cpp/usleep (cpp/cast cpp/uint 1000000))))})
```

A failure after the response has started aborts it (h1 drops the connection, h2 resets the
stream) so clients can tell a truncated body from a complete one. **Except over HTTP/3:**
h2o's h3 server finishes the stream cleanly on error (TODO in `lib/http3/server.c`), so an h3
client cannot detect truncation of a streamed body. Fixing that needs a small h2o patch.

## Deviations from the Ring spec

Until jank has protocols and streams:

- Request `:body` is a reader fn standing in for an `InputStream`: each call returns the next
  chunk as a string (blocking until it arrives), nil at the end. Consumed once, nothing
  retained. `ring.util.request/body-string` reads it all. nil when there is no body.
- Response `:body` may be nil, a string (sent with content-length), a seq (each element
  `str`'d and sent as it is realized, so a lazy seq streams), or a fn of one `write!` arg.
  No files yet.
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
- An unmatched regex group is `""`, not nil, so `(or (m 1) (m 2))` picks the wrong branch.
- `(char 200)` is a 2-byte UTF-8 string; build raw bytes some other way (e.g. percent-decode).
- A string literal bound in a `let` is a native `const char[N]`, and closures can't capture it.
- Functions take at most 10 fixed parameters.
- `(thrown? :default ...)` works in clojure.test; `thrown-with-msg?` crashes (see the nil-binding
  `catch :default` bug above).
- jank's JIT can't link TLS relocations (local-dynamic, initial-exec) from static archives,
  and `jank compile` loads previously cached module objects into that JIT. See "How it
  builds" for how this project copes.

## h2o gotchas found so far

- Ignore SIGPIPE, or a client closing mid-write kills the process.
- `h2o_evloop_run` returns -1 when `epoll_wait` is interrupted (e.g. by the GC stopping the
  world from a worker); keep looping.
- The HTTP/3 server sends NEW_TOKEN frames once ~200KB has gone out and dereferences
  `generate_resumption_token` without checking it, so one must be configured.
- HEAD responses: h1/h2 drop the body, h3 doesn't; the shim drops it for all three.

## Spike-grade

No QUIC retry/address validation (resumption tokens are issued but never checked), no
connection limits, no graceful shutdown, IPv4 only, self-signed cert, h3 truncation not
signalled (above).
