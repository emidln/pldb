(ns pldb.logic
  (:require [clojure.core.logic :as l]))


;; ----------------------------------------
(def ^:dynamic *logic-dbs* [])

(def empty-db {})


(defmacro with-dbs [dbs & body]
  `(binding [*logic-dbs* (concat *logic-dbs* ~dbs)]
          ~@body))

(defmacro with-db [db & body]
  `(binding [*logic-dbs* (conj *logic-dbs* ~db)]
          ~@body))

(defn facts-for [kname]
  (mapcat #(get-in % [kname ::unindexed])
          *logic-dbs*))

(defn facts-using-index [kname index val]
  (mapcat #(get-in % [kname index val])
          *logic-dbs*))

;; ----------------------------------------
(defn rel-key [rel]
  (if (keyword? rel)
    rel
    (:rel-name (meta rel))))

(defn rel-indexes [rel]
  (:indexes (meta rel)))

(defn indexed? [v]
  (true? (:index (meta v))))

(defn ground? [s term]
  (not (l/contains-lvar? (l/walk* s term))))

(defn index-for-query [s q indexes]
  (let [indexable (map #(ground? s %)  q)
        triples (map vector (range) indexable indexes)]
    (first (for [[i indexable indexed] triples
                 :when (and indexable indexed)]
             i))))

(defmacro db-rel [name & args]
  (let [arity
        (count args)

        kname
        (str name "_" arity)

        indexes
        (vec (map indexed? args))]
    `(def ~name
       (with-meta
         (fn [& query#]
           (fn [subs#]
             (let [facts#
                   (if-let [index# (index-for-query subs# query# ~indexes)]
                     (facts-using-index ~kname
                                        index#
                                        (l/walk* subs# (nth query# index#)))
                     (facts-for ~kname))]
               (l/to-stream (map (fn [potential#]
                                   (l/unify subs# query# potential#))
                                 facts#)))))
         {:rel-name ~kname
          :indexes ~indexes}))))

;; ----------------------------------------

(defn db-fact [db rel & args]
  (let [key
        (rel-key rel)

        add-to-set
        (fn [current new]
          (conj (or current #{}) new))

        db-with-fact
        (update-in db [key ::unindexed] #(add-to-set %1 args))

        indexes-to-update ;; ugly - get the vector indexes of indexed attributes
        (map vector (rel-indexes rel) (range) args)

        update-index-fn
        (fn [db [is-indexed index-num val]]
          (if is-indexed
            (update-in db [key index-num val] #(add-to-set %1 args))
            db))]
    (reduce update-index-fn db-with-fact indexes-to-update)))

(defn db-retraction [db rel & args]
  (let [key
        (rel-key rel)

        retract-args
        #(disj %1 args)

        db-without-fact
        (update-in db [key ::unindexed] retract-args)

        indexes-to-update ;; also a bit ugly
        (map vector (rel-indexes rel) (range) args)

        remove-from-index-fn
        (fn [db [is-indexed index-num val]]
          (if is-indexed
            (update-in db [key index-num val] retract-args)
            db))]

    (reduce remove-from-index-fn db-without-fact indexes-to-update)))

;; ----------------------------------------
(defn db-facts [base-db & facts]
  (reduce #(apply db-fact %1 %2) base-db facts))

(defn db [& facts]
  (apply db-facts empty-db facts))

(defn db-retractions [base-db & retractions]
  (reduce #(apply db-retraction %1 %2) base-db retractions))

