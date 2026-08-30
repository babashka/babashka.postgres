(ns babashka.postgres-test
  (:require [babashka.postgres :as pg]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]])
  (:import [java.time LocalDate LocalDateTime LocalTime OffsetDateTime]))

(def conninfo
  (or (System/getenv "PG_TEST_CONNINFO")
      (System/getProperty "babashka.postgres.test.conninfo")))

(defn- table-name [prefix]
  (str prefix "_" (System/nanoTime)))

(deftest version-test
  (is (re-find #"^\d+\." (pg/version)))
  (pg/with-conn [db conninfo]
    (is (re-find #"^\d+\." (pg/server-version db)))))

(deftest query-test
  (pg/with-conn [db conninfo]
    (let [t (table-name "t")]
      (testing "ddl and insert report rows changed"
        (is (= {:rows-changed 0}
               (pg/execute! db (str "create temp table " t
                                    " (i int, r double precision, s text, b bytea)"))))
        (is (= {:rows-changed 2}
               (pg/execute! db [(str "insert into " t " values ($1, $2, $3, $4), ($5, $6, $7, $8)")
                                1 1.5 "a" (byte-array [1 2 3])
                                2 2.5 nil nil]))))
      (testing "values come back typed per column, NULL as nil"
        (let [[r1 r2] (pg/query db (str "select * from " t " order by i"))]
          (is (= [1 1.5 "a"] [(:i r1) (:r r1) (:s r1)]))
          (is (= [1 2 3] (vec (:b r1))))
          (is (= [2 2.5 nil nil] [(:i r2) (:r r2) (:s r2) (:b r2)])))))
    (testing "multibyte text round trip"
      (is (= [{:s "héllo wörld ✓"}]
             (pg/query db ["select $1::text s" "héllo wörld ✓"]))))
    (testing "the string form runs multiple statements and returns the last result"
      (is (= [{:v 2}] (pg/query db "select 1 v; select 2 v"))))
    (testing "a statement without rows returns an empty vector from query"
      (is (= [] (pg/query db "set application_name to 'bb'"))))
    (testing "a parameter can be used more than once"
      (is (= [{:a 7 :b 7}] (pg/query db ["select $1::int a, $1::int b" 7]))))
    (testing "bad sql throws with the server message and sqlstate"
      (let [e (try (pg/query db "select nope from nothing")
                   (catch Exception e e))]
        (is (re-find #"postgres: .*nothing" (ex-message e)))
        (is (= "42P01" (:sqlstate (ex-data e))))))
    (testing "a failing statement leaves the connection usable"
      (is (thrown-with-msg? Exception #"postgres:"
                            (pg/query db ["select nope from nothing where x = $1" 1])))
      (is (= [{:one 1}] (pg/query db "select 1 one"))))
    (testing "an unbindable value throws"
      (is (thrown-with-msg? Exception #"cannot bind"
                            (pg/query db ["select $1" :kw]))))))

(deftest types-test
  (pg/with-conn [db conninfo]
    (testing "integers of every width are longs"
      (is (= [{:a 1 :b 2 :c 3}]
             (pg/query db "select 1::smallint a, 2::int b, 3::bigint c"))))
    (testing "floats are doubles, numeric is BigDecimal"
      (is (= [{:a 1.5 :b 2.5 :c 3.25M}]
             (pg/query db "select 1.5::real a, 2.5::float8 b, 3.25::numeric c"))))
    (testing "special float values"
      (let [[{:keys [n i]}] (pg/query db "select 'NaN'::float8 n, 'Infinity'::float8 i")]
        (is (Double/isNaN n))
        (is (= ##Inf i))))
    (testing "booleans"
      (is (= [{:t true :f false}] (pg/query db "select true t, false f")))
      (is (= [{:v true}] (pg/query db ["select $1 v" true]))))
    (testing "uuid"
      (let [u (random-uuid)]
        (is (= [{:u u}] (pg/query db ["select $1::uuid u" u])))))
    (testing "bytea round trip, including an empty value"
      (is (= [1 2 3] (vec (:b (first (pg/query db ["select $1::bytea b" (byte-array [1 2 3])]))))))
      (is (= [] (vec (:b (first (pg/query db ["select $1::bytea b" (byte-array 0)])))))))
    (testing "json comes back as a string without :read-json"
      (is (= [{:j "{\"a\": 1}"}] (pg/query db "select '{\"a\": 1}'::jsonb j"))))
    (testing "a map parameter throws without :write-json"
      (is (thrown-with-msg? Exception #"write-json"
                            (pg/query db ["select $1::jsonb j" {:a 1}]))))
    (testing "a string inside json or jsonb needs no :write-json"
      (is (= [{:j "{\"a\": 1}"}] (pg/query db ["select $1 j" (pg/jsonb "{\"a\": 1}")])))
      (is (= [{:j "{\"a\":1}"}] (pg/query db ["select $1 j" (pg/json "{\"a\":1}")]))))
    (testing "numeric parameters"
      (is (= [{:v 12345678901234567890.5M}]
             (pg/query db ["select $1::numeric v" 12345678901234567890.5M])))
      (is (= [{:v 9007199254740993}]
             (pg/query db ["select $1::bigint v" 9007199254740993]))))
    (testing "date and time types map to java.time"
      (let [[{:keys [d t ts tstz]}]
            (pg/query db "select '2026-08-30'::date d,
                                 '14:41:43.5'::time t,
                                 '2026-08-30 14:41:43'::timestamp ts,
                                 '2026-08-30 14:41:43+02'::timestamptz tstz")]
        (is (= (LocalDate/parse "2026-08-30") d))
        (is (= (LocalTime/parse "14:41:43.5") t))
        (is (= (LocalDateTime/parse "2026-08-30T14:41:43") ts))
        (is (= (.toInstant (OffsetDateTime/parse "2026-08-30T14:41:43+02:00"))
               (.toInstant ^OffsetDateTime tstz)))))
    (testing "java.time parameters"
      (let [odt (OffsetDateTime/parse "2026-08-30T14:41:43+02:00")
            [{:keys [d ts tstz]}]
            (pg/query db ["select $1::date d, $2::timestamp ts, $3::timestamptz tstz"
                          (LocalDate/parse "2026-08-30")
                          (LocalDateTime/parse "2026-08-30T14:41:43")
                          odt])]
        (is (= (LocalDate/parse "2026-08-30") d))
        (is (= (LocalDateTime/parse "2026-08-30T14:41:43") ts))
        (is (= (.toInstant odt) (.toInstant ^OffsetDateTime tstz)))))
    (testing "time with time zone"
      (is (= [{:t (java.time.OffsetTime/parse "14:41:43+02:00")}]
             (pg/query db "select '14:41:43+02'::timetz t"))))
    (testing "an unparseable date stays a string"
      (is (= [{:d "infinity"}] (pg/query db "select 'infinity'::date d"))))
    (testing "a session with another DateStyle gets date and time columns as strings"
      (pg/execute! db "set datestyle to 'German, DMY'")
      (is (= [{:d "30.08.2026"}] (pg/query db "select '2026-08-30'::date d")))
      (pg/execute! db "set datestyle to ISO"))
    (testing "COPY throws and leaves the connection usable"
      (is (thrown-with-msg? Exception #"COPY is not supported"
                            (pg/query db "copy (select 1) to stdout")))
      (is (= [{:one 1}] (pg/query db "select 1 one")))
      (pg/execute! db "create temp table copy_in (i int)")
      (is (thrown-with-msg? Exception #"COPY is not supported"
                            (pg/execute! db "copy copy_in from stdin")))
      (is (= [{:one 1}] (pg/query db "select 1 one"))))))

(def json-opts
  {:read-json #(json/parse-string % true)
   :write-json json/generate-string})

(deftest json-test
  (pg/with-conn [db conninfo json-opts]
    (let [t (table-name "j")
          doc {:a 1 :nested {:b [1 2 3]} :s "x"}]
      (pg/execute! db (str "create temp table " t " (id int, j json, jb jsonb)"))
      (testing "maps and vectors bind as json text and json columns decode"
        (pg/execute! db [(str "insert into " t " values ($1, $2, $3)") 1 doc [1 "two" nil]])
        (is (= [{:id 1 :j doc :jb [1 "two" nil]}]
               (pg/query db (str "select * from " t)))))
      (testing "json and jsonb mark a parameter with its type"
        (is (= [{:v {:a 1}}] (pg/query db ["select $1 v" (pg/jsonb {:a 1})])))
        (is (= [{:v {:a 1}}] (pg/query db ["select $1 v" (pg/json {:a 1})])))
        (is (= [{:v 1}] (pg/query db ["select ($1->>'a')::int v" (pg/jsonb {:a 1})]))))
      (testing "per-call opts override the connection opts"
        (is (= [{:jb "[1, \"two\", null]"}]
               (pg/query db (str "select jb from " t) {:read-json nil}))))
      (testing "opts also apply to per-call connections"
        (is (= [{:v {:a 1}}]
               (pg/query conninfo ["select $1 v" (pg/jsonb {:a 1})] json-opts)))))))

(deftest transaction-test
  (pg/with-conn [db conninfo]
    (let [t (table-name "tx")]
      (pg/execute! db (str "create temp table " t " (id serial primary key, s text)"))
      (testing "returning gives the generated id through query"
        (is (= [{:id 1}] (pg/query db [(str "insert into " t " (s) values ($1) returning id") "a"]))))
      (testing "with-transaction commits and returns the body value"
        (is (= :done (pg/with-transaction db
                       (pg/execute! db [(str "insert into " t " (s) values ($1)") "b"])
                       :done)))
        (is (= [{:n 2}] (pg/query db (str "select count(*) n from " t)))))
      (testing "a throw rolls back everything since begin"
        (is (thrown-with-msg? Exception #"kaboom"
                              (pg/with-transaction db
                                (pg/execute! db [(str "insert into " t " (s) values ($1)") "c"])
                                (throw (ex-info "kaboom" {})))))
        (is (= [{:n 2}] (pg/query db (str "select count(*) n from " t)))))
      (testing "a failing rollback does not hide the body's exception"
        (pg/with-conn [db2 conninfo]
          (let [e (try (pg/with-transaction db2
                         (pg/execute! db2 "select pg_terminate_backend(pg_backend_pid())"))
                       (catch Exception e e))]
            (is (re-find #"terminat" (ex-message e)))
            (is (= 1 (count (.getSuppressed ^Throwable e)))))))
      (testing "a nested with-transaction joins the outer transaction"
        (pg/with-transaction db
          (pg/execute! db [(str "insert into " t " (s) values ($1)") "d"])
          (pg/with-transaction db
            (is (pg/in-transaction? db))
            (pg/execute! db [(str "insert into " t " (s) values ($1)") "e"]))
          (is (pg/in-transaction? db)))
        (is (not (pg/in-transaction? db)))
        (is (= [{:n 4}] (pg/query db (str "select count(*) n from " t))))))))

(deftest cancel-test
  (pg/with-conn [db conninfo]
    (testing "cancel! from another thread cancels a running statement"
      (let [fut (future
                  (try (pg/query db "select pg_sleep(10)")
                       :finished
                       (catch Exception e (ex-message e))))]
        (Thread/sleep 200)
        (pg/cancel! db)
        (is (re-find #"cancel" (str (deref fut 10000 :timeout))))))))

(deftest conninfo-test
  (testing "a conninfo value opens and closes a connection around the call"
    (is (= [{:answer 42}] (pg/query conninfo "select 42 answer"))))
  (testing "a map of libpq parameters connects"
    (let [db (pg/connect conninfo)
          m (try
              (first (pg/query db "select host(inet_server_addr()) host,
                                          inet_server_port() port,
                                          current_user \"user\",
                                          current_database() dbname"))
              (finally (pg/close! db)))]
      (is (= [{:one 1}] (pg/query m "select 1 one")))))
  (testing "connect sets DateStyle to ISO when the server default differs"
    (let [db (pg/connect conninfo)
          m (try (first (pg/query db "select host(inet_server_addr()) host, inet_server_port() port,
                                             current_user \"user\", current_database() dbname"))
                 (finally (pg/close! db)))]
      (pg/with-conn [db (assoc m :options "-c DateStyle=German,DMY")]
        (is (= [{:d (LocalDate/parse "2026-08-30")}]
               (pg/query db "select '2026-08-30'::date d"))))))
  (testing "a bad conninfo throws"
    (is (thrown-with-msg? Exception #"postgres:"
                          (pg/query {:host "127.0.0.1" :port 1 :dbname "x"} "select 1")))))

(defmacro with-table [[t ddl] & body]
  `(let [~t (table-name "c")]
     (pg/execute! conninfo (str "create table " ~t " " ~ddl))
     (try ~@body
          (finally (pg/execute! conninfo (str "drop table " ~t))))))

(defn- count-rows [t]
  (:n (first (pg/query conninfo (str "select count(*) n from " t)))))

(deftest concurrency-test
  (testing "concurrent per-call connections from a conninfo value"
    (with-table [t "(id int, val int)"]
      (let [n 20
            futures (mapv (fn [i]
                            (future (pg/execute! conninfo [(str "insert into " t " values ($1, $2)") i (* i 10)])))
                          (range n))]
        (run! deref futures)
        (is (= n (count-rows t))))))
  (testing "connections opened, used, and closed on different threads"
    (with-table [t "(id int, val int)"]
      (let [n 20
            conns (mapv deref (mapv (fn [_] (future (pg/connect conninfo))) (range n)))
            futures (mapv (fn [i]
                            (future (pg/execute! (nth conns i)
                                                 [(str "insert into " t " values ($1, $2)") i (* i 10)])))
                          (range n))]
        (run! deref futures)
        (is (= n (count-rows t)))
        (run! deref (mapv (fn [conn] (future (pg/close! conn))) conns)))))
  (testing "concurrent transactions commit independently"
    (with-table [t "(id int, val int)"]
      (let [n 10
            futures (mapv (fn [i]
                            (future
                              (pg/with-conn [db conninfo]
                                (pg/with-transaction db
                                  (pg/execute! db [(str "insert into " t " values ($1, $2)") i (* i 10)])))))
                          (range n))]
        (run! deref futures)
        (is (= n (count-rows t))))
      (testing "concurrent rollbacks leave no rows"
        (let [n 10
              before (count-rows t)
              futures (mapv (fn [i]
                              (future
                                (pg/with-conn [db conninfo]
                                  (try
                                    (pg/with-transaction db
                                      (pg/execute! db [(str "insert into " t " values ($1, 999)") (+ n i)])
                                      (throw (ex-info "rollback" {})))
                                    (catch clojure.lang.ExceptionInfo _ nil)))))
                            (range n))]
          (run! deref futures)
          (is (= before (count-rows t))))))))
