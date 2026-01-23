(ns tasklane.sqlite
  (:require [clojure.set :as set]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [next.jdbc.sql :as sql]
            [tasklane.store :as store]))

(defn- row->task
  [row]
  (when row
    (update row :status keyword)))

(defrecord SQLiteStore [ds]
  store/TaskStore
  (create-task! [this task]
    (jdbc/execute-one! ds
                       ["insert into tasks (name, description, status, priority, due_at, created_at, updated_at)
                         values (?, ?, ?, ?, ?, ?, ?)"
                        (:name task)
                        (:description task)
                        (name (:status task))
                        (:priority task)
                        (:due-at task)
                        (:created-at task)
                        (:updated-at task)])
    (let [id (-> (jdbc/execute-one! ds ["select last_insert_rowid() as id"]
                                    {:builder-fn rs/as-unqualified-kebab-maps})
                 :id)]
      (store/get-task this id)))
  (list-tasks [_ {:keys [status limit offset]}]
    (let [status (when status (name status))
          offset (or offset 0)
          [sql params] (cond
                         (and status (some? limit))
                         ["select * from tasks where status = ? order by id asc limit ? offset ?"
                          [status limit offset]]
                         (and status (pos? offset))
                         ["select * from tasks where status = ? order by id asc limit -1 offset ?"
                          [status offset]]
                         status
                         ["select * from tasks where status = ? order by id asc"
                          [status]]
                         (some? limit)
                         ["select * from tasks order by id asc limit ? offset ?"
                          [limit offset]]
                         (pos? offset)
                         ["select * from tasks order by id asc limit -1 offset ?"
                          [offset]]
                         :else
                         ["select * from tasks order by id asc" []])]
      (mapv row->task
            (jdbc/execute! ds
                           (into [sql] params)
                           {:builder-fn rs/as-unqualified-kebab-maps}))))
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
      (sql/update! ds :tasks updates {:id id})
      (store/get-task this id)))
  (delete-task! [this id]
    (let [current (store/get-task this id)]
      (when current
        (sql/delete! ds :tasks {:id id})
        current)))
  (reset-store! [_]
    (jdbc/execute! ds ["delete from tasks"])))

(defn init!
  "Create the tasks table if it doesn't exist."
  [ds]
  (jdbc/execute! ds
                 ["create table if not exists tasks (
                    id integer primary key autoincrement,
                    name text not null,
                    description text,
                    status text not null,
                    priority integer,
                    due_at text,
                    created_at text not null,
                    updated_at text
                  )"]))

(defn open-store
  "Open a SQLite-backed task store and ensure the schema exists."
  [db-spec]
  (let [ds (jdbc/get-datasource db-spec)]
    (init! ds)
    (->SQLiteStore ds)))
