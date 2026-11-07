/* Thin C facade over libh2o: one handler, HTTP/1.1 + HTTP/2 over TLS/TCP and HTTP/3 over QUIC/UDP on the same port.
 *
 * Threading model: h2o runs on the thread that calls brrrr_serve() (the event loop). Each request is handed to one of a
 * pool of worker threads, where the handler runs and may block. Request bodies are streamed to the worker and responses
 * streamed back, both with bounded buffering: brrrr_read_body() blocks until data arrives, brrrr_write() blocks while the
 * client is not keeping up. All functions taking a brrrr_request_t are called from the worker running that request. */
#ifndef BRRRR_H
#define BRRRR_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct brrrr_str {
    const char *base;
    size_t len;
} brrrr_str_t;

typedef struct brrrr_header {
    brrrr_str_t name; /* always lowercase */
    brrrr_str_t value;
} brrrr_header_t;

/* All strings are owned by the request and live until the handler returns. */
typedef struct brrrr_request {
    brrrr_str_t method;
    brrrr_str_t path;      /* raw path, without the query string */
    brrrr_str_t query;     /* without the leading '?'; base == NULL if absent */
    brrrr_str_t scheme;    /* "https" */
    brrrr_str_t authority; /* host[:port] from Host / :authority */
    int http_version;      /* 0x100, 0x101, 0x200, 0x300 */
    brrrr_str_t remote_addr;
    uint16_t server_port;
    const brrrr_header_t *headers;
    size_t num_headers;
    int has_body; /* whether the request has a body (possibly empty); read it with brrrr_read_body */
} brrrr_request_t;

/* Reads the next chunk of the request body. Returns 1 and sets *chunk (valid until the next call), 0 at the end of the
   body, or -1 if the client went away. Blocks until one of those is true. */
int brrrr_read_body(brrrr_request_t *req, brrrr_str_t *chunk);

/* Starts the response. Header names must be lowercase; content-length, transfer-encoding and connection are ignored.
   content_length is SIZE_MAX if unknown (the body is then chunked/framed by the protocol). Copies everything.
   Returns 0, or -1 if the response was already started or the client went away. */
int brrrr_start_response(brrrr_request_t *req, int status, const brrrr_header_t *headers, size_t num_headers,
                         size_t content_length);

/* Appends to the response body; blocks while too much is buffered. Returns 0, or -1 if the response has not been
   started or the client went away. */
int brrrr_write(brrrr_request_t *req, brrrr_str_t data);

/* Convenience: brrrr_start_response with a known length, then brrrr_write. */
int brrrr_respond(brrrr_request_t *req, int status, const brrrr_header_t *headers, size_t num_headers, brrrr_str_t body);

/* Ends the request in error: a 500 if the response has not started, otherwise the response is aborted (HTTP/1 closes
   the connection, HTTP/2 and 3 reset the stream) so the client can tell the body is incomplete. */
void brrrr_fail(brrrr_request_t *req);

/* Called on a worker thread. The response is finished when it returns; if it never started one, a 500 is sent. */
typedef void (*brrrr_handler_fn)(brrrr_request_t *req);

typedef struct brrrr_config {
    const char *host;      /* IPv4 literal, e.g. "0.0.0.0" */
    uint16_t port;         /* used for both TCP and UDP */
    const char *cert_file; /* PEM certificate chain */
    const char *key_file;  /* PEM private key */
    brrrr_handler_fn handler;
    size_t num_workers;           /* default 8 */
    void (*worker_init)(void);    /* optional; runs first on each worker thread */
    size_t max_request_body_size; /* default 1GiB */
} brrrr_config_t;

/* Runs the event loop on the calling thread. Returns non-zero on setup failure; otherwise does not return. */
int brrrr_serve(const brrrr_config_t *config);

#ifdef __cplusplus
}
#endif

#endif
