# babashka.postgres

Use PostgreSQL from babashka through
[babashka.ffi](https://github.com/babashka/babashka/blob/master/doc/ffi.md)
and libpq.

This library and `babashka.ffi` are experimental. This library requires a
babashka build that includes `babashka.ffi`.

## Install libpq

The library loads the libpq shared library from the system. The PostgreSQL
version depends on the system.

- macOS: `brew install libpq` or any `postgresql@N` formula, or Postgres.app.
- Linux: the `libpq5` package on Debian and Ubuntu, `libpq` on Fedora.
- Windows: put the `bin` directory of a PostgreSQL installation on the `PATH`.

## Query

```clojure
(require '[babashka.postgres :as pg])

(pg/query "postgresql://app@localhost/app" "select version() v, 1 + 1 sum")
;;=> [{:v "PostgreSQL 17.11 ...", :sum 2}]
```

`query` returns a vector of row maps. Each map uses keywords for its column
names.

The first argument is a libpq connection string, a URI, a map of libpq
parameters, or `nil`. `nil` and missing parameters use the libpq defaults and
the `PG*` environment variables.

```clojure
(def db {:host "localhost" :dbname "app" :user "app"})

(pg/execute! db "create table if not exists users (name text, age int)")
(pg/execute! db ["insert into users values ($1, $2), ($3, $4)" "rich" 17 "stu" 12])
(pg/query db ["select * from users where age > $1" 15])
;;=> [{:name "rich", :age 17}]
```

Use `execute!` for statements that do not return rows. It returns
`{:rows-changed n}`. `:rows-changed` is the number of rows that the statement
inserted, updated, or deleted.

If the statement has no parameters, pass SQL as a string. A string can hold
multiple statements separated by semicolons. The result comes from the last
statement. If the statement has parameters, use the `[sql & params]` vector
form with `$1`, `$2`, ... placeholders.

Use `returning` with `query` to get generated values:

```clojure
(pg/query db ["insert into events (what) values ($1) returning id" "ship"])
;;=> [{:id 1}]
```

Each result value has the Clojure type for its PostgreSQL column type:

| PostgreSQL type | Clojure value |
| --- | --- |
| `smallint`, `int`, `bigint`, `oid` | long |
| `real`, `double precision` | double |
| `numeric` | BigDecimal |
| `boolean` | boolean |
| `text`, `varchar`, `char`, `name` | string |
| `bytea` | byte array |
| `uuid` | `java.util.UUID` |
| `date` | `java.time.LocalDate` |
| `time` | `java.time.LocalTime` |
| `time with time zone` | `java.time.OffsetTime` |
| `timestamp` | `java.time.LocalDateTime` |
| `timestamp with time zone` | `java.time.OffsetDateTime` |
| `json`, `jsonb` | string, or the `:read-json` result |
| arrays, other types | string |
| `NULL` | `nil` |

`connect` sets `DateStyle` to `ISO` when the server default is different.
If a session changes `DateStyle` later, date and time columns come back as
strings. A date or time value that `java.time` cannot parse, such as
`infinity`, stays a string.

`COPY` statements are not supported. Server notices such as `NOTICE: relation
already exists, skipping` go to stderr.

Parameters accept longs, doubles, BigDecimals, booleans, strings, byte
arrays, UUIDs, `java.time` values, `nil`, and with `:write-json`, maps and
vectors. A string parameter has no declared type, so the server infers the
type from its use. Add a cast such as `$1::uuid` where the server cannot
infer one.

### JSON

The library has no JSON dependency. Pass a JSON library through two options:

- `:read-json` is a function from a JSON string to a value. `query` applies
  it to `json` and `jsonb` columns.
- `:write-json` is a function from a value to a JSON string. It encodes map
  and vector parameters.

Use cheshire in babashka, or any library on the JVM:

```clojure
(require '[cheshire.core :as json])

(def json-opts {:read-json #(json/parse-string % true)
                :write-json json/generate-string})

(pg/with-conn [db "postgresql://app@localhost/app" json-opts]
  (pg/execute! db "create table if not exists docs (id int, doc jsonb)")
  (pg/execute! db ["insert into docs values ($1, $2)" 1 {:a 1 :tags ["x" "y"]}])
  (pg/query db "select * from docs"))
;;=> [{:id 1, :doc {:a 1, :tags ["x" "y"]}}]
```

The options also go on `connect` and as a third argument to `query` and
`execute!`. A call option overrides the connection option.

A map or vector parameter has no declared type, so the server infers `json`
or `jsonb` from the column. Wrap a value in `pg/json` or `pg/jsonb` to
declare the type, such as for a JSON operator:

```clojure
(pg/query db ["select $1->>'a' a" (pg/jsonb {:a 1})] json-opts)
;;=> [{:a "1"}]
```

A string inside `pg/json` or `pg/jsonb` is sent as is and needs no
`:write-json`.

### HoneySQL

[HoneySQL](https://github.com/seancorfield/honeysql) builds SQL from Clojure
data. Pass the result of `sql/format` with `:numbered true` to `query`:

```clojure
(require '[honey.sql :as sql])

(pg/query db
  (sql/format {:select [:name :age]
               :from [:users]
               :where [:> :age 15]
               :order-by [[:age :desc]]}
              {:numbered true}))
;;=> [{:name "rich", :age 17}]
```

## Connections

A connection string or a map opens and closes a connection for each call.
For multiple operations, keep one connection open:

```clojure
(pg/with-conn [db "postgresql://app@localhost/app"]
  (pg/execute! db "create table if not exists events (at date, what text)")
  (pg/execute! db ["insert into events values ($1, $2)" (java.time.LocalDate/now) "ship"])
  (pg/query db "select * from events"))
```

Use `(pg/connect conninfo)` and `(pg/close! db)` when you cannot use `with-conn`.

Map keys are libpq parameter names such as `:host`, `:port`, `:dbname`,
`:user`, `:password`, and `:sslmode`. See the
[libpq documentation](https://www.postgresql.org/docs/current/libpq-connect.html#LIBPQ-PARAMKEYWORDS)
for the full list.

`server-version` returns the version of the server behind a connection.
`version` returns the libpq version.

## Thread safety

Use a connection from one thread at a time. `cancel!` is the one function
for use from another thread. Never call `close!` while another thread uses
the connection.

Use one of these safe patterns:

- Open one connection for each thread.
- Pass a connection string or map to `query` or `execute!`. Each call opens a
  private connection.

## Transactions

`with-transaction` starts a transaction and evaluates its body. It commits
the transaction when the body returns. It rolls back the transaction when
the body throws.

Use one transaction for a batch of inserts:

```clojure
(pg/with-conn [db "postgresql://app@localhost/app"]
  (pg/with-transaction db
    (doseq [i (range 1000)]
      (pg/execute! db ["insert into events values ($1, $2)" (java.time.LocalDate/now) (str "tick " i)]))))
```

When the connection already has an open transaction, `with-transaction`
runs its body inside that transaction. `in-transaction?` reports whether a
connection has an open transaction.

## Interrupting a query

Call `(pg/cancel! db)` from another thread to cancel the running
statement. The cancelled statement throws an exception.

## clj-kondo

The library exports a clj-kondo config for `with-conn` and `with-transaction`.
Copy it with:

```bash
clj-kondo --lint "$(clojure -Spath)" --copy-configs --skip-lint
```

## Test

Run the tests:

```bash
bb test:bb
bb test:jvm
```

With `PG_TEST_CONNINFO` set, the tests use that server. Without it, the
tests start a temporary server with the PostgreSQL binaries on the `PATH` or
in a standard installation directory.

## License

Copyright (c) 2026 Michiel Borkent

Distributed under the MIT License. See LICENSE.
