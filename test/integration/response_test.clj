(ns integration.response-test
  "Status codes, headers, content-length, HEAD and Alt-Svc."
  (:require [babashka.process]
            [clojure.string]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [integration.echo :as e]
            [integration.harness :as h]))

(use-fixtures :once (h/server-fixture e/argv))
(use-fixtures :each h/alive-fixture)

(deftest status-codes
  (doseq [code [200 201 202 301 302 400 404 418 500 503]
          proto e/all-protos]
    (testing [code proto]
      (is (= code (:status (h/request (str "/status?code=" code) :proto proto)))))))

(deftest no-body-for-204
  (doseq [proto e/tcp]
    (testing proto
      (let [r (h/request "/status?code=204" :proto proto)]
        (is (= 204 (:status r)))
        (is (zero? (alength ^bytes (:body r))))))))

(deftest headers
  (doseq [proto e/all-protos]
    (testing proto
      (let [r (h/request "/status?code=302" :proto proto)]
        (is (= "/elsewhere" (h/header r "location")))
        (is (= ["a" "b"] (h/header-all r "x-multi")) "vector values become repeated headers")
        (is (= "kept" (h/header r "x-mixed-case")) "names are lowercased on the wire")))))

(deftest handler-content-length-is-ignored
  ;; the handler claims Content-Length: 999; the real length must win
  (doseq [proto e/all-protos]
    (testing proto
      (let [r (h/request "/status?code=200" :proto proto)]
        (is (= "status body" (h/text r)))
        (is (= ["11"] (h/header-all r "content-length")))))))

(deftest string-body-has-content-length
  (doseq [proto e/all-protos]
    (testing proto
      (is (= "256" (h/header (h/request "/binary" :proto proto) "content-length"))))))

(deftest streamed-body-has-no-content-length
  (doseq [path ["/stream" "/lazy"]]
    (testing path
      (let [r (h/request path :proto :h1)]
        (is (nil? (h/header r "content-length")))
        (is (= "chunked" (h/header r "transfer-encoding")))
        (is (= "000000111111222222" (h/text r))))
      (is (= "000000111111222222" (h/text (h/request path :proto :h3)))))))

(deftest nil-body
  (doseq [proto e/all-protos]
    (testing proto
      (let [r (h/request "/nil-body" :proto proto)]
        (is (= 200 (:status r)))
        (is (= "" (h/text r)))
        (is (= "0" (h/header r "content-length")))))))

(deftest alt-svc-on-tcp-only
  (doseq [proto e/tcp]
    (testing proto
      (is (= (str "h3=\":" (:port h/*server*) "\"") (h/header (h/request "/echo" :proto proto) "alt-svc")))))
  (is (nil? (h/header (h/request "/echo" :proto :h3) "alt-svc"))))

(deftest head
  ;; h2o sends the headers of the GET response and no body
  (doseq [proto e/tcp]
    (testing proto
      (let [r (h/curl (h/url "/binary") :proto proto :args ["-I"])]
        (is (= 200 (:status r)))
        (is (= "256" (h/header r "content-length")))
        (is (zero? (get-in r [:info :size_download]))))))
  (let [r (h/h3 (h/url "/binary") :method "HEAD")]
    (is (= 200 (:status r)))
    (is (zero? (alength ^bytes (:body r))))))

(deftest alpn-offers-h2-and-http1
  ;; clients asking for http/1.1 must get it negotiated, not fall back silently
  (doseq [[offer expected] [["http/1.1" "http/1.1"] ["h2" "h2"] ["h2,http/1.1" "h2"]]]
    (testing offer
      (let [{:keys [out]} (babashka.process/shell
                           {:in "" :out :string :err :string :continue true}
                           "openssl" "s_client" "-alpn" offer "-connect" (str "127.0.0.1:" (:port h/*server*)))]
        (is (clojure.string/includes? out (str "ALPN protocol: " expected)) out)))))
