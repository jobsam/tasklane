(ns tasklane.planner-test
  (:require [clojure.test :refer :all]
            [tasklane.planner :as planner]))

(def fixed-now
  (java.time.Instant/parse "2024-01-01T00:00:00Z"))

(deftest prioritize-orders-by-urgency
  (let [tasks [{:id 1
                :name "overdue"
                :status :pending
                :priority 3
                :due-at "2023-12-31T23:00:00Z"}
               {:id 2
                :name "later"
                :status :pending
                :priority 3
                :due-at "2024-01-05T00:00:00Z"}]
        ranked (planner/prioritize tasks {:now fixed-now})
        top-task (-> ranked first :task)]
    (is (= 2 (count ranked)))
    (is (= 1 (:id top-task)))
    (is (some #{"overdue"} (-> ranked first :reasons)))))

(deftest prioritize-skips-done
  (let [tasks [{:id 1 :name "done" :status :done :priority 5}
               {:id 2 :name "active" :status :pending :priority 1}]
        ranked (planner/prioritize tasks {:now fixed-now})]
    (is (= 1 (count ranked)))
    (is (= 2 (-> ranked first :task :id)))))

(deftest workload-report-counts-risk
  (let [tasks [{:id 1
                :name "overdue"
                :status :pending
                :due-at "2023-12-31T23:00:00Z"}
               {:id 2
                :name "soon"
                :status :in-progress
                :due-at "2024-01-02T12:00:00Z"}
               {:id 3
                :name "done"
                :status :done
                :due-at "2024-01-10T00:00:00Z"}]
        report (planner/workload-report tasks {:now fixed-now :horizon-hours 48})]
    (is (= 3 (:total report)))
    (is (= 1 (:overdue report)))
    (is (= 1 (:due-soon report)))
    (is (= 1 (:done report)))))
