(ns integration.echo
  "Shared bits for the adapter tests, which run against test/server/echo.jank."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [is]]
            [integration.harness :as h]))

(def argv
  (if-let [cmd (System/getenv "ECHO_BIN")]
    (str/split cmd #"\s+")
    [(str (fs/path h/root "target" "debug" "echo-server"))]))

(def tcp [:h1 :h2])
(def all-protos [:h1 :h2 :h3])

(defn echo
  "Requests /echo-style `path` and returns the parsed echo, asserting a 200."
  [path & opts]
  (let [r (apply h/request path opts)]
    (is (= 200 (:status r)) (pr-str (dissoc r :body)))
    (h/parse-echo r)))
