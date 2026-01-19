(ns tasklane.service
  (:require [clojure.string :as str]))

(def ^:private allowed-statuses
  "Legal task workflow states."
  #{:pending :in-progress :done})

(defonce ^{:doc "In-memory task store for demo purposes."}
  tasks
  (atom []))

(defn reset-tasks!
  "Clear all tasks. Intended for tests."
  []
  (reset! tasks []))

(defn- now-iso
  "Return current time as an ISO-8601 string."
  []
  (str (java.time.Instant/now)))

(defn- normalize-status
  "Normalize input into a status keyword when possible."
  [status]
  (cond
    (keyword? status) status
    (string? status) (keyword status)
    :else nil))

(defn- valid-name?
  [name]
  (and (string? name) (not (str/blank? name))))

(defn- valid-priority?
  [priority]
  (or (nil? priority)
      (and (integer? priority) (<= 1 priority 5))))

(defn- validate-common
  [task errors]
  (let [status (normalize-status (:status task))
        errors (cond-> errors
                 (and (contains? task :status)
                      (not (contains? allowed-statuses status)))
                 (conj {:field :status
                        :message "status must be pending, in-progress, or done"}))
        errors (cond-> errors
                 (and (contains? task :priority)
                      (not (valid-priority? (:priority task))))
                 (conj {:field :priority
                        :message "priority must be an integer between 1 and 5"}))
        errors (cond-> errors
                 (and (contains? task :description)
                      (not (string? (:description task))))
                 (conj {:field :description
                        :message "description must be a string"}))]
    {:errors errors
     :status status}))

(defn- validate-create
  [task]
  (validate-common
   task
   (cond-> []
     (not (valid-name? (:name task)))
     (conj {:field :name :message "name is required"}))))

(defn- validate-update
  [updates]
  (let [allowed-keys #{:name :description :status :priority}
        updates (select-keys updates allowed-keys)]
    (validate-common
     updates
     (cond-> []
       (and (contains? updates :name) (not (valid-name? (:name updates))))
       (conj {:field :name :message "name must be a non-empty string"})
       (empty? updates)
       (conj {:field :body :message "at least one updatable field is required"})))))

(defn- error-result
  [type message errors]
  {:error {:type type
           :message message
           :errors errors}})

(defn- next-id
  []
  (inc (reduce max 0 (map :id @tasks))))

(defn create-task
  "Create a task from the provided map.
   Required fields: :name. Optional: :description, :status, :priority."
  [task]
  (if (map? task)
    (let [{:keys [errors status]} (validate-create task)]
      (if (seq errors)
        (error-result :validation "Task validation failed" errors)
        (let [task (-> task
                       (select-keys [:name :description :priority])
                       (assoc :id (next-id)
                              :status (or status :pending)
                              :created-at (now-iso)))
              _ (swap! tasks conj task)]
          {:ok task})))
    (error-result :validation "Task body must be a JSON object"
                  [{:field :body :message "expected an object"}])))

(defn list-tasks
  "Return tasks filtered and paginated by the provided options."
  ([]
   @tasks)
  ([{:keys [status limit offset]}]
   (let [status (normalize-status status)
         filtered (cond->> @tasks
                    status (filter #(= status (:status %))))
         offset (or offset 0)
         limit (or limit 50)]
     (->> filtered
          (drop offset)
          (take limit)
          vec))))

(defn get-task
  "Return a task by id, or nil if missing."
  [id]
  (first (filter #(= id (:id %)) @tasks)))

(defn update-task
  "Update an existing task. Returns {:ok task} or {:error ...}."
  [id updates]
  (cond
    (not (map? updates))
    (error-result :validation "Task update must be a JSON object"
                  [{:field :body :message "expected an object"}])

    (nil? (get-task id))
    (error-result :not-found "Task not found"
                  [{:field :id :message "no task for that id"}])

    :else
    (let [current (get-task id)
          {:keys [errors status]} (validate-update updates)]
      (if (seq errors)
        (error-result :validation "Task update failed" errors)
        (let [updates (-> updates
                          (select-keys [:name :description :priority])
                          (cond-> (contains? updates :status)
                            (assoc :status status))
                          (assoc :updated-at (now-iso)))
              updated (merge current updates)]
          (swap! tasks (fn [items]
                         (mapv (fn [task]
                                 (if (= id (:id task)) updated task))
                               items)))
          {:ok updated})))))

(defn delete-task
  "Delete a task by id. Returns {:ok task} or {:error ...}."
  [id]
  (if-let [current (get-task id)]
    (do
      (swap! tasks (fn [items] (vec (remove #(= id (:id %)) items))))
      {:ok current})
    (error-result :not-found "Task not found"
                  [{:field :id :message "no task for that id"}])))
