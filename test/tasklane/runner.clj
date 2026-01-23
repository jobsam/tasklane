(ns tasklane.runner
  (:require [clojure.test :as test]
            [tasklane.http-test]
            [tasklane.planner-test]
            [tasklane.sqlite-test]
            [tasklane.service-test]))

(defn -main
  "Run the test suite."
  [& _]
  (let [result (test/run-all-tests)]
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))
