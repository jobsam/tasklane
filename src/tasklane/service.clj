(ns tasklane.service
  (:require [clojure.string :as str]
            [tasklane.hooks :as hooks]
            [tasklane.store :as store]))

(def ^:private allowed-statuses
  "Legal task workflow states."
  #{:pending :in-progress :done})

(defonce ^{:doc "Default in-memory task store for demo purposes."}
  default-store
  (store/new-memory-store))

(defonce ^{:doc "Global hook registry for task events."}
  global-hooks
  (atom {}))

(defn reset-tasks!
  "Clear all tasks. Intended for tests."
  []
  (store/reset-store! default-store))

(defn clear-hooks!
  "Clear all globally registered hooks. Intended for tests."
  []
  (reset! global-hooks {}))

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

(defn- normalize-tags
  [tags]
  (when (some? tags)
    (let [tags (cond
                 (vector? tags) tags
                 (sequential? tags) (vec tags)
                 :else ::invalid)]
      (when (not= tags ::invalid)
        (let [tags (mapv (fn [tag]
                           (if (string? tag)
                             (str/trim tag)
                             tag))
                         tags)]
          (when (every? #(and (string? %) (not (str/blank? %))) tags)
            (vec (distinct tags))))))))

(defn- coerce-handlers
  [handlers]
  (cond
    (nil? handlers) []
    (fn? handlers) [handlers]
    (sequential? handlers) (vec handlers)
    :else []))

(defn- hooks->vector-map
  [hooks]
  (into {}
        (map (fn [[event handlers]]
               [event (coerce-handlers handlers)]))
        hooks))

(defn register-hooks!
  "Register global hooks. Handlers are appended per event."
  [hooks]
  (swap! global-hooks
         (fn [current]
           (merge-with into
                       (hooks->vector-map current)
                       (hooks->vector-map hooks)))))

(defn- merge-hooks
  [global local]
  (merge-with into
              (hooks->vector-map global)
              (hooks->vector-map local)))

(defn- validate-common
  [task errors]
  (let [status (normalize-status (:status task))
        due-at (:due-at task)
        due-instant (when (contains? task :due-at) (parse-iso due-at))
        tags (when (contains? task :tags) (normalize-tags (:tags task)))
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
                        :message "due-at must be an ISO-8601 instant"}))
        errors (cond-> errors
                 (and (contains? task :tags)
                      (nil? tags))
                 (conj {:field :tags
                        :message "tags must be a list of non-empty strings"}))]
    {:errors errors
     :status status
     :tags tags}))

(defn- validate-create
  [task]
  (validate-common
   task
   (cond-> []
     (not (valid-name? (:name task)))
     (conj {:field :name :message "name is required"}))))

