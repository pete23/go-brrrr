#include "brrrr.h"

#include <arpa/inet.h>
#include <netinet/in.h>
#include <stdio.h>
#include <netdb.h>
#include <string.h>
#include <sys/socket.h>
#include <openssl/err.h>
#include <openssl/pem.h>
#include <openssl/ssl.h>

#include "h2o.h"
#include "h2o/http2.h"
#include "h2o/http3_server.h"
#include "picotls/openssl.h"

static struct {
    brrrr_handler_fn handler;
    uint16_t port;
    h2o_globalconf_t globalconf;
    h2o_context_t ctx;
    h2o_accept_ctx_t accept_ctx;
    h2o_iovec_t alt_svc;
    struct {
        ptls_context_t tls;
        ptls_openssl_sign_certificate_t sign_cert;
        ptls_on_client_hello_t on_client_hello;
        quicly_context_t quic;
        h2o_http3_server_ctx_t server;
    } h3;
} g;

/* ---- request handling ---- */

typedef struct {
    brrrr_request_t pub;
    h2o_req_t *req;
    int responded;
} brrrr_req_impl_t;

static brrrr_str_t to_str(h2o_iovec_t v)
{
    return (brrrr_str_t){v.base, v.len};
}

void brrrr_respond(brrrr_request_t *_req, int status, const brrrr_header_t *headers, size_t num_headers, brrrr_str_t body)
{
    brrrr_req_impl_t *impl = (brrrr_req_impl_t *)_req;
    h2o_req_t *req = impl->req;
    if (impl->responded)
        return;
    impl->responded = 1;

    req->res.status = status;
    req->res.reason = status == 200 ? "OK" : "";
    for (size_t i = 0; i != num_headers; ++i) {
        const brrrr_header_t *h = headers + i;
        /* h2o computes framing itself */
        if (h2o_memis(h->name.base, h->name.len, H2O_STRLIT("content-length")) ||
            h2o_memis(h->name.base, h->name.len, H2O_STRLIT("transfer-encoding")) ||
            h2o_memis(h->name.base, h->name.len, H2O_STRLIT("connection")))
            continue;
        h2o_iovec_t name = h2o_strdup(&req->pool, h->name.base, h->name.len);
        h2o_iovec_t value = h2o_strdup(&req->pool, h->value.base, h->value.len);
        h2o_add_header_by_str(&req->pool, &req->res.headers, name.base, name.len, 1, NULL, value.base, value.len);
    }
    /* advertise HTTP/3 to TCP clients */
    if (req->version < 0x300)
        h2o_add_header(&req->pool, &req->res.headers, H2O_TOKEN_ALT_SVC, NULL, g.alt_svc.base, g.alt_svc.len);
    h2o_iovec_t b = h2o_strdup(&req->pool, body.base != NULL ? body.base : "", body.base != NULL ? body.len : 0);
    req->res.content_length = b.len;
    h2o_send_inline(req, b.base, b.len);
}

static int on_req(h2o_handler_t *self, h2o_req_t *req)
{
    (void)self;
    brrrr_req_impl_t impl = {.req = req};
    brrrr_request_t *r = &impl.pub;

    r->method = to_str(req->method);
    if (req->query_at != SIZE_MAX) {
        r->path = (brrrr_str_t){req->path.base, req->query_at};
        r->query = (brrrr_str_t){req->path.base + req->query_at + 1, req->path.len - req->query_at - 1};
    } else {
        r->path = to_str(req->path);
    }
    r->scheme = (brrrr_str_t){req->scheme->name.base, req->scheme->name.len};
    r->authority = to_str(req->authority);
    r->http_version = req->version;
    r->server_port = g.port;

    struct sockaddr_storage ss;
    socklen_t sslen = req->conn->callbacks->get_peername(req->conn, (struct sockaddr *)&ss);
    char *addr = h2o_mem_alloc_pool(&req->pool, char, NI_MAXHOST);
    size_t addrlen = sslen != 0 ? h2o_socket_getnumerichost((struct sockaddr *)&ss, sslen, addr) : SIZE_MAX;
    r->remote_addr = addrlen != SIZE_MAX ? (brrrr_str_t){addr, addrlen} : (brrrr_str_t){"", 0};

    /* h2/h3 carry the host in :authority, not in a header; Ring wants a host header either way */
    int has_host = h2o_find_header(&req->headers, H2O_TOKEN_HOST, -1) != -1;
    /* h2o moves content-length out of the header list; Ring expects it as a header */
    int has_length = req->content_length != SIZE_MAX;
    size_t n = req->headers.size + !has_host + has_length;
    brrrr_header_t *hs = h2o_mem_alloc_pool(&req->pool, brrrr_header_t, n == 0 ? 1 : n);
    size_t j = 0;
    for (size_t i = 0; i != req->headers.size; ++i)
        hs[j++] = (brrrr_header_t){to_str(*req->headers.entries[i].name), to_str(req->headers.entries[i].value)};
    if (!has_host)
        hs[j++] = (brrrr_header_t){{"host", 4}, r->authority};
    if (has_length) {
        char *len = h2o_mem_alloc_pool(&req->pool, char, sizeof(H2O_UINT64_LONGEST_STR));
        size_t len_len = sprintf(len, "%zu", req->content_length);
        hs[j++] = (brrrr_header_t){{"content-length", 14}, {len, len_len}};
    }
    r->headers = hs;
    r->num_headers = j;
    r->body = to_str(req->entity);

    g.handler(r);

    if (!impl.responded)
        brrrr_respond(r, 500, (brrrr_header_t[]){{{"content-type", 12}, {"text/plain", 10}}}, 1, (brrrr_str_t){"internal error", 14});
    return 0;
}

