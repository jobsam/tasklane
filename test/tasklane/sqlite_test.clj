(ns tasklane.sqlite-test
  (:require [clojure.test :refer :all]
            [next.jdbc :as jdbc]
            [tasklane.service :as service]
            [tasklane.sqlite :as sqlite]))

(defn- temp-db-path
  []
  (let [path (java.nio.file.Files/createTempFile
              "tasklane"
              ".db"
              (make-array java.nio.file.attribute.FileAttribute 0))]
    (.deleteOnExit (.toFile path))
    (str path)))

(deftest sqlite-store-lifecycle-test
  (let [store (sqlite/open-store (str "jdbc:sqlite:" (temp-db-path)))
        due-at "2024-01-02T00:00:00Z"
        created (:ok (service/create-task store {:name "demo" :priority 2 :due-at due-at :tags ["db"]}))]
    (is (= "demo" (:name created)))
    (is (= due-at (:due-at created)))
    (is (= ["db"] (:tags created)))
    (is (= 1 (count (service/list-tasks store {}))))
    (let [updated (:ok (service/update-task store (:id created) {:status "done" :tags ["db" "done"]}))]
      (is (= :done (:status updated)))
      (is (= ["db" "done"] (:tags updated))))
    (let [deleted (:ok (service/delete-task store (:id created)))]
      (is (= (:id created) (:id deleted)))
      (is (nil? (service/get-task store (:id created)))))))

(deftest sqlite-list-filter-test
  (let [store (sqlite/open-store (str "jdbc:sqlite:" (temp-db-path)))]
    (service/create-task store {:name "a" :status "pending"})
    (service/create-task store {:name "b" :status "done" :tags ["ops"]})
    (service/create-task store {:name "c" :status "done" :tags ["ops"]})
    (let [filtered (service/list-tasks store {:status "done" :limit 1 :offset 1})]
      (is (= 1 (count filtered)))
      (is (= "c" (:name (first filtered))))))
  (let [store (sqlite/open-store (str "jdbc:sqlite:" (temp-db-path)))]
    (service/create-task store {:name "a" :tags ["infra"]})
    (service/create-task store {:name "b" :tags ["ops"]})
    (service/create-task store {:name "c" :tags ["ops"]})
    (let [filtered (service/list-tasks store {:tag "ops"})]
      (is (= 2 (count filtered)))
      (is (= #{"b" "c"} (set (map :name filtered)))))))

(deftest sqlite-legacy-migration-test
  (let [db (str "jdbc:sqlite:" (temp-db-path))
        ds (jdbc/get-datasource db)]
    (jdbc/execute! ds
                   ["create table tasks (
                      id integer primary key autoincrement,
                      name text not null,
                      description text,
                      status text not null,
                      priority integer,
                      due_at text,
                      created_at text not null,
                      updated_at text
                    )"])
    (jdbc/execute! ds
                   ["insert into tasks (name, status, created_at) values (?, ?, ?)"
                    "legacy" "pending" "2024-01-01T00:00:00Z"])
    (let [store (sqlite/open-store db)
          task (service/get-task store 1)]
      (is (= "legacy" (:name task)))
      (is (= [] (:tags task))))))
