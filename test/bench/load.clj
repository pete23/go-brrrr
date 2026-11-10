(ns bench.load
  "Fixed-rate load test of the release binary with h2load (nghttp2):

    bb bench [--rate 100] [--duration 60] [--clients 10] [--path /] [--protocols h1,h2,h3]
             [--uncapped [--streams 1]] [--label name] [--out bench-results]

  Runs each protocol in turn for `duration` seconds at `rate` requests/second in
  total, spread over `clients` connections. Fails if any request fails, any
  response is not 2xx, the achieved rate falls more than 5% short, or the server
  dies. Also reports bytes allocated per request (from the server's GC, via
  BRRRR_STATS), failing over --max-bytes-per-request if given. HTTP/3 needs an
  h2load built with QUIC support (it is skipped otherwise).

  --uncapped drops the rate limit to find the ceiling: each client keeps
  --streams requests in flight for the whole duration.

  Every run writes <out>/<label>-<mode>-<timestamp>.json: the system it ran on
  (see bench.system), the options, and per protocol the summary, latency
  percentiles and a per-second series, computed from h2load's per-request log so
  they mean the same thing whatever the h2load version. `bb bench:report` charts
  every results file it finds."
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [bench.system :as system]
            [cheshire.core :as json]
            [clojure.string :as str]
            [integration.harness :as h])
  (:import [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]))

(def spec
  {:rate      {:default 100 :coerce :long :desc "total requests per second"}
   :duration  {:default 60 :coerce :long :desc "seconds per protocol"}
   :clients   {:default 10 :coerce :long :desc "concurrent connections"}
   :path      {:default "/" :desc "request path"}
   :protocols {:default "h1,h2,h3" :desc "comma-separated subset of h1,h2,h3"}
   :binary    {:default "target/release/go-brrrr" :desc "server to start (port is appended)"}
   :max-bytes-per-request {:coerce :long :desc "fail if the server allocates more than this per request"}
   :uncapped  {:coerce :boolean :desc "no rate limit: find the ceiling"}
   :streams   {:default 1 :coerce :long :desc "with --uncapped: requests in flight per client (h2load -m)"}
   :label     {:desc "name for this run in results and charts (default: OS and git sha)"}
   :out       {:default "bench-results" :desc "directory for the results JSON"}})

;; ---- latency from h2load's per-request log ----

(def percentiles
  "Points for percentile curves: dense in the tail."
  [0 10 25 50 75 90 95 97.5 99 99.5 99.9 99.95 99.99 99.999 100])

