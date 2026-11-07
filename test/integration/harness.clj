(ns integration.harness
  "Shared helpers for the integration tests (run with Babashka): start a server on
  a free port, and drive it with curl (HTTP/1.x, HTTP/2) and h2o-httpclient
  (HTTP/3)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [is]])
  (:import [java.io ByteArrayInputStream]
           [java.net DatagramSocket InetAddress ServerSocket]
           [java.util Arrays]))

(def root
  (str (fs/canonicalize (fs/path (fs/parent *file*) ".." ".."))))

(defn- find-h3client []
  (or (System/getenv "H3CLIENT")
      (some-> (fs/glob root "target/*/_cache/go-brrrr-out-*/bin/h2o-httpclient") first str)
      (throw (ex-info "h2o-httpclient not found; build first (bb build:echo)" {}))))

(def h3client (delay (find-h3client)))

;; ---- servers ----

(def ^:dynamic *server* nil)

(defn free-port
  "A port free for both TCP and UDP (the server binds both)."
  []
  (loop [attempts 50]
    (when (zero? attempts)
      (throw (ex-info "no free port" {})))
    (let [port (with-open [s (ServerSocket. 0 1 (InetAddress/getByName "127.0.0.1"))]
                 (.getLocalPort s))
          udp-ok (try (with-open [_ (DatagramSocket. port (InetAddress/getByName "127.0.0.1"))] true)
                      (catch Exception _ false))]
      (if udp-ok port (recur (dec attempts))))))

(defn log-text [server]
  (slurp (:log server)))

(defn alive? [server]
  (.isAlive ^Process (:proc (:process server))))

(defn start!
  "Starts `argv + [port]` and waits for it to log that it is listening."
  [argv & {:keys [timeout-ms] :or {timeout-ms 300000}}]
  (let [port    (free-port)
        log     (str (fs/create-temp-file {:prefix "server-" :suffix ".log"}))
        proc    (p/process (conj (vec argv) (str port)) {:dir root :out (fs/file log) :err :out})
        server  {:port port :base (str "https://127.0.0.1:" port) :log log :process proc}
        started (System/currentTimeMillis)]
    (loop []
      (cond
        (not (alive? server))
        (throw (ex-info (str "server exited:\n" (log-text server)) {}))

        (str/includes? (log-text server) "listening on")
        server

        (> (- (System/currentTimeMillis) started) timeout-ms)
        (do (p/destroy-tree proc)
            (throw (ex-info (str "server did not start:\n" (log-text server)) {})))

        :else (do (Thread/sleep 100) (recur))))))

(defn stop! [server]
  (when (alive? server)
    (p/destroy-tree (:process server))
    (deref (:process server) 10000 nil)))

(defn url [path]
  (str (:base *server*) path))

