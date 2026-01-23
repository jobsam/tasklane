(ns tasklane.sqlite-test
  (:require [clojure.test :refer :all]
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
        created (:ok (service/create-task store {:name "demo" :priority 2 :due-at due-at}))]
    (is (= "demo" (:name created)))
    (is (= due-at (:due-at created)))
    (is (= 1 (count (service/list-tasks store {}))))
    (let [updated (:ok (service/update-task store (:id created) {:status "done"}))]
      (is (= :done (:status updated))))
    (let [deleted (:ok (service/delete-task store (:id created)))]
      (is (= (:id created) (:id deleted)))
      (is (nil? (service/get-task store (:id created)))))))

(deftest sqlite-list-filter-test
  (let [store (sqlite/open-store (str "jdbc:sqlite:" (temp-db-path)))]
    (service/create-task store {:name "a" :status "pending"})
    (service/create-task store {:name "b" :status "done"})
    (service/create-task store {:name "c" :status "done"})
    (let [filtered (service/list-tasks store {:status "done" :limit 1 :offset 1})]
      (is (= 1 (count filtered)))
      (is (= "c" (:name (first filtered)))))))
