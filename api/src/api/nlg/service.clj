(ns api.nlg.service
  (:require [api.nlg.core :refer [generate-text]]
            [api.nlg.service.request :as request]
            [api.nlg.service.response :as response]
            [api.nlg.service.utils :as utils]
            [api.utils :refer [gen-uuid]]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [data.spec.result :as result]
            [data.entities.reader-model :as reader-model]
            [data.entities.results :as results]
            [data.entities.user-group :as user-group]))

(s/def ::generate-request
  (s/keys :opt-un [::request/documentPlanId
                   ::request/documentPlanName
                   ::request/dataId
                   ::request/dataRow
                   ::request/sampleMethod
                   ::request/readerFlagValues
                   ::request/async]))

(s/def ::generate-request-bulk
  (s/keys :req-un [::request/dataRows]
          :opt-un [::request/documentPlanId
                   ::request/documentPlanName
                   ::request/readerFlagValues]))

(s/def ::generate-response
  (s/keys :req-un [::response/resultId]
          :opt-un [::response/offset
                   ::response/totalCount
                   ::response/ready
                   ::response/updatedAt
                   ::response/variants
                   ::response/error
                   ::response/message]))

(s/def ::generate-response-bulk
  (s/keys :req-un [::response/resultIds]))

(s/def ::get-result
  (s/keys :opt-un [::request/format]))

(defn generate-request
  ([request] (generate-request request {:group-id user-group/DUMMY-USER-GROUP-ID}))
  ([{data-id :dataId sample-method :sampleMethod data-row :dataRow reader-model :readerFlagValues async :async :as request :or {async true}}
    {group-id :group-id}]
   (try
     (log/infof "Generate request with %s" (utils/request->text request))
     (let [{row-index :dataSampleRow :as document-plan} (utils/get-document-plan request group-id)
           result-id (gen-uuid)
           body      {:id            result-id
                      :document-plan document-plan
                      :data          (or data-row (utils/get-data-row data-id (or sample-method "first") (or row-index 0) group-id) {})
                      :reader-model  (map #(reader-model/update! % group-id) (utils/form-reader-model reader-model group-id))}]
       (results/write #::result{:id     result-id
                                :status :pending})
       (if (true? async)
         (do
           (future (results/write (generate-text body)))
           {:status 200
            :body   {:resultId result-id}})
         (do
           (results/write (generate-text body))
           {:status 200
            :body   (utils/translate-result (results/fetch result-id) {:format "raw"} group-id)})))
     (catch Exception e
       (utils/error-response e "Generate request failure")))))

(defn generate-request-bulk
  ([request] (generate-request-bulk request {:group-id user-group/DUMMY-USER-GROUP-ID}))
  ([{reader-model :readerFlagValues data-rows :dataRows :as request} {group-id :group-id}]
   (try
     (log/infof "Bulk generate request with %s" (utils/request->text request))
     (let [document-plan (utils/get-document-plan request group-id)]
       (doseq [result-id (keys data-rows)]
         (results/write #::result{:id     result-id
                                  :status :pending}))
       (->> data-rows
            (pmap (fn [[request-id data-row]]
                    (-> {:id            request-id
                         :document-plan document-plan
                         :data          data-row
                         :reader-model  (utils/form-reader-model reader-model group-id)}
                        (generate-text)
                        (results/write))))
            (doall)
            (future))
       {:status 200
        :body   {:resultIds (keys data-rows)}})
     (catch Exception e
       (utils/error-response e "Bulk generate request failure")))))

(defn get-result [{{{request-id :id} :path {result-format :format} :query} :parameters {group-id :group-id} :auth-info}]
  (try
    (log/infof "Result request with id `%s`" request-id)
    (if-let [result (results/fetch request-id)]
      {:status 200
       :body   (utils/translate-result result {:format result-format} group-id)}
      (do
        (log/warnf "Result with id `%s` not found" request-id)
        {:status 404}))
    (catch Exception e
      (utils/error-response e (format "Failed to get result with id `%s`" request-id)))))

(defn delete-result [{{request-id :id} :path-params {group-id :group-id} :auth-info}]
  (try
    (log/infof "Delete result request with id `%s`" request-id)
    (if-let [item (results/fetch request-id)]
      (do
        (results/delete request-id)
        {:status 200
         :body   (utils/translate-result item {:format "raw"} group-id)})
      (do
        (log/warnf "Result with id `%s` not found" request-id)
        {:status 404}))
    (catch Exception e
      (utils/error-response e (format "Failed to delete result with id `%s`" request-id)))))
