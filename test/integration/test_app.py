"""End-to-end tests of the go-brrrr app, run both as the AOT binary and under the JIT.

Run via `make test-app`. APP_BIN / APP_JIT override the server commands."""

import os
import shlex
import unittest

from harness import ROOT, ServerTestCase, curl, get

AOT = shlex.split(os.environ.get("APP_BIN", os.path.join(ROOT, "target/go-brrrr")))
JIT = shlex.split(os.environ.get(
    "APP_JIT", "jank --module-path src -Inative -Lbuild -lbrrrr run-main go-brrrr.main --"))
ALL = ("h1", "h2", "h3")


class AppTests:
    def test_root_is_23(self):
        for proto in ALL:
            with self.subTest(proto=proto):
                r = get(self.server, "/", proto=proto)
                self.assertEqual(r.status, 200)
                self.assertEqual(r.body, b"23")
                self.assertEqual(r.header("content-type"), "text/plain")
                self.assertEqual(r.header("content-length"), "2")

    def test_head_root(self):
        r = curl(self.server.url("/"), "-I", proto="h2")
        self.assertEqual(r.status, 200)
        self.assertEqual(r.header("content-type"), "text/plain")
        self.assertEqual(r.info["size_download"], 0)

    def test_add_query_params(self):
        for proto in ALL:
            with self.subTest(proto=proto):
                r = get(self.server, "/add?a=20&b=3", proto=proto)
                self.assertEqual((r.status, r.body), (200, b"23"))

    def test_add_form_params(self):
        for proto in ALL:
            with self.subTest(proto=proto):
                kw = {"method": "POST", "data": b"a=40&b=2",
                      "headers": ["content-type: application/x-www-form-urlencoded"]} if proto == "h3" \
                    else {"data": b"a=40&b=2"}
                r = get(self.server, "/add", proto=proto, **kw)
                self.assertEqual((r.status, r.body), (200, b"42"))

    def test_add_negative_and_mixed_sources(self):
        r = get(self.server, "/add?a=-5", data=b"b=3")
        self.assertEqual(r.body, b"-2")

    def test_add_bad_params(self):
        for query in ("a=x&b=1", "a=1", "", "a=1.5&b=1"):
            with self.subTest(query=query):
                r = get(self.server, "/add?" + query)
                self.assertEqual((r.status, r.body), (400, b"need integer a and b"))

    def test_not_found(self):
        for path in ("/nope", "/add/x", "/ "):
            with self.subTest(path=path):
                r = get(self.server, path.replace(" ", "%20"))
                self.assertEqual((r.status, r.body), (404, b"not found"))

    def test_root_ignores_query_string(self):
        for path in ("/?", "/?x=1"):
            with self.subTest(path=path):
                self.assertEqual(get(self.server, path).body, b"23")

    def test_wrong_method(self):
        r = curl(self.server.url("/"), "-X", "DELETE")
        self.assertEqual(r.status, 404)


class AotAppTest(AppTests, ServerTestCase):
    argv = AOT


class JitAppTest(AppTests, ServerTestCase):
    argv = JIT


if __name__ == "__main__":
    unittest.main()
