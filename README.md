# carejobs-clojure

Clojure client for the [CareJobs.Now](https://carejobs.now) healthcare jobs API. Search open US healthcare jobs, see the hourly pay employers post, and look up employers and states.

- Free, no API key.
- Clojure 1.10 or newer on Java 11 or newer. Uses the JDK's `java.net.http` and `org.clojure/data.json`.
- Plain maps in, plain maps out. Every failure is an `ex-info` with `:type :carejobs/error`.
- The same API is a remote MCP server at <https://carejobs.now/api/mcp>, so any MCP client can use it too.

## Where the data comes from

CareJobs.Now is a free US healthcare job board. Its jobs are read daily from the career sites of US hospitals, health systems, clinics and care employers, and the pay is what each posting states (hourly rates before shift differentials and bonuses). Every job links to its page on CareJobs.Now, where the user applies. Data and docs: <https://carejobs.now/data>.

Data from CareJobs.Now (https://carejobs.now), CC BY 4.0.

## Install

Leiningen or Boot:

```clojure
[org.clojars.carejobs/carejobs "1.0.0"]
```

deps.edn:

```clojure
org.clojars.carejobs/carejobs {:mvn/version "1.0.0"}
```

## Quick start

```clojure
(require '[carejobs.core :as carejobs])

(def client (carejobs/client))

(def jobs (carejobs/search-jobs client {:query "ICU" :kind :nurses :state "TX" :limit 5}))

(println (:total jobs) "jobs match")
(doseq [job (:jobs jobs)]
  (println (:title job) "|" (:employer job) "|" (:location job) "|" (:url job)))

(def pay (carejobs/pay-by-kind client :nurses))
(println "Median posted pay for nurses: $" (get-in pay [:pay :median]) "/hr")

;; Every answer also carries the server's readable summary:
(println (carejobs/summary pay))
```

A runnable version is in [`examples/search.clj`](examples/search.clj): `clojure -M examples/search.clj OH`.

## Functions

Every function takes the client first and returns the tool's data as a map. The keys are keywords named as the API sends them (`:typical_low`, `:open_jobs`, and so on), and where the API sends `null` the value is `nil`. `(carejobs/summary result)` is the readable summary the server writes (it is kept as metadata, so the map itself stays plain data). Kinds may be strings or keywords.

| Function | What it answers |
| --- | --- |
| `(search-jobs client {:query :kind :employer :state :remote :min-hourly-usd :sort :limit})` | Open jobs. Keys: `:total`, `:pay`, `:jobs`, `:more`. `:limit` is 1 to 25. `:sort` is `"mixed"`, `"newest"` or `"highest_pay"`. |
| `(pay client {:kind :employer})` | Posted hourly pay: every kind, one kind with the employers posting the most, one employer, or both. |
| `(pay-by-kind client kind)` | `pay` for one kind of job. |
| `(job-market client)` | How many jobs are open, new in the last 7 days, and the open jobs by kind, state and employer. |
| `(jobs-by-state client {:state :kind :limit})` | Every state, one state, or one kind of job in one state (a kind needs a state). |
| `(states client {:state :kind :limit})` | Alias of `jobs-by-state`. |
| `(employers client {:query :type :care :state :kind :limit})` | The employers, AI-training programs and job boards the site reads. `:limit` is 1 to 100. |
| `(employer client slug)` | One employer by slug (`"kaiser-permanente"`) or name. |
| `carejobs/kinds` | The 17 kinds of job as slugs. |
| `(tools client)` | The server's tools with their input schemas. |
| `(call-tool client name arguments)` | Any tool, for a tool this version does not know yet. |

```clojure
(def ohio (carejobs/states client {:state "OH" :kind :nursing-assistants}))
(println (:open_jobs ohio) "CNA jobs in Ohio")

(doseq [e (:entries (carejobs/employers client {:query "Kaiser" :limit 3}))]
  (println (:name e) (str "(" (:open_jobs e) " open jobs)")))
```

### Options

```clojure
(carejobs/client {:base-url "https://carejobs.now"   ; default
                  :timeout-ms 60000                  ; default
                  :user-agent "my-app/1.0"})         ; default "carejobs-clojure/<version>"
```

The server answers a query it has not seen before in up to about 25 seconds, then repeats of it in a fraction of one, which is why the default timeout is a minute.

### Errors

Every failure throws an `ex-info` whose `ex-data` has `:type :carejobs/error` and a `:code`:

```clojure
(try
  (carejobs/jobs-by-state client {:kind :nurses}) ; a kind without a state
  (catch clojure.lang.ExceptionInfo e
    (println (:code (ex-data e)) (ex-message e))))
;; :tool    the server ran the tool and said no
;; :rpc     a JSON-RPC error (:rpc-code)
;; :http    a non-2xx status (:status)
;; :network, :timeout   no answer
;; :protocol   the answer was not what MCP promises
```

### Kinds

`nurses`, `nurse-practitioners`, `physicians`, `nursing-assistants`, `caregivers`, `therapy-rehab`, `mental-health`, `pharmacy`, `imaging`, `lab`, `surgical-techs`, `emts-paramedics`, `medical-assistants`, `dental`, `admin`, `allied-health`, `ai-training`.

## Tests

```sh
clojure -M:test   # offline, with a fake transport
```

## Plain JSON

Without this library the same data is plain JSON: <https://carejobs.now/roles.json> (open jobs by kind of job and employer) and <https://carejobs.now/pay.json> (posted hourly pay by kind of job).

## License

Code: MIT, copyright O-mega Enterprise, Inc. Data: CC BY 4.0, credit CareJobs.Now.
