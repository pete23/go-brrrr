(ns bench.histogram
  "Latency histograms for bb bench: log-spaced buckets, drawn as static SVG into a
  self-contained HTML page (styles and hover script: latency.html next to this
  file). One panel per protocol on shared axes, with percentile markers, and a
  percentile table."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def buckets-per-decade 20)

(defn buckets
  "Counts of sorted latencies (microseconds) in log-spaced buckets, as
  [{:lo us :hi us :count n} ...] for the non-empty ones."
  [xs]
  (let [index #(long (Math/floor (* buckets-per-decade (Math/log10 (max 1 %)))))
        edge  #(Math/pow 10 (/ (double %) buckets-per-decade))]
    (->> xs (map index) frequencies (sort-by key)
         (mapv (fn [[i n]] {:lo (edge i) :hi (edge (inc i)) :count n})))))

(def ^:private levels [[:p50 "p50"] [:p90 "p90"] [:p95 "p95"] [:p99 "p99"] [:p999 "p99.9"]])
(def ^:private series {"h1" "var(--series-1)" "h2" "var(--series-2)" "h3" "var(--series-3)"})
(def ^:private names {"h1" "HTTP/1.1" "h2" "HTTP/2" "h3" "HTTP/3"})

(defn- esc [s] (str/escape (str s) {\< "&lt;" \> "&gt;" \& "&amp;" \" "&quot;"}))

(defn- fmt-ms [us]
  (let [v (/ us 1000.0)]
    (cond (>= v 100) (format "%.0f" v)
          (>= v 10)  (format "%.1f" v)
          (>= v 1)   (format "%.2f" v)
          :else      (format "%.3f" v))))

(defn- fmt-pct [v]
  (cond (>= v 10)  (format "%.1f" v)
        (>= v 0.1) (format "%.2f" v)
        :else      (format "%.2g" v)))

(defn- fmt-int [n] (format "%,d" (long n)))

(defn- fmt-tick [us]
  (let [v (/ us 1000.0)]
    (if (>= v 1) (format "%.0f" v) (str/replace (format "%.3f" v) #"0+$" ""))))

(defn- fmt-count [n]
  (cond (>= n 1e6) (str (long (/ n 1e6)) "M") (>= n 1e3) (str (long (/ n 1e3)) "k") :else (str (long n))))

(def ^:private W 860)
(def ^:private H 280)
(def ^:private M {:l 52 :r 16 :t 96 :b 40})

(defn- panel [{:keys [protocol total percentiles histogram]} {:keys [lo-us hi-us y-max x-ticks]}]
  (let [pw    (- W (:l M) (:r M))
        ph    (- H (:t M) (:b M))
        x0    (Math/log10 lo-us)
        x1    (Math/log10 hi-us)
        sx    #(+ (:l M) (* pw (/ (- (Math/log10 %) x0) (- x1 x0))))
        ;; log count axis, so the tail's few requests show; floor at 0.5 so a lone request has height
        sy    #(+ (:t M) (* ph (- 1 (/ (Math/log10 (/ (max % 0.5) 0.5)) (Math/log10 (/ y-max 0.5))))))
        color (series protocol "var(--series-1)")
        f     #(format "%.1f" (double %))
        base  (sy 0)
        grid  (cons (str "<line x1='" (:l M) "' x2='" (- W (:r M)) "' y1='" (f base) "' y2='" (f base)
                         "' stroke='var(--axis)'/><text x='" (:l M) "' y='" (- (:t M) 10)
                         "' text-anchor='end' class='axis-title'>requests</text>")
                    (for [v (take-while #(<= % y-max) (iterate #(* 10 %) 1))]
                      (str "<line x1='" (:l M) "' x2='" (- W (:r M)) "' y1='" (f (sy v)) "' y2='" (f (sy v))
                           "' stroke='var(--grid)'/><text x='" (- (:l M) 8) "' y='" (f (+ (sy v) 4))
                           "' text-anchor='end' class='tick'>" (fmt-count v) "</text>")))
        xaxis (for [us x-ticks]
                (str "<line x1='" (f (sx us)) "' x2='" (f (sx us)) "' y1='" (f base) "' y2='" (f (+ base 4))
                     "' stroke='var(--axis)'/><text x='" (f (sx us)) "' y='" (f (+ base 18))
                     "' text-anchor='middle' class='tick'>" (fmt-tick us) "</text>"))
        ;; bars: 2px surface gap between neighbours, 4px rounded tops on the baseline;
        ;; each has a full-height hit target carrying its tooltip
        bars  (->> histogram
                   (reductions (fn [[cum] b] (let [s (/ (* 100.0 (:count b)) total)] [(+ cum s) s b])) [0.0])
                   rest
                   (map (fn [[cum share {:keys [lo hi count]}]]
                          (let [xa (+ (sx lo) 1) w (max 1.0 (- (sx hi) 1 xa))
                                yt (sy count) h (- base yt) r (min 4.0 (/ w 2) h)
                                tip (str "<b>" (fmt-ms lo) "–" (fmt-ms hi) " ms</b><br>" (fmt-int count)
                                         " requests (" (fmt-pct share) "%)<br><span class='muted'>"
                                         (fmt-pct cum) "% at or below</span>")]
                            (str (if (> h 0.5)
                                   (str "<path fill='" color "' d='M" (f xa) "," (f base) " V" (f (+ yt r))
                                        " Q" (f xa) "," (f yt) " " (f (+ xa r)) "," (f yt)
                                        " H" (f (- (+ xa w) r)) " Q" (f (+ xa w)) "," (f yt) " "
                                        (f (+ xa w)) "," (f (+ yt r)) " V" (f base) " Z'/>")
                                   (str "<rect x='" (f xa) "' y='" (f (- base 1)) "' width='" (f w)
                                        "' height='1' fill='" color "'/>"))
                                 "<rect class='hit' x='" (f (sx lo)) "' y='" (:t M) "' width='"
                                 (f (max 2.0 (- (sx hi) (sx lo)))) "' height='" ph "' data-tip=\"" (esc tip) "\"/>")))))
        ;; percentile markers, labels on their own rows so they never collide
        marks (map-indexed
               (fn [i [k label]]
                 (let [xv (sx (max lo-us (percentiles k))) row (+ 16 (* i 16))
                       right? (> xv (- W (:r M) 130))]
                   (str "<line x1='" (f xv) "' x2='" (f xv) "' y1='" (+ row 4) "' y2='" (f base)
                        "' stroke='var(--text-muted)' stroke-dasharray='3 3'/>"
                        "<text x='" (f (if right? (- xv 5) (+ xv 5))) "' y='" row "' text-anchor='"
                        (if right? "end" "start") "' class='pct-label'>" label " " (fmt-ms (percentiles k)) " ms</text>")))
               levels)]
    (str "<section class='panel'><h2><span class='key' style='background:" color "'></span>"
         (names protocol protocol) "</h2><p class='meta'>" (fmt-int total) " requests · p50 "
         (fmt-ms (:p50 percentiles)) " ms · p99.9 " (fmt-ms (:p999 percentiles)) " ms · max "
         (fmt-ms (:max percentiles)) " ms</p>"
         "<svg viewBox='0 0 " W " " H "' role='img' aria-label='Latency histogram for " (names protocol protocol) "'>"
         (str/join grid) (str/join xaxis) (str/join bars) (str/join marks)
         "<text x='" (+ (:l M) (/ pw 2)) "' y='" (- H 4) "' text-anchor='middle' class='axis-title'>"
         "latency (ms, log scale)</text></svg></section>")))

(defn- table [runs]
  (str "<table><caption>Percentiles (ms)</caption><thead><tr><th>Protocol</th><th>Requests</th>"
       (str/join (for [[_ l] levels] (str "<th>" l "</th>"))) "<th>max</th></tr></thead><tbody>"
       (str/join (for [{:keys [protocol total percentiles]} runs]
                   (str "<tr><td>" (names protocol protocol) "</td><td>" (fmt-int total) "</td>"
                        (str/join (for [[k _] levels] (str "<td>" (fmt-ms (percentiles k)) "</td>")))
                        "<td>" (fmt-ms (:max percentiles)) "</td></tr>")))
       "</tbody></table>"))

(defn- run-view
  "A bench.load result as this page needs it: totals, percentiles in microseconds."
  [{:keys [protocol latency]}]
  (let [pct (into {} (map (juxt :p :ms) (:percentiles latency)))
        us  #(some-> (pct %) (* 1000.0))]
    {:protocol    protocol
     :total       (:count latency)
     :histogram   (:histogram latency)
     :percentiles {:p50 (us 50) :p90 (us 90) :p95 (us 95) :p99 (us 99) :p999 (us 99.9) :max (us 100)}}))

(defn html
  "The histogram page for bench.load results."
  [{:keys [rate duration clients path label uncapped streams warmup]} results]
  (let [runs   (map run-view (filter (comp seq :histogram :latency) results))
        lo-us  (apply min (map (comp :lo first :histogram) runs))
        hi-us  (apply max (map (comp :hi last :histogram) runs))
        mx     (apply max (for [r runs b (:histogram r)] (:count b)))
        scales {:lo-us lo-us :hi-us hi-us
                :y-max (Math/pow 10 (Math/ceil (Math/log10 (max 10 mx))))
                :x-ticks (for [e (range -1 8) m [1 2 5] :let [us (* m (Math/pow 10 e))]
                               :when (<= lo-us us hi-us)] us)}
        sub    (str (esc label) ": GET " (esc path)
                    (if uncapped
                      (str " uncapped, " clients " connections × " streams " streams")
                      (str " at " (fmt-int rate) " req/s over " clients " connections"))
                    ", " duration "s per protocol"
                    (when (pos? (or warmup 0)) (str " after " warmup "s warm-up")) " ("
                    (.format (java.time.LocalDateTime/now) (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm"))
                    ")")]
    (-> (slurp (io/resource "bench/latency.html"))
        (str/replace "__SUBTITLE__" sub)
        (str/replace "__PANELS__" (str/join (map #(panel % scales) runs)))
        (str/replace "__TABLE__" (table runs)))))

(defn write!
  "Writes the histogram page for these bench results to `file`; returns its path."
  [file opts results]
  (spit file (html opts results))
  file)
