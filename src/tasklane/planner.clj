(ns tasklane.planner
  (:require [clojure.string :as str]))

(defn- to-instant
  [value]
  (cond
    (instance? java.time.Instant value) value
    (string? value) (try
                      (java.time.Instant/parse value)
                      (catch Exception _ nil))
    :else nil))

(defn- status-key
  [status]
  (cond
    (keyword? status) status
    (string? status) (keyword (str/lower-case status))
    :else nil))

(defn- hours-between
  [from to]
  (/ (double (.toMinutes (java.time.Duration/between from to))) 60.0))

(defn task-overdue?
  "Return true if the task is overdue and not done."
  ([task]
   (task-overdue? task (java.time.Instant/now)))
  ([task now]
   (let [now (to-instant now)
         due (to-instant (:due-at task))
         status (status-key (:status task))]
     (and now due (not= status :done) (neg? (hours-between now due))))))

(defn prioritize
  "Rank tasks by urgency and return a vector of {:task :score :reasons}."
  ([tasks]
   (prioritize tasks {}))
  ([tasks {:keys [now limit]}]
   (let [now (or (to-instant now) (java.time.Instant/now))
         limit (when limit (max 0 limit))
         scored (->> tasks
                     (keep (fn [task]
                             (let [status (status-key (:status task))]
                               (when (not= status :done)
                                 (let [priority (let [p (:priority task)]
                                                  (if (number? p) (int p) 3))
                                       priority (-> priority (max 1) (min 5))
                                       base (* 10 priority)
                                       reasons []
                                       score base
                                       reasons (cond-> reasons
                                                 (= status :in-progress)
                                                 (conj "in progress"))
                                       score (if (= status :in-progress)
                                               (+ score 5)
                                               score)
                                       due (to-instant (:due-at task))
                                       due-hours (when due (hours-between now due))
                                       [score reasons]
                                       (cond
                                         (and due (neg? due-hours))
                                         [(+ score 100) (conj reasons "overdue")]
                                         (and due (<= due-hours 24))
                                         [(+ score 50) (conj reasons "due within 24h")]
                                         (and due (<= due-hours 72))
                                         [(+ score 20) (conj reasons "due within 72h")]
                                         due
                                         [score (conj reasons "due later")]
                                         :else
                                         [score reasons])
                                       created (to-instant (:created-at task))
                                       age-hours (when created (hours-between created now))
                                       age-score (if (and age-hours (pos? age-hours))
                                                   (min 10 (int (/ age-hours 24)))
                                                   0)
                                       reasons (cond-> reasons
                                                 (pos? age-score)
                                                 (conj "aging task"))]
                                   {:task task
                                    :score (+ score age-score)
                                    :reasons reasons}))))))]
     (cond-> (->> scored
                  (sort-by :score >)
                  vec)
       (some? limit) (subvec 0 (min limit (count scored)))))))

(defn workload-report
  "Summarize workload risk across tasks."
  ([tasks]
   (workload-report tasks {}))
  ([tasks {:keys [now horizon-hours]}]
   (let [now (or (to-instant now) (java.time.Instant/now))
         horizon-hours (or horizon-hours 48)]
     (reduce
      (fn [acc task]
        (let [status (status-key (:status task))
              due (to-instant (:due-at task))
              due-hours (when due (hours-between now due))
              acc (update acc :total inc)
              acc (update acc status (fnil inc 0))]
          (cond
            (and due (neg? due-hours) (not= status :done))
            (update acc :overdue inc)
            (and due (<= due-hours horizon-hours) (not= status :done))
            (update acc :due-soon inc)
            :else acc)))
      {:total 0
       :pending 0
       :in-progress 0
       :done 0
       :overdue 0
       :due-soon 0}
      tasks))))
