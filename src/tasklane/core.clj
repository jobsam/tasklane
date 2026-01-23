(ns tasklane.core
  (:require [tasklane.http :as http]
            [tasklane.sqlite :as sqlite]))

(defn -main
  "Start the HTTP server."
  [& _]
  (let [db-path (System/getenv "TASKLANE_DB")
        store (when db-path
                (sqlite/open-store (str "jdbc:sqlite:" db-path)))]
    (if store
      (http/start (http/app-with-store store))
      (http/start))))
