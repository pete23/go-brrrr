(ns integration.app-test
  "End to end tests of the go-brrrr app, as the release binary and under the JIT
  (`lein run`). APP_BIN / APP_JIT override the commands."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [integration.harness :as h]))

(defn- argv [env default]
  (if-let [cmd (System/getenv env)] (str/split cmd #"\s+") default))

(def aot-argv (argv "APP_BIN" [(str (fs/path h/root "target" "release" "go-brrrr"))]))
(def jit-argv (argv "APP_JIT" ["lein" "run" "--"]))

(def all-protos [:h1 :h2 :h3])

(defn- root-is-23 []
  (doseq [proto all-protos]
    (testing proto
      (let [r (h/request "/" :proto proto)]
        (is (= 200 (:status r)))
        (is (= "23" (h/text r)))
        (is (= "text/plain" (h/header r "content-type")))
        (is (= "2" (h/header r "content-length")))))))

(defn- head-root []
  (let [r (h/curl (h/url "/") :args ["-I"])]
    (is (= 200 (:status r)))
    (is (= "text/plain" (h/header r "content-type")))
    (is (zero? (get-in r [:info :size_download])))))

(defn- add-query-params []
  (doseq [proto all-protos]
    (testing proto
      (let [r (h/request "/add?a=20&b=3" :proto proto)]
        (is (= [200 "23"] [(:status r) (h/text r)]))))))

(defn- add-form-params []
  (doseq [proto all-protos]
    (testing proto
      (let [r (if (= :h3 proto)
                (h/request "/add" :proto :h3 :method "POST" :data "a=40&b=2"
                           :headers ["content-type: application/x-www-form-urlencoded"])
                (h/request "/add" :proto proto :data "a=40&b=2"))]
        (is (= [200 "42"] [(:status r) (h/text r)]))))))

(defn- add-negative-and-mixed-sources []
  (is (= "-2" (h/text (h/request "/add?a=-5" :data "b=3")))))

(defn- add-bad-params []
  (doseq [query ["a=x&b=1" "a=1" "" "a=1.5&b=1"]]
    (testing query
      (let [r (h/request (str "/add?" query))]
        (is (= [400 "need integer a and b"] [(:status r) (h/text r)]))))))

(defn- not-found []
  (doseq [path ["/nope" "/add/x" "/%20"]]
    (testing path
      (let [r (h/request path)]
        (is (= [404 "not found"] [(:status r) (h/text r)]))))))

(defn- root-ignores-query-string []
  (doseq [path ["/?" "/?x=1"]]
    (testing path
      (is (= "23" (h/text (h/request path)))))))

(defn- wrong-method []
  (is (= 404 (:status (h/curl (h/url "/") :args ["-X" "DELETE"])))))

(defn- check-app [argv]
  (h/with-server* argv
    (fn []
      (doseq [[name check] [["root is 23" root-is-23]
                            ["HEAD /" head-root]
                            ["add: query params" add-query-params]
                            ["add: form params" add-form-params]
                            ["add: negative, mixed sources" add-negative-and-mixed-sources]
                            ["add: bad params" add-bad-params]
                            ["not found" not-found]
                            ["root ignores query string" root-ignores-query-string]
                            ["wrong method" wrong-method]]]
        (testing name
          (check)
          (is (h/alive? h/*server*) "server died"))))))

(deftest release-binary
  (check-app aot-argv))

(deftest jit
  (check-app jit-argv))