/* ---- TCP: HTTP/1.1 + HTTP/2 over OpenSSL TLS ---- */

static void on_tcp_accept(h2o_socket_t *listener, const char *err)
{
    h2o_socket_t *sock;
    if (err != NULL || (sock = h2o_evloop_socket_accept(listener)) == NULL)
        return;
    h2o_accept(&g.accept_ctx, sock);
}

static int setup_tcp(const struct sockaddr_in *addr, const brrrr_config_t *c)
{
    SSL_CTX *ssl = SSL_CTX_new(TLS_server_method());
    SSL_CTX_set_min_proto_version(ssl, TLS1_2_VERSION);
    if (SSL_CTX_use_certificate_chain_file(ssl, c->cert_file) != 1 ||
        SSL_CTX_use_PrivateKey_file(ssl, c->key_file, SSL_FILETYPE_PEM) != 1) {
        fprintf(stderr, "brrrr: failed to load cert/key\n");
        ERR_print_errors_fp(stderr);
        return -1;
    }
    h2o_ssl_register_alpn_protocols(ssl, h2o_http2_alpn_protocols);
    g.accept_ctx.ssl_ctx = ssl;

    int fd, on = 1;
    if ((fd = socket(AF_INET, SOCK_STREAM, 0)) == -1 || setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &on, sizeof(on)) != 0 ||
        bind(fd, (const struct sockaddr *)addr, sizeof(*addr)) != 0 || listen(fd, SOMAXCONN) != 0) {
        perror("brrrr: tcp listen");
        return -1;
    }
    h2o_socket_t *sock = h2o_evloop_socket_create(g.ctx.loop, fd, H2O_SOCKET_FLAG_DONT_READ);
    h2o_socket_read_start(sock, on_tcp_accept);
    return 0;
}

/* ---- UDP: HTTP/3 over QUIC (quicly + picotls, OpenSSL for crypto primitives) ---- */

static int on_client_hello(ptls_on_client_hello_t *self, ptls_t *tls, ptls_on_client_hello_parameters_t *params)
{
    (void)self;
    for (size_t i = 0; i != sizeof(h2o_http3_alpn) / sizeof(h2o_http3_alpn[0]); ++i)
        for (size_t j = 0; j != params->negotiated_protocols.count; ++j)
            if (h2o_memis(h2o_http3_alpn[i].base, h2o_http3_alpn[i].len, params->negotiated_protocols.list[j].base,
                          params->negotiated_protocols.list[j].len))
                return ptls_set_negotiated_protocol(tls, (const char *)h2o_http3_alpn[i].base, h2o_http3_alpn[i].len);
    return PTLS_ALERT_NO_APPLICATION_PROTOCOL;
}

static h2o_quic_conn_t *on_h3_accept(h2o_quic_ctx_t *ctx, quicly_address_t *destaddr, quicly_address_t *srcaddr,
                                     quicly_decoded_packet_t *packet)
{
    h2o_http3_conn_t *conn =
        h2o_http3_server_accept((h2o_http3_server_ctx_t *)ctx, destaddr, srcaddr, packet, NULL, &H2O_HTTP3_CONN_CALLBACKS);
    if (conn == NULL || conn == &h2o_http3_accept_conn_closed || &conn->super == &h2o_quic_accept_conn_decryption_failed)
        return NULL;
    return &conn->super;
}

