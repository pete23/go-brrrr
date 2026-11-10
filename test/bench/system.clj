(ns bench.system
  "What a benchmark ran on, for labelling results: CPU, frequency, CPU count, OS,
  virtualisation and tool versions. Linux and macOS."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(defn- sh
  "Trimmed stdout of a command, or nil if it fails or is missing."
  [& argv]
  (try
    (let [{:keys [exit out]} (apply p/shell {:out :string :err :string :continue true} argv)]
      (when (zero? exit) (not-empty (str/trim out))))
    (catch Exception _ nil)))

(defn- slurp-safe [path]
  (when (fs/exists? path)
    (try (str/trim (str/join "\n" (fs/read-all-lines path))) (catch Exception _ nil))))

(defn- cpuinfo-field [cpuinfo k]
  (some #(when (str/starts-with? % k) (str/trim (second (str/split % #":" 2))))
        (str/split-lines (or cpuinfo ""))))

(defn- linux []
  (let [cpuinfo (slurp-safe "/proc/cpuinfo")
        model   (or (cpuinfo-field cpuinfo "model name") (cpuinfo-field cpuinfo "Model"))
        lscpu   (or (sh "lscpu") "")
        lscpu-f (fn [k] (some #(when (str/starts-with? % k) (str/trim (second (str/split % #":" 2))))
                              (str/split-lines lscpu)))
        max-khz (some-> (slurp-safe "/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq") parse-long)
        os      (slurp-safe "/etc/os-release")
        pretty  (some->> os (re-find #"PRETTY_NAME=\"?([^\"\n]+)") second)]
    {:cpu-model       model
     :cpu-nominal-ghz (some->> model (re-find #"@\s*([\d.]+)\s*GHz") second parse-double)
     :cpu-max-mhz     (when max-khz (quot max-khz 1000))
     :cpu-current-mhz (some-> (cpuinfo-field cpuinfo "cpu MHz") parse-double)
     :cpu-freq-note   (when-not max-khz "no cpufreq: frequency scaling not exposed (typical of VMs)")
     :threads-per-core (some-> (lscpu-f "Thread(s) per core") parse-long)
     :hypervisor      (lscpu-f "Hypervisor vendor")
     :virtualization  (sh "systemd-detect-virt")
     :cgroup-cpu-max  (slurp-safe "/sys/fs/cgroup/cpu.max")
     :memory-gb       (some->> (slurp-safe "/proc/meminfo") (re-find #"MemTotal:\s+(\d+)") second parse-long
                               (#(/ % 1048576.0)) (format "%.1f") parse-double)
     :os              (str pretty " (Linux " (sh "uname" "-r") ")")}))

(defn- macos []
  (let [ctl   (fn [k] (sh "sysctl" "-n" k))
        hz    (some-> (ctl "hw.cpufrequency") parse-long)
        p     (some-> (ctl "hw.perflevel0.physicalcpu") parse-long)
        e     (some-> (ctl "hw.perflevel1.physicalcpu") parse-long)]
    {:cpu-model       (ctl "machdep.cpu.brand_string")
     :cpu-max-mhz     (when hz (quot hz 1000000))
     :cpu-freq-note   (when-not hz "not exposed by macOS on Apple silicon")
     :performance-cores p
     :efficiency-cores  e
     :virtualization  "none"
     :memory-gb       (some-> (ctl "hw.memsize") parse-long (/ 1073741824.0) (#(format "%.1f" %)) parse-double)
     :os              (str (sh "sw_vers" "-productName") " " (sh "sw_vers" "-productVersion")
                           " (" (sh "sw_vers" "-buildVersion") ", Darwin " (sh "uname" "-r") ")")}))

(defn info
  "System facts for the results file. :ncpus is what the JVM-free bb process can
  use (honours cgroup limits on Linux)."
  []
  (let [os-name (str/lower-case (System/getProperty "os.name"))]
    (merge {:hostname (sh "hostname")
            :arch     (sh "uname" "-m")
            :ncpus    (.availableProcessors (Runtime/getRuntime))}
           (cond (str/includes? os-name "mac")   (macos)
                 (str/includes? os-name "linux") (linux)
                 :else {:os os-name})
           {:h2load (sh "h2load" "--version")
            :jank   (try (let [{:keys [out err]} (p/shell {:out :string :err :string :continue true} "jank" "--help")]
                           (re-find #"jank-[\w.-]+" (str out err)))
                         (catch Exception _ nil))
            :git    {:sha   (sh "git" "rev-parse" "--short" "HEAD")
                     :dirty (boolean (sh "git" "status" "--porcelain" "--untracked-files=no"))}})))

(defn label
  "A one-line description for chart legends, e.g.
  \"Intel Xeon @ 2.10GHz, 4 CPUs, Ubuntu 24.04.5 LTS (KVM, docker)\"."
  [{:keys [cpu-model ncpus os hypervisor virtualization cpu-nominal-ghz cpu-max-mhz]}]
  (let [virt (->> [hypervisor (when-not (= "none" virtualization) virtualization)]
                  (remove nil?) distinct (str/join ", "))]
    (str cpu-model
         (when (and cpu-max-mhz (not (str/includes? (str cpu-model) "GHz")))
           (format " @ %.2fGHz" (/ cpu-max-mhz 1000.0)))
         ", " ncpus " CPUs, " (first (str/split (str os) #" \("))
         (when (seq virt) (str " (" virt ")")))))
