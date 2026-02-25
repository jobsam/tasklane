(ns tasklane.sqlite
  (:require [clojure.data.json :as json]
            [clojure.set :as set]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [next.jdbc.sql :as sql]
            [tasklane.store :as store]))

(def ^:private migrations
  [{:id 1
    :name "create-tasks"
    :sql "create table if not exists tasks (
            id integer primary key autoincrement,
            name text not null,
            description text,
            status text not null,
            priority integer,
            due_at text,
            created_at text not null,
            updated_at text
          )"}
   {:id 2
    :name "add-tags"
    :sql "alter table tasks add column tags text"}])

(defn- now-iso
  []
  (str (java.time.Instant/now)))

(defn- migration-name
  [id]
  (:name (first (filter #(= id (:id %)) migrations))))

(defn- ensure-schema-migrations!
  [ds]
  (jdbc/execute! ds
                 ["create table if not exists schema_migrations (
                    id integer primary key,
                    name text not null,
                    applied_at text not null
                  )"]))

(defn- applied-migration-ids
  [ds]
  (into #{}
        (map :id)
        (jdbc/execute! ds
                       ["select id from schema_migrations order by id asc"]
                       {:builder-fn rs/as-unqualified-kebab-maps})))

(defn- table-exists?
  [ds table]
  (-> (jdbc/execute-one! ds
                         ["select name from sqlite_master where type = 'table' and name = ?" table])
      some?))

(defn- column-exists?
  [ds table column]
  (->> (jdbc/execute! ds [(str "pragma table_info(" table ")")]
                      {:builder-fn rs/as-unqualified-kebab-maps})
       (some #(= column (:name %)))
       boolean))

(defn- bootstrap-migrations!
  [ds]
  (let [applied (applied-migration-ids ds)]
    (when (and (empty? applied) (table-exists? ds "tasks"))
      (let [applied-ids (cond-> #{1}
                          (column-exists? ds "tasks" "tags") (conj 2))]
        (doseq [id applied-ids]
          (jdbc/execute! ds
                         ["insert into schema_migrations (id, name, applied_at)
                           values (?, ?, ?)"
                          id (migration-name id) (now-iso)]))))))

(defn migrate!
  "Run pending SQLite migrations."
  [ds]
  (jdbc/with-transaction [tx ds]
    (ensure-schema-migrations! tx)
    (bootstrap-migrations! tx)
    (let [applied (applied-migration-ids tx)
          pending (remove #(contains? applied (:id %)) migrations)]
      (doseq [{:keys [id name sql]} pending]
        (jdbc/execute! tx [sql])
        (jdbc/execute! tx
                       ["insert into schema_migrations (id, name, applied_at)
                         values (?, ?, ?)"
                        id name (now-iso)])))))

(defn- encode-tags
  [tags]
  (when (some? tags)
    (json/write-str (vec tags))))

(defn- decode-tags
  [value]
  (cond
    (nil? value) []
    (string? value) (try
                      (let [parsed (json/read-str value)]
                        (if (sequential? parsed) (vec parsed) []))
                      (catch Exception _ []))
    (sequential? value) (vec value)
    :else []))

(defn- row->task
  [row]
  (when row
    (-> row
        (update :status keyword)
        (update :tags decode-tags))))

(defrecord SQLiteStore [ds]
  store/TaskStore
  (create-task! [this task]
    (let [row (jdbc/execute-one! ds
                                 ["insert into tasks (name, description, status, priority, due_at, created_at, updated_at, tags)
                                   values (?, ?, ?, ?, ?, ?, ?, ?)
                                   returning id"
                                  (:name task)
                                  (:description task)
                                  (name (:status task))
                                  (:priority task)
                                  (:due-at task)
                                  (:created-at task)
                                  (:updated-at task)
                                  (encode-tags (:tags task))]
                                 {:builder-fn rs/as-unqualified-kebab-maps})]
      (store/get-task this (:id row))))
  (list-tasks [_ {:keys [status tag limit offset]}]
    (let [status (when status (name status))
          tag (when (and (string? tag) (not (str/blank? tag))) tag)
          offset (or offset 0)
          [sql params] (if status
                         ["select * from tasks where status = ? order by id asc"
                          [status]]
                         ["select * from tasks order by id asc" []])
          rows (mapv row->task
                     (jdbc/execute! ds
                                    (into [sql] params)
                                    {:builder-fn rs/as-unqualified-kebab-maps}))
          filtered (if tag
                     (filterv #(some #{tag} (:tags %)) rows)
                     rows)]
      (cond->> filtered
        (pos? offset) (drop offset)
        (some? limit) (take limit)
        :always vec)))
  (get-task [_ id]
    (row->task
     (jdbc/execute-one! ds
                        ["select * from tasks where id = ?" id]
                        {:builder-fn rs/as-unqualified-kebab-maps})))
  (update-task! [this id updates]
    (let [updates (cond-> updates
                    (contains? updates :status) (update :status name))
          updates (set/rename-keys updates {:due-at :due_at
                                            :created-at :created_at
                                            :updated-at :updated_at})]
      (let [updates (cond-> updates
                      (contains? updates :tags) (update :tags encode-tags))]
        (sql/update! ds :tasks updates {:id id})
        (store/get-task this id))))
  (delete-task! [this id]
    (let [current (store/get-task this id)]
      (when current
        (sql/delete! ds :tasks {:id id})
        current)))
  (reset-store! [_]
    (jdbc/execute! ds ["delete from tasks"])))

(defn open-store
  "Open a SQLite-backed task store and ensure migrations are applied."
  [db-spec]
  (let [ds (jdbc/get-datasource db-spec)]
    (migrate! ds)
    (->SQLiteStore ds)))
