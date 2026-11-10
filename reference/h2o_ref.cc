// Reference for benchmarks: src/go_brrrr/main.jank's API written directly against libh2o,
// with no jank, no worker threads and no Ring. One h2o event loop serves HTTP/1.1 and
// HTTP/2 over TLS, and handles every request inline on that loop, which is how an h2o
// application normally works. What it answers, and how, mirrors the jank app:
//
//   GET /            -> 200 "23" (text/plain)
//   GET|POST /add    -> 200 a+b (text/plain), a and b from the query string or a urlencoded
//                       form body, parsed like clojure.core/parse-long; else 400
//   anything else    -> 404 "not found"
//
// HEAD is routed as GET (wrap-head); h2o drops the body. Parameters follow ring's
// wrap-params: form and query parameters are merged, the query string winning, and a key
// repeated within one of them becomes a vector. Errors follow Clojure on the JVM (checked
// against jvm/): parse-long throws on a vector and + throws on long overflow, so both are
// a 500, with the body ring.adapter.h2o sends for a handler that throws.
//
// Usage: h2o-ref [port] (default 8443), from the repository root (certs/ is relative).

#include <arpa/inet.h>
#include <climits>
#include <csignal>
#include <cstdio>
#include <cstring>
#include <map>
#include <netinet/in.h>
#include <openssl/err.h>
#include <openssl/ssl.h>
#include <optional>
#include <string>
#include <string_view>
#include <sys/socket.h>

extern "C" {
#include "h2o.h"
}

namespace
{
  h2o_globalconf_t globalconf;
  h2o_context_t ctx;
  h2o_accept_ctx_t accept_ctx;

  int hex_value(char const c)
  {
    if(c >= '0' && c <= '9') { return c - '0'; }
    if(c >= 'a' && c <= 'f') { return c - 'a' + 10; }
    if(c >= 'A' && c <= 'F') { return c - 'A' + 10; }
    return -1;
  }

  // java.net.URLDecoder semantics, as ring.util.codec/form-decode-str: + is a space, and a
  // malformed escape makes the whole value invalid.
  std::optional<std::string> form_decode(std::string_view const s)
  {
    std::string out;
    out.reserve(s.size());
    for(size_t i{}; i < s.size(); ++i)
    {
      if(s[i] == '+') { out += ' '; }
      else if(s[i] == '%')
      {
        int hi, lo;
        if(i + 2 >= s.size() || (hi = hex_value(s[i + 1])) < 0 || (lo = hex_value(s[i + 2])) < 0)
        { return std::nullopt; }
        out += static_cast<char>((hi << 4) | lo);
        i += 2;
      }
      else { out += s[i]; }
    }
    return out;
  }

  // A parameter's value, or nullopt where ring would have a vector (a repeated key).
  struct handler_threw {};
  using params_t = std::map<std::string, std::optional<std::string>>;

  // ring.util.codec/form-decode-map: pairs split on & and the first =, both sides decoded;
  // pairs that don't decode are dropped.
  params_t parse_params(std::string_view s)
  {
    params_t params;
    while(!s.empty())
    {
      auto const amp{ s.find('&') };
      auto const pair{ s.substr(0, amp) };
      s = amp == std::string_view::npos ? std::string_view{} : s.substr(amp + 1);
      if(pair.empty()) { continue; }
      auto const eq{ pair.find('=') };
      auto const k{ form_decode(pair.substr(0, eq)) };
      auto const v{ form_decode(eq == std::string_view::npos ? std::string_view{} : pair.substr(eq + 1)) };
      if(!k || !v) { continue; }
      if(auto const [it, inserted]{ params.try_emplace(*k, *v) }; !inserted) { it->second.reset(); }
    }
    return params;
  }

  // clojure.core/parse-long: an optional sign, then decimal digits only, within a long;
  // anything but a string (here, a vector) throws.
  std::optional<long long> parse_long(std::optional<std::string> const &s)
  {
    if(!s) { throw handler_threw{}; }
    if(s->empty()) { return std::nullopt; }
    size_t i{ (*s)[0] == '+' || (*s)[0] == '-' ? 1u : 0u };
    if(i == s->size()) { return std::nullopt; }
    bool const negative{ (*s)[0] == '-' };
    long long n{};
    for(; i < s->size(); ++i)
    {
      char const c{ (*s)[i] };
      if(c < '0' || c > '9') { return std::nullopt; }
      int const d{ c - '0' };
      if(__builtin_mul_overflow(n, 10, &n) || __builtin_add_overflow(n, negative ? -d : d, &n))
      { return std::nullopt; }
    }
    return n;
  }

  void respond(h2o_req_t * const req, int const status, char const * const reason, std::string_view const body,
               bool const text_plain)
  {
    req->res.status = status;
    req->res.reason = reason;
    if(text_plain)
    { h2o_add_header(&req->pool, &req->res.headers, H2O_TOKEN_CONTENT_TYPE, nullptr, H2O_STRLIT("text/plain")); }
    h2o_send_inline(req, body.data(), body.size());
  }

