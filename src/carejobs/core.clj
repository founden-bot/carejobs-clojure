(ns carejobs.core
  "Client for the CareJobs.Now healthcare jobs API.

  Search open US healthcare jobs, see the hourly pay employers post, and look
  up employers and states. The API is a remote MCP server (JSON-RPC over HTTP)
  that needs no key: https://carejobs.now/api/mcp

  Make a client with `client`, then call the functions with it as the first
  argument. Every function returns the tool's data as a map with the keys the
  API sends (keywords such as :total, :jobs, :open_jobs), and the server's
  readable summary is the `summary` of the result. Every failure throws an
  `ex-info` whose data has :type :carejobs/error, a :code (:tool, :rpc, :http,
  :network, :timeout or :protocol) and, where they apply, :tool, :status and
  :rpc-code.

  Data from CareJobs.Now (https://carejobs.now), CC BY 4.0."
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import (java.net URI)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest HttpRequest$BodyPublishers
                          HttpResponse HttpResponse$BodyHandlers HttpTimeoutException)
           (java.time Duration)))

(def version "1.0.0")

(def default-base-url "https://carejobs.now")

;; The server answers a query it has not seen before in up to about 25 seconds
;; (its database may be waking up), then repeats of it in a fraction of one.
(def default-timeout-ms 60000)

(def kinds
  "The kinds of job the API knows, in the order it documents them."
  ["nurses" "nurse-practitioners" "physicians" "nursing-assistants" "caregivers"
   "therapy-rehab" "mental-health" "pharmacy" "imaging" "lab" "surgical-techs"
   "emts-paramedics" "medical-assistants" "dental" "admin" "allied-health"
   "ai-training"])

(def sorts "The sort orders of `search-jobs`." ["mixed" "newest" "highest_pay"])

(def employer-types "The types of entry `employers` can list." ["employer" "ai-program" "job-board"])

(defn- fail
  ([code message] (fail code message nil nil))
  ([code message data] (fail code message data nil))
  ([code message data cause]
   (throw (ex-info message
                   (merge {:type :carejobs/error :code code} data)
                   cause))))

;; HTTP ----------------------------------------------------------------------

(defn- default-transport
  "Sends one POST with the JDK's HttpClient. Returns {:status :content-type :body}."
  [^HttpClient http url payload headers timeout-ms]
  (let [builder (-> (HttpRequest/newBuilder (URI/create url))
                    (.timeout (Duration/ofMillis timeout-ms))
                    (.POST (HttpRequest$BodyPublishers/ofString payload)))]
    (doseq [[k v] headers] (.header builder (str k) (str v)))
    (try
      (let [^HttpResponse response (.send http (.build builder) (HttpResponse$BodyHandlers/ofString))]
        {:status (.statusCode response)
         :content-type (.orElse (.firstValue (.headers response) "content-type") "")
         :body (.body response)})
      (catch HttpTimeoutException e
        (fail :timeout (str "No answer from " url " within " timeout-ms " ms") nil e))
      (catch java.io.IOException e
        (fail :network (str "Could not reach " url ": " (.getMessage e)) nil e))
      (catch InterruptedException e
        (.interrupt (Thread/currentThread))
        (fail :network (str "The request to " url " was interrupted") nil e)))))

