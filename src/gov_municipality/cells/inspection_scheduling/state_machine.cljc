(ns gov-municipality.cells.inspection-scheduling.state-machine
  "1:1 cljc port of cells/inspection_scheduling/cell.py (ADR-2605250800).
  Fetch permit status → jurisdiction rules → emit inspection schedule.
  String keys mirror the Python dataclass __dict__.")

(defn- s* [state] (get state "inspection_state" {}))

(defn initialize-state [state]
  {"inspection_state" {"phase" "init"
                        "projectId" (get state "projectId" "unknown")
                        "completionPct" 0}
   "next_node" "fetch"})

(defn fetch-permit-status [state]
  {"inspection_state" (assoc (s* state)
                              "phase" "permit_verified"
                              "completionPct" 25)
   "next_node" "rules"})

(defn jurisdiction-rules [state]
  (let [mock-schedule {"foundation_inspection" "2026-06-20"
                       "structural_inspection" "2026-07-15"
                       "mep_inspection" "2026-08-10"
                       "finishing_inspection" "2026-09-05"
                       "final_inspection" "2026-09-20"}]
    {"inspection_state" (assoc (s* state)
                                "phase" "schedule_ready"
                                "schedule" mock-schedule
                                "completionPct" 75)
     "next_node" "emit"}))

(defn emit-schedule [state]
  (let [s (assoc (s* state) "phase" "complete" "completionPct" 100)]
    {"inspection_state" s
     "inspection_schedule_record" {"projectId" (get s "projectId")
                                   "schedule" (get s "schedule" {})}
     "next_node" "end"}))

(defn run-chain [input-state]
  (reduce (fn [s f] (merge s (f s)))
          input-state
          [initialize-state fetch-permit-status jurisdiction-rules emit-schedule]))
