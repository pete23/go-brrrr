"""Integration tests for ring.adapter.h2o, against test/server/echo.jank built as a binary.

Run via `make test-integration`. ECHO_BIN overrides the server command."""

import os
import shlex
import subprocess
import threading
import time
import unittest

from harness import ROOT, ServerTestCase, curl, get, h3

ECHO = shlex.split(os.environ.get("ECHO_BIN", os.path.join(ROOT, "target/echo-server")))
TCP = ("h1", "h2")
ALL = ("h1", "h2", "h3")
PROTOCOL_NAMES = {"h1": "HTTP/1.1", "h1.0": "HTTP/1.0", "h2": "HTTP/2.0", "h3": "HTTP/3.0"}


class RequestMapTest(ServerTestCase):
    argv = ECHO

    def echo(self, path="/echo", proto="h2", **kw):
        r = get(self.server, path, proto=proto, **kw)
        self.assertEqual(r.status, 200, r)
        return self.parse_echo(r)

    def test_basic_fields_every_protocol(self):
        for proto in ("h1.0", "h1", "h2", "h3"):
            with self.subTest(proto=proto):
                e = self.echo("/echo?x=1&y=2", proto=proto)
                self.assertEqual(e["request-method"], ":get")
                self.assertEqual(e["uri"], "/echo")
                self.assertEqual(e["query-string"], "x=1&y=2")
                self.assertEqual(e["scheme"], ":https")
                self.assertEqual(e["protocol"], PROTOCOL_NAMES[proto])
                self.assertEqual(e["server-name"], "127.0.0.1")
                self.assertEqual(e["server-port"], str(self.server.port))
                self.assertEqual(e["remote-addr"], "127.0.0.1")
                self.assertEqual(e["header.host"], f"127.0.0.1:{self.server.port}", "host header on every protocol")
                self.assertEqual(e["has-body"], "false")

    def test_server_name_from_host(self):
        r = curl(f"https://localhost:{self.server.port}/echo", proto="h2")
        self.assertEqual(self.parse_echo(r)["server-name"], "localhost")
        r = curl(self.server.url("/echo"), "-H", "Host: example.com:1234", proto="h1")
        e = self.parse_echo(r)
        self.assertEqual(e["server-name"], "example.com")
        self.assertEqual(e["header.host"], "example.com:1234")

    def test_absent_vs_empty_query_string(self):
        for proto in ALL:
            with self.subTest(proto=proto):
                e = self.echo("/echo", proto=proto)
                self.assertEqual(e["has-query"], "false")
                e = self.echo("/echo?", proto=proto)
                self.assertEqual(e["has-query"], "true")
                self.assertEqual(e["query-string"], "")

    def test_uri_is_not_decoded(self):
        for proto in ALL:
            with self.subTest(proto=proto):
                e = self.echo("/echo?a=%20&b=%2F", proto=proto)
                self.assertEqual(e["query-string"], "a=%20&b=%2F")
        r = curl(self.server.url("/a%20b/c%2Fd"), proto="h1")
        self.assertEqual(r.status, 404)
        self.assertEqual(r.body, b"not found")
        e = self.parse_echo(curl(self.server.url("/echo"), "--path-as-is", proto="h1"))
        self.assertEqual(e["uri"], "/echo")

    def test_methods(self):
        for method in ("POST", "PUT", "DELETE", "PATCH", "OPTIONS", "PURGE"):
            with self.subTest(method=method):
                r = curl(self.server.url("/echo"), "-X", method, proto="h2")
                self.assertEqual(self.parse_echo(r)["request-method"], ":" + method.lower())
        self.assertEqual(self.echo(proto="h3", method="DELETE")["request-method"], ":delete")

    def test_headers_lowercased_and_repeats_joined(self):
        for proto in TCP:
            with self.subTest(proto=proto):
                r = curl(self.server.url("/echo"), "-H", "X-Mixed-CASE: v", "-H", "X-Dup: 1", "-H", "X-Dup: 2", proto=proto)
                e = self.parse_echo(r)
                self.assertEqual(e["header.x-mixed-case"], "v")
                self.assertEqual(e["header.x-dup"], "1,2")
        e = self.echo(proto="h3", headers=["X-Three: 3"])
        self.assertEqual(e["header.x-three"], "3")

    def test_content_length_header_present(self):
        for proto in ALL:
            with self.subTest(proto=proto):
                if proto == "h3":
                    e = self.echo(proto="h3", method="POST", body_size=7)
                else:
                    e = self.echo(proto=proto, data=b"1234567")
                self.assertEqual(e["header.content-length"], "7")
                self.assertEqual(e["body-length"], "7")
                self.assertEqual(e["has-body"], "true")

    def test_empty_post_has_empty_body(self):
        e = self.echo(proto="h1", data=b"")
        self.assertEqual(e["header.content-length"], "0")
        self.assertEqual(e["body-length"], "0")

    def test_get_has_no_body(self):
        for proto in ALL:
            with self.subTest(proto=proto):
                e = self.echo(proto=proto)
                self.assertEqual(e["has-body"], "false")
                self.assertNotIn("header.content-length", e)