(defn client
  "Makes a client. Options (all optional):

    :base-url    the host, default \"https://carejobs.now\"; the endpoint is <base-url>/api/mcp
    :timeout-ms  milliseconds a request may take, default 60000
    :user-agent  default \"carejobs-clojure/<version>\"
    :transport   replaces the HTTP call (for tests): (fn [url payload headers timeout-ms])
                 returning {:status :content-type :body}

  A client is a plain map, safe to share between threads."
  ([] (client {}))
  ([{:keys [base-url timeout-ms user-agent transport]
     :or {base-url default-base-url timeout-ms default-timeout-ms}}]
   (let [base (str/replace (str base-url) #"/+$" "")]
     (when-not (re-find #"^https?://[^/\s]+" base)
       (throw (IllegalArgumentException.
               (str "base-url must be an http:// or https:// address, not " base))))
     (when-not (and (number? timeout-ms) (pos? timeout-ms))
       (throw (IllegalArgumentException. "timeout-ms must be a number above 0")))
     (let [http (-> (HttpClient/newBuilder)
                    (.followRedirects HttpClient$Redirect/NORMAL)
                    (.connectTimeout (Duration/ofMillis (min timeout-ms 30000)))
                    (.build))]
       {:base-url base
        :endpoint (str base "/api/mcp")
        :timeout-ms (long timeout-ms)
        :user-agent (or user-agent (str "carejobs-clojure/" version))
        :transport (or transport (partial default-transport http))
        :next-id (atom 0)}))))

;; JSON-RPC ------------------------------------------------------------------

(defn- decode-object [text tool]
  (let [value (try (json/read-str text :key-fn keyword)
                   (catch Exception _
                     (fail :protocol "The server did not answer with JSON" {:tool tool})))]
    (if (map? value)
      value
      (fail :protocol "The server did not answer with a JSON-RPC object" {:tool tool}))))

(defn- sse-events [body]
  (str/split (str/replace body "\r\n" "\n") #"\n\n"))

(defn- event-data [event]
  (->> (str/split event #"\n")
       (filter #(str/starts-with? % "data:"))
       (map #(let [rest (subs % 5)] (if (str/starts-with? rest " ") (subs rest 1) rest)))
       (str/join "\n")))

(defn- parse-message
  "Reads the JSON-RPC message out of a plain JSON body or a text/event-stream body."
  [body content-type id tool]
  (if-not (str/includes? (str/lower-case (or content-type "")) "text/event-stream")
    (decode-object body tool)
    (let [messages (->> (sse-events body)
                        (map event-data)
                        (remove str/blank?)
                        (keep #(try (decode-object % tool) (catch Exception _ nil))))]
      (or (first (filter #(= id (:id %)) messages))
          (first (filter #(or (contains? % :result) (contains? % :error)) messages))
          (fail :protocol "The event stream held no JSON-RPC answer" {:tool tool})))))

(defn- http-error-detail [status body content-type id]
  (let [detail (str "HTTP " status)
        message (try (get-in (parse-message body content-type id nil) [:error :message])
                     (catch Exception _ nil))]
    (cond
      (and (string? message) (not (str/blank? message))) (str detail ": " message)
      (str/blank? body) detail
      :else (let [text (str/trim body)]
              (str detail ": " (subs text 0 (min 200 (count text))))))))

(defn- rpc
  "Sends one JSON-RPC request and returns the result of its answer."
  [{:keys [endpoint timeout-ms user-agent transport next-id]} method params tool]
  (let [id (swap! next-id inc)
        payload (json/write-str {:jsonrpc "2.0" :id id :method method :params params})
        headers {"Content-Type" "application/json"
                 "Accept" "application/json, text/event-stream"
                 "User-Agent" user-agent}
        {:keys [status content-type body]} (transport endpoint payload headers timeout-ms)
        body (or body "")]
    (when-not (<= 200 status 299)
      (fail :http (http-error-detail status body content-type id) {:status status :tool tool}))
    (let [message (parse-message body content-type id tool)
          error (:error message)]
      (when (some? error)
        (if (map? error)
          (fail :rpc (let [m (:message error)] (if (and (string? m) (seq m)) m "JSON-RPC error"))
                {:rpc-code (when (integer? (:code error)) (:code error)) :tool tool})
          (fail :protocol "Malformed JSON-RPC error" {:tool tool})))
      (let [result (:result message)]
        (when-not (map? result)
          (fail :protocol "The answer held no result" {:tool tool}))
        result))))

(defn summary
  "The readable plain-text summary the server wrote for `result`, handy to show
  a person or to hand to a language model."
  [result]
  (:summary (meta result)))

(defn call-tool
  "Calls any tool by name, for a tool added after this version. `arguments` keep
  the API's names; nil values are left out. Keyword and symbol values become
  their names. The functions below use this."
  ([client name] (call-tool client name {}))
  ([client name arguments]
   (let [args (->> arguments
                   (remove (comp nil? val))
                   (map (fn [[k v]] [(clojure.core/name k) (if (or (keyword? v) (symbol? v)) (clojure.core/name v) v)]))
                   (into {}))
         raw (rpc client "tools/call" {:name name :arguments args} name)
         text (->> (:content raw)
                   (filter #(and (map? %) (= "text" (:type %)) (string? (:text %))))
                   (map :text)
                   (str/join "\n"))]
     (when (true? (:isError raw))
       (fail :tool (if (str/blank? text) (str name " failed") text) {:tool name}))
     (let [data (:structuredContent raw)]
       (when-not (map? data)
         (fail :protocol (str name " answered without structured content") {:tool name}))
       (with-meta data {:summary text})))))

;; The six tools ---------------------------------------------------------------

(defn search-jobs
  "Searches the open healthcare jobs. Every key of `params` is optional:

    :query           words every job must carry in its title, place, department or schedule
    :kind            a kind of job, one of `kinds` (a string or a keyword)
    :employer        an employer slug (\"kaiser-permanente\") or name
    :state           a US state: \"TX\" or \"Texas\"
    :remote          true keeps only remote jobs
    :min-hourly-usd  only jobs whose posted hourly ceiling reaches this (1 to 1000)
    :sort            \"mixed\" (default), \"newest\" or \"highest_pay\"
    :limit           1 to 25 (default 10)

  The result has :total (every match), :pay, :jobs (the ones returned) and :more."
  ([client] (search-jobs client {}))
  ([client {:keys [query kind employer state remote min-hourly-usd sort limit]}]
   (call-tool client "search_healthcare_jobs"
              {:query query :kind kind :employer employer :state state
               :remote (when remote true) :min_hourly_usd min-hourly-usd
               :sort sort :limit limit})))

(defn pay
  "What healthcare jobs post as hourly pay, measured from open jobs (before shift
  differentials and bonuses). With no params: every kind of job. With :kind: that
  job's pay and the employers posting the most for it. With :employer: its pay
  overall and by kind. With both: that employer's pay for that kind."
  ([client] (pay client {}))
  ([client {:keys [kind employer]}]
   (call-tool client "get_healthcare_pay" {:kind kind :employer employer})))

(defn pay-by-kind
  "The pay of one kind of job and the employers posting the most for it."
  [client kind]
  (pay client {:kind kind}))

(defn job-market
  "How many healthcare jobs are open, how many were first listed in the last 7
  days, and the open jobs by kind of job, by state and by employer."
  [client]
  (call-tool client "get_healthcare_job_market" {}))

(defn jobs-by-state
  "Open jobs by US state. With no params: every state. With :state: its jobs by
  kind, pay, towns, employers and newest jobs. With :state and :kind: the same
  for that kind (a kind needs a state). :limit (1 to 25) is how many of the
  newest jobs to return."
  ([client] (jobs-by-state client {}))
  ([client {:keys [state kind limit]}]
   (call-tool client "get_healthcare_jobs_by_state" {:state state :kind kind :limit limit})))

(defn states
  "Alias of `jobs-by-state`: the states and their open jobs."
  ([client] (jobs-by-state client {}))
  ([client params] (jobs-by-state client params)))

(defn employers
  "The employers (and AI-training programs and job boards) the site reads, most
  open jobs first. Every key of `params` is optional:

    :query  part of the name (\"Kaiser\", \"children\")
    :type   one of `employer-types`
    :care   a kind of care, for example \"home-care\" or \"health-system\"
    :state  keep employers with open jobs in this state
    :kind   keep employers with open jobs of this kind
    :limit  1 to 100 (default 20)"
  ([client] (employers client {}))
  ([client {:keys [query type care state kind limit]}]
   (call-tool client "list_healthcare_employers"
              {:query query :type type :care care :state state :kind kind :limit limit})))

(defn employer
  "One employer, AI-training program or job board, by slug (\"kaiser-permanente\")
  or name: what it is, where it hires, its open jobs by kind, the pay it posts,
  its newest and best-paid jobs, and links to its pages."
  [client slug]
  (call-tool client "get_healthcare_employer" {:employer slug}))

(defn tools
  "The tools the server offers, with their input schemas."
  [client]
  (let [result (rpc client "tools/list" {} nil)]
    (when-not (sequential? (:tools result))
      (fail :protocol "tools/list answered without a tools list"))
    (vec (:tools result))))
