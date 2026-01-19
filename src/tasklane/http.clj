(ns tasklane.http
  (:require
   [clojure.string :as str]
   [reitit.ring :as ring]
   [ring.adapter.jetty :as jetty]
   [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
   [ring.middleware.params :refer [wrap-params]]
   [tasklane.service :as service]))

(defn- ok
  [body]
  {:status 200 :body body})

(defn- created
  [body]
  {:status 201 :body body})

(defn- bad-request
  [body]
  {:status 400 :body body})

(defn- not-found
  [body]
  {:status 404 :body body})

(defn- parse-id
  [value]
  (try
    (Long/parseLong value)
    (catch Exception _ nil)))

(defn- parse-int
  [value]
  (try
    (Integer/parseInt value)
    (catch Exception _ nil)))

(defn- keywordize-status
  [status]
  (when (and status (not (str/blank? status)))
    (keyword status)))

(defn- list-query
  [params]
  (let [status (keywordize-status (get params "status"))
        limit (some-> (get params "limit") parse-int)
        offset (some-> (get params "offset") parse-int)
        errors (cond-> []
                 (and (some? limit) (neg? limit))
                 (conj {:field :limit :message "limit must be >= 0"})
                 (and (some? offset) (neg? offset))
                 (conj {:field :offset :message "offset must be >= 0"}))]
    (if (seq errors)
      {:error {:type :validation
               :message "Invalid query params"
               :errors errors}}
      {:ok {:status status :limit limit :offset offset}})))

(defn- handle-create
  [req]
  (let [result (service/create-task (:body req))]
    (if-let [task (:ok result)]
      (created task)
      (bad-request (:error result)))))

(defn- handle-list
  [req]
  (let [query (list-query (:query-params req))]
    (if-let [filters (:ok query)]
      (ok (service/list-tasks filters))
      (bad-request (:error query)))))

(defn- handle-get
  [id]
  (if-let [task (service/get-task id)]
    (ok task)
    (not-found {:type :not-found
                :message "Task not found"
                :errors [{:field :id :message "no task for that id"}]})))

(defn- handle-update
  [id req]
  (let [result (service/update-task id (:body req))]
    (cond
      (:ok result) (ok (:ok result))
      (= :not-found (get-in result [:error :type])) (not-found (:error result))
      :else (bad-request (:error result)))))

(defn- handle-delete
  [id]
  (let [result (service/delete-task id)]
    (if-let [task (:ok result)]
      (ok task)
      (not-found (:error result)))))

(def app
  (let [handler
        (ring/ring-handler
         (ring/router
          [["/health" {:get (fn [_] (ok {:status "OK"}))}]
           ["/tasks"
            {:get handle-list
             :post handle-create}]
           ["/tasks/:id"
            {:get (fn [req]
                    (if-let [id (parse-id (get-in req [:path-params :id]))]
                      (handle-get id)
                      (bad-request {:type :validation
                                    :message "Invalid task id"
                                    :errors [{:field :id :message "id must be an integer"}]})))
             :patch (fn [req]
                      (if-let [id (parse-id (get-in req [:path-params :id]))]
                        (handle-update id req)
                        (bad-request {:type :validation
                                      :message "Invalid task id"
                                      :errors [{:field :id :message "id must be an integer"}]})))
             :delete (fn [req]
                       (if-let [id (parse-id (get-in req [:path-params :id]))]
                         (handle-delete id)
                         (bad-request {:type :validation
                                       :message "Invalid task id"
                                       :errors [{:field :id :message "id must be an integer"}]})))}]]))]
    (-> handler
        (wrap-params)
        (wrap-json-body {:keywords? true
                         :malformed-response {:status 400
                                              :body {:type :validation
                                                     :message "Malformed JSON"}}})
        (wrap-json-response))))

(defonce server (atom nil))

(defn start
  "Start the HTTP server on port 3000."
  []
  (reset! server (jetty/run-jetty app {:port 3000 :join? false})))
