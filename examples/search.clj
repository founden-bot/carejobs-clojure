;; clojure -M examples/search.clj OH
(require '[carejobs.core :as carejobs])

(let [state (or (first *command-line-args*) "TX")
      client (carejobs/client)
      res (carejobs/search-jobs client {:kind :nurses :state state :limit 5})]
  (println (:total res) "nursing jobs in" state)
  (doseq [job (:jobs res)]
    (println (str (:title job) " | " (:employer job) " | " (:location job) " | " (:url job)))))