class BodyTest(ServerTestCase):
    argv = ECHO

    def test_echo_body_exact(self):
        body = "héllo wörld\n".encode() * 10
        for proto in ALL:
            with self.subTest(proto=proto):
                r = get(self.server, "/echo", proto=proto, data=body)
                self.assertEqual(self.parse_echo(r)["body"].encode(), body)

    def test_binary_round_trip(self):
        body = bytes(range(256)) * 400  # includes NUL and every byte value
        for proto in TCP:
            with self.subTest(proto=proto):
                r = curl(self.server.url("/mirror"), proto=proto, data=body)
                self.assertEqual(r.status, 200)
                self.assertEqual(r.body, body)
        r = h3(self.server.url("/mirror"), method="POST", data=body)
        self.assertEqual(r.body, body)

    def test_binary_response(self):
        for proto in ALL:
            with self.subTest(proto=proto):
                r = get(self.server, "/binary", proto=proto)
                self.assertEqual(r.body, bytes(range(256)))

    def test_large_body_mirror(self):
        body = os.urandom(8 * 1024 * 1024)
        for proto in TCP:
            with self.subTest(proto=proto):
                r = curl(self.server.url("/mirror"), proto=proto, data=body)
                self.assertEqual(len(r.body), len(body))
                self.assertEqual(r.body, body)

    def test_chunked_upload_without_content_length(self):
        body = b"x" * (3 * 1024 * 1024 + 17)
        r = curl(self.server.url("/count"), "-T", "-", "-H", "Transfer-Encoding: chunked", proto="h1", stdin=body)
        self.assertEqual(r.status, 200, r)
        self.assertEqual(r.text.split()[0], str(len(body)))

    def test_h3_streamed_upload(self):
        # 40 chunks of 1000 bytes, 10ms apart: arrives as many separate chunks
        r = h3(self.server.url("/count"), method="POST", body_size=40000, chunk=1000, interval=10)
        self.assertEqual(r.status, 200, r)
        size, chunks = r.text.split()
        self.assertEqual(size, "40000")
        self.assertGreater(int(chunks), 1, "body should arrive in more than one chunk")

    def test_request_body_streams_to_handler(self):
        """The handler sees the first chunk before the client has finished sending."""
        r = h3(self.server.url("/mirror"), method="POST", body_size=20, chunk=1, interval=50)
        self.assertEqual(r.body, b"a" * 20)


