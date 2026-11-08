#include "brrrr.h"

#include <arpa/inet.h>
#include <pthread.h>
#include <signal.h>
#include <stdlib.h>
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

/* High-water marks for buffered body bytes, per request and direction. */
#define IN_HIGH_WATER (1024 * 1024)
#define OUT_HIGH_WATER (1024 * 1024)

typedef struct chunk {
    struct chunk *next;
    size_t len;
    char data[];
} chunk_t;

typedef struct exchange exchange_t;

static struct {
    brrrr_handler_fn handler;
    void (*worker_init)(void);
    uint16_t port;
    h2o_globalconf_t globalconf;
    h2o_context_t ctx;
    h2o_accept_ctx_t accept_ctx;
    h2o_iovec_t alt_svc;
    h2o_multithread_queue_t *queue;
    h2o_multithread_receiver_t receiver; /* workers -> event loop */
    struct {
        pthread_mutex_t mu;
        pthread_cond_t cond;
        exchange_t *head, *tail;
    } jobs; /* event loop -> workers */
    struct {
        ptls_context_t tls;
        ptls_openssl_sign_certificate_t sign_cert;
        ptls_on_client_hello_t on_client_hello;
        quicly_context_t quic;
        h2o_http3_server_ctx_t server;
        ptls_aead_context_t *token_aead;
        quicly_generate_resumption_token_t token_generator;
    } h3;
} g;

/* ---- exchanges ----
 *
 * One per request, shared by the event loop (EL) and the worker running the handler. Fields under `mu` are shared;
 * fields marked EL are only touched on the event loop. h2o is only ever called from the EL. The worker never touches
 * the h2o_req_t; it pokes the EL, which then "pumps" the exchange: delivering response headers/body to h2o and asking
 * h2o for more request body. The exchange is reference counted: one for the h2o request, one for the worker, one while
 * a poke is queued. */

struct exchange {
    brrrr_request_t pub; /* must be first: the handler's brrrr_request_t* is an exchange_t* */
    char *meta;          /* owned storage for pub's strings and headers */
    exchange_t *next_job;

    pthread_mutex_t mu;
    pthread_cond_t cond;
    int refs;
    int closed;           /* client went away or the request was disposed */
    int poke_queued;      /* a message is in g.receiver's queue */
    h2o_multithread_message_t msg;

    /* request body: EL appends, worker consumes */
    chunk_t *in_head, *in_tail, *in_last_read;
    size_t in_bytes;
    int in_eos;
    int owe_proceed; /* EL holds a chunk h2o is waiting to hear we consumed */
    int in_discard;  /* handler finished: drop the rest of the body so the connection can be reused */

    /* response: worker produces, EL sends */
    int res_ready;
    int res_status;
    brrrr_header_t *res_headers;
    size_t res_num_headers;
    char *res_header_data;
    size_t res_content_length;
    chunk_t *out_head, *out_tail;
    size_t out_bytes;
    int out_eos;
    int out_error; /* abort instead of finishing cleanly */

    /* EL only */
    h2o_req_t *req;
    h2o_generator_t generator;
    h2o_timer_t pump_timer;
    int started;
    int sending;
    int final_sent;
    chunk_t *inflight; /* buffers passed to h2o_send, freed when h2o is done with them */
};

static void free_chunks(chunk_t *c)
{
    while (c != NULL) {
        chunk_t *next = c->next;
        free(c);
        c = next;
    }
}

static chunk_t *new_chunk(const char *data, size_t len)
{
    chunk_t *c = h2o_mem_alloc(offsetof(chunk_t, data) + (len == 0 ? 1 : len));
    c->next = NULL;
    c->len = len;
    if (len != 0)
        memcpy(c->data, data, len);
    return c;
}

static void append_chunk(chunk_t **head, chunk_t **tail, chunk_t *c)
{
    if (*tail != NULL)
        (*tail)->next = c;
    else
        *head = c;
    *tail = c;
}

static void exchange_retain_locked(exchange_t *ex)
{
    ++ex->refs;
}

static void exchange_release(exchange_t *ex)
{
    pthread_mutex_lock(&ex->mu);
    int last = --ex->refs == 0;
    pthread_mutex_unlock(&ex->mu);
    if (!last)
        return;
    free_chunks(ex->in_head);
    free_chunks(ex->in_last_read);
    free_chunks(ex->out_head);
    free_chunks(ex->inflight);
    free(ex->res_headers);
    free(ex->res_header_data);
    free(ex->meta);
    pthread_mutex_destroy(&ex->mu);
    pthread_cond_destroy(&ex->cond);
    free(ex);
}

