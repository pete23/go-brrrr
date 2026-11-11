/* libh2o for Chez Scheme: the C side of chez/go-brrrr.ss.
 *
 * The same server shape as reference/h2o_ref.cc: one h2o event loop serving HTTP/1.1 and
 * HTTP/2 over TLS, handling every request inline on that loop. The difference is that each
 * request is handed to a Scheme procedure (a foreign-callable), which routes it, parses
 * parameters, and answers through brrrr_chez_respond. The Scheme thread calls
 * brrrr_chez_serve and stays in it; callbacks run on that same thread, so Chez needs no
 * thread activation and its collector never waits on another thread.
 *
 * Strings handed to Scheme are NUL-terminated copies in the request's memory pool, valid
 * until the response is sent. */
#include <arpa/inet.h>
#include <netinet/in.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <openssl/err.h>
#include <openssl/ssl.h>

#include "h2o.h"

/* method, path (normalized, no query), query (or NULL), content-type (or NULL),
   body (POST with a body only, else NULL) */
typedef void (*brrrr_chez_handler)(h2o_req_t *req, const char *method, const char *path, const char *query,
                                   const char *content_type, const char *body);

static struct {
    brrrr_chez_handler handler;
    h2o_globalconf_t globalconf;
    h2o_context_t ctx;
    h2o_accept_ctx_t accept_ctx;
} g;

static const char *pool_cstr(h2o_req_t *req, const char *base, size_t len)
{
    return h2o_strdup(&req->pool, base, len).base;
}

static const char *reason(int status)
{
    switch (status) {
    case 200: return "OK";
    case 400: return "Bad Request";
    case 404: return "Not Found";
    case 500: return "Internal Server Error";
    default: return "";
    }
}

/* Called from Scheme, once per request, from inside the handler callback. */
void brrrr_chez_respond(h2o_req_t *req, int status, int text_plain, const char *body)
{
    req->res.status = status;
    req->res.reason = reason(status);
    if (text_plain)
        h2o_add_header(&req->pool, &req->res.headers, H2O_TOKEN_CONTENT_TYPE, NULL, H2O_STRLIT("text/plain"));
    size_t len = strlen(body);
    req->res.content_length = len; /* h2o_send_inline doesn't set it; without, h1 goes chunked */
    h2o_send_inline(req, body, len); /* copies the body */
}

static int on_req(h2o_handler_t *self, h2o_req_t *req)
{
    (void)self;
    const char *method = pool_cstr(req, req->method.base, req->method.len);
    const char *path = pool_cstr(req, req->path_normalized.base, req->path_normalized.len);
    const char *query = req->query_at == SIZE_MAX
                            ? NULL
                            : pool_cstr(req, req->path.base + req->query_at + 1, req->path.len - req->query_at - 1);
    const char *content_type = NULL;
    ssize_t ct = h2o_find_header(&req->headers, H2O_TOKEN_CONTENT_TYPE, -1);
    if (ct != -1)
        content_type = pool_cstr(req, req->headers.entries[ct].value.base, req->headers.entries[ct].value.len);
    const char *body = req->entity.base != NULL ? pool_cstr(req, req->entity.base, req->entity.len) : NULL;
    g.handler(req, method, path, query, content_type, body);
    return 0;
}

static void on_accept(h2o_socket_t *listener, const char *err)
{
    h2o_socket_t *sock;
    if (err != NULL || (sock = h2o_evloop_socket_accept(listener)) == NULL)
        return;
    h2o_accept(&g.accept_ctx, sock);
}

/* Serves on `port` until the process ends; returns non-zero only if setup fails. Must be
   called from the Scheme thread that will run the handler. */
int brrrr_chez_serve(int port, brrrr_chez_handler handler)
{
    signal(SIGPIPE, SIG_IGN);
    g.handler = handler;

    h2o_config_init(&g.globalconf);
    h2o_hostconf_t *hostconf = h2o_config_register_host(&g.globalconf, h2o_iovec_init(H2O_STRLIT("default")), 65535);
    h2o_pathconf_t *pathconf = h2o_config_register_path(hostconf, "/", 0);
    h2o_handler_t *h = h2o_create_handler(pathconf, sizeof(*h));
    h->on_req = on_req;

    h2o_context_init(&g.ctx, h2o_evloop_create(), &g.globalconf);
    g.accept_ctx.ctx = &g.ctx;
    g.accept_ctx.hosts = g.globalconf.hosts;

    SSL_CTX *ssl = SSL_CTX_new(TLS_server_method());
    SSL_CTX_set_min_proto_version(ssl, TLS1_2_VERSION);
    if (SSL_CTX_use_certificate_chain_file(ssl, "certs/cert.pem") != 1 ||
        SSL_CTX_use_PrivateKey_file(ssl, "certs/key.pem", SSL_FILETYPE_PEM) != 1) {
        fputs("go-brrrr-chez: failed to load certs/cert.pem and certs/key.pem\n", stderr);
        ERR_print_errors_fp(stderr);
        return 1;
    }
    h2o_ssl_register_alpn_protocols(ssl, h2o_alpn_protocols); /* h2 first, then http/1.1 */
    g.accept_ctx.ssl_ctx = ssl;

    struct sockaddr_in addr = {.sin_family = AF_INET, .sin_port = htons((uint16_t)port), .sin_addr.s_addr = htonl(INADDR_ANY)};
    int fd, on = 1;
    if ((fd = socket(AF_INET, SOCK_STREAM, 0)) == -1 || setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &on, sizeof(on)) != 0 ||
        bind(fd, (struct sockaddr *)&addr, sizeof(addr)) != 0 || listen(fd, SOMAXCONN) != 0) {
        perror("go-brrrr-chez: listen");
        return 1;
    }
    h2o_socket_read_start(h2o_evloop_socket_create(g.ctx.loop, fd, H2O_SOCKET_FLAG_DONT_READ), on_accept);

    fprintf(stderr, "go-brrrr-chez: listening on https://0.0.0.0:%d (h1, h2 over tcp; 1 event loop)\n", port);
    for (;;)
        h2o_evloop_run(g.ctx.loop, INT32_MAX);
}
