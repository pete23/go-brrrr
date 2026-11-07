/* Thin C facade over libh2o: one handler, HTTP/1.1 + HTTP/2 over TLS/TCP and HTTP/3 over QUIC/UDP on the same port. */
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

/* All strings are views into memory owned by the request; valid until brrrr_respond() returns. */
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
    brrrr_str_t body; /* fully buffered; base == NULL if none */
    void *_impl;
} brrrr_request_t;

/* Sends the response. Header names must be lowercase. Everything is copied before returning.
   Must be called exactly once, from inside the handler; if it is not, a 500 is sent. */
void brrrr_respond(brrrr_request_t *req, int status, const brrrr_header_t *headers, size_t num_headers, brrrr_str_t body);

typedef void (*brrrr_handler_fn)(brrrr_request_t *req);

typedef struct brrrr_config {
    const char *host;      /* IPv4 literal, e.g. "0.0.0.0" */
    uint16_t port;         /* used for both TCP and UDP */
    const char *cert_file; /* PEM certificate chain */
    const char *key_file;  /* PEM private key */
    brrrr_handler_fn handler;
} brrrr_config_t;

/* Runs the event loop on the calling thread. Returns non-zero on setup failure; otherwise does not return. */
int brrrr_serve(const brrrr_config_t *config);

#ifdef __cplusplus
}
#endif

#endif
