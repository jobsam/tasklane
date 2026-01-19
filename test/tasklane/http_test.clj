(ns tasklane.http-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer :all]
            [ring.mock.request :as mock]
            [tasklane.http :as http]
            [tasklane.service :as service]))

(defn- parse-json
  [response]
  (let [body (:body response)
        text (if (string? body) body (slurp body))]
    (json/read-str text :key-fn keyword)))

(use-fixtures :each
  (fn [f]
    (service/reset-tasks!)
    (f)))

(deftest health-check-test
  (let [resp (http/app (mock/request :get "/health"))
        body (parse-json resp)]
    (is (= 200 (:status resp)))
    (is (= "OK" (:status body)))))

(deftest create-and-list-tasks-test
  (let [req (-> (mock/request :post "/tasks" (json/write-str {:name "demo"}))
                (mock/content-type "application/json"))
        resp (http/app req)
        created (parse-json resp)
        list-resp (http/app (mock/request :get "/tasks"))
        list-body (parse-json list-resp)]
    (is (= 201 (:status resp)))
    (is (= "demo" (:name created)))
    (is (= 1 (count list-body)))))

(deftest task-lifecycle-test
  (let [create-req (-> (mock/request :post "/tasks" (json/write-str {:name "demo"}))
                       (mock/content-type "application/json"))
        create-resp (http/app create-req)
        created (parse-json create-resp)
        id (:id created)
        get-resp (http/app (mock/request :get (str "/tasks/" id)))
        update-req (-> (mock/request :patch (str "/tasks/" id)
                                     (json/write-str {:status "done"}))
                       (mock/content-type "application/json"))
        update-resp (http/app update-req)
        update-body (parse-json update-resp)
        delete-resp (http/app (mock/request :delete (str "/tasks/" id)))
        missing-resp (http/app (mock/request :get (str "/tasks/" id)))]
    (is (= 200 (:status get-resp)))
    (is (= 200 (:status update-resp)))
    (is (= "done" (:status update-body)))
    (is (= 200 (:status delete-resp)))
    (is (= 404 (:status missing-resp)))))

(deftest list-filters-test
  (let [reqs [(mock/request :post "/tasks" (json/write-str {:name "a"}))
              (mock/request :post "/tasks" (json/write-str {:name "b" :status "done"}))
              (mock/request :post "/tasks" (json/write-str {:name "c" :status "done"}))]
        _ (doseq [req reqs]
            (http/app (mock/content-type req "application/json")))
        list-resp (http/app (mock/request :get "/tasks?status=done&limit=1&offset=1"))
        list-body (parse-json list-resp)]
    (is (= 200 (:status list-resp)))
    (is (= 1 (count list-body)))
    (is (= "c" (:name (first list-body))))))
