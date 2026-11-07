/* Thin C facade over libh2o: one handler, HTTP/1.1 + HTTP/2 over TLS/TCP and HTTP/3 over QUIC/UDP on the same port. */
#ifndef BRRRR_H
#define BRRRR_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct brrrr_request {
    const char *method;
    size_t method_len;
    const char *path;
    size_t path_len;
} brrrr_request_t;

/* Filled by the handler. `body` only needs to stay valid until the handler returns; it is copied. */
typedef struct brrrr_response {
    int status;
    const char *content_type;
    const char *body;
    size_t body_len;
} brrrr_response_t;

typedef void (*brrrr_handler_fn)(const brrrr_request_t *req, brrrr_response_t *res);

typedef struct brrrr_config {
    const char *host;      /* e.g. "0.0.0.0" */
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
