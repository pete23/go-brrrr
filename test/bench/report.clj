(ns bench.report
  "Charts every results file in bench-results/ into bench-results/report.html:

    bb bench:report [--dir bench-results]

  Keeps the newest file for each run label, mode and path, so rerunning a
  benchmark replaces its old result. Files with \"reported\": true are summaries
  quoted from elsewhere (no per-request data) and are drawn as such."
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [integration.harness :as h]))

(defn- run-key [d]
  (if (:reported d)
    [(:label d) :reported]
    [(:label d) (get-in d [:run :mode]) (get-in d [:run :path])]))

(defn load-results [dir]
  (->> (fs/glob dir "*.json")
       (map #(json/parse-string (slurp (str %)) true))
       (group-by run-key)
       vals
       (map #(last (sort-by (fn [d] (str (get-in d [:run :timestamp]))) %)))
       ;; measured first, oldest commit label first, so series colours are stable
       (sort-by (juxt #(if (:reported %) 1 0) #(str (get-in % [:run :timestamp]))))
       vec))

(defn -main [& args]
  (let [{:keys [dir]} (cli/parse-opts args {:spec {:dir {:default "bench-results"}}})
        results  (load-results dir)
        template (slurp (str (fs/path h/root "test" "bench" "report-template.html")))
        ;; keep "</script>" inside the data from closing the tag
        payload  (str/replace (json/generate-string results) "</" "<\\/")
        out      (str (fs/path dir "report.html"))]
    (spit out (str/replace template "__BENCH_DATA__" payload))
    (println "wrote" out "from" (count results) "results")))
