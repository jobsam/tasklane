(ns tasklane.service
  (:require [clojure.string :as str]
            [tasklane.store :as store]))

(def ^:private allowed-statuses
  "Legal task workflow states."
  #{:pending :in-progress :done})

(defonce ^{:doc "Default in-memory task store for demo purposes."}
  default-store
  (store/new-memory-store))

(defn reset-tasks!
  "Clear all tasks. Intended for tests."
  []
  (store/reset-store! default-store))

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

(defn- parse-iso
  "Parse ISO-8601 string into an Instant, returning nil if invalid."
  [value]
  (when (string? value)
    (try
      (java.time.Instant/parse value)
      (catch Exception _ nil))))

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
        due-at (:due-at task)
        due-instant (when (contains? task :due-at) (parse-iso due-at))
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
                        :message "description must be a string"}))
        errors (cond-> errors
                 (and (contains? task :due-at)
                      (nil? due-instant))
                 (conj {:field :due-at
                        :message "due-at must be an ISO-8601 instant"}))]
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
  (let [allowed-keys #{:name :description :status :priority :due-at}
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

(defn create-task
  "Create a task from the provided map.
   Required fields: :name. Optional: :description, :status, :priority."
  ([task]
   (create-task default-store task))
  ([store task]
   (if (map? task)
     (let [{:keys [errors status]} (validate-create task)]
       (if (seq errors)
         (error-result :validation "Task validation failed" errors)
         (let [task (-> task
                        (select-keys [:name :description :priority :due-at])
                        (assoc :status (or status :pending)
                               :created-at (now-iso)))
               task (store/create-task! store task)]
           {:ok task})))
     (error-result :validation "Task body must be a JSON object"
                   [{:field :body :message "expected an object"}]))))

(defn list-tasks
  "Return tasks filtered and paginated by the provided options."
  ([]
   (store/list-tasks default-store {:limit nil :offset 0}))
  ([filters]
   (list-tasks default-store filters))
  ([store {:keys [status limit offset]}]
   (let [limit (if (nil? limit) 50 limit)
         offset (or offset 0)]
     (store/list-tasks store {:status (normalize-status status)
                              :limit limit
                              :offset offset}))))

(defn get-task
  "Return a task by id, or nil if missing."
  ([id]
   (get-task default-store id))
  ([store id]
   (store/get-task store id)))

(defn update-task
  "Update an existing task. Returns {:ok task} or {:error ...}."
  ([id updates]
   (update-task default-store id updates))
  ([store id updates]
   (cond
     (not (map? updates))
     (error-result :validation "Task update must be a JSON object"
                   [{:field :body :message "expected an object"}])

     (nil? (get-task store id))
     (error-result :not-found "Task not found"
                   [{:field :id :message "no task for that id"}])

     :else
     (let [current (get-task store id)
           {:keys [errors status]} (validate-update updates)]
       (if (seq errors)
         (error-result :validation "Task update failed" errors)
         (let [updates (-> updates
                           (select-keys [:name :description :priority :due-at])
                           (cond-> (contains? updates :status)
                             (assoc :status status))
                           (assoc :updated-at (now-iso)))
               updated (store/update-task! store id updates)]
           {:ok (merge current updates updated)}))))))

(defn delete-task
  "Delete a task by id. Returns {:ok task} or {:error ...}."
  ([id]
   (delete-task default-store id))
  ([store id]
   (if-let [current (store/delete-task! store id)]
     {:ok current}
     (error-result :not-found "Task not found"
                   [{:field :id :message "no task for that id"}]))))
