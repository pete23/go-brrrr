(ns bench.load
  "Fixed-rate load test of the release binary with h2load (nghttp2):

    bb bench [--rate 100] [--duration 60] [--clients 10] [--path /] [--protocols h1,h2,h3]

  Runs each protocol in turn for `duration` seconds at `rate` requests/second in
  total, spread over `clients` connections. Fails if any request fails, any
  response is not 2xx, the achieved rate falls more than 5% short, or the server
  dies. HTTP/3 needs an h2load built with QUIC support (it is skipped otherwise)."
  (:require [babashka.cli :as cli]
            [babashka.process :as p]
            [clojure.string :as str]
            [integration.harness :as h]))

(def spec
  {:rate      {:default 100 :coerce :long :desc "total requests per second"}
   :duration  {:default 60 :coerce :long :desc "seconds per protocol"}
   :clients   {:default 10 :coerce :long :desc "concurrent connections"}
   :path      {:default "/" :desc "request path"}
   :protocols {:default "h1,h2,h3" :desc "comma-separated subset of h1,h2,h3"}
   :binary    {:default "target/release/go-brrrr" :desc "server to start (port is appended)"}})

(defn h3-supported?
  "Whether this h2load speaks HTTP/3: builds without QUIC still accept
  --alpn-list=h3 (and document QUIC options), but then try it over TLS/TCP and
  fail. So probe with one real request."
  [server]
  (let [{:keys [out]} (p/shell {:out :string :err :string :continue true}
                               "h2load" "--alpn-list=h3" "-n" "1" (str (:base server) "/"))]
    (str/includes? out "1 succeeded")))

(def proto-flags
  {"h1" ["--h1"]
   "h2" ["--alpn-list=h2"]
   "h3" ["--alpn-list=h3"]})

(defn- num [s] (parse-double (str/replace s #"[^0-9.]" "")))

(defn- duration->ms
  "h2load prints latencies as e.g. 306us, 5.23ms or 1.2s."
  [s]
  (let [v (num s)]
    (cond (str/ends-with? s "us") (/ v 1000)
          (str/ends-with? s "ms") v
          (str/ends-with? s "s")  (* v 1000)
          :else v)))

(defn parse
  "The figures we check from h2load's summary."
  [out]
  (let [line   (fn [prefix] (some #(when (str/starts-with? (str/trim %) prefix) (str/trim %)) (str/split-lines out)))
        counts (fn [l k] (some-> (re-find (re-pattern (str "(\\d+) " k)) (or l "")) second parse-long))
        req    (line "requests:")
        codes  (line "status codes:")
        ;; "time for request:  306us  5.23ms  896us  472us  92.10%"
        [mn mx mean sd] (some-> (line "time for request:") (str/split #"\s+") (->> (drop 3)))]
    {:rps       (some->> (line "finished in") (re-find #"([\d.]+) req/s") second parse-double)
     :total     (counts req "total")
     :succeeded (counts req "succeeded")
     :failed    (counts req "failed")
     :errored   (counts req "errored")
     :timeout   (counts req "timeout")
     :status-2xx (counts codes "2xx")
     :latency-ms (when mean {:min (duration->ms mn) :max (duration->ms mx)
                             :mean (duration->ms mean) :sd (duration->ms sd)})}))

(defn run-one [server proto {:keys [rate duration clients path]}]
  (let [per-client (max 1 (quot rate clients))
        argv (concat ["h2load"] (proto-flags proto)
                     ["-c" (str clients) "--rps" (str per-client) "-D" (str duration)
                      (str (:base server) path)])
        {:keys [out err exit]} (apply p/shell {:out :string :err :string :continue true} argv)
        result (assoc (parse out) :protocol proto :target-rps (* per-client clients) :exit exit)]
    (when-not (zero? exit)
      (println err))
    result))

(defn problems [{:keys [exit total succeeded failed errored timeout status-2xx rps target-rps]}]
  (cond-> []
    (not (zero? exit))                       (conj (str "h2load exited " exit))
    (nil? total)                             (conj "no summary from h2load")
    (and total (not= total succeeded))       (conj (str (- total succeeded) " requests did not succeed"))
    (some pos? (keep identity [failed errored timeout])) (conj (str "failed/errored/timeout: " failed "/" errored "/" timeout))
    (and total (not= total status-2xx))      (conj (str (- total (or status-2xx 0)) " non-2xx responses"))
    (and rps (< rps (* 0.95 target-rps)))    (conj (format "achieved %.1f req/s, target %d" rps target-rps))))

(defn report [{:keys [protocol rps target-rps total succeeded latency-ms]}]
  (let [{:keys [min mean max sd]} latency-ms]
    (println (format "%-3s %8.2f req/s (target %d)  %d/%d ok  latency ms min %.2f mean %.2f max %.2f sd %.2f"
                     protocol (or rps 0.0) target-rps (or succeeded 0) (or total 0)
                     (or min 0.0) (or mean 0.0) (or max 0.0) (or sd 0.0)))))

(defn -main [& args]
  (let [opts (cli/parse-opts args {:spec spec})]
    (h/with-server* [(:binary opts)]
      (fn []
        (let [requested (str/split (:protocols opts) #",")
              h3?       (and (some #{"h3"} requested) (h3-supported? h/*server*))
              protocols (cond->> requested (not h3?) (remove #{"h3"}))
              _         (when (and (some #{"h3"} requested) (not h3?))
                          (println "skipping h3: this h2load was built without QUIC support"))
              results (doall
                       (for [proto protocols]
                         (do (println (format "%s: %ds at %d req/s over %d clients..."
                                              proto (:duration opts) (:rate opts) (:clients opts)))
                             (let [r (run-one h/*server* proto opts)]
                               (report r)
                               r))))
              failures (for [r results, prob (problems r)] (str (:protocol r) ": " prob))
              failures (cond-> (vec failures)
                         (not (h/alive? h/*server*)) (conj "server died"))]
          (when (seq failures)
            (run! println failures)
            (throw (ex-info "benchmark failed" {:babashka/exit 1}))))))))
