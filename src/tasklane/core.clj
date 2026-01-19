(ns tasklane.core
  (:require [tasklane.http :as http]))

(defn -main
  "Start the HTTP server."
  [& _]
  (http/start))
