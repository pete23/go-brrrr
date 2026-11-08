(ns integration.disconnect-test
  "Clients going away mid-request must not hurt the server or leak workers."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [integration.echo :as e]
            [integration.harness :as h]))

(use-fixtures :once (h/server-fixture e/argv))
(use-fixtures :each h/alive-fixture)

(deftest client-disconnects-mid-response
  (doseq [proto e/tcp
          path ["/stream?n=1000&size=1000000" "/lazy?n=1000&size=1000000"]]
    (testing [proto path]
      (let [r (h/curl (h/url path) :proto proto :args ["--limit-rate" "2M" "--max-time" "1"])]
        (is (= 28 (:exit r)) "timed out, i.e. we hung up"))))
  (is (= 200 (:status (h/request "/echo")))))

(deftest client-disconnects-mid-upload
  (let [proc (p/process ["curl" "-sk" "--http1.1" "-T" "-" "-H" "Transfer-Encoding: chunked" (h/url "/count")]
                        {:out :discard :err :discard})
        in   ^java.io.OutputStream (:in proc)]
    (.write in (byte-array 100000 (byte 120)))
    (.flush in)
    (Thread/sleep 300)
    (p/destroy-tree proc)
    (deref proc 5000 nil))
  (is (= 200 (:status (h/request "/echo")))))

(deftest workers-are-released-after-disconnects
  ;; hang up on 4 streams that would each keep a worker busy; all 4 must come back
  (->> (range 4)
       (mapv (fn [_] (p/process ["curl" "-sk" "--http2" "-o" "/dev/null" "--max-time" "1"
                                 (h/url "/stream?n=100000&size=100000")])))
       (run! deref))
  (let [start (System/nanoTime)]
    (run! deref (doall (repeatedly 4 #(future (h/request "/sleep?ms=500")))))
    (is (< (h/elapsed-s start) 2.0) "workers should be free again")))

(deftest unread-body-keeps-h1-connection-usable
  ;; the handler ignores a 5MB body; the next request on the same connection must work
  (fs/with-temp-dir [d {}]
    (let [body (str (fs/path d "unread"))
          _    (fs/write-bytes body (byte-array 5000000 (byte 122)))
          out  (:out (p/shell {:out :string}
                              "curl" "-sk" "--http1.1" "--data-binary" (str "@" body) "-o" "/dev/null"
                              "-w" "%{http_code} %{num_connects}\n" (h/url "/status?code=201")
                              "--next" "-sk" "--http1.1" "-o" "/dev/null"
                              "-w" "%{http_code} %{num_connects}\n" (h/url "/echo")))]
      (is (= ["201" "1" "200" "0"] (str/split (str/trim out) #"\s+"))
          "second request reuses the connection"))))