class ResponseTest(ServerTestCase):
    argv = ECHO

    def test_status_codes(self):
        for code in (200, 201, 202, 301, 302, 400, 404, 418, 500, 503):
            for proto in ALL:
                with self.subTest(code=code, proto=proto):
                    r = get(self.server, f"/status?code={code}", proto=proto)
                    self.assertEqual(r.status, code)

    def test_204_has_no_body(self):
        for proto in TCP:
            with self.subTest(proto=proto):
                r = get(self.server, "/status?code=204", proto=proto)
                self.assertEqual(r.status, 204)
                self.assertEqual(r.body, b"")

    def test_headers(self):
        for proto in ALL:
            with self.subTest(proto=proto):
                r = get(self.server, "/status?code=302", proto=proto)
                self.assertEqual(r.header("location"), "/elsewhere")
                self.assertEqual(r.header_all("x-multi"), ["a", "b"], "vector values become repeated headers")
                self.assertEqual(r.header("x-mixed-case"), "kept", "names are lowercased on the wire")

    def test_handler_content_length_is_ignored(self):
        """The handler claims Content-Length: 999; the real length must win."""
        for proto in ALL:
            with self.subTest(proto=proto):
                r = get(self.server, "/status?code=200", proto=proto)
                self.assertEqual(r.body, b"status body")
                self.assertEqual(r.header_all("content-length"), ["11"])

    def test_string_body_has_content_length(self):
        for proto in ALL:
            with self.subTest(proto=proto):
                r = get(self.server, "/binary", proto=proto)
                self.assertEqual(r.header("content-length"), "256")

    def test_streamed_body_has_no_content_length(self):
        for path in ("/stream", "/lazy"):
            with self.subTest(path=path):
                r = get(self.server, path, proto="h1")
                self.assertIsNone(r.header("content-length"))
                self.assertEqual(r.header("transfer-encoding"), "chunked")
                self.assertEqual(r.body, b"000000111111222222")
                r = get(self.server, path, proto="h3")
                self.assertEqual(r.body, b"000000111111222222")

    def test_nil_body(self):
        for proto in ALL:
            with self.subTest(proto=proto):
                r = get(self.server, "/nil-body", proto=proto)
                self.assertEqual(r.status, 200)
                self.assertEqual(r.body, b"")
                self.assertEqual(r.header("content-length"), "0")

    def test_alt_svc_on_tcp_only(self):
        for proto in TCP:
            with self.subTest(proto=proto):
                self.assertEqual(get(self.server, "/echo", proto=proto).header("alt-svc"), f'h3=":{self.server.port}"')
        self.assertIsNone(get(self.server, "/echo", proto="h3").header("alt-svc"))

    def test_head(self):
        """h2o sends the headers of the GET response and no body."""
        for proto in TCP:
            with self.subTest(proto=proto):
                r = curl(self.server.url("/binary"), "-I", proto=proto)
                self.assertEqual(r.status, 200)
                self.assertEqual(r.header("content-length"), "256")
                self.assertEqual(r.info["size_download"], 0)
        r = h3(self.server.url("/binary"), method="HEAD")
        self.assertEqual(r.status, 200)
        self.assertEqual(r.body, b"")


class ErrorTest(ServerTestCase):
    argv = ECHO

    def test_handler_errors_are_500(self):
        for path in ("/throw", "/throw-native", "/bad-body"):
            for proto in ALL:
                with self.subTest(path=path, proto=proto):
                    r = get(self.server, path, proto=proto)
                    self.assertEqual(r.status, 500)
                    self.assertEqual(r.body, b"Internal Server Error")
        log = self.server.log_text()
        self.assertIn('handler threw: #error {\n :cause "boom"', log)
        self.assertIn("The first argument to `subs` must be a `persistent_string`", log)
        self.assertIn("Unsupported response body type", log)

    def test_nil_response_is_404(self):
        for proto in ALL:
            with self.subTest(proto=proto):
                r = get(self.server, "/nil", proto=proto)
                self.assertEqual(r.status, 404)

    def test_failure_mid_stream_aborts_response(self):
        """Once streaming has started, a failure must not look like a complete response."""
        r = curl(self.server.url("/throw-mid-stream"), proto="h1")
        self.assertEqual(r.status, 200)
        self.assertEqual(r.body, b"partial")
        self.assertEqual(r.exit_code, 56, "h1: connection closed without the terminating chunk")
        r = curl(self.server.url("/throw-mid-stream"), proto="h2")
        self.assertEqual(r.body, b"partial")
        self.assertEqual(r.exit_code, 92, "h2: stream reset")
        self.assertIn("mid-stream boom", self.server.log_text())

    def test_failure_mid_stream_h3_known_limitation(self):
        """h2o's HTTP/3 server ends the stream cleanly on error (TODO in lib/http3/server.c), so an
        h3 client cannot tell the body is truncated. This pins the current behaviour."""
        r = h3(self.server.url("/throw-mid-stream"))
        self.assertEqual(r.status, 200)
        self.assertEqual(r.body, b"partial")

    def test_server_healthy_after_errors(self):
        for proto in ALL:
            self.assertEqual(get(self.server, "/echo", proto=proto).status, 200)