(defn- read-log
  "h2load --log-file rows: start (us since epoch), status, latency (us)."
  [path]
  (with-open [r (clojure.java.io/reader path)]
    (->> (line-seq r)
         (keep (fn [line]
                 (let [[start status latency] (str/split line #"\t")]
                   (when latency [(parse-long start) (parse-long status) (parse-long latency)]))))
         vec)))

(defn- percentile-of
  "Nearest-rank percentile of a sorted vector."
  [sorted p]
  (let [n (count sorted)]
    (when (pos? n)
      (nth sorted (min (dec n) (max 0 (dec (long (Math/ceil (* (/ p 100.0) n))))))))))

(defn- sorted-latencies [rows]
  (vec (sort (map #(nth % 2) rows))))

(defn latency-summary
  "Percentiles (ms) over all requests, and a per-second series of p50/p99/max."
  [rows]
  (let [all (sorted-latencies rows)
        t0  (some->> (seq rows) (map first) (apply min))
        ms  #(some-> % (/ 1000.0))]
    {:count       (count all)
     :percentiles (into [] (for [p percentiles] {:p p :ms (ms (percentile-of all p))}))
     :mean-ms     (when (seq all) (ms (/ (reduce + all) (count all))))
     :per-second  (->> rows
                       (group-by #(quot (- (first %) t0) 1000000))
                       (sort-by key)
                       (mapv (fn [[sec rs]]
                               (let [a (sorted-latencies rs)]
                                 {:t sec :count (count a)
                                  :p50-ms (ms (percentile-of a 50)) :p99-ms (ms (percentile-of a 99))
                                  :max-ms (ms (percentile-of a 100))}))))}))

(defn stats
  "The server's request count and GC totals (BRRRR_STATS endpoint)."
  [server]
  (let [{:keys [out]} (p/shell {:out :string :err :string :continue true}
                               "curl" "-sk" (str (:base server) "/_brrrr/stats"))]
    (into {} (for [[_ k v] (re-seq #"(\w+) (\d+)" out)] [(keyword k) (parse-long v)]))))

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
        ;; Read columns by the header above them, which differs between versions:
        ;;   "        min  max  mean  sd  +/- sd"  /  "time for request:  306us  5.23ms  896us  472us  92.10%"
        ;;   "        min  max  median  p95  p99  mean  sd  +/- sd"  /  "request     :  178us  990us  ...  (1.67+)"
        lines  (mapv str/trim (str/split-lines out))
        row    (some (fn [[hdr l]] (when (re-find #"^(time for request|request\s*):" l) [hdr l]))
                     (partition 2 1 lines))
        stats  (when-let [[hdr l] row]
                 (zipmap (str/split hdr #"\s+") (str/split (str/trim (subs l (inc (str/index-of l ":")))) #"\s+")))
        {mn "min" mx "max" mean "mean" sd "sd"} stats]
    {:rps       (some->> (line "finished in") (re-find #"([\d.]+) req/s") second parse-double)
     :total     (counts req "total")
     :succeeded (counts req "succeeded")
     :failed    (counts req "failed")
     :errored   (counts req "errored")
     :timeout   (counts req "timeout")
     :status-2xx (counts codes "2xx")
     :latency-ms (when mean {:min (duration->ms mn) :max (duration->ms mx)
                             :mean (duration->ms mean) :sd (duration->ms sd)})}))

(defn run-one [server proto {:keys [rate duration clients path uncapped streams]}]
  (fs/with-temp-dir [d {}]
    (let [per-client (max 1 (quot rate clients))
          log  (str (fs/path d "requests.tsv"))
          argv (concat ["h2load"] (proto-flags proto)
                       ["-c" (str clients) "-D" (str duration) (str "--log-file=" log)]
                       (if uncapped ["-m" (str streams)] ["--rps" (str per-client)])
                       [(str (:base server) path)])
          before (stats server)
          {:keys [out err exit]} (apply p/shell {:out :string :err :string :continue true} argv)
          after  (stats server)
          reqs   (- (:requests after 0) (:requests before 0))
          result (assoc (parse out) :protocol proto :exit exit
                        :target-rps (when-not uncapped (* per-client clients))
                        :latency (when (fs/exists? log) (latency-summary (read-log log)))
                        :bytes-per-request (when (pos? reqs)
                                             (quot (- (:allocated_bytes after) (:allocated_bytes before)) reqs))
                        :gcs (some-> (:gc_count after) (- (:gc_count before))))]
      (when-not (zero? exit)
        (println err))
      result)))

(defn problems [{:keys [exit total succeeded failed errored timeout status-2xx rps target-rps
                        bytes-per-request max-bytes-per-request]}]
  (cond-> []
    (not (zero? exit))                       (conj (str "h2load exited " exit))
    (nil? total)                             (conj "no summary from h2load")
    (and total (not= total succeeded))       (conj (str (- total succeeded) " requests did not succeed"))
    (some pos? (keep identity [failed errored timeout])) (conj (str "failed/errored/timeout: " failed "/" errored "/" timeout))
    (and total (not= total status-2xx))      (conj (str (- total (or status-2xx 0)) " non-2xx responses"))
    (and rps target-rps (< rps (* 0.95 target-rps))) (conj (format "achieved %.1f req/s, target %d" rps target-rps))
    (and max-bytes-per-request (nil? bytes-per-request)) (conj "no allocation stats from the server")
    (and max-bytes-per-request bytes-per-request (> bytes-per-request max-bytes-per-request))
    (conj (format "allocated %d bytes/request, budget %d" bytes-per-request max-bytes-per-request))))

(defn report [{:keys [protocol rps target-rps total succeeded latency bytes-per-request gcs]}]
  (let [pct (into {} (map (juxt :p :ms) (:percentiles latency)))]
    (println (format "%-3s %9.2f req/s (target %s)  %d/%d ok  latency ms p50 %.2f p99 %.2f p99.9 %.2f max %.2f mean %.2f  alloc %s/req, %s GCs"
                     protocol (or rps 0.0) (or target-rps "none") (or succeeded 0) (or total 0)
                     (or (pct 50) 0.0) (or (pct 99) 0.0) (or (pct 99.9) 0.0) (or (pct 100) 0.0)
                     (or (:mean-ms latency) 0.0)
                     (if bytes-per-request (format "%.1fKB" (/ bytes-per-request 1024.0)) "?")
                     (or gcs "?")))))

(defn- write-results! [opts sysinfo results]
  (let [stamp (.format (LocalDateTime/now) (DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss"))
        label (or (:label opts)
                  (str (if (str/includes? (str (:os sysinfo)) "macOS") "macos" "linux") "-" (get-in sysinfo [:git :sha])))
        mode  (if (:uncapped opts) "uncapped" (str (:rate opts) "rps"))
        file  (str (fs/path (:out opts) (str label "-" mode "-" stamp ".json")))]
    (fs/create-dirs (:out opts))
    (spit file (json/generate-string
                {:label label
                 :system (assoc sysinfo :description (system/label sysinfo))
                 :run {:timestamp stamp :mode mode :path (:path opts) :duration-s (:duration opts)
                       :clients (:clients opts) :rate (when-not (:uncapped opts) (:rate opts))
                       :streams (when (:uncapped opts) (:streams opts)) :binary (:binary opts)
                       :load-generator "h2load on the same host, 1 thread"}
                 :results (mapv #(dissoc % :max-bytes-per-request) results)}
                {:pretty true}))
    (println "wrote" file)))

(defn -main [& args]
  (let [opts (cli/parse-opts args {:spec spec})]
    (h/with-server* [(:binary opts)] {:env {"BRRRR_STATS" "1"}}
      (fn []
        (let [requested (str/split (:protocols opts) #",")
              h3?       (and (some #{"h3"} requested) (h3-supported? h/*server*))
              protocols (cond->> requested (not h3?) (remove #{"h3"}))
              _         (when (and (some #{"h3"} requested) (not h3?))
                          (println "skipping h3: this h2load was built without QUIC support"))
              results (doall
                       (for [proto protocols]
                         (do (println (if (:uncapped opts)
                                        (format "%s: %ds uncapped, %d clients x %d streams..."
                                                proto (:duration opts) (:clients opts) (:streams opts))
                                        (format "%s: %ds at %d req/s over %d clients..."
                                                proto (:duration opts) (:rate opts) (:clients opts))))
                             (let [r (assoc (run-one h/*server* proto opts)
                                            :max-bytes-per-request (:max-bytes-per-request opts))]
                               (report r)
                               r))))
              failures (for [r results, prob (problems r)] (str (:protocol r) ": " prob))
              failures (cond-> (vec failures)
                         (not (h/alive? h/*server*)) (conj "server died"))
              _        (write-results! opts (system/info) results)]
          (when (seq failures)
            (run! println failures)
            (throw (ex-info "benchmark failed" {:babashka/exit 1}))))))))