/* worker -> EL: ask the event loop to pump this exchange. Call with ex->mu held. */
static void poke_locked(exchange_t *ex)
{
    if (ex->poke_queued || ex->closed)
        return;
    ex->poke_queued = 1;
    exchange_retain_locked(ex);
    h2o_multithread_send_message(&g.receiver, &ex->msg);
}

static void pump(exchange_t *ex);

/* EL: pump on the next loop iteration, to avoid re-entering h2o from its own callbacks */
static void schedule_pump(exchange_t *ex)
{
    if (ex->req != NULL && !h2o_timer_is_linked(&ex->pump_timer))
        h2o_timer_link(g.ctx.loop, 0, &ex->pump_timer);
}

static void on_pump_timer(h2o_timer_t *timer)
{
    pump(H2O_STRUCT_FROM_MEMBER(exchange_t, pump_timer, timer));
}

static void on_pokes(h2o_multithread_receiver_t *receiver, h2o_linklist_t *messages)
{
    (void)receiver;
    while (!h2o_linklist_is_empty(messages)) {
        exchange_t *ex = H2O_STRUCT_FROM_MEMBER(exchange_t, msg.link, messages->next);
        h2o_linklist_unlink(&ex->msg.link);
        pthread_mutex_lock(&ex->mu);
        ex->poke_queued = 0;
        pthread_mutex_unlock(&ex->mu);
        if (ex->req != NULL)
            pump(ex);
        exchange_release(ex);
    }
}

/* EL: the client went away, or the request is being torn down */
static void mark_closed(exchange_t *ex)
{
    pthread_mutex_lock(&ex->mu);
    ex->closed = 1;
    pthread_cond_broadcast(&ex->cond);
    pthread_mutex_unlock(&ex->mu);
}

static void on_generator_proceed(h2o_generator_t *generator, h2o_req_t *req)
{
    (void)req;
    exchange_t *ex = H2O_STRUCT_FROM_MEMBER(exchange_t, generator, generator);
    free_chunks(ex->inflight);
    ex->inflight = NULL;
    ex->sending = 0;
    schedule_pump(ex);
}

static void on_generator_stop(h2o_generator_t *generator, h2o_req_t *req)
{
    (void)req;
    mark_closed(H2O_STRUCT_FROM_MEMBER(exchange_t, generator, generator));
}

/* EL: h2o is freeing the request */
static void on_req_dispose(void *p)
{
    exchange_t *ex = *(exchange_t **)p;
    if (h2o_timer_is_linked(&ex->pump_timer))
        h2o_timer_unlink(&ex->pump_timer);
    ex->req = NULL;
    mark_closed(ex);
    exchange_release(ex);
}

/* EL: a request body chunk is in req->entity */
static int on_write_req(void *ctx, int is_end_stream)
{
    exchange_t *ex = ctx;
    h2o_req_t *req = ex->req;
    chunk_t *c = req->entity.len != 0 ? new_chunk(req->entity.base, req->entity.len) : NULL;
    pthread_mutex_lock(&ex->mu);
    if (c != NULL && ex->in_discard) {
        free(c);
    } else if (c != NULL) {
        append_chunk(&ex->in_head, &ex->in_tail, c);
        ex->in_bytes += c->len;
    }
    if (is_end_stream) {
        ex->in_eos = 1;
        req->write_req.cb = NULL;
    }
    ex->owe_proceed = 1;
    pthread_cond_broadcast(&ex->cond);
    pthread_mutex_unlock(&ex->mu);
    schedule_pump(ex);
    return 0;
}

