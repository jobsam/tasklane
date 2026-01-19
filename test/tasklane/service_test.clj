(ns tasklane.service-test
  (:require [clojure.test :refer :all]
            [tasklane.service :as service]))

(use-fixtures :each
  (fn [f]
    (service/reset-tasks!)
    (f)))

(deftest task-creation-test
  (testing "task creation assigns fields"
    (let [result (service/create-task {:name "demo" :priority 2})
          task (:ok result)]
      (is (= "demo" (:name task)))
      (is (= 1 (:id task)))
      (is (= :pending (:status task)))
      (is (= 2 (:priority task)))
      (is (string? (:created-at task))))))

(deftest task-validation-test
  (testing "task creation requires a name"
    (let [result (service/create-task {:priority 3})]
      (is (= :validation (get-in result [:error :type])))))
  (testing "task update requires a field"
    (let [task (:ok (service/create-task {:name "demo"}))
          result (service/update-task (:id task) {})]
      (is (= :validation (get-in result [:error :type]))))))

(deftest task-update-test
  (testing "task update changes status and name"
    (let [task (:ok (service/create-task {:name "demo"}))
          result (service/update-task (:id task) {:status "done" :name "updated"})
          updated (:ok result)]
      (is (= :done (:status updated)))
      (is (= "updated" (:name updated)))
      (is (string? (:updated-at updated))))))

(deftest task-delete-test
  (testing "task delete removes item"
    (let [task (:ok (service/create-task {:name "demo"}))
          deleted (:ok (service/delete-task (:id task)))]
      (is (= (:id task) (:id deleted)))
      (is (nil? (service/get-task (:id task)))))))

(deftest task-list-test
  (testing "task list supports status filter and paging"
    (service/create-task {:name "a" :status "pending"})
    (service/create-task {:name "b" :status "done"})
    (service/create-task {:name "c" :status "done"})
    (let [filtered (service/list-tasks {:status "done" :limit 1 :offset 1})]
      (is (= 1 (count filtered)))
      (is (= "c" (:name (first filtered)))))))
