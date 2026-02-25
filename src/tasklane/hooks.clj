(ns tasklane.hooks)

(defn- coerce-handlers
  [handlers]
  (cond
    (nil? handlers) []
    (fn? handlers) [handlers]
    (sequential? handlers) (vec handlers)
    :else []))

(defn run-hooks
  "Run hook handlers for an event.
   Handlers receive a context map and may return a map to merge into it.
   If a handler returns {:error ...}, execution stops and the error is returned."
  [hooks event ctx]
  (let [handlers (coerce-handlers (get hooks event))]
    (reduce (fn [ctx handler]
              (if (:error ctx)
                (reduced ctx)
                (let [next (handler ctx)]
                  (if (map? next)
                    (merge ctx next)
                    ctx))))
            ctx
            handlers)))
