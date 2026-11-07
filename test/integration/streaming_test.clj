(ns integration.streaming-test
  "Responses are delivered as they are produced."
  (:require [babashka.process :as p]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [integration.echo :as e]
            [integration.harness :as h]))

(use-fixtures :once (h/server-fixture e/argv))
(use-fixtures :each h/alive-fixture)

(deftest response-is-delivered-incrementally
  ;; with 3 chunks 600ms apart, the first byte arrives long before the last
  (doseq [path ["/stream?n=3&delay=600" "/lazy?n=3&delay=600"]
          proto e/tcp]
    (testing [path proto]
      (let [r (h/curl (h/url path) :proto proto)
            {:keys [time_starttransfer time_total]} (:info r)]
        (is (= "000000111111222222" (h/text r)))
        (is (< time_starttransfer 0.5) (pr-str (:info r)))
        (is (> time_total 1.1) (pr-str (:info r)))))))

(deftest h3-response-is-delivered-incrementally
  (let [start (System/nanoTime)
        proc  (p/process [@h/h3client "-3" "100" "-k" (h/url "/stream?n=3&delay=600")] {:err :discard})
        out   ^java.io.InputStream (:out proc)
        first-bytes (.readNBytes out 6)
        first-at (h/elapsed-s start)
        rest-bytes (.readAllBytes out)
        total (h/elapsed-s start)]
    @proc
    (is (= "000000111111222222" (str (String. first-bytes) (String. rest-bytes))))
    (is (< first-at 0.5))
    (is (> total 1.1))))

(deftest large-streamed-response
  (let [r (h/curl (h/url "/stream?n=200&size=100000"))
        ^bytes body (:body r)
        n 100000]
    (is (= (* 200 n) (alength body)))
    (is (= (apply str (repeat n "0")) (String. body 0 n)))
    (is (= (apply str (repeat n "9")) (String. body (- (alength body) n) n)))))
