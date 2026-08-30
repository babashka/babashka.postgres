(ns babashka.postgres
  "Use PostgreSQL from babashka through babashka.ffi and libpq. Pass SQL as
  a string or as a [sql & params] vector with $1, $2, ... placeholders:

      (require '[babashka.postgres :as pg])
      (pg/query \"postgresql://app@localhost/app\"
                [\"select * from users where name = $1\" \"rich\"])

  A connection string or a map opens and closes a connection for each
  call. nil connects with the libpq defaults and PG* environment variables.
  Keep one connection open for multiple operations:

      (pg/with-conn [db {:host \"localhost\" :dbname \"app\"}]
        (pg/execute! db \"create table t (i int, s text)\")
        (pg/query db \"select * from t\"))

  query returns a vector of row maps with keyword column names. Result
  values use longs, doubles, BigDecimals, booleans, strings, byte arrays,
  UUIDs, java.time values, and nil, chosen by the column type. The
  :read-json and :write-json options plug in a JSON library."
  (:require [babashka.ffi :as ffi :refer [defcfn]]
            [babashka.fs :as fs]
            [clojure.string :as str])
  (:import [java.time LocalDate LocalDateTime LocalTime OffsetDateTime OffsetTime]
           [java.time.format DateTimeFormatter DateTimeFormatterBuilder]))

(defn- installed-candidates
  "Returns the libpq files under the directories in dir that match pattern,
  newest version first."
  [dir pattern suffix]
  (when (fs/directory? dir)
    (->> (fs/list-dir dir pattern)
         (map #(fs/path % suffix))
         (filter fs/exists?)
         (map str)
         sort
         reverse)))

(defn- mac-candidates []
  (vec
   (concat ["libpq.5.dylib"
           "/opt/homebrew/opt/libpq/lib/libpq.5.dylib"
           "/usr/local/opt/libpq/lib/libpq.5.dylib"]
          (installed-candidates "/opt/homebrew/opt" "postgresql*" "lib/postgresql/libpq.5.dylib")
          (installed-candidates "/usr/local/opt" "postgresql*" "lib/postgresql/libpq.5.dylib")
          (installed-candidates "/Applications/Postgres.app/Contents/Versions" "*" "lib/libpq.5.dylib")
          (installed-candidates "/Library/PostgreSQL" "*" "lib/libpq.5.dylib"))))

(try
  (ffi/load-library {:mac (mac-candidates)
                     :linux "libpq.so.5"
                     ;; on the PATH next to the postgres binaries
                     :windows "libpq.dll"})
  (catch Exception e
    (throw (ex-info (str "postgres: libpq not found. Install it with brew install libpq "
                         "(macOS), apt install libpq5 (Debian, Ubuntu), dnf install libpq "
                         "(Fedora), or put the bin directory of a PostgreSQL installation "
                         "on the PATH (Windows).")
                    {} e))))

(defcfn ^:private c-connectdb "PQconnectdb" [:string] :pointer)
(defcfn ^:private c-status "PQstatus" [:pointer] :int)
(defcfn ^:private c-error-message "PQerrorMessage" [:pointer] :string)
(defcfn ^:private c-finish "PQfinish" [:pointer] :void)
(defcfn ^:private c-server-version "PQserverVersion" [:pointer] :int)
(defcfn ^:private c-transaction-status "PQtransactionStatus" [:pointer] :int)
(defcfn ^:private c-parameter-status "PQparameterStatus" [:pointer :string] :string)
(defcfn ^:private c-host "PQhost" [:pointer] :string)
(defcfn ^:private c-port "PQport" [:pointer] :string)
(defcfn ^:private c-user "PQuser" [:pointer] :string)
(defcfn ^:private c-pass "PQpass" [:pointer] :string)
(defcfn ^:private c-db "PQdb" [:pointer] :string)
(defcfn ^:private c-get-result "PQgetResult" [:pointer] :pointer)
(defcfn ^:private c-put-copy-end "PQputCopyEnd" [:pointer :string] :int)
(defcfn ^:private c-get-copy-data "PQgetCopyData" [:pointer :pointer :int] :int)
(defcfn ^:private c-exec "PQexec" [:pointer :string] :pointer)
(defcfn ^:private c-exec-params "PQexecParams"
  [:pointer :string :int :pointer :pointer :pointer :pointer :int] :pointer)
(defcfn ^:private c-result-status "PQresultStatus" [:pointer] :int)
(defcfn ^:private c-result-error-message "PQresultErrorMessage" [:pointer] :string)
(defcfn ^:private c-result-error-field "PQresultErrorField" [:pointer :int] :string)
(defcfn ^:private c-clear "PQclear" [:pointer] :void)
(defcfn ^:private c-ntuples "PQntuples" [:pointer] :int)
(defcfn ^:private c-nfields "PQnfields" [:pointer] :int)
(defcfn ^:private c-fname "PQfname" [:pointer :int] :string)
(defcfn ^:private c-ftype "PQftype" [:pointer :int] :uint)
(defcfn ^:private c-getvalue "PQgetvalue" [:pointer :int :int] :string)
(defcfn ^:private c-getvalue-ptr "PQgetvalue" [:pointer :int :int] :pointer)
(defcfn ^:private c-getisnull "PQgetisnull" [:pointer :int :int] :int)
(defcfn ^:private c-cmd-tuples "PQcmdTuples" [:pointer] :string)
(defcfn ^:private c-unescape-bytea "PQunescapeBytea" [:pointer :pointer] :pointer)
(defcfn ^:private c-freemem "PQfreemem" [:pointer] :void)
(defcfn ^:private c-get-cancel "PQgetCancel" [:pointer] :pointer)
(defcfn ^:private c-cancel "PQcancel" [:pointer :pointer :int] :int)
(defcfn ^:private c-free-cancel "PQfreeCancel" [:pointer] :void)

(defcfn ^:private c-lib-version "PQlibVersion" [] :int)

(defn- version-string [v]
  (if (< v 100000)
    (str (quot v 10000) "." (quot (rem v 10000) 100) "." (rem v 100))
    (str (quot v 10000) "." (rem v 10000))))

(defn version
  "Returns the libpq version as a string."
  []
  (version-string (c-lib-version)))

(def ^:private CONNECTION-OK 0)

(defn- quote-value [v]
  (str "'" (str/replace (str v) #"['\\]" "\\\\$0") "'"))

(defn- conninfo-string [conninfo]
  (cond
    (nil? conninfo) ""
    (string? conninfo) conninfo
    (map? conninfo) (str/join " " (for [[k v] conninfo :when (some? v)]
                                    (str (name k) "=" (quote-value v))))
    :else (throw (ex-info (str "postgres: cannot connect with " (type conninfo))
                          {:conninfo conninfo}))))

(defn- iso-datestyle? [conn]
  (some-> (c-parameter-status conn "DateStyle") (str/starts-with? "ISO")))

(defrecord Connection [conn opts])

(defn connect
  "Opens a PostgreSQL connection. conninfo is a libpq connection string, a
  URI, a map of libpq parameters, or nil:

      (connect \"postgresql://user:secret@localhost:5432/app\")
      (connect \"host=localhost dbname=app\")
      (connect {:host \"localhost\" :dbname \"app\" :user \"app\"})
      (connect nil)

  Map keys are libpq parameter names such as :host, :port, :dbname, :user,
  :password, and :sslmode. nil and missing parameters use the libpq
  defaults and the PG* environment variables.

  opts sets the defaults for query and execute! on this connection:

  :read-json  A function from a JSON string to a value. query applies it
              to json and jsonb columns. Without it, these columns return
              strings.
  :write-json A function from a value to a JSON string. It encodes map
              and vector parameters, and the values in json and jsonb.
              Without it, map and vector parameters throw.

  Use the connection from one thread at a time. Returns a connection for
  use with query, execute!, with-transaction, cancel!, and close!."
  ([conninfo] (connect conninfo nil))
  ([conninfo opts]
   (let [conn (c-connectdb (conninfo-string conninfo))]
     (when (ffi/null? conn)
       (throw (ex-info "postgres: out of memory" {:conninfo conninfo})))
     (when-not (= CONNECTION-OK (c-status conn))
       (let [msg (str/trim (c-error-message conn))]
         (c-finish conn)
         (throw (ex-info (str "postgres: " msg) {:conninfo conninfo}))))
     (when-not (iso-datestyle? conn)
       (c-clear (c-exec conn "set datestyle to ISO")))
     (->Connection conn opts))))

(defn close!
  "Closes a connection from connect. Returns nil."
  [{:keys [conn]}]
  (c-finish conn)
  nil)

(defmacro with-conn
  "Opens a connection for the enclosed code. Closes the connection after
  the code finishes. Returns the result of the enclosed code. conninfo and
  opts accept the same values as in connect.

      (with-conn [db \"postgresql://localhost/app\"]
        (query db \"select * from users\"))"
  [[sym conninfo & [opts]] & body]
  `(let [~sym (connect ~conninfo ~opts)]
     (try ~@body
          (finally (close! ~sym)))))

(defn- connection-params
  "Returns the libpq parameters of an open connection as a map."
  [{:keys [conn]}]
  (into {}
        (filter (fn [[_ v]] (not (str/blank? v))))
        {:host (c-host conn) :port (c-port conn) :user (c-user conn)
         :password (c-pass conn) :dbname (c-db conn)}))

(defn server-version
  "Returns the server version of the connection as a string."
  [{:keys [conn]}]
  (version-string (c-server-version conn)))

(def ^:private PGRES-EMPTY-QUERY 0)
(def ^:private PGRES-COMMAND-OK 1)
(def ^:private PGRES-TUPLES-OK 2)
(def ^:private PGRES-COPY-OUT 3)
(def ^:private PGRES-COPY-IN 4)
(def ^:private PGRES-COPY-BOTH 8)
(def ^:private PG-DIAG-SQLSTATE (int \C))

(defn- drain-results! [conn]
  (loop []
    (let [res (c-get-result conn)]
      (when-not (ffi/null? res)
        (c-clear res)
        (recur)))))

(defn- abort-copy!
  "Takes the connection out of COPY mode so that it stays usable."
  [conn status]
  (if (= PGRES-COPY-OUT status)
    (with-open [arena (ffi/confined-arena)]
      (let [pbuf (ffi/alloc arena :pointer)]
        (loop []
          (when (pos? (c-get-copy-data conn pbuf 0))
            (c-freemem (ffi/read pbuf :pointer))
            (recur)))))
    (c-put-copy-end conn "COPY is not supported"))
  (drain-results! conn))

(defn- result-error [res sql]
  (ex-info (str "postgres: " (str/trim (c-result-error-message res)))
           {:sql sql :sqlstate (c-result-error-field res PG-DIAG-SQLSTATE)}))

(defn- check-result! [conn res sql]
  (when (ffi/null? res)
    (throw (ex-info (str "postgres: " (str/trim (c-error-message conn))) {:sql sql})))
  (let [status (c-result-status res)]
    (when (or (= PGRES-COPY-OUT status) (= PGRES-COPY-IN status) (= PGRES-COPY-BOTH status))
      (abort-copy! conn status)
      (throw (ex-info "postgres: COPY is not supported" {:sql sql})))
    (when-not (or (= PGRES-COMMAND-OK status)
                  (= PGRES-TUPLES-OK status)
                  (= PGRES-EMPTY-QUERY status))
      (throw (result-error res sql)))))

(defn- bytea-bytes
  "Decodes the bytea text at pointer p into a byte array."
  ^bytes [p]
  (with-open [arena (ffi/confined-arena)]
    (let [plen (ffi/alloc arena :size_t)
          buf (c-unescape-bytea p plen)]
      (try
        (let [n (ffi/read plen :size_t)]
          (if (pos? n)
            (ffi/read-array (ffi/reinterpret buf n) :byte n)
            (byte-array 0)))
        (finally (c-freemem buf))))))

(defn- parse-or-string [f s]
  (try (f s)
       (catch java.time.format.DateTimeParseException _ s)))

(def ^:private timestamp-format
  (-> (DateTimeFormatterBuilder.)
      (.append DateTimeFormatter/ISO_LOCAL_DATE)
      (.appendLiteral " ")
      (.append DateTimeFormatter/ISO_LOCAL_TIME)
      .toFormatter))

(def ^:private timetz-format
  (-> (DateTimeFormatterBuilder.)
      (.append DateTimeFormatter/ISO_LOCAL_TIME)
      (.appendOffset "+HH:mm:ss" "Z")
      .toFormatter))

(def ^:private timestamptz-format
  (-> (DateTimeFormatterBuilder.)
      (.append DateTimeFormatter/ISO_LOCAL_DATE)
      (.appendLiteral " ")
      (.append DateTimeFormatter/ISO_LOCAL_TIME)
      (.appendOffset "+HH:mm:ss" "Z")
      .toFormatter))

(def ^:private array-elem-oid
  "The element type of each built-in array type."
  {1000 16, 1001 17, 1002 18, 1003 19, 1005 21, 1007 23, 1009 25, 1014 1042
   1015 1043, 1016 20, 1021 700, 1022 701, 1028 26, 1231 1700, 2951 2950
   1182 1082, 1183 1083, 1270 1266, 1115 1114, 1185 1184, 199 114, 3807 3802})

(defn- hex-bytes
  "Decodes a bytea hex literal, backslash x and hex digits, to a byte array."
  ^bytes [^String s]
  (let [n (quot (- (count s) 2) 2)
        out (byte-array n)]
    (dotimes [i n]
      (aset out i (unchecked-byte (Integer/parseInt (subs s (+ 2 (* 2 i)) (+ 4 (* 2 i))) 16))))
    out))

(defn- parse-array
  "Parses the text form of an array, such as {1,NULL,\"a b\"}, into nested
  vectors. decode maps an element string to its value."
  [^String s decode]
  (let [n (count s)
        start (long (if (= \[ (.charAt s 0)) (inc (.indexOf s "=")) 0))
        parse (fn parse [^long i]
                ;; i is at the opening brace; returns [value next-index]
                (loop [i (inc i) acc (transient [])]
                  (case (.charAt s i)
                    \} [(persistent! acc) (inc i)]
                    \, (recur (inc i) acc)
                    \{ (let [[v j] (parse i)] (recur (long j) (conj! acc v)))
                    \" (let [sb (StringBuilder.)
                             j (loop [j (inc i)]
                                 (case (.charAt s j)
                                   \" (inc j)
                                   \\ (do (.append sb (.charAt s (inc j))) (recur (+ j 2)))
                                   (do (.append sb (.charAt s j)) (recur (inc j)))))]
                         (recur (long j) (conj! acc (decode (str sb)))))
                    (let [j (loop [j i]
                              (if (or (= j n) (#{\, \}} (.charAt s j))) j (recur (inc j))))
                          tok (subs s i j)]
                      (recur (long j) (conj! acc (if (= "NULL" tok) nil (decode tok))))))))]
    (first (parse start))))

(defn- text-decoder
  "Returns a fn from the text form of a value of type oid to its value.
  Used for array elements; columns fetch through column-decoder."
  [oid read-json iso?]
  (case (long oid)
    16 (fn [s] (= "t" s))
    (20 21 23 26) (fn [^String s] (Long/parseLong s))
    (700 701) (fn [^String s] (Double/parseDouble s))
    1700 (fn [^String s] (if (= "NaN" s) ##NaN (BigDecimal. s)))
    17 hex-bytes
    2950 (fn [^String s] (java.util.UUID/fromString s))
    (114 3802) (if read-json read-json identity)
    1082 (if iso? (fn [s] (parse-or-string #(LocalDate/parse %) s)) identity)
    1083 (if iso? (fn [s] (parse-or-string #(LocalTime/parse %) s)) identity)
    1266 (if iso? (fn [s] (parse-or-string #(OffsetTime/parse % timetz-format) s)) identity)
    1114 (if iso? (fn [s] (parse-or-string #(LocalDateTime/parse % timestamp-format) s)) identity)
    1184 (if iso? (fn [s] (parse-or-string #(OffsetDateTime/parse % timestamptz-format) s)) identity)
    identity))

(defn- column-decoder
  "Returns a fn of [res row col] that decodes a value of the column type
  oid. Chosen once per column, so a row only calls it."
  [oid read-json iso?]
  (case (long oid)
    16 (fn [res row col] (= "t" (c-getvalue res row col)))
    (20 21 23 26) (fn [res row col] (Long/parseLong (c-getvalue res row col)))
    (700 701) (fn [res row col] (Double/parseDouble (c-getvalue res row col)))
    1700 (fn [res row col]
           (let [s (c-getvalue res row col)]
             (if (= "NaN" s) ##NaN (BigDecimal. ^String s))))
    17 (fn [res row col] (bytea-bytes (c-getvalue-ptr res row col)))
    2950 (fn [res row col] (java.util.UUID/fromString (c-getvalue res row col)))
    (114 3802) (if read-json
                 (fn [res row col] (read-json (c-getvalue res row col)))
                 c-getvalue)
    1082 (if iso? (fn [res row col] (parse-or-string #(LocalDate/parse %) (c-getvalue res row col))) c-getvalue)
    1083 (if iso? (fn [res row col] (parse-or-string #(LocalTime/parse %) (c-getvalue res row col))) c-getvalue)
    1266 (if iso? (fn [res row col] (parse-or-string #(OffsetTime/parse % timetz-format) (c-getvalue res row col))) c-getvalue)
    1114 (if iso? (fn [res row col] (parse-or-string #(LocalDateTime/parse % timestamp-format) (c-getvalue res row col))) c-getvalue)
    1184 (if iso? (fn [res row col] (parse-or-string #(OffsetDateTime/parse % timestamptz-format) (c-getvalue res row col))) c-getvalue)
    (if-let [elem (array-elem-oid (long oid))]
      (let [decode (text-decoder elem read-json iso?)]
        (fn [res row col] (parse-array (c-getvalue res row col) decode)))
      c-getvalue)))

(defn- rows [conn res {:keys [read-json]}]
  (let [iso? (iso-datestyle? conn)
        ncols (long (c-nfields res))
        nrows (long (c-ntuples res))
        keys (object-array (map #(keyword (c-fname res %)) (range ncols)))
        decoders (object-array (map #(column-decoder (c-ftype res %) read-json iso?) (range ncols)))]
    (loop [row 0 acc (transient [])]
      (if (< row nrows)
        (recur (inc row)
               (conj! acc (loop [col 0 m (transient {})]
                            (if (< col ncols)
                              (recur (inc col)
                                     (assoc! m (aget ^objects keys col)
                                             (when (zero? (long (c-getisnull res row col)))
                                               ((aget ^objects decoders col) res row col))))
                              (persistent! m)))))
        (persistent! acc)))))

(def ^:private OID-BOOL 16)
(def ^:private OID-BYTEA 17)
(def ^:private OID-INT8 20)
(def ^:private OID-JSON 114)
(def ^:private OID-FLOAT8 701)
(def ^:private OID-NUMERIC 1700)
(def ^:private OID-JSONB 3802)

(defrecord JsonParam [oid value])

(defn json
  "Marks v as a json parameter. A string is sent as is. Any other value
  is encoded with the :write-json option."
  [v]
  (->JsonParam OID-JSON v))

(defn jsonb
  "Marks v as a jsonb parameter. A string is sent as is. Any other value
  is encoded with the :write-json option."
  [v]
  (->JsonParam OID-JSONB v))

(defn- json-text [write-json v]
  (cond
    (string? v) v
    write-json (write-json v)
    :else (throw (ex-info "postgres: set the :write-json option to bind JSON values"
                          {:value v}))))

(defn- hex-literal ^String [^bytes bs]
  (let [sb (StringBuilder. "\\x")]
    (dotimes [i (alength bs)]
      (.append sb (format "%02x" (bit-and 0xff (aget bs i)))))
    (str sb)))

(defn- array-literal
  "Encodes vector v as the text form of an array. Elements follow the
  parameter types; strings are quoted."
  ^String [write-json v]
  (let [sb (StringBuilder.)
        quoted (fn [^String s]
                 (.append sb \")
                 (.append sb (-> s (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")))
                 (.append sb \"))
        emit (fn emit [v]
               (.append sb \{)
               (doseq [[i x] (map-indexed vector v)]
                 (when (pos? i) (.append sb \,))
                 (cond
                   (nil? x) (.append sb "NULL")
                   (vector? x) (emit x)
                   (boolean? x) (.append sb (if x "t" "f"))
                   (number? x) (.append sb (str x))
                   (string? x) (quoted x)
                   (bytes? x) (quoted (hex-literal x))
                   (uuid? x) (.append sb (str x))
                   (instance? java.time.temporal.Temporal x) (quoted (str x))
                   (instance? JsonParam x) (quoted (json-text write-json (:value x)))
                   :else (throw (ex-info (str "postgres: cannot bind " (type x) " in an array")
                                         {:value x}))))
               (.append sb \}))]
    (emit v)
    (str sb)))

(defn- param-spec
  "Returns [oid text-or-bytes binary?] for a parameter value."
  [{:keys [write-json]} v]
  (cond
    (nil? v) [0 nil false]
    (boolean? v) [OID-BOOL (if v "t" "f") false]
    (integer? v) [OID-INT8 (str v) false]
    (float? v) [OID-FLOAT8 (str v) false]
    (decimal? v) [OID-NUMERIC (str v) false]
    (string? v) [0 v false]
    (bytes? v) [OID-BYTEA v true]
    (uuid? v) [0 (str v) false]
    (instance? java.time.temporal.Temporal v) [0 (str v) false]
    (instance? JsonParam v) [(:oid v) (json-text write-json (:value v)) false]
    (vector? v) [0 (array-literal write-json v) false]
    (map? v) [0 (json-text write-json v) false]
    :else (throw (ex-info (str "postgres: cannot bind " (type v)) {:value v}))))

(defn- exec-params [conn sql params opts]
  (with-open [arena (ffi/confined-arena)]
    (let [n (count params)
          specs (mapv #(param-spec opts %) params)
          types (ffi/alloc arena (* 4 (max n 1)))
          values (ffi/alloc arena (* 8 (max n 1)))
          lengths (ffi/alloc arena (* 4 (max n 1)))
          formats (ffi/alloc arena (* 4 (max n 1)))]
      (doseq [[i [oid v binary?]] (map-indexed vector specs)]
        (ffi/write types :uint oid (* 4 i))
        (ffi/write formats :int (if binary? 1 0) (* 4 i))
        (cond
          (nil? v) (ffi/write values :pointer ffi/null (* 8 i))
          binary? (let [len (alength ^bytes v)
                        p (ffi/alloc arena (max len 1))]
                    (ffi/write-array p :byte v)
                    (ffi/write values :pointer p (* 8 i))
                    (ffi/write lengths :int len (* 4 i)))
          :else (ffi/write values :pointer (ffi/string->ptr arena v) (* 8 i))))
      (c-exec-params conn sql n types values lengths formats 0))))

(defn- run* [{:keys [conn] :as db} q opts collect-rows?]
  (let [opts (merge (:opts db) opts)
        [sql & params] (if (string? q) [q] q)
        res (if (string? q)
              (c-exec conn sql)
              (exec-params conn sql params opts))]
    (try
      (check-result! conn res sql)
      (if collect-rows?
        (if (= PGRES-TUPLES-OK (c-result-status res))
          (rows conn res opts)
          [])
        (let [n (c-cmd-tuples res)]
          {:rows-changed (if (str/blank? n) 0 (Long/parseLong n))}))
      (finally
        (when-not (ffi/null? res)
          (c-clear res))))))

(defn- call-with-conn [db opts f]
  (if (instance? Connection db)
    (f db)
    (with-conn [db db opts] (f db))))

(defn query
  "Runs a query and returns a vector of maps. Each map is one result row.
  Column names are keywords. SQL NULL values become nil. A statement
  without a result set returns an empty vector.

  db can be a connection from connect or a conninfo value for connect. A
  conninfo value opens and closes a connection for this call. q can be a
  SQL string or a vector. The vector starts with SQL. Each $n in SQL uses
  the nth value in the vector. A SQL string can hold multiple statements
  separated by semicolons. The result comes from the last statement.

  opts accepts the keys of connect. It overrides the options of db for this
  call."
  ([db q] (query db q nil))
  ([db q opts]
   (call-with-conn db opts (fn [db] (run* db q opts true)))))

(defn execute!
  "Runs a statement and returns {:rows-changed n}. :rows-changed is the
  number of rows that the statement inserted, updated, or deleted. db, q,
  and opts accept the same values as in query."
  ([db q] (execute! db q nil))
  ([db q opts]
   (call-with-conn db opts (fn [db] (run* db q opts false)))))

(def ^:private PQTRANS-INTRANS 2)

(defn in-transaction?
  "Returns true when db has an open transaction block."
  [{:keys [conn]}]
  (= PQTRANS-INTRANS (c-transaction-status conn)))

(defmacro with-transaction
  "Evaluates body in a transaction on db. Commits when body returns and
  returns its result. Rolls back when body throws.

  When db already has an open transaction, body runs inside that
  transaction. All statements in body must use db."
  [db & body]
  `(let [db# ~db]
     (if (in-transaction? db#)
       (do ~@body)
       (do
         (execute! db# "begin")
         (try
           (let [res# (do ~@body)]
             (execute! db# "commit")
             res#)
           (catch Throwable e#
             (try (execute! db# "rollback")
                  (catch Throwable r# (.addSuppressed e# r#)))
             (throw e#)))))))

(defn cancel!
  "Cancels the running statement on db. Call this function from another
  thread. The cancelled statement throws an exception. Returns nil."
  [{:keys [conn]}]
  (let [cancel (c-get-cancel conn)]
    (when-not (ffi/null? cancel)
      (try
        (with-open [arena (ffi/confined-arena)]
          (let [errbuf (ffi/alloc arena 256)]
            (when (zero? (c-cancel cancel errbuf 256))
              (throw (ex-info (str "postgres: " (str/trim (ffi/ptr->string errbuf 256)))
                              {})))))
        (finally (c-free-cancel cancel)))))
  nil)