(defn- validate-update
  [updates]
  (let [allowed-keys #{:name :description :status :priority :due-at :tags}
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
   Required fields: :name. Optional: :description, :status, :priority, :tags."
  ([task]
   (create-task default-store task))
  ([store task]
   (create-task store task nil))
  ([store task {:keys [hooks]}]
   (if (map? task)
     (let [{:keys [errors status tags]} (validate-create task)]
       (if (seq errors)
         (error-result :validation "Task validation failed" errors)
         (let [task (-> task
                        (select-keys [:name :description :priority :due-at])
                        (assoc :status (or status :pending)
                               :tags (or tags [])
                               :created-at (now-iso)))
               hooks (merge-hooks @global-hooks hooks)
               ctx (hooks/run-hooks hooks :task/before-create
                                    {:store store :task task})]
           (if-let [err (:error ctx)]
             {:error err}
             (let [task (store/create-task! store (:task ctx))
                   ctx (hooks/run-hooks hooks :task/after-create
                                        {:store store :task (:task ctx) :result task})]
               (if-let [err (:error ctx)]
                 {:error err}
                 {:ok (:result ctx)}))))))
     (error-result :validation "Task body must be a JSON object"
                   [{:field :body :message "expected an object"}]))))

(defn list-tasks
  "Return tasks filtered and paginated by the provided options."
  ([]
   (store/list-tasks default-store {:limit nil :offset 0}))
  ([filters]
   (list-tasks default-store filters))
  ([store {:keys [status tag limit offset]}]
   (let [limit (if (nil? limit) 50 limit)
         offset (or offset 0)
         tag (cond
               (keyword? tag) (name tag)
               (string? tag) tag
               :else nil)]
     (store/list-tasks store {:status (normalize-status status)
                              :tag tag
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
   (update-task store id updates nil))
  ([store id updates {:keys [hooks]}]
   (cond
     (not (map? updates))
     (error-result :validation "Task update must be a JSON object"
                   [{:field :body :message "expected an object"}])

     (nil? (get-task store id))
     (error-result :not-found "Task not found"
                   [{:field :id :message "no task for that id"}])

     :else
     (let [current (get-task store id)
           {:keys [errors status tags]} (validate-update updates)]
       (if (seq errors)
         (error-result :validation "Task update failed" errors)
         (let [updates (-> updates
                           (select-keys [:name :description :priority :due-at :tags])
                           (cond-> (contains? updates :status)
                             (assoc :status status))
                           (cond-> (contains? updates :tags)
                             (assoc :tags (or tags [])))
                           (assoc :updated-at (now-iso)))
               hooks (merge-hooks @global-hooks hooks)
               ctx (hooks/run-hooks hooks :task/before-update
                                    {:store store :task current :updates updates})]
           (if-let [err (:error ctx)]
             {:error err}
             (let [updates (:updates ctx)
                   updated (store/update-task! store id updates)
                   result (merge current updates updated)
                   ctx (hooks/run-hooks hooks :task/after-update
                                        {:store store
                                         :task current
                                         :updates (:updates ctx)
                                         :result result})]
               (if-let [err (:error ctx)]
                 {:error err}
                 {:ok (:result ctx)})))))))))

(defn delete-task
  "Delete a task by id. Returns {:ok task} or {:error ...}."
  ([id]
   (delete-task default-store id))
  ([store id]
   (delete-task store id nil))
  ([store id {:keys [hooks]}]
   (if-let [current (store/get-task store id)]
     (let [hooks (merge-hooks @global-hooks hooks)
           ctx (hooks/run-hooks hooks :task/before-delete
                                {:store store :task current})]
       (if-let [err (:error ctx)]
         {:error err}
         (let [deleted (store/delete-task! store id)
               ctx (hooks/run-hooks hooks :task/after-delete
                                    {:store store :task current :result deleted})]
           (if-let [err (:error ctx)]
             {:error err}
             {:ok (:result ctx)}))))
     (error-result :not-found "Task not found"
                   [{:field :id :message "no task for that id"}]))))

(defn- sequential-items?
  [value]
  (and (sequential? value) (not (map? value))))

(defn- bulk-summary
  [ok-key ok-items error-items]
  {:ok (assoc {ok-key ok-items
               :errors error-items}
         :total (+ (count ok-items) (count error-items))
         :succeeded (count ok-items)
         :failed (count error-items))})

(defn bulk-create-tasks
  "Create tasks in bulk. Returns {:ok {:created [...] :errors [...] ...}} or {:error ...}."
  ([tasks]
   (bulk-create-tasks default-store tasks))
  ([store tasks]
   (bulk-create-tasks store tasks nil))
  ([store tasks opts]
   (if (sequential-items? tasks)
     (let [result (reduce (fn [acc [index task]]
                            (let [response (create-task store task opts)]
                              (if-let [created (:ok response)]
                                (update acc :created conj created)
                                (update acc :errors conj {:index index
                                                          :error (:error response)}))))
                          {:created []
                           :errors []}
                          (map-indexed vector tasks))]
       (bulk-summary :created (:created result) (:errors result)))
     (error-result :validation
                   "Bulk create body must contain a list of tasks"
                   [{:field :tasks :message "expected a list"}]))))

(defn bulk-update-tasks
  "Update tasks in bulk. Input items must be maps with :id and :changes."
  ([items]
   (bulk-update-tasks default-store items))
  ([store items]
   (bulk-update-tasks store items nil))
  ([store items opts]
   (if (sequential-items? items)
     (let [result (reduce (fn [acc [index item]]
                            (cond
                              (not (map? item))
                              (update acc :errors conj {:index index
                                                        :error {:type :validation
                                                                :message "Bulk update item must be an object"
                                                                :errors [{:field :item
                                                                          :message "expected an object"}]}})

                              (not (integer? (:id item)))
                              (update acc :errors conj {:index index
                                                        :error {:type :validation
                                                                :message "Bulk update item must include integer id"
                                                                :errors [{:field :id
                                                                          :message "id must be an integer"}]}})

                              (not (map? (:changes item)))
                              (update acc :errors conj {:index index
                                                        :error {:type :validation
                                                                :message "Bulk update item must include changes object"
                                                                :errors [{:field :changes
                                                                          :message "expected an object"}]}})

                              :else
                              (let [response (update-task store (:id item) (:changes item) opts)]
                                (if-let [updated (:ok response)]
                                  (update acc :updated conj updated)
                                  (update acc :errors conj {:index index
                                                            :id (:id item)
                                                            :error (:error response)})))))
                          {:updated []
                           :errors []}
                          (map-indexed vector items))]
       (bulk-summary :updated (:updated result) (:errors result)))
     (error-result :validation
                   "Bulk update body must contain a list of updates"
                   [{:field :updates :message "expected a list"}]))))

(defn bulk-delete-tasks
  "Delete tasks in bulk by id. Returns per-item errors for missing/invalid ids."
  ([ids]
   (bulk-delete-tasks default-store ids))
  ([store ids]
   (bulk-delete-tasks store ids nil))
  ([store ids opts]
   (if (sequential-items? ids)
     (let [result (reduce (fn [acc [index id]]
                            (if (integer? id)
                              (let [response (delete-task store id opts)]
                                (if-let [deleted (:ok response)]
                                  (update acc :deleted conj deleted)
                                  (update acc :errors conj {:index index
                                                            :id id
                                                            :error (:error response)})))
                              (update acc :errors conj {:index index
                                                        :error {:type :validation
                                                                :message "Bulk delete ids must be integers"
                                                                :errors [{:field :id
                                                                          :message "id must be an integer"}]}})))
                          {:deleted []
                           :errors []}
                          (map-indexed vector ids))]
       (bulk-summary :deleted (:deleted result) (:errors result)))
     (error-result :validation
                   "Bulk delete body must contain a list of ids"
                   [{:field :ids :message "expected a list"}]))))
