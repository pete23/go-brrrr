;; Builds h2o (vendor/h2o) and the brrrr C shim (native/), installs them into the
;; jank-build out dir and emits the flags jank needs. Run by lein-jank before
;; run/test/compile; see https://book.jank-lang.org/jank-build/overview.html
;;
;; Outputs (under the out dir):
;;   include/brrrr.h
;;   lib/libh2o-evloop.a, lib/libbrrrr.a  static, for release binaries
;;   lib/libbrrrr.so (.dylib on macOS)    shim + h2o, for anything that JITs
;;   bin/h2o-httpclient                   HTTP/3-capable client used by the tests
(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell]]
         '[jank.build.util :as util])

(defn- openssl-prefix
  "OpenSSL's install prefix, when it is not on the default search paths. macOS has
  no usable system OpenSSL and Homebrew's openssl@3 is keg-only, so it has to be
  found explicitly. OPENSSL_ROOT_DIR overrides."
  []
  (or (System/getenv "OPENSSL_ROOT_DIR")
      (when (util/mac?)
        (or (some #(when (fs/exists? (fs/path % "include" "openssl" "ssl.h")) (str %))
                  ["/opt/homebrew/opt/openssl@3" "/usr/local/opt/openssl@3"])
            (throw (ex-info "OpenSSL 3 not found: brew install openssl@3, or set OPENSSL_ROOT_DIR" {}))))))

(let [{:keys [src-dir build-dir out-dir optimization-level static?]} *input*
      ssl       (openssl-prefix)
      h2o-src   (str (fs/path src-dir "vendor" "h2o"))
      h2o-build (str (fs/path build-dir "h2o"))
      lib       (fs/path out-dir "lib")
      include   (fs/path out-dir "include")
      bin       (fs/path out-dir "bin")
      opt       (if (pos? optimization-level) "-O2" "-O0")]
  (when-not (fs/exists? (fs/path h2o-src "CMakeLists.txt"))
    (throw (ex-info "vendor/h2o is empty: run `git submodule update --init vendor/h2o`" {})))
  (fs/create-dirs lib)
  (fs/create-dirs include)
  (fs/create-dirs bin)

  ;; h2o: only the evloop library and the test client, without the optional extras.
  (apply shell "cmake" "-S" h2o-src "-B" h2o-build "-G" "Ninja"
         (str "-DCMAKE_BUILD_TYPE=" (if (pos? optimization-level) "Release" "Debug"))
         "-DCMAKE_POSITION_INDEPENDENT_CODE=ON"
         "-DWITH_MRUBY=OFF" "-DWITH_BROTLI=OFF" "-DWITH_ZSTD=OFF" "-DWITH_DTRACE=OFF"
         "-DWITH_IO_URING=OFF" "-DWITH_AEGIS=OFF" "-DWITH_CCACHE=OFF"
         (when ssl [(str "-DOPENSSL_ROOT_DIR=" ssl)]))
  (shell "ninja" "-C" h2o-build "libh2o-evloop" "h2o-httpclient")
  (fs/copy (fs/path h2o-build "libh2o-evloop.a") lib {:replace-existing true})
  (fs/copy (fs/path h2o-build "h2o-httpclient") bin {:replace-existing true})

  ;; The shim. Its defines must match libh2o-evloop's, or struct layouts drift.
  (let [obj (str (fs/path build-dir "brrrr.o"))]
    (apply shell "clang" opt "-g" "-fPIC" "-std=c11" "-pthread" "-Wall" "-Wextra"
           "-Wno-unused-parameter" "-Wno-missing-field-initializers"
           "-D_GNU_SOURCE" "-DH2O_USE_LIBUV=0" "-DQUICLY_USE_TRACER=1"
           (str "-I" (fs/path h2o-src "include"))
           (str "-I" (fs/path h2o-src "deps" "picotls" "include"))
           (str "-I" (fs/path h2o-src "deps" "quicly" "include"))
           (concat (when ssl [(str "-I" (fs/path ssl "include"))])
                   ["-c" (str (fs/path src-dir "native" "brrrr.c")) "-o" obj]))
    (shell "ar" "rcs" (str (fs/path lib "libbrrrr.a")) obj)
    ;; The shared lib records its own absolute path (SONAME on Linux, install name
    ;; on macOS), so binaries linked against it find it without an rpath.
    (let [so     (str (fs/path lib (if (util/mac?) "libbrrrr.dylib" "libbrrrr.so")))
          shared (if (util/mac?)
                   ["-dynamiclib" (str "-Wl,-install_name," so)]
                   ["-shared" (str "-Wl,-soname," so)])]
      (apply shell "clang" (concat shared ["-o" so obj (str (fs/path lib "libh2o-evloop.a"))]
                                   (when ssl [(str "-L" (fs/path ssl "lib"))])
                                   ["-lssl" "-lcrypto" "-lz" "-lm" "-pthread"]))))
  (fs/copy (fs/path src-dir "native" "brrrr.h") include {:replace-existing true})

  (util/include-dir "include")
  (util/link-dir "lib")
  (when ssl
    (util/include-dir (str (fs/path ssl "include")))
    (util/link-dir (str (fs/path ssl "lib"))))
  (if static?
    (do (util/link-static-library "libbrrrr.a")
        (util/link-static-library "libh2o-evloop.a"))
    (util/link-library "brrrr"))
  (doseq [l ["ssl" "crypto" "z"]]
    (util/link-library l))

  (util/rerun-if-changed "jank-build.bb")
  (util/rerun-if-changed "native")
  (util/rerun-if-changed "vendor/h2o")
  (util/rerun-if-env-changed "OPENSSL_ROOT_DIR"))
