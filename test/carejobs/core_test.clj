(ns carejobs.core-test
  "Offline tests: the HTTP call is replaced by a fake transport, so nothing
  leaves the machine. Run with `clojure -M:test`."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [carejobs.core :as c]))

(defn- answer [structured & [text]]
  (json/write-str {:jsonrpc "2.0" :id 1
                   :result {:content [{:type "text" :text (or text "summary")}]
                            :structuredContent structured}}))

(defn- recording-client
  "A client whose transport records the request in `sent` and answers with `reply`."
  [sent reply]
  (c/client {:transport (fn [url payload headers timeout-ms]
                          (reset! sent {:url url :raw payload :payload (json/read-str payload :key-fn keyword)
                                        :headers headers :timeout-ms timeout-ms})
                          reply)}))

(defn- error-code [f]
  (try (f) :none
       (catch clojure.lang.ExceptionInfo e
         (when (= :carejobs/error (:type (ex-data e))) (:code (ex-data e))))))

(deftest search-jobs-test
  (let [sent (atom nil)
        cl (recording-client sent {:status 200 :content-type "application/json"
                                   :body (answer {:total 3 :jobs [{:title "RN"}]} "three jobs")})
        res (c/search-jobs cl {:query "ICU" :kind :nurses :state "TX" :limit 5 :remote false})]
    (is (= "https://carejobs.now/api/mcp" (:url @sent)))
    (is (= "tools/call" (get-in @sent [:payload :method])))
    (is (= "search_healthcare_jobs" (get-in @sent [:payload :params :name])))
    (is (= {:query "ICU" :kind "nurses" :state "TX" :limit 5} (get-in @sent [:payload :params :arguments])))
    (is (= 3 (:total res)))
    (is (= "RN" (get-in res [:jobs 0 :title])))
    (is (= "three jobs" (c/summary res)))))

(deftest other-tools-test
  (let [sent (atom nil)
        cl (recording-client sent {:status 200 :content-type "application/json" :body (answer {:ok true})})]
    (testing "empty arguments are an object"
      (c/job-market cl)
      (is (str/includes? (:raw @sent) "\"arguments\":{}")))
    (testing "pay by kind"
      (c/pay-by-kind cl "physicians")
      (is (= "get_healthcare_pay" (get-in @sent [:payload :params :name])))
      (is (= {:kind "physicians"} (get-in @sent [:payload :params :arguments]))))
    (testing "states"
      (c/states cl {:state "OH" :kind "nursing-assistants"})
      (is (= "get_healthcare_jobs_by_state" (get-in @sent [:payload :params :name])))
      (is (= {:state "OH" :kind "nursing-assistants"} (get-in @sent [:payload :params :arguments]))))
    (testing "employers"
      (c/employers cl {:query "Kaiser" :limit 3})
      (is (= "list_healthcare_employers" (get-in @sent [:payload :params :name])))
      (is (= {:query "Kaiser" :limit 3} (get-in @sent [:payload :params :arguments]))))
    (testing "employer"
      (c/employer cl "cvs-health")
      (is (= {:employer "cvs-health"} (get-in @sent [:payload :params :arguments]))))
    (testing "kinds"
      (is (= 17 (count c/kinds)))
      (is (= "ai-training" (last c/kinds))))))

(deftest event-stream-test
  (let [cl (c/client {:transport (fn [& _] {:status 200 :content-type "text/event-stream"
                                            :body (str "event: message\ndata: " (answer {:open_jobs 9}) "\n\n")})})]
    (is (= 9 (:open_jobs (c/job-market cl))))))

(deftest failure-test
  (let [reply (fn [status ct body] (c/client {:transport (fn [& _] {:status status :content-type ct :body body})}))]
    (is (= :tool (error-code #(c/jobs-by-state
                               (reply 200 "application/json"
                                      (json/write-str {:jsonrpc "2.0" :id 1
                                                       :result {:isError true :content [{:type "text" :text "bad state"}]}}))
                               {:state "ZZ"}))))
    (is (= :rpc (error-code #(c/job-market
                              (reply 200 "application/json"
                                     (json/write-str {:jsonrpc "2.0" :id 1 :error {:code -32601 :message "no such method"}}))))))
    (is (= :http (error-code #(c/job-market (reply 503 "text/plain" "down")))))
    (is (= :protocol (error-code #(c/job-market (reply 200 "text/html" "<html>")))))
    (is (= :protocol (error-code #(c/job-market
                                   (reply 200 "application/json"
                                          (json/write-str {:jsonrpc "2.0" :id 1 :result {:content []}}))))))
    (is (= "HTTP 503: down"
           (try (c/job-market (reply 503 "text/plain" "down"))
                (catch clojure.lang.ExceptionInfo e (ex-message e)))))
    (is (thrown? IllegalArgumentException (c/client {:base-url "not a url"})))))