/* EL: move whatever the worker has produced into h2o, and pull more request body if there is room */
static void pump(exchange_t *ex)
{
    h2o_req_t *req = ex->req;
    if (req == NULL)
        return;

    int do_proceed = 0, do_start = 0, eos = 0, error = 0;
    chunk_t *to_send = NULL;

    chunk_t *discarded = NULL;
    pthread_mutex_lock(&ex->mu);
    if (ex->in_discard && ex->in_head != NULL) {
        discarded = ex->in_head;
        ex->in_head = ex->in_tail = NULL;
        ex->in_bytes = 0;
    }
    if (ex->owe_proceed && ex->in_bytes < IN_HIGH_WATER) {
        ex->owe_proceed = 0;
        do_proceed = 1;
    }
    if (!ex->started && ex->res_ready)
        do_start = 1;
    if ((ex->started || do_start) && !ex->sending && !ex->final_sent) {
        to_send = ex->out_head;
        ex->out_head = ex->out_tail = NULL;
        ex->out_bytes = 0;
        eos = ex->out_eos;
        error = ex->out_error;
        pthread_cond_broadcast(&ex->cond);
    }
    exchange_retain_locked(ex); /* h2o calls below may dispose the request */
    pthread_mutex_unlock(&ex->mu);
    free_chunks(discarded);

    if (do_proceed && req->proceed_req != NULL)
        req->proceed_req(req, NULL);

    if (do_start && ex->req != NULL) {
        ex->started = 1;
        req->res.status = ex->res_status;
        req->res.reason = ex->res_status == 200 ? "OK" : "";
        for (size_t i = 0; i != ex->res_num_headers; ++i) {
            const brrrr_header_t *h = ex->res_headers + i;
            h2o_add_header_by_str(&req->pool, &req->res.headers, h->name.base, h->name.len, 1, NULL, h->value.base,
                                  h->value.len);
        }
        if (req->version < 0x300) /* advertise HTTP/3 to TCP clients */
            h2o_add_header(&req->pool, &req->res.headers, H2O_TOKEN_ALT_SVC, NULL, g.alt_svc.base, g.alt_svc.len);
        req->res.content_length = ex->res_content_length;
        ex->generator = (h2o_generator_t){on_generator_proceed, on_generator_stop};
        h2o_start_response(req, &ex->generator);
    }

    /* HEAD responses carry no body. h2o strips it for h1/h2 but not h3, so drop it here for all of them. */
    if (ex->req != NULL && to_send != NULL && h2o_memis(req->method.base, req->method.len, H2O_STRLIT("HEAD"))) {
        free_chunks(to_send);
        to_send = NULL;
    }

    if (ex->req != NULL && (to_send != NULL || eos)) {
        size_t n = 0;
        for (chunk_t *c = to_send; c != NULL; c = c->next)
            ++n;
        h2o_sendvec_t *vecs = h2o_mem_alloc_pool(&req->pool, h2o_sendvec_t, n == 0 ? 1 : n);
        size_t i = 0;
        for (chunk_t *c = to_send; c != NULL; c = c->next)
            h2o_sendvec_init_raw(vecs + i++, c->data, c->len);
        ex->inflight = to_send;
        ex->sending = 1; /* before h2o_send, which may call proceed synchronously */
        if (eos)
            ex->final_sent = 1;
        h2o_sendvec(req, vecs, n, error ? H2O_SEND_STATE_ERROR : eos ? H2O_SEND_STATE_FINAL : H2O_SEND_STATE_IN_PROGRESS);
    } else {
        free_chunks(to_send);
    }

    exchange_release(ex);
}

/* ---- worker-side API ---- */

int brrrr_read_body(brrrr_request_t *req, brrrr_str_t *chunk)
{
    exchange_t *ex = (exchange_t *)req;
    int ret;
    pthread_mutex_lock(&ex->mu);
    free_chunks(ex->in_last_read);
    ex->in_last_read = NULL;
    while (ex->in_head == NULL && !ex->in_eos && !ex->closed)
        pthread_cond_wait(&ex->cond, &ex->mu);
    if (ex->in_head != NULL) {
        chunk_t *c = ex->in_head;
        if ((ex->in_head = c->next) == NULL)
            ex->in_tail = NULL;
        c->next = NULL;
        ex->in_bytes -= c->len;
        ex->in_last_read = c;
        *chunk = (brrrr_str_t){c->data, c->len};
        if (ex->owe_proceed && ex->in_bytes < IN_HIGH_WATER)
            poke_locked(ex);
        ret = 1;
    } else if (ex->in_eos) {
        ret = 0;
    } else {
        ret = -1;
    }
    pthread_mutex_unlock(&ex->mu);
    return ret;
}