(defn rss-mb [server]
  ;; fs/read-all-lines rather than slurp: /proc files report a zero length
  (let [line (->> (fs/read-all-lines (str "/proc/" (.pid ^Process (:proc (:process server))) "/status"))
                  (filter #(str/starts-with? % "VmRSS:"))
                  first)]
    (/ (parse-long (second (str/split (str/trim line) #"\s+"))) 1024.0)))

(defn with-server*
  "Runs `f` with *server* bound to a fresh server started from `argv`."
  [argv f]
  (binding [*server* (start! argv)]
    (try (f) (finally (stop! *server*)))))

(defn server-fixture
  "A :once fixture: one server per test namespace."
  [argv]
  (fn [f] (with-server* argv f)))

(defn alive-fixture
  "An :each fixture: fails the test if the server died during it."
  [f]
  (f)
  (is (alive? *server*)
      (str "server died during the test:\n"
           (let [t (log-text *server*)] (subs t (max 0 (- (count t) 4000)))))))

;; ---- responses ----

(defn header-all [resp name]
  (vec (keep (fn [[k v]] (when (= k name) v)) (:headers resp))))

(defn header [resp name]
  (first (header-all resp name)))

(defn text [resp]
  (String. ^bytes (:body resp) "UTF-8"))

(defn bytes= [^bytes a ^bytes b]
  (Arrays/equals a b))

(defn ->bytes ^bytes [x]
  (if (string? x) (.getBytes ^String x "UTF-8") x))

(defn- parse-headers
  "The last header block in curl's -D output (skipping 100 Continue etc)."
  [raw]
  (let [blocks (->> (str/split (str/replace raw "\r\n" "\n") #"\n\n")
                    (remove str/blank?))]
    (if (empty? blocks)
      [nil []]
      (let [[status-line & lines] (str/split-lines (last blocks))]
        [(parse-long (second (str/split status-line #"\s+")))
         (vec (for [line lines
                    :let [i (str/index-of line ":")]
                    :when i]
                [(str/lower-case (str/trim (subs line 0 i))) (str/trim (subs line (inc i)))]))]))))

(def ^:private proto-flags
  {:h1 "--http1.1" :h1.0 "--http1.0" :h2 "--http2"})

(defn curl
  "Runs curl. `:proto` is :h1, :h1.0 or :h2; `:data` (bytes or string) is POSTed;
  `:stdin` (bytes) is fed to curl's stdin, for use with \"-T\" \"-\"."
  [target & {:keys [proto args data stdin timeout-ms] :or {proto :h2 timeout-ms 60000}}]
  (fs/with-temp-dir [d {}]
    (let [hdr  (str (fs/path d "h"))
          out  (str (fs/path d "b"))
          body (when data
                 (let [f (str (fs/path d "req"))]
                   (fs/write-bytes f (->bytes data))
                   f))
          argv (concat ["curl" "-sk" (proto-flags proto) "-D" hdr "-o" out "-w" "%{json}"]
                       args
                       (when body ["--data-binary" (str "@" body)])
                       [target])
          proc (p/process argv {:in (when stdin (ByteArrayInputStream. stdin)) :out :string :err :string})
          res  (deref proc timeout-ms ::timeout)]
      (when (= ::timeout res)
        (p/destroy-tree proc)
        (throw (ex-info "curl timed out" {:argv argv})))
      (let [[status headers] (if (fs/exists? hdr) (parse-headers (slurp hdr)) [nil []])]
        {:status  status
         :headers headers
         :body    (if (fs/exists? out) (fs/read-all-bytes out) (byte-array 0))
         :exit    (:exit res)
         :info    (try (json/parse-string (:out res) true) (catch Exception _ {}))
         :stderr  (:err res)}))))

(defn h3
  "Runs h2o-httpclient over HTTP/3; it prints the status line and headers to
  stderr. `:data` is sent through a pipe (its --input uses epoll, which cannot
  watch regular files)."
  [target & {:keys [method body-size chunk interval headers data timeout-ms]
             :or {method "GET" timeout-ms 60000}}]
  (let [argv (concat [@h3client "-3" "100" "-k" "-m" method]
                     (when body-size ["-b" (str body-size)])
                     (when chunk ["-c" (str chunk)])
                     (when interval ["-i" (str interval)])
                     (when data ["--input" "/dev/stdin"])
                     (mapcat (fn [h] ["-H" h]) headers)
                     [target])
        proc (p/process argv {:in (if data (ByteArrayInputStream. (->bytes data)) "")
                              :out :bytes :err :string})
        res  (deref proc timeout-ms ::timeout)]
    (when (= ::timeout res)
      (p/destroy-tree proc)
      (throw (ex-info "h2o-httpclient timed out" {:argv argv})))
    (let [[status headers]
          (reduce (fn [[status hs] line]
                    (cond
                      (str/starts-with? line "HTTP/") [(parse-long (second (str/split line #"\s+"))) []]
                      (and status (str/includes? line ": "))
                      (let [[k v] (str/split line #": " 2)] [status (conj hs [(str/lower-case k) v])])
                      :else [status hs]))
                  [nil []]
                  (str/split-lines (:err res)))]
      {:status status :headers headers :body (:out res) :exit (:exit res) :stderr (:err res)})))

(defn request
  "GET (or POST with :data) `path` over `proto` (:h1, :h1.0, :h2 or :h3)."
  [path & {:keys [proto] :or {proto :h2} :as opts}]
  (if (= :h3 proto)
    (apply h3 (url path) (mapcat identity (dissoc opts :proto)))
    (apply curl (url path) (mapcat identity opts))))

(defn parse-echo
  "test/server/echo.jank's /echo: key=value lines, with the body last."
  [resp]
  (let [t (text resp)
        i (str/index-of t "\nbody=")
        head (if i (subs t 0 i) t)]
    (into {"body" (if i (subs t (+ i 6)) "")}
          (for [line (str/split-lines head)
                :let [[k v] (str/split line #"=" 2)]]
            [k (or v "")]))))

(defn elapsed-s [start-nanos]
  (/ (- (System/nanoTime) start-nanos) 1e9))