static int setup_udp(const struct sockaddr_in *addr, const brrrr_config_t *c)
{
    static ptls_key_exchange_algorithm_t *key_exchanges[] = {&ptls_openssl_x25519, &ptls_openssl_secp256r1, NULL};

    g.h3.on_client_hello.cb = on_client_hello;
    g.h3.tls = (ptls_context_t){
        .random_bytes = ptls_openssl_random_bytes,
        .get_time = &ptls_get_time,
        .key_exchanges = key_exchanges,
        .cipher_suites = ptls_openssl_cipher_suites,
        .on_client_hello = &g.h3.on_client_hello,
    };
    /* certificates (DER) */
    FILE *fp;
    if ((fp = fopen(c->cert_file, "r")) == NULL) {
        perror("brrrr: open cert");
        return -1;
    }
    X509 *cert = PEM_read_X509(fp, NULL, NULL, NULL);
    STACK_OF(X509) *chain = sk_X509_new_null();
    X509 *extra;
    while ((extra = PEM_read_X509(fp, NULL, NULL, NULL)) != NULL)
        sk_X509_push(chain, extra);
    ERR_clear_error();
    fclose(fp);
    if (cert == NULL || ptls_openssl_load_certificates(&g.h3.tls, cert, chain) != 0) {
        fprintf(stderr, "brrrr: failed to load certificate for QUIC\n");
        return -1;
    }

    /* signing key */
    if ((fp = fopen(c->key_file, "r")) == NULL) {
        perror("brrrr: open key");
        return -1;
    }
    EVP_PKEY *key = PEM_read_PrivateKey(fp, NULL, NULL, NULL);
    fclose(fp);
    if (key == NULL || ptls_openssl_init_sign_certificate(&g.h3.sign_cert, key) != 0) {
        fprintf(stderr, "brrrr: failed to load private key for QUIC\n");
        return -1;
    }
    EVP_PKEY_free(key);
    g.h3.tls.sign_certificate = &g.h3.sign_cert.super;
    quicly_amend_ptls_context(&g.h3.tls);

    g.h3.quic = quicly_spec_context;
    g.h3.quic.tls = &g.h3.tls;
    uint8_t cid_key[PTLS_SHA256_DIGEST_SIZE];
    ptls_openssl_random_bytes(cid_key, sizeof(cid_key));
    g.h3.quic.cid_encryptor = quicly_new_default_cid_encryptor(&ptls_openssl_quiclb, &ptls_openssl_aes128ecb, &ptls_openssl_sha256,
                                                                ptls_iovec_init(cid_key, sizeof(cid_key)));
    ptls_clear_memory(cid_key, sizeof(cid_key));
    h2o_http3_server_amend_quicly_context(&g.globalconf, &g.h3.quic);

    int fd;
    if ((fd = socket(AF_INET, SOCK_DGRAM, 0)) == -1) {
        perror("brrrr: udp socket");
        return -1;
    }
    h2o_socket_set_df_bit(fd, AF_INET);
    if (bind(fd, (const struct sockaddr *)addr, sizeof(*addr)) != 0) {
        perror("brrrr: udp bind");
        return -1;
    }
    h2o_socket_t *sock = h2o_evloop_socket_create(g.ctx.loop, fd, H2O_SOCKET_FLAG_DONT_READ);
    h2o_http3_server_init_context(&g.ctx, &g.h3.server.super, g.ctx.loop, sock, NULL, &g.h3.quic, &g.ctx.http3.next_cid,
                                  on_h3_accept, NULL, 0);
    g.h3.server.accept_ctx = &g.accept_ctx;
    return 0;
}

/* ---- entry point ---- */

int brrrr_serve(const brrrr_config_t *c)
{
    g.handler = c->handler;
    g.port = c->port;

    h2o_config_init(&g.globalconf);
    h2o_hostconf_t *hostconf = h2o_config_register_host(&g.globalconf, h2o_iovec_init(H2O_STRLIT("default")), 65535);
    h2o_pathconf_t *pathconf = h2o_config_register_path(hostconf, "/", 0);
    h2o_handler_t *handler = h2o_create_handler(pathconf, sizeof(*handler));
    handler->on_req = on_req;

    h2o_context_init(&g.ctx, h2o_evloop_create(), &g.globalconf);
    g.accept_ctx.ctx = &g.ctx;
    g.accept_ctx.hosts = g.globalconf.hosts;

    char alt_svc[64];
    snprintf(alt_svc, sizeof(alt_svc), "h3=\":%u\"", (unsigned)c->port);
    g.alt_svc = h2o_strdup(NULL, alt_svc, SIZE_MAX);

    struct sockaddr_in addr = {.sin_family = AF_INET, .sin_port = htons(c->port)};
    if (inet_pton(AF_INET, c->host, &addr.sin_addr) != 1) {
        fprintf(stderr, "brrrr: bad host %s\n", c->host);
        return -1;
    }
    if (setup_tcp(&addr, c) != 0 || setup_udp(&addr, c) != 0)
        return -1;

    fprintf(stderr, "brrrr: listening on https://%s:%u (h1, h2 over tcp; h3 over udp)\n", c->host, (unsigned)c->port);
    while (h2o_evloop_run(g.ctx.loop, INT32_MAX) == 0)
        ;
    return 0;
}