  void respond_text(h2o_req_t * const req, std::string const &body)
  {
    // h2o_send_inline copies the body into the request's pool
    respond(req, 200, "OK", body, true);
  }

  std::string_view view(h2o_iovec_t const v) { return { v.base, v.len }; }

  int on_req(h2o_handler_t *, h2o_req_t * const req)
  {
    auto const method{ view(req->method) };
    bool const get{ method == "GET" || method == "HEAD" };
    bool const post{ method == "POST" };
    auto const path{ view(req->path_normalized) };

    if(get && path == "/")
    {
      respond_text(req, std::to_string(20 + 3));
      return 0;
    }
    if((get || post) && path == "/add")
    {
      params_t params;
      if(post && req->entity.base != nullptr)
      {
        auto const ct{ h2o_find_header(&req->headers, H2O_TOKEN_CONTENT_TYPE, -1) };
        if(ct != -1 && view(req->headers.entries[ct].value).starts_with("application/x-www-form-urlencoded"))
        { params = parse_params(view(req->entity)); }
      }
      if(req->query_at != SIZE_MAX)
      {
        auto const query{ view(req->path).substr(req->query_at + 1) };
        for(auto &[k, v] : parse_params(query)) { params.insert_or_assign(k, v); }
      }
      try
      {
        auto const a{ params.contains("a") ? parse_long(params["a"]) : std::nullopt };
        auto const b{ params.contains("b") ? parse_long(params["b"]) : std::nullopt };
        long long sum;
        if(!a || !b)
        { respond(req, 400, "Bad Request", "need integer a and b", false); }
        else if(__builtin_add_overflow(*a, *b, &sum))
        { throw handler_threw{}; }
        else
        { respond_text(req, std::to_string(sum)); }
      }
      catch(handler_threw const &)
      { respond(req, 500, "Internal Server Error", "Internal Server Error", true); }
      return 0;
    }
    respond(req, 404, "Not Found", "not found", false);
    return 0;
  }

  void on_accept(h2o_socket_t * const listener, char const * const err)
  {
    h2o_socket_t *sock;
    if(err != nullptr || (sock = h2o_evloop_socket_accept(listener)) == nullptr) { return; }
    h2o_accept(&accept_ctx, sock);
  }
}

int main(int const argc, char ** const argv)
{
  signal(SIGPIPE, SIG_IGN);
  auto const port{ static_cast<uint16_t>(argc > 1 ? atoi(argv[1]) : 8443) };

  h2o_config_init(&globalconf);
  auto * const hostconf{ h2o_config_register_host(&globalconf, h2o_iovec_init(H2O_STRLIT("default")), 65535) };
  auto * const pathconf{ h2o_config_register_path(hostconf, "/", 0) };
  auto * const handler{ h2o_create_handler(pathconf, sizeof(h2o_handler_t)) };
  handler->on_req = on_req;

  h2o_context_init(&ctx, h2o_evloop_create(), &globalconf);
  accept_ctx.ctx = &ctx;
  accept_ctx.hosts = globalconf.hosts;

  auto * const ssl{ SSL_CTX_new(TLS_server_method()) };
  SSL_CTX_set_min_proto_version(ssl, TLS1_2_VERSION);
  if(SSL_CTX_use_certificate_chain_file(ssl, "certs/cert.pem") != 1
     || SSL_CTX_use_PrivateKey_file(ssl, "certs/key.pem", SSL_FILETYPE_PEM) != 1)
  {
    fputs("h2o-ref: failed to load certs/cert.pem and certs/key.pem\n", stderr);
    ERR_print_errors_fp(stderr);
    return 1;
  }
  h2o_ssl_register_alpn_protocols(ssl, h2o_alpn_protocols); // h2 first, then http/1.1
  accept_ctx.ssl_ctx = ssl;

  sockaddr_in addr{};
  addr.sin_family = AF_INET;
  addr.sin_port = htons(port);
  addr.sin_addr.s_addr = htonl(INADDR_ANY);
  int fd, on{ 1 };
  if((fd = socket(AF_INET, SOCK_STREAM, 0)) == -1 || setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &on, sizeof(on)) != 0
     || bind(fd, reinterpret_cast<sockaddr *>(&addr), sizeof(addr)) != 0 || listen(fd, SOMAXCONN) != 0)
  {
    perror("h2o-ref: listen");
    return 1;
  }
  h2o_socket_read_start(h2o_evloop_socket_create(ctx.loop, fd, H2O_SOCKET_FLAG_DONT_READ), on_accept);

  fprintf(stderr, "h2o-ref: listening on https://0.0.0.0:%u (h1, h2 over tcp; 1 event loop)\n", port);
  for(;;) { h2o_evloop_run(ctx.loop, INT32_MAX); }
}
