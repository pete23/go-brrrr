(ns integration.request-map-test
  "The Ring request map, as built by ring.adapter.h2o, on every protocol."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [integration.echo :as e :refer [echo]]
            [integration.harness :as h]))

(use-fixtures :once (h/server-fixture e/argv))
(use-fixtures :each h/alive-fixture)

(def protocol-names {:h1 "HTTP/1.1" :h1.0 "HTTP/1.0" :h2 "HTTP/2.0" :h3 "HTTP/3.0"})

(deftest basic-fields-every-protocol
  (doseq [proto [:h1.0 :h1 :h2 :h3]]
    (testing proto
      (let [m (echo "/echo?x=1&y=2" :proto proto)
            port (:port h/*server*)]
        (is (= ":get" (m "request-method")))
        (is (= "/echo" (m "uri")))
        (is (= "x=1&y=2" (m "query-string")))
        (is (= ":https" (m "scheme")))
        (is (= (protocol-names proto) (m "protocol")))
        (is (= "127.0.0.1" (m "server-name")))
        (is (= (str port) (m "server-port")))
        (is (= "127.0.0.1" (m "remote-addr")))
        (is (= (str "127.0.0.1:" port) (m "header.host")) "host header on every protocol")
        (is (= "false" (m "has-body")))))))

(deftest server-name-from-host
  (let [r (h/curl (str "https://localhost:" (:port h/*server*) "/echo"))]
    (is (= "localhost" ((h/parse-echo r) "server-name"))))
  (let [m (h/parse-echo (h/curl (h/url "/echo") :proto :h1 :args ["-H" "Host: example.com:1234"]))]
    (is (= "example.com" (m "server-name")))
    (is (= "example.com:1234" (m "header.host")))))

(deftest absent-vs-empty-query-string
  (doseq [proto e/all-protos]
    (testing proto
      (is (= "false" ((echo "/echo" :proto proto) "has-query")))
      (let [m (echo "/echo?" :proto proto)]
        (is (= "true" (m "has-query")))
        (is (= "" (m "query-string")))))))

(deftest uri-is-not-decoded
  (doseq [proto e/all-protos]
    (testing proto
      (is (= "a=%20&b=%2F" ((echo "/echo?a=%20&b=%2F" :proto proto) "query-string")))))
  (let [r (h/curl (h/url "/a%20b/c%2Fd") :proto :h1)]
    (is (= 404 (:status r)))
    (is (= "not found" (h/text r))))
  (is (= "/echo" ((h/parse-echo (h/curl (h/url "/echo") :proto :h1 :args ["--path-as-is"])) "uri"))))

(deftest methods
  (doseq [method ["POST" "PUT" "DELETE" "PATCH" "OPTIONS" "PURGE"]]
    (testing method
      (is (= (str ":" (clojure.string/lower-case method))
             ((h/parse-echo (h/curl (h/url "/echo") :args ["-X" method])) "request-method")))))
  (is (= ":delete" ((echo "/echo" :proto :h3 :method "DELETE") "request-method"))))

(deftest headers-lowercased-and-repeats-joined
  (doseq [proto e/tcp]
    (testing proto
      (let [m (h/parse-echo (h/curl (h/url "/echo") :proto proto
                                    :args ["-H" "X-Mixed-CASE: v" "-H" "X-Dup: 1" "-H" "X-Dup: 2"]))]
        (is (= "v" (m "header.x-mixed-case")))
        (is (= "1,2" (m "header.x-dup"))))))
  (is (= "3" ((echo "/echo" :proto :h3 :headers ["X-Three: 3"]) "header.x-three"))))

(deftest content-length-header-present
  (doseq [proto e/all-protos]
    (testing proto
      (let [m (if (= :h3 proto)
                (echo "/echo" :proto :h3 :method "POST" :body-size 7)
                (echo "/echo" :proto proto :data "1234567"))]
        (is (= "7" (m "header.content-length")))
        (is (= "7" (m "body-length")))
        (is (= "true" (m "has-body")))))))

(deftest empty-post-has-empty-body
  (let [m (echo "/echo" :proto :h1 :data "")]
    (is (= "0" (m "header.content-length")))
    (is (= "0" (m "body-length")))))

(deftest get-has-no-body
  (doseq [proto e/all-protos]
    (testing proto
      (let [m (echo "/echo" :proto proto)]
        (is (= "false" (m "has-body")))
        (is (not (contains? m "header.content-length")))))))