int brrrr_start_response(brrrr_request_t *req, int status, const brrrr_header_t *headers, size_t num_headers,
                         size_t content_length)
{
    exchange_t *ex = (exchange_t *)req;

    /* copy headers, skipping the ones h2o computes itself */
    size_t total = 0, n = 0;
    for (size_t i = 0; i != num_headers; ++i)
        total += headers[i].name.len + headers[i].value.len;
    brrrr_header_t *hs = h2o_mem_alloc(sizeof(*hs) * (num_headers == 0 ? 1 : num_headers));
    char *data = h2o_mem_alloc(total == 0 ? 1 : total), *p = data;
    for (size_t i = 0; i != num_headers; ++i) {
        const brrrr_header_t *h = headers + i;
        if (h2o_memis(h->name.base, h->name.len, H2O_STRLIT("content-length")) ||
            h2o_memis(h->name.base, h->name.len, H2O_STRLIT("transfer-encoding")) ||
            h2o_memis(h->name.base, h->name.len, H2O_STRLIT("connection")))
            continue;
        memcpy(p, h->name.base, h->name.len);
        hs[n].name = (brrrr_str_t){p, h->name.len};
        p += h->name.len;
        memcpy(p, h->value.base, h->value.len);
        hs[n].value = (brrrr_str_t){p, h->value.len};
        p += h->value.len;
        ++n;
    }

    pthread_mutex_lock(&ex->mu);
    if (ex->res_ready || ex->closed) {
        pthread_mutex_unlock(&ex->mu);
        free(hs);
        free(data);
        return -1;
    }
    ex->res_ready = 1;
    ex->res_status = status;
    ex->res_headers = hs;
    ex->res_num_headers = n;
    ex->res_header_data = data;
    ex->res_content_length = content_length;
    poke_locked(ex);
    pthread_mutex_unlock(&ex->mu);
    return 0;
}

int brrrr_write(brrrr_request_t *req, brrrr_str_t data)
{
    exchange_t *ex = (exchange_t *)req;
    if (data.len == 0) {
        pthread_mutex_lock(&ex->mu);
        int ok = ex->res_ready && !ex->closed;
        pthread_mutex_unlock(&ex->mu);
        return ok ? 0 : -1;
    }
    chunk_t *c = new_chunk(data.base, data.len);
    pthread_mutex_lock(&ex->mu);
    while (ex->out_bytes >= OUT_HIGH_WATER && !ex->closed)
        pthread_cond_wait(&ex->cond, &ex->mu);
    if (!ex->res_ready || ex->out_eos || ex->closed) {
        pthread_mutex_unlock(&ex->mu);
        free(c);
        return -1;
    }
    append_chunk(&ex->out_head, &ex->out_tail, c);
    ex->out_bytes += c->len;
    poke_locked(ex);
    pthread_mutex_unlock(&ex->mu);
    return 0;
}

int brrrr_respond(brrrr_request_t *req, int status, const brrrr_header_t *headers, size_t num_headers, brrrr_str_t body)
{
    if (brrrr_start_response(req, status, headers, num_headers, body.len) != 0)
        return -1;
    return brrrr_write(req, body);
}

void brrrr_fail(brrrr_request_t *req)
{
    exchange_t *ex = (exchange_t *)req;
    pthread_mutex_lock(&ex->mu);
    int started = ex->res_ready;
    if (started && !ex->out_eos) {
        ex->out_error = 1;
        ex->out_eos = 1;
        poke_locked(ex);
    }
    pthread_mutex_unlock(&ex->mu);
    if (!started)
        brrrr_respond(req, 500, (brrrr_header_t[]){{{"content-type", 12}, {"text/plain", 10}}}, 1,
                      (brrrr_str_t){"Internal Server Error", 21});
}

/* worker: the handler returned */
static void finish(exchange_t *ex)
{
    pthread_mutex_lock(&ex->mu);
    int started = ex->res_ready;
    pthread_mutex_unlock(&ex->mu);
    if (!started)
        brrrr_respond(&ex->pub, 500, (brrrr_header_t[]){{{"content-type", 12}, {"text/plain", 10}}}, 1,
                      (brrrr_str_t){"Internal Server Error", 21});
    pthread_mutex_lock(&ex->mu);
    ex->out_eos = 1;
    ex->in_discard = 1;
    poke_locked(ex);
    pthread_mutex_unlock(&ex->mu);
    exchange_release(ex);
}

