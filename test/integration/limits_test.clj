(ns integration.limits-test
  "Body size limits and bounded memory (the echo server allows 512MiB bodies)."
  (:require [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [integration.echo :as e]
            [integration.harness :as h]))

(use-fixtures :once (h/server-fixture e/argv))
(use-fixtures :each h/alive-fixture)

(deftest oversized-body-rejected
  (let [r (h/curl (h/url "/count") :proto :h1 :data ""
                  :args ["-H" "Content-Length: 600000000" "-H" "Expect:"] :timeout-ms 30000)]
    (is (= 413 (:status r)) (pr-str (dissoc r :body)))))

(deftest upload-memory-is-bounded
  ;; stream 400MB through a handler that drains without retaining: RSS must not follow
  (h/request "/count" :data "warm-up")
  (let [before (h/rss-mb h/*server*)]
    (doseq [flag ["--http1.1" "--http2"]]
      (testing flag
        (let [out (:out (p/shell {:out :string} "bash" "-c"
                                 (str "head -c 200000000 /dev/zero | curl -sk " flag " -T - " (h/url "/count"))))]
          (is (= "200000000" (first (str/split out #" ")))))))
    (let [growth (- (h/rss-mb h/*server*) before)]
      (is (< growth 150) (format "RSS grew %.0fMB for 400MB uploaded" growth)))))

(deftest download-memory-is-bounded-for-slow-readers
  ;; a writer producing 100MB to a client reading at 4MB/s must block, not buffer
  (let [before (h/rss-mb h/*server*)
        r      (h/curl (h/url "/stream?n=100&size=1000000") :args ["--limit-rate" "4M" "--max-time" "3"]
                       :timeout-ms 10000)
        growth (- (h/rss-mb h/*server*) before)]
    (is (= 28 (:exit r)))
    (is (< growth 50) (format "RSS grew %.0fMB" growth))
    (is (< (alength ^bytes (:body r)) 30000000))))
