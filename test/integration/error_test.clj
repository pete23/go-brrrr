(ns integration.error-test
  "Handler errors, before and after the response has started."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [integration.echo :as e]
            [integration.harness :as h]))

(use-fixtures :once (h/server-fixture e/argv))
(use-fixtures :each h/alive-fixture)

(deftest handler-errors-are-500
  (doseq [path ["/throw" "/throw-native" "/bad-body"]
          proto e/all-protos]
    (testing [path proto]
      (let [r (h/request path :proto proto)]
        (is (= 500 (:status r)))
        (is (= "Internal Server Error" (h/text r))))))
  (let [log (h/log-text h/*server*)]
    (is (str/includes? log "handler threw: #error {\n :cause \"boom\""))
    (is (str/includes? log "The first argument to `subs` must be a `persistent_string`"))
    (is (str/includes? log "Unsupported response body type"))))

(deftest nil-response-is-404
  (doseq [proto e/all-protos]
    (testing proto
      (is (= 404 (:status (h/request "/nil" :proto proto)))))))

(deftest failure-mid-stream-aborts-response
  ;; once streaming has started, a failure must not look like a complete response
  (let [r (h/curl (h/url "/throw-mid-stream") :proto :h1)]
    (is (= 200 (:status r)))
    (is (= "partial" (h/text r)))
    (is (= 56 (:exit r)) "h1: connection closed without the terminating chunk"))
  (let [r (h/curl (h/url "/throw-mid-stream") :proto :h2)]
    (is (= "partial" (h/text r)))
    (is (= 92 (:exit r)) "h2: stream reset"))
  (is (str/includes? (h/log-text h/*server*) "mid-stream boom")))

(deftest failure-mid-stream-h3-known-limitation
  ;; h2o's HTTP/3 server ends the stream cleanly on error (TODO in
  ;; lib/http3/server.c), so an h3 client cannot tell the body is truncated.
  ;; This pins the current behaviour.
  (let [r (h/h3 (h/url "/throw-mid-stream"))]
    (is (= 200 (:status r)))
    (is (= "partial" (h/text r)))))

(deftest server-healthy-after-errors
  (doseq [proto e/all-protos]
    (is (= 200 (:status (h/request "/echo" :proto proto))))))
