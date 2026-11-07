(ns integration.runner
  "Entry points for the bb test tasks."
  (:require [clojure.test :as t]))

(def adapter-nses
  '[integration.request-map-test
    integration.body-test
    integration.response-test
    integration.error-test
    integration.streaming-test
    integration.concurrency-test
    integration.disconnect-test
    integration.limits-test])

(def app-nses
  '[integration.app-test])

(defn run!
  "Runs the given test namespaces (or `args`, if any, as namespace names) and
  fails the bb task if anything failed."
  [nses args]
  (let [nses (if (seq args) (map symbol args) nses)]
    (apply require nses)
    (let [{:keys [fail error]} (apply t/run-tests nses)]
      (when (pos? (+ fail error))
        (throw (ex-info (str fail " failures, " error " errors") {:babashka/exit 1}))))))
