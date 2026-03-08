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
              (mock/request :post "/tasks" (json/write-str {:name "b" :status "done" :tags ["ops"]}))
              (mock/request :post "/tasks" (json/write-str {:name "c" :status "done" :tags ["ops"]}))]
        _ (doseq [req reqs]
            (http/app (mock/content-type req "application/json")))
        list-resp (http/app (mock/request :get "/tasks?status=done&limit=1&offset=1"))
        list-body (parse-json list-resp)
        tag-resp (http/app (mock/request :get "/tasks?tag=ops"))
        tag-body (parse-json tag-resp)]
    (is (= 200 (:status list-resp)))
    (is (= 1 (count list-body)))
    (is (= "c" (:name (first list-body))))
    (is (= 200 (:status tag-resp)))
    (is (= #{"b" "c"} (set (map :name tag-body))))))

(deftest bulk-api-test
  (let [bulk-create-req (-> (mock/request :post "/tasks/bulk/create"
                                           (json/write-str {:tasks [{:name "a"}
                                                                    {:priority 3}
                                                                    {:name "c"}]}))
                            (mock/content-type "application/json"))
        bulk-create-resp (http/app bulk-create-req)
        bulk-create-body (parse-json bulk-create-resp)
        created-ids (map :id (:created bulk-create-body))
        bulk-update-req (-> (mock/request :patch "/tasks/bulk/update"
                                          (json/write-str {:updates [{:id (first created-ids)
                                                                      :changes {:status "done"}}
                                                                     {:id 9999
                                                                      :changes {:name "missing"}}
                                                                     {:id (second created-ids)
                                                                      :changes {:name "c2"}}]}))
                            (mock/content-type "application/json"))
        bulk-update-resp (http/app bulk-update-req)
        bulk-update-body (parse-json bulk-update-resp)
        bulk-delete-req (-> (mock/request :post "/tasks/bulk/delete"
                                          (json/write-str {:ids [(first created-ids) "bad"]}))
                            (mock/content-type "application/json"))
        bulk-delete-resp (http/app bulk-delete-req)
        bulk-delete-body (parse-json bulk-delete-resp)]
    (is (= 200 (:status bulk-create-resp)))
    (is (= 3 (:total bulk-create-body)))
    (is (= 2 (:succeeded bulk-create-body)))
    (is (= 1 (:failed bulk-create-body)))
    (is (= "validation" (get-in bulk-create-body [:errors 0 :error :type])))

    (is (= 200 (:status bulk-update-resp)))
    (is (= 3 (:total bulk-update-body)))
    (is (= 2 (:succeeded bulk-update-body)))
    (is (= 1 (:failed bulk-update-body)))
    (is (= "not-found" (get-in bulk-update-body [:errors 0 :error :type])))

    (is (= 200 (:status bulk-delete-resp)))
    (is (= 2 (:total bulk-delete-body)))
    (is (= 1 (:succeeded bulk-delete-body)))
    (is (= 1 (:failed bulk-delete-body)))
    (is (= "validation" (get-in bulk-delete-body [:errors 0 :error :type])))))
