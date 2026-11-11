;; go-brrrr in Chez Scheme, for comparison: the same API as src/go_brrrr/main.jank and
;; reference/h2o_ref.cc, served by libh2o through chez/brrrr_chez.c (one event loop, h1 and
;; h2 over TLS, each request handled inline by `handle` below).
;;
;;   GET /            -> 200 "23" (text/plain)
;;   GET|POST /add    -> 200 a+b (text/plain), a and b from the query string or a urlencoded
;;                       form body, parsed like clojure.core/parse-long; else 400
;;   anything else    -> 404 "not found"
;;
;; Behaviour mirrors the reference (and so the JVM): HEAD is routed as GET; form and query
;; parameters merge with the query winning; a key repeated within one of them is a vector in
;; Ring, which parse-long rejects by throwing, so a 500; a sum outside a 64-bit long is a 500,
;; as + throws on overflow.
;;
;; With BRRRR_STATS set, GET /_brrrr/stats reports requests served and the collector's
;; cumulative allocation, collections and heap in use, for bb bench.
;;
;; Usage (from the repository root, after bb build:chez): chez/go-brrrr-chez [port]
(import (chezscheme))

(load-shared-object
 (if (memq (machine-type) '(ta6osx tarm64osx a6osx arm64osx))
     "target/chez/libbrrrr-chez.dylib"
     "target/chez/libbrrrr-chez.so"))

(define respond (foreign-procedure "brrrr_chez_respond" (void* int boolean utf-8) void))
(define serve (foreign-procedure "brrrr_chez_serve" (int void*) int))

;; ---- parameters: ring.util.codec/form-decode-map and ring.middleware.params ----

(define (hex-value c)
  (cond [(char<=? #\0 c #\9) (- (char->integer c) 48)]
        [(char<=? #\a c #\f) (- (char->integer c) 87)]
        [(char<=? #\A c #\F) (- (char->integer c) 55)]
        [else #f]))

;; java.net.URLDecoder semantics: + is a space, %XX a byte; a malformed escape makes the
;; whole value invalid (#f).
(define (form-decode s start end)
  (let-values ([(out get) (open-bytevector-output-port)])
    (let loop ([i start])
      (cond
        [(= i end) (utf8->string (get))]
        [(char=? (string-ref s i) #\+) (put-u8 out 32) (loop (+ i 1))]
        [(char=? (string-ref s i) #\%)
         (let* ([room (< (+ i 2) end)]
                [hi (and room (hex-value (string-ref s (+ i 1))))]
                [lo (and room (hex-value (string-ref s (+ i 2))))])
           (and hi lo (begin (put-u8 out (+ (* hi 16) lo)) (loop (+ i 3)))))]
        [else (put-bytevector out (string->utf8 (string (string-ref s i)))) (loop (+ i 1))]))))

(define (index-of s c start end)
  (let loop ([i start]) (cond [(= i end) #f] [(char=? (string-ref s i) c) i] [else (loop (+ i 1))])))

;; Pairs split on & and the first =, both sides decoded; pairs that don't decode are dropped.
;; A repeated key's value becomes 'repeated (a vector in Ring).
(define (parse-params! s table)
  (let ([n (string-length s)])
    (let loop ([start 0])
      (when (< start n)
        (let* ([amp (or (index-of s #\& start n) n)]
               [eq  (index-of s #\= start amp)]
               [k   (and (< start amp) (form-decode s start (or eq amp)))]
               [v   (and (< start amp) (if eq (form-decode s (+ eq 1) amp) ""))])
          (when (and k v)
            (hashtable-update! table k (lambda (old) (if (eq? old 'absent) v 'repeated)) 'absent))
          (loop (+ amp 1)))))))

;; ---- clojure.core/parse-long ----

(define long-min (- (expt 2 63)))
(define long-max (- (expt 2 63) 1))

(define-condition-type &handler-threw &condition make-handler-threw handler-threw?)

;; An optional sign, then decimal digits only, within a long; else #f. A vector throws.
(define (parse-long v)
  (cond
    [(eq? v 'repeated) (raise (make-handler-threw))]
    [(not v) #f]
    [else
     (let* ([n (string-length v)]
            [start (if (and (> n 0) (memv (string-ref v 0) '(#\+ #\-))) 1 0)])
       (and (< start n)
            (let loop ([i start] [acc 0])
              (if (= i n)
                  (let ([r (if (char=? (string-ref v 0) #\-) (- acc) acc)])
                    (and (<= long-min r long-max) r))
                  (let ([c (string-ref v i)])
                    (and (char<=? #\0 c #\9)
                         (loop (+ i 1) (+ (* acc 10) (- (char->integer c) 48)))))))))]))

;; ---- the app ----

(define stats? (getenv "BRRRR_STATS"))
(define requests 0)

(define (stats-text)
  (format "requests ~a\nallocated_bytes ~a\ngc_count ~a\nheap_bytes ~a\n"
          requests (sstats-bytes (statistics)) (collections) (bytes-allocated)))

(define (string-prefix? p s)
  (and (<= (string-length p) (string-length s)) (string=? p (substring s 0 (string-length p)))))

(define (add req post? query content-type body)
  (let ([params (make-hashtable string-hash string=?)])
    (when (and post? body content-type (string-prefix? "application/x-www-form-urlencoded" content-type))
      (parse-params! body params))
    (when query
      (let ([q (make-hashtable string-hash string=?)])
        (parse-params! query q)
        (vector-for-each (lambda (k) (hashtable-set! params k (hashtable-ref q k #f)))
                         (hashtable-keys q))))
    (let ([a (parse-long (hashtable-ref params "a" #f))]
          [b (parse-long (hashtable-ref params "b" #f))])
      (cond
        [(not (and a b)) (respond req 400 #f "need integer a and b")]
        [(<= long-min (+ a b) long-max) (respond req 200 #t (number->string (+ a b)))]
        [else (raise (make-handler-threw))]))))

(define (route req method path query content-type body)
  (let ([get? (or (string=? method "GET") (string=? method "HEAD"))]
        [post? (string=? method "POST")])
    (cond
      [(and stats? get? (string=? path "/_brrrr/stats"))
       (respond req 200 #t (stats-text))]
      [else
       (set! requests (+ requests 1))
       (cond
         [(and get? (string=? path "/")) (respond req 200 #t (number->string (+ 20 3)))]
         [(and (or get? post?) (string=? path "/add")) (add req post? query content-type body)]
         [else (respond req 404 #f "not found")])])))

;; Called from C for every request. Nothing may unwind out of it into C, so any error,
;; expected (a 500 the API defines) or not, becomes a 500 here.
(define (handle req method path query content-type body)
  (guard (e [#t (unless (handler-threw? e)
                  (fprintf (current-error-port) "go-brrrr-chez: handler threw: ~a\n"
                           (if (condition? e) (with-output-to-string (lambda () (display-condition e))) e)))
                (respond req 500 #t "Internal Server Error")])
    (route req method path query content-type body)))

(define handler
  (let ([code (foreign-callable handle (void* utf-8 utf-8 utf-8 utf-8 utf-8) void)])
    (lock-object code)
    code))

(let ([port (let ([args (command-line-arguments)])
              (or (and (pair? args) (string->number (car args))) 8443))])
  (exit (serve port (foreign-callable-entry-point handler))))
