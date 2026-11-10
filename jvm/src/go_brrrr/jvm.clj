(ns go-brrrr.jvm
  "src/go_brrrr/main.jank on the JVM, for comparison: the same handler and middleware,
  with upstream ring-core, served by Jetty 12 over TLS (HTTP/1.1 and HTTP/2)."
  (:require [ring.adapter.jetty9 :refer [run-jetty]]
            [ring.middleware.head :refer [wrap-head]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.util.response :as resp])
  (:import (java.lang.management ManagementFactory)
           (java.util.concurrent.atomic AtomicLong)))

(defn- text [body]
  (-> (resp/response body)
      (resp/content-type "text/plain")))

(defn handler [{:keys [request-method uri params]}]
  (case [request-method uri]
    [:get "/"]     (text (str (+ 20 3)))
    ([:get "/add"]
     [:post "/add"]) (let [a (some-> params :a parse-long)
                           b (some-> params :b parse-long)]
                       (if (and a b)
                         (text (str (+ a b)))
                         (resp/bad-request "need integer a and b")))
    (resp/not-found "not found")))

(def app
  (-> #'handler
      wrap-keyword-params
      wrap-params
      wrap-head))

;; The counterpart of ring.adapter.h2o's BRRRR_STATS endpoint, for bb bench. The JVM
;; counts every thread's allocation, so this includes Jetty's own (HTTP parsing, TLS),
;; where the jank figure leaves out h2o (which uses malloc, not the GC).
(def ^:private requests (AtomicLong.))

(defn- stats []
  (let [threads ^com.sun.management.ThreadMXBean (ManagementFactory/getThreadMXBean)
        rt      (Runtime/getRuntime)]
    (str "requests " (.get requests) "\n"
         "allocated_bytes " (.getTotalThreadAllocatedBytes threads) "\n"
         "gc_count " (reduce + (map #(max 0 (.getCollectionCount %))
                                    (ManagementFactory/getGarbageCollectorMXBeans))) "\n"
         "heap_bytes " (.totalMemory rt) "\n")))

(defn- wrap-stats [handler]
  (if (System/getenv "BRRRR_STATS")
    (fn [req]
      (if (= "/_brrrr/stats" (:uri req))
        (text (stats))
        (do (.incrementAndGet requests)
            (handler req))))
    handler))

(defn -main [& args]
  (let [port (or (some-> (first args) parse-long) 8443)]
    (run-jetty (wrap-stats app)
               {:http?         false
                :ssl-port      port
                :h2?           true
                :keystore      "../certs/keystore.p12"
                :keystore-type "PKCS12"
                :key-password  "go-brrrr"
                :join?         false})
    (println (str "go-brrrr-jvm: listening on https://0.0.0.0:" port " (h1, h2 over tcp)"))
    @(promise)))