static void *worker_main(void *arg)
{
    (void)arg;
    if (g.worker_init != NULL)
        g.worker_init();
    for (;;) {
        pthread_mutex_lock(&g.jobs.mu);
        while (g.jobs.head == NULL)
            pthread_cond_wait(&g.jobs.cond, &g.jobs.mu);
        exchange_t *ex = g.jobs.head;
        if ((g.jobs.head = ex->next_job) == NULL)
            g.jobs.tail = NULL;
        pthread_mutex_unlock(&g.jobs.mu);

        g.handler(&ex->pub);
        finish(ex);
    }
    return NULL;
}

/* ---- request intake (EL) ---- */

static brrrr_str_t copy_into(char **p, const char *base, size_t len)
{
    brrrr_str_t s = {*p, len};
    if (len != 0)
        memcpy(*p, base, len);
    *p += len;
    return s;
}

static int on_req(h2o_handler_t *self, h2o_req_t *req)
{
    (void)self;

    /* h2/h3 carry the host in :authority, not in a header; Ring wants a host header either way. h2o moves
       content-length out of the header list; Ring expects it as a header. */
    int add_host = h2o_find_header(&req->headers, H2O_TOKEN_HOST, -1) == -1;
    int add_length = req->content_length != SIZE_MAX;
    char length_buf[sizeof(H2O_UINT64_LONGEST_STR)];
    size_t length_len = add_length ? (size_t)snprintf(length_buf, sizeof(length_buf), "%zu", req->content_length) : 0;

    struct sockaddr_storage ss;
    socklen_t sslen = req->conn->callbacks->get_peername(req->conn, (struct sockaddr *)&ss);
    char addr[NI_MAXHOST];
    size_t addr_len = sslen != 0 ? h2o_socket_getnumerichost((struct sockaddr *)&ss, sslen, addr) : SIZE_MAX;
    if (addr_len == SIZE_MAX)
        addr_len = 0;

    size_t num_headers = req->headers.size + add_host + add_length;
    size_t meta_size = req->method.len + req->path.len + req->scheme->name.len + req->authority.len + addr_len + length_len +
                       sizeof("hostcontent-length") + sizeof(brrrr_header_t) * (num_headers + 1) + _Alignof(brrrr_header_t);
    for (size_t i = 0; i != req->headers.size; ++i)
        meta_size += req->headers.entries[i].name->len + req->headers.entries[i].value.len;

    exchange_t *ex = h2o_mem_alloc(sizeof(*ex));
    *ex = (exchange_t){.req = req, .refs = 2 /* h2o request + worker */};
    pthread_mutex_init(&ex->mu, NULL);
    pthread_cond_init(&ex->cond, NULL);
    h2o_timer_init(&ex->pump_timer, on_pump_timer);
    ex->meta = h2o_mem_alloc(meta_size);

    /* headers array first (aligned), then string data */
    brrrr_header_t *hs = (brrrr_header_t *)ex->meta;
    char *p = ex->meta + sizeof(brrrr_header_t) * (num_headers + 1);
    brrrr_request_t *r = &ex->pub;
    r->method = copy_into(&p, req->method.base, req->method.len);
    if (req->query_at != SIZE_MAX) {
        r->path = copy_into(&p, req->path.base, req->query_at);
        r->query = copy_into(&p, req->path.base + req->query_at + 1, req->path.len - req->query_at - 1);
    } else {
        r->path = copy_into(&p, req->path.base, req->path.len);
    }
    r->scheme = copy_into(&p, req->scheme->name.base, req->scheme->name.len);
    r->authority = copy_into(&p, req->authority.base, req->authority.len);
    r->http_version = req->version;
    r->remote_addr = copy_into(&p, addr, addr_len);
    r->server_port = g.port;
    size_t j = 0;
    for (size_t i = 0; i != req->headers.size; ++i) {
        const h2o_header_t *h = req->headers.entries + i;
        hs[j].name = copy_into(&p, h->name->base, h->name->len);
        hs[j++].value = copy_into(&p, h->value.base, h->value.len);
    }
    if (add_host) {
        hs[j].name = copy_into(&p, "host", 4);
        hs[j++].value = r->authority;
    }
    if (add_length) {
        hs[j].name = copy_into(&p, "content-length", 14);
        hs[j++].value = copy_into(&p, length_buf, length_len);
    }
    r->headers = hs;
    r->num_headers = j;

    /* request body: complete (proceed_req == NULL), streaming, or absent */
    r->has_body = req->entity.base != NULL || req->proceed_req != NULL;
    if (req->entity.len != 0) {
        chunk_t *c = new_chunk(req->entity.base, req->entity.len);
        append_chunk(&ex->in_head, &ex->in_tail, c);
        ex->in_bytes = c->len;
    }
    if (req->proceed_req != NULL) {
        req->write_req.cb = on_write_req;
        req->write_req.ctx = ex;
        ex->owe_proceed = req->entity.base != NULL; /* h2o waits for us to consume what it already handed over */
    } else {
        ex->in_eos = 1;
    }

    *(exchange_t **)h2o_mem_alloc_shared(&req->pool, sizeof(exchange_t *), on_req_dispose) = ex;
    if (ex->owe_proceed)
        schedule_pump(ex);

    pthread_mutex_lock(&g.jobs.mu);
    if (g.jobs.tail != NULL)
        g.jobs.tail->next_job = ex;
    else
        g.jobs.head = ex;
    g.jobs.tail = ex;
    pthread_cond_signal(&g.jobs.cond);
    pthread_mutex_unlock(&g.jobs.mu);
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
    h2o_ssl_register_alpn_protocols(ssl, h2o_alpn_protocols); /* h2 first, then http/1.1 */
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

/* h2o's HTTP/3 server sends NEW_TOKEN frames periodically and assumes a generator is set. Tokens are encrypted
   with a per-process key. They are not validated on the way back in (no address validation/retry yet). */
static quicly_error_t generate_resumption_token(quicly_generate_resumption_token_t *self, quicly_conn_t *conn,
                                                ptls_buffer_t *buf, quicly_address_token_plaintext_t *token)
{
    return quicly_encrypt_address_token(ptls_openssl_random_bytes, g.h3.token_aead, buf, buf->off, token);
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

    uint8_t token_secret[PTLS_SHA256_DIGEST_SIZE];
    ptls_openssl_random_bytes(token_secret, sizeof(token_secret));
    g.h3.token_aead = ptls_aead_new(&ptls_openssl_aes128gcm, &ptls_openssl_sha256, 1, token_secret, "");
    ptls_clear_memory(token_secret, sizeof(token_secret));
    g.h3.token_generator.cb = generate_resumption_token;
    g.h3.quic.generate_resumption_token = &g.h3.token_generator;
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
    /* a client closing its connection mid-write must not kill the process */
    signal(SIGPIPE, SIG_IGN);

    g.handler = c->handler;
    g.worker_init = c->worker_init;
    g.port = c->port;

    h2o_config_init(&g.globalconf);
    g.globalconf.max_request_entity_size = c->max_request_body_size != 0 ? c->max_request_body_size : 1024 * 1024 * 1024;
    h2o_hostconf_t *hostconf = h2o_config_register_host(&g.globalconf, h2o_iovec_init(H2O_STRLIT("default")), 65535);
    h2o_pathconf_t *pathconf = h2o_config_register_path(hostconf, "/", 0);
    h2o_handler_t *handler = h2o_create_handler(pathconf, sizeof(*handler));
    handler->on_req = on_req;
    handler->supports_request_streaming = 1;

    h2o_context_init(&g.ctx, h2o_evloop_create(), &g.globalconf);
    g.accept_ctx.ctx = &g.ctx;
    g.accept_ctx.hosts = g.globalconf.hosts;
    g.queue = h2o_multithread_create_queue(g.ctx.loop);
    h2o_multithread_register_receiver(g.queue, &g.receiver, on_pokes);

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

    pthread_mutex_init(&g.jobs.mu, NULL);
    pthread_cond_init(&g.jobs.cond, NULL);
    size_t num_workers = c->num_workers != 0 ? c->num_workers : 8;
    for (size_t i = 0; i != num_workers; ++i) {
        pthread_t tid;
        if (pthread_create(&tid, NULL, worker_main, NULL) != 0) {
            perror("brrrr: pthread_create");
            return -1;
        }
        pthread_detach(tid);
    }

    fprintf(stderr, "brrrr: listening on https://%s:%u (h1, h2 over tcp; h3 over udp; %zu workers)\n", c->host,
            (unsigned)c->port, num_workers);
    /* -1 just means epoll was interrupted, e.g. by the GC stopping the world from a worker */
    for (;;)
        h2o_evloop_run(g.ctx.loop, INT32_MAX);
}
