(ns integration.body-test
  "Request and response bodies: exact, binary, large, chunked and streamed."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [integration.echo :as e]
            [integration.harness :as h]))

(use-fixtures :once (h/server-fixture e/argv))
(use-fixtures :each h/alive-fixture)

(defn- all-byte-values
  "Every byte value, `times` over."
  [times]
  (byte-array (take (* 256 times) (cycle (range -128 128)))))

(def ^:private byte-ramp
  (byte-array (map unchecked-byte (range 256))))

(deftest echo-body-exact
  (let [body (apply str (repeat 10 "héllo wörld\n"))]
    (doseq [proto e/all-protos]
      (testing proto
        (is (= body ((h/parse-echo (h/request "/echo" :proto proto :data body)) "body")))))))

(deftest binary-round-trip
  (let [body (all-byte-values 400)]
    (doseq [proto e/tcp]
      (testing proto
        (let [r (h/curl (h/url "/mirror") :proto proto :data body)]
          (is (= 200 (:status r)))
          (is (h/bytes= body (:body r))))))
    (testing :h3
      (is (h/bytes= body (:body (h/h3 (h/url "/mirror") :method "POST" :data body)))))))

(deftest binary-response
  (doseq [proto e/all-protos]
    (testing proto
      (is (h/bytes= byte-ramp (:body (h/request "/binary" :proto proto)))))))

(deftest large-body-mirror
  (let [body (let [b (byte-array (* 8 1024 1024))] (.nextBytes (java.util.Random. 42) b) b)]
    (doseq [proto e/tcp]
      (testing proto
        (let [r (h/curl (h/url "/mirror") :proto proto :data body)]
          (is (= (alength body) (alength ^bytes (:body r))))
          (is (h/bytes= body (:body r))))))))

(deftest chunked-upload-without-content-length
  (let [n (+ (* 3 1024 1024) 17)
        r (h/curl (h/url "/count") :proto :h1 :args ["-T" "-" "-H" "Transfer-Encoding: chunked"]
                  :stdin (byte-array n (byte 120)))]
    (is (= 200 (:status r)) (pr-str (dissoc r :body)))
    (is (= (str n) (first (str/split (h/text r) #" "))))))

(deftest h3-streamed-upload
  ;; 40 chunks of 1000 bytes, 10ms apart: arrives as many separate chunks
  (let [r (h/h3 (h/url "/count") :method "POST" :body-size 40000 :chunk 1000 :interval 10)
        [size chunks] (str/split (h/text r) #" ")]
    (is (= 200 (:status r)))
    (is (= "40000" size))
    (is (< 1 (parse-long chunks)) "body should arrive in more than one chunk")))

(deftest request-body-streams-to-handler
  ;; one byte every 50ms, mirrored back as it arrives
  (is (= (apply str (repeat 20 "a"))
         (h/text (h/h3 (h/url "/mirror") :method "POST" :body-size 20 :chunk 1 :interval 50)))))
