(ns data.datomic.entities.user-group
  (:require [data.spec.user-group :as user-group]
            [data.datomic.entities.dictionary :as dict-entity]
            [data.datomic.entities.data-files :as data-file-entity]
            [data.datomic.entities.reader-model :as reader-model-entity]
            [datomic.api :as d]))

(def pattern [::user-group/id
              {::user-group/data-files data-file-entity/pattern}
              ::user-group/document-plans
              {::user-group/dictionary-items dict-entity/pattern}
              {::user-group/reader-models reader-model-entity/pattern}])

(defn pull-entity [conn key]
  (d/pull (d/db conn) pattern [::user-group/id key]))

(defn update! [conn key data]
  @(d/transact conn [(assoc data ::user-group/id key)])
  (pull-entity conn key))

(defn transact-item [conn key data]
  @(d/transact conn [(assoc data ::user-group/id key)])
  (pull-entity conn key))

(defn make-pattern [entities]
  (vec (conj
        (filter #(contains? entities (cond-> % (map? %) (-> (keys) (first))))
                (rest pattern))
        (first pattern))))

(defn scan [conn {:keys [group-id entities]}]
  (ffirst (d/q '[:find (pull ?e pattern)
                 :in $ ?group-id pattern
                 :where [?e ::user-group/id ?group]
                 [(= ?group-id ?group)]]
               (d/db conn) group-id (make-pattern entities))))
