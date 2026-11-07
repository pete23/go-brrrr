"""Shared helpers for the integration tests: start a server on a free port, and drive it
with curl (HTTP/1.1, HTTP/2) and h2o-httpclient (HTTP/3). Standard library only."""

import json
import os
import socket
import subprocess
import tempfile
import time
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
H3CLIENT = os.environ.get("H3CLIENT", os.path.join(ROOT, "build/h2o/h2o-httpclient"))


def free_port():
    """A port that is free for both TCP and UDP (the server binds both)."""
    for _ in range(50):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as t:
            t.bind(("127.0.0.1", 0))
            port = t.getsockname()[1]
            try:
                with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as u:
                    u.bind(("127.0.0.1", port))
            except OSError:
                continue
            return port
    raise RuntimeError("no free port")


class Server:
    """A server process started with `argv + [port]`; its stderr is captured to a file."""

    def __init__(self, argv, startup_timeout=180):
        self.port = free_port()
        self.base = f"https://127.0.0.1:{self.port}"
        self.log = tempfile.NamedTemporaryFile(prefix="server-", suffix=".log", delete=False)
        self.proc = subprocess.Popen(argv + [str(self.port)], cwd=ROOT, stdout=self.log, stderr=subprocess.STDOUT)
        deadline = time.time() + startup_timeout
        while time.time() < deadline:
            if self.proc.poll() is not None:
                raise RuntimeError(f"server exited with {self.proc.returncode}:\n{self.log_text()}")
            with open(self.log.name, "rb") as f:
                if b"listening on" in f.read():
                    return
            time.sleep(0.1)
        self.stop()
        raise RuntimeError(f"server did not start:\n{self.log_text()}")

    def url(self, path):
        return self.base + path

    def alive(self):
        return self.proc.poll() is None

    def log_text(self):
        with open(self.log.name, "rb") as f:
            return f.read().decode(errors="replace")

    def rss_mb(self):
        with open(f"/proc/{self.proc.pid}/status") as f:
            for line in f:
                if line.startswith("VmRSS:"):
                    return int(line.split()[1]) / 1024
        raise RuntimeError("no VmRSS")

    def stop(self):
        if self.alive():
            self.proc.terminate()
            try:
                self.proc.wait(10)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                self.proc.wait()
        self.log.close()


class Response:
    def __init__(self, status, headers, body, exit_code, info=None, stderr=b""):
        self.status = status
        self.headers = headers  # list of (lowercase name, value), in order
        self.body = body
        self.exit_code = exit_code
        self.info = info or {}
        self.stderr = stderr

    def header(self, name):
        values = self.header_all(name)
        return values[0] if values else None

    def header_all(self, name):
        return [v for (k, v) in self.headers if k == name]

    @property
    def text(self):
        return self.body.decode()

    def __repr__(self):
        return f"<Response {self.status} exit={self.exit_code} headers={self.headers} body={self.body[:200]!r}>"


def _parse_headers(raw):
    """The last header block in curl's -D output (skips 100 Continue etc)."""
    blocks = [b for b in raw.replace(b"\r\n", b"\n").split(b"\n\n") if b.strip()]
    if not blocks:
        return None, []
    lines = blocks[-1].decode(errors="replace").split("\n")
    status = int(lines[0].split()[1])
    headers = []
    for line in lines[1:]:
        if ":" in line:
            k, v = line.split(":", 1)
            headers.append((k.strip().lower(), v.strip()))
    return status, headers


def curl(url, *args, proto="h2", data=None, stdin=None, timeout=60):
    """Runs curl; proto is "h1", "h1.0" or "h2". `data` is bytes sent as the body (POST);
    `stdin` is bytes fed to curl's stdin (use with "-T", "-")."""
    flag = {"h1": "--http1.1", "h1.0": "--http1.0", "h2": "--http2"}[proto]
    with tempfile.TemporaryDirectory() as d:
        hdr, out = os.path.join(d, "h"), os.path.join(d, "b")
        argv = ["curl", "-sk", flag, "-D", hdr, "-o", out, "-w", "%{json}", *args]
        input_bytes = stdin
        if data is not None:
            body = os.path.join(d, "req")
            with open(body, "wb") as f:
                f.write(data)
            argv += ["--data-binary", "@" + body]
        argv.append(url)
        p = subprocess.run(argv, input=input_bytes, capture_output=True, timeout=timeout)
        status, headers, body = None, [], b""
        if os.path.exists(hdr):
            with open(hdr, "rb") as f:
                status, headers = _parse_headers(f.read())
        if os.path.exists(out):
            with open(out, "rb") as f:
                body = f.read()
        try:
            info = json.loads(p.stdout)
        except ValueError:
            info = {}
        return Response(status, headers, body, p.returncode, info, p.stderr)


def h3(url, *args, method="GET", body_size=None, chunk=None, interval=None, headers=(), data=None, timeout=60):
    """Runs h2o-httpclient over HTTP/3. It prints the status line and headers to stderr. `data` is sent as
    the body through a pipe (its --input reads via epoll, which cannot watch regular files)."""
    argv = [H3CLIENT, "-3", "100", "-k", "-m", method]
    if body_size is not None:
        argv += ["-b", str(body_size)]
    if chunk is not None:
        argv += ["-c", str(chunk)]
    if interval is not None:
        argv += ["-i", str(interval)]
    if data is not None:
        argv += ["--input", "/dev/stdin"]
    for h in headers:
        argv += ["-H", h]
    argv += [*args, url]
    p = subprocess.run(argv, input=data, capture_output=True, timeout=timeout)
    status, hs = None, []
    for line in p.stderr.decode(errors="replace").split("\n"):
        if line.startswith("HTTP/"):
            status, hs = int(line.split()[1]), []
        elif ": " in line and status is not None:
            k, v = line.split(": ", 1)
            hs.append((k.lower(), v))
    return Response(status, hs, p.stdout, p.returncode, stderr=p.stderr)


def get(server, path, proto="h2", **kw):
    if proto == "h3":
        return h3(server.url(path), **kw)
    return curl(server.url(path), proto=proto, **kw)


class ServerTestCase(unittest.TestCase):
    """Starts one server per test class and checks it is still alive after every test."""

    argv = None  # set by subclasses

    @classmethod
    def setUpClass(cls):
        cls.server = Server(cls.argv)

    @classmethod
    def tearDownClass(cls):
        cls.server.stop()

    def tearDown(self):
        if not self.server.alive():
            self.fail(f"server died during the test:\n{self.server.log_text()[-4000:]}")

    def parse_echo(self, resp):
        """server.echo's /echo: key=value lines, with the body last."""
        fields = {}
        text = resp.body.decode(errors="replace")
        head, _, body = text.partition("\nbody=")
        for line in head.split("\n"):
            k, _, v = line.partition("=")
            fields[k] = v
        fields["body"] = body
        return fields
