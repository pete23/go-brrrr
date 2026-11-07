(ns integration.concurrency-test
  "Handlers run on worker threads (the echo server has 4)."
  (:require [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [integration.echo :as e]
            [integration.harness :as h]))

(use-fixtures :once (h/server-fixture e/argv))
(use-fixtures :each h/alive-fixture)

(deftest slow-handler-does-not-block-others
  (let [slow (future (h/request "/sleep?ms=2000"))]
    (Thread/sleep 200)
    (let [start (System/nanoTime)]
      (doseq [proto e/all-protos]
        (is (= 200 (:status (h/request "/echo" :proto proto)))))
      (is (< (h/elapsed-s start) 1.5)))
    @slow))

(deftest workers-run-in-parallel
  ;; 4 workers, 4 handlers sleeping 1s each: about 1s in total, not 4
  (let [start (System/nanoTime)]
    (run! deref (doall (repeatedly 4 #(future (h/request "/sleep?ms=1000")))))
    (is (< (h/elapsed-s start) 2.5))))

(defn- check-one [i]
  (let [proto (e/all-protos (mod i 3))
        body  (str "req-" i)]
    (if (= :h3 proto)
      (let [r (h/h3 (h/url (str "/echo?i=" i)))]
        (and (= 200 (:status r)) (= (str "i=" i) ((h/parse-echo r) "query-string"))))
      (let [r (h/curl (h/url (str "/echo?i=" i)) :proto proto :data body)
            m (h/parse-echo r)]
        (and (= 200 (:status r)) (= (str "i=" i) (m "query-string")) (= body (m "body")))))))

(deftest parallel-mixed-load
  ;; 150 requests across h1/h2/h3, 25 at a time
  (let [results (->> (range 150)
                     (partition 25)
                     (mapcat (fn [batch]
                               (->> batch
                                    (mapv (fn [i] (future (try (check-one i) (catch Exception ex ex)))))
                                    (mapv deref)))))]
    (is (empty? (filter #(instance? Exception %) results)))
    (is (= 150 (count (filter true? results))))))

(deftest h2-multiplexed-streams
  ;; 8 concurrent requests multiplexed over one HTTP/2 connection, on 4 workers
  (let [argv  (concat ["curl" "-sk" "--http2" "--parallel" "--parallel-max" "8"
                       "-w" "%{http_code} %{num_connects}\n"]
                      (mapcat (fn [i] ["-o" "/dev/null" (h/url (str "/sleep?ms=300&i=" i))]) (range 8)))
        start (System/nanoTime)
        out   (:out (apply p/shell {:out :string} argv))
        elapsed (h/elapsed-s start)
        lines (map #(str/split % #" ") (str/split-lines (str/trim out)))]
    (is (= (repeat 8 "200") (map first lines)))
    (is (<= (reduce + (map (comp parse-long second) lines)) 2) "requests should share a connection")
    (is (< elapsed 2.0))))