class StreamingTest(ServerTestCase):
    argv = ECHO

    def test_response_is_delivered_incrementally(self):
        """With 3 chunks 600ms apart, the first byte arrives long before the last."""
        for path in ("/stream?n=3&delay=600", "/lazy?n=3&delay=600"):
            for proto in TCP:
                with self.subTest(path=path, proto=proto):
                    r = curl(self.server.url(path), proto=proto)
                    self.assertEqual(r.body, b"000000111111222222")
                    ttfb, total = r.info["time_starttransfer"], r.info["time_total"]
                    self.assertLess(ttfb, 0.5, r.info)
                    self.assertGreater(total, 1.1, r.info)

    def test_h3_response_is_delivered_incrementally(self):
        p = subprocess.Popen(
            [os.environ.get("H3CLIENT", os.path.join(ROOT, "build/h2o/h2o-httpclient")), "-3", "100", "-k",
             self.server.url("/stream?n=3&delay=600")],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        start = time.time()
        first = p.stdout.read(6)
        first_at = time.time() - start
        rest = p.stdout.read()
        total = time.time() - start
        p.wait()
        self.assertEqual(first + rest, b"000000111111222222")
        self.assertLess(first_at, 0.5)
        self.assertGreater(total, 1.1)

    def test_large_streamed_response(self):
        r = curl(self.server.url("/stream?n=200&size=100000"), proto="h2")
        self.assertEqual(len(r.body), 200 * 100000)
        self.assertEqual(r.body[:100000], b"0" * 100000)
        self.assertEqual(r.body[-100000:], b"9" * 100000)


class ConcurrencyTest(ServerTestCase):
    argv = ECHO  # 4 worker threads

    def test_slow_handler_does_not_block_others(self):
        slow = threading.Thread(target=lambda: get(self.server, "/sleep?ms=2000"))
        slow.start()
        time.sleep(0.2)
        start = time.time()
        for proto in ALL:
            self.assertEqual(get(self.server, "/echo", proto=proto).status, 200)
        self.assertLess(time.time() - start, 1.5)
        slow.join()

    def test_workers_run_in_parallel(self):
        """4 workers, 4 handlers sleeping 1s each: about 1s in total, not 4."""
        start = time.time()
        threads = [threading.Thread(target=lambda: get(self.server, "/sleep?ms=1000")) for _ in range(4)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        self.assertLess(time.time() - start, 2.5)

    def test_parallel_mixed_load(self):
        results, errors = [], []

        def worker(i):
            try:
                proto = ALL[i % 3]
                body = f"req-{i}".encode()
                if proto == "h3":
                    r = h3(self.server.url(f"/echo?i={i}"))
                    ok = r.status == 200 and self.parse_echo(r)["query-string"] == f"i={i}"
                else:
                    r = curl(self.server.url(f"/echo?i={i}"), proto=proto, data=body)
                    e = self.parse_echo(r)
                    ok = r.status == 200 and e["query-string"] == f"i={i}" and e["body"] == body.decode()
                results.append(ok)
            except Exception as ex:  # noqa: BLE001
                errors.append(repr(ex))

        threads = [threading.Thread(target=worker, args=(i,)) for i in range(150)]
        for batch in range(0, 150, 25):
            for t in threads[batch:batch + 25]:
                t.start()
            for t in threads[batch:batch + 25]:
                t.join()
        self.assertEqual(errors, [])
        self.assertEqual(results.count(True), 150)

    def test_h2_multiplexed_streams(self):
        """8 concurrent requests multiplexed over one HTTP/2 connection, on 4 workers."""
        argv = ["curl", "-sk", "--http2", "--parallel", "--parallel-max", "8", "-w", "%{http_code} %{num_connects}\n"]
        for i in range(8):
            argv += ["-o", "/dev/null", self.server.url(f"/sleep?ms=300&i={i}")]
        start = time.time()
        p = subprocess.run(argv, capture_output=True, timeout=30)
        elapsed = time.time() - start
        lines = [l.split() for l in p.stdout.decode().strip().split("\n")]
        self.assertEqual([l[0] for l in lines], ["200"] * 8)
        self.assertLessEqual(sum(int(l[1]) for l in lines), 2, "requests should share a connection")
        self.assertLess(elapsed, 2.0)


class DisconnectTest(ServerTestCase):
    argv = ECHO

    def test_client_disconnects_mid_response(self):
        for proto in TCP:
            for path in ("/stream?n=1000&size=1000000", "/lazy?n=1000&size=1000000"):
                with self.subTest(proto=proto, path=path):
                    r = curl(self.server.url(path), "--limit-rate", "2M", "--max-time", "1", proto=proto)
                    self.assertEqual(r.exit_code, 28)  # timed out, i.e. we hung up
        self.assertEqual(get(self.server, "/echo").status, 200)

    def test_client_disconnects_mid_upload(self):
        p = subprocess.Popen(["curl", "-sk", "--http1.1", "-T", "-", "-H", "Transfer-Encoding: chunked",
                              self.server.url("/count")], stdin=subprocess.PIPE, stdout=subprocess.DEVNULL)
        p.stdin.write(b"x" * 100000)
        p.stdin.flush()
        time.sleep(0.3)
        p.kill()
        p.wait()
        self.assertEqual(get(self.server, "/echo").status, 200)

    def test_workers_are_released_after_disconnects(self):
        """Hang up on 4 streams that would each keep a worker busy; all 4 workers must come back."""
        procs = [subprocess.Popen(["curl", "-sk", "--http2", "-o", "/dev/null", "--max-time", "1",
                                   self.server.url("/stream?n=100000&size=100000")]) for _ in range(4)]
        for p in procs:
            p.wait()
        start = time.time()
        threads = [threading.Thread(target=lambda: get(self.server, "/sleep?ms=500")) for _ in range(4)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        self.assertLess(time.time() - start, 2.0, "workers should be free again")

    def test_unread_body_keeps_h1_connection_usable(self):
        """The handler ignores a 5MB body; the next request on the same connection must work."""
        path = os.path.join(ROOT, "build", "unread-body.bin")
        with open(path, "wb") as f:
            f.write(b"z" * 5_000_000)
        p = subprocess.run(["curl", "-sk", "--http1.1",
                            "--data-binary", "@" + path, "-o", "/dev/null", "-w", "%{http_code} %{num_connects}\n",
                            self.server.url("/status?code=201"),
                            "--next", "-sk", "--http1.1", "-o", "/dev/null", "-w", "%{http_code} %{num_connects}\n",
                            self.server.url("/echo")], capture_output=True, timeout=30)
        lines = p.stdout.decode().split()
        self.assertEqual(lines, ["201", "1", "200", "0"], "second request reuses the connection")


class LimitsTest(ServerTestCase):
    argv = ECHO  # :max-body-size 512MiB

    def test_oversized_body_rejected(self):
        r = curl(self.server.url("/count"), "-H", "Content-Length: 600000000", "-H", "Expect:",
                 proto="h1", data=b"", timeout=30)
        self.assertEqual(r.status, 413, r)

    def test_upload_memory_is_bounded(self):
        """Stream 400MB through a handler that drains without retaining: RSS must not follow."""
        get(self.server, "/count", proto="h2", data=b"warm-up")
        before = self.server.rss_mb()
        for flag in ("--http1.1", "--http2"):
            with self.subTest(proto=flag):
                src = subprocess.Popen(["head", "-c", "200000000", "/dev/zero"], stdout=subprocess.PIPE)
                p = subprocess.run(["curl", "-sk", flag, "-T", "-", self.server.url("/count")],
                                   stdin=src.stdout, capture_output=True, timeout=120)
                src.wait()
                self.assertEqual(p.stdout.decode().split()[0], "200000000")
        growth = self.server.rss_mb() - before
        self.assertLess(growth, 150, f"RSS grew {growth:.0f}MB for 400MB uploaded")

    def test_download_memory_is_bounded_for_slow_readers(self):
        """A writer producing 100MB to a client reading at 4MB/s must block, not buffer."""
        before = self.server.rss_mb()
        r = curl(self.server.url("/stream?n=100&size=1000000"), "--limit-rate", "4M", "--max-time", "3",
                 proto="h2", timeout=10)
        self.assertEqual(r.exit_code, 28)
        growth = self.server.rss_mb() - before
        self.assertLess(growth, 50, f"RSS grew {growth:.0f}MB")
        self.assertLess(len(r.body), 30_000_000)


if __name__ == "__main__":
    unittest.main()
