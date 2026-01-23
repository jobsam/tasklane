(ns tasklane.store
  (:require [clojure.string :as str]))

(defprotocol TaskStore
  (create-task! [store task])
  (list-tasks [store filters])
  (get-task [store id])
  (update-task! [store id updates])
  (delete-task! [store id])
  (reset-store! [store]))

(defn- normalize-status
  [status]
  (cond
    (keyword? status) status
    (string? status) (keyword (str/lower-case status))
    :else nil))

(defrecord MemoryStore [tasks]
  TaskStore
  (create-task! [_ task]
    (let [next-id (inc (reduce max 0 (map :id @tasks)))
          task (assoc task :id next-id)]
      (swap! tasks conj task)
      task))
  (list-tasks [_ {:keys [status limit offset]}]
    (let [status (normalize-status status)
          filtered (cond->> @tasks
                     status (filter #(= status (:status %))))
          offset (or offset 0)]
      (cond->> filtered
        (pos? offset) (drop offset)
        (some? limit) (take limit)
        :always vec)))
  (get-task [_ id]
    (first (filter #(= id (:id %)) @tasks)))
  (update-task! [this id updates]
    (if-let [current (get-task this id)]
      (let [updated (merge current updates)]
        (swap! tasks (fn [items]
                       (mapv (fn [task]
                               (if (= id (:id task)) updated task))
                             items)))
        updated)
      nil))
  (delete-task! [this id]
    (if-let [current (get-task this id)]
      (do
        (swap! tasks (fn [items] (vec (remove #(= id (:id %)) items))))
        current)
      nil))
  (reset-store! [_]
    (reset! tasks [])))

(defn new-memory-store
  "Create an in-memory store suitable for tests or demos."
  []
  (->MemoryStore (atom [])))
