(ns babashka.postgres.dev
  "Starts a temporary PostgreSQL server for the tests."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]))

(defn- installed-bin-dirs [dir pattern]
  (when (fs/directory? dir)
    (->> (fs/list-dir dir pattern)
         (map #(fs/path % "bin"))
         (filter #(fs/exists? (fs/path % (if (fs/windows?) "pg_ctl.exe" "pg_ctl"))))
         (map str)
         sort
         reverse)))

(defn- pg-bin-dir []
  (or (some-> (fs/which "pg_ctl") fs/parent str)
      (some-> (System/getenv "PGBIN") not-empty)
      (first (concat (installed-bin-dirs "/opt/homebrew/opt" "postgresql*")
                     (installed-bin-dirs "/usr/local/opt" "postgresql*")
                     (installed-bin-dirs "/usr/lib/postgresql" "*")
                     (installed-bin-dirs "/Library/PostgreSQL" "*")
                     (installed-bin-dirs "C:\\Program Files\\PostgreSQL" "*")))
      (throw (ex-info "pg_ctl not found. Set PG_TEST_CONNINFO to use an existing server." {}))))

(defn- free-port []
  (with-open [s (java.net.ServerSocket. 0)]
    (.getLocalPort s)))

(defn with-temp-server
  "Starts a PostgreSQL server in a temporary directory and calls f with
  its conninfo string. Stops the server and deletes the directory after
  f returns."
  [f]
  (let [bin (pg-bin-dir)
        dir (fs/create-temp-dir {:prefix "babashka-postgres"})
        data (str (fs/path dir "data"))
        log (str (fs/path dir "postgres.log"))
        port (free-port)
        ;; the unix socket path has a length limit, so it does not go in the
        ;; temporary directory
        socket-opts (if (fs/windows?) "" " -k /tmp")
        pg-ctl (str (fs/path bin "pg_ctl"))
        stopped (atom false)
        stop! (fn []
                (when (compare-and-set! stopped false true)
                  (p/shell {:out :string :err :string :continue true}
                           pg-ctl "-D" data "-m" "fast" "-w" "stop")
                  (fs/delete-tree dir)))]
    (p/shell {:out log :err log}
             (str (fs/path bin "initdb")) "-D" data "-U" "postgres"
             "--auth=trust" "-E" "UTF8" "--no-locale")
    (p/shell {:out :inherit :err :inherit}
             pg-ctl "-D" data "-l" log "-w"
             "-o" (str "-p " port " -c listen_addresses=127.0.0.1" socket-opts)
             "start")
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable stop!))
    (try
      (f (str "host=127.0.0.1 port=" port " user=postgres dbname=postgres"))
      (finally (stop!)))))

(defn with-test-server
  "Calls f with the conninfo string from PG_TEST_CONNINFO. Without that
  variable, starts a temporary server and passes its conninfo."
  [f]
  (if-let [conninfo (System/getenv "PG_TEST_CONNINFO")]
    (f conninfo)
    (with-temp-server f)))
