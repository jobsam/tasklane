(ns tasklane.service-test
  (:require [clojure.test :refer :all]
            [tasklane.service :as service]))

(use-fixtures :each
  (fn [f]
    (service/reset-tasks!)
    (service/clear-hooks!)
    (f)))

(deftest task-creation-test
  (testing "task creation assigns fields"
    (let [due-at "2024-01-01T00:00:00Z"
          result (service/create-task {:name "demo" :priority 2 :due-at due-at :tags ["work" "ops"]})
          task (:ok result)]
      (is (= "demo" (:name task)))
      (is (= 1 (:id task)))
      (is (= :pending (:status task)))
      (is (= 2 (:priority task)))
      (is (= due-at (:due-at task)))
      (is (= ["work" "ops"] (:tags task)))
      (is (string? (:created-at task))))))

(deftest task-validation-test
  (testing "task creation requires a name"
    (let [result (service/create-task {:priority 3})]
      (is (= :validation (get-in result [:error :type])))))
  (testing "task creation validates due date"
    (let [result (service/create-task {:name "demo" :due-at "not-a-date"})]
      (is (= :validation (get-in result [:error :type])))))
  (testing "task update requires a field"
    (let [task (:ok (service/create-task {:name "demo"}))
          result (service/update-task (:id task) {})]
      (is (= :validation (get-in result [:error :type])))))
  (testing "task creation validates tags"
    (let [result (service/create-task {:name "demo" :tags ["ok" ""]})]
      (is (= :validation (get-in result [:error :type]))))))

(deftest task-update-test
  (testing "task update changes status and name"
    (let [task (:ok (service/create-task {:name "demo"}))
          result (service/update-task (:id task) {:status "done" :name "updated" :tags ["ship"]})
          updated (:ok result)]
      (is (= :done (:status updated)))
      (is (= "updated" (:name updated)))
      (is (= ["ship"] (:tags updated)))
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
      (is (= "c" (:name (first filtered))))))
  (testing "task list supports tag filter"
    (service/create-task {:name "a" :tags ["alpha"]})
    (service/create-task {:name "b" :tags ["beta"]})
    (service/create-task {:name "c" :tags ["beta"]})
    (let [filtered (service/list-tasks {:tag "beta"})]
      (is (= 2 (count filtered)))
      (is (= #{"b" "c"} (set (map :name filtered)))))))

(deftest task-hooks-test
  (let [events (atom [])
        hooks {:task/before-create (fn [ctx]
                                     (swap! events conj :before-create)
                                     ctx)
               :task/after-create (fn [ctx]
                                    (swap! events conj :after-create)
                                    ctx)}
        result (service/create-task service/default-store {:name "hooked"} {:hooks hooks})]
    (is (= [:before-create :after-create] @events))
    (is (= "hooked" (get-in result [:ok :name]))))
  (let [task (:ok (service/create-task {:name "demo"}))
        hooks {:task/before-update (fn [_]
                                     {:error {:type :forbidden
                                              :message "Blocked"
                                              :errors []}})}
        result (service/update-task service/default-store (:id task) {:name "nope"} {:hooks hooks})]
    (is (= :forbidden (get-in result [:error :type])))))

(deftest global-hooks-test
  (let [events (atom [])]
    (service/register-hooks!
     {:task/before-create (fn [ctx]
                            (swap! events conj :global-before)
                            ctx)
      :task/after-create (fn [ctx]
                           (swap! events conj :global-after)
                           ctx)})
    (let [result (service/create-task {:name "global"})]
      (is (= "global" (get-in result [:ok :name])))
      (is (= [:global-before :global-after] @events)))))
