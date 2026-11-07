(defproject go-brrrr "0.1-SNAPSHOT"
  :description "A jank Ring app served over HTTP/1.1, HTTP/2 and HTTP/3 by h2o."
  :license {:name "MIT"
            :url "https://opensource.org/licenses/MIT"}
  :dependencies []
  :plugins [[org.jank-lang/lein-jank "2026.10-3"]]
  :middleware [leiningen.jank/middleware]
  ;; jank-build.bb builds h2o and the brrrr shim (native/) and links them in.
  :build-dependencies [[org.jank-lang.commons/jank-build "2026.10-1"]]
  :main go-brrrr.main
  ;; :static? picks how jank-build.bb links the native code. jank's JIT cannot
  ;; link h2o's thread-local-storage relocations from a static archive, so
  ;; anything that JITs (run, test, debug compiles) links libbrrrr.so; release
  ;; binaries are fully static.
  :profiles {:base {:jank {:target-dir "target/debug"
                           :optimization-level 0
                           :static? false}}
             :release {:jank {:target-dir "target/release"
                              :optimization-level 3
                              :static? true}}})
