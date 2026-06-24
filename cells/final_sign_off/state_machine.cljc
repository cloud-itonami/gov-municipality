(ns gov-municipality.cells.final-sign-off.state-machine
  "1:1 cljc port of cells/final_sign_off/cell.py (ADR-2605250800).
  Validate inspections → request authority signature → emit occupancy clearance.
  String keys mirror the Python dataclass __dict__.")

(defn- s* [state] (get state "signoff_state" {}))

(defn initialize-state [state]
  {"signoff_state" {"phase" "init"
                    "projectId" (get state "projectId" "unknown")
                    "completionPct" 0}
   "next_node" "validate"})

(defn validate-inspections [state]
  {"signoff_state" (assoc (s* state)
                          "phase" "inspections_validated"
                          "completionPct" 40)
   "next_node" "request"})

(defn request-authority-signature [state]
  (let [mock-sig {"authority_did" "did:web:tokyo.lg.jp:building"
                  "signature" "aB3cD6eF9gH..."
                  "occupancy_clearance" true}]
    {"signoff_state" (assoc (s* state)
                             "phase" "signed"
                             "signature" mock-sig
                             "completionPct" 100)
     "next_node" "emit"}))

(defn emit-occupancy-clearance [state]
  (let [s (s* state)]
    {"signoff_state" s
     "permits_finalized_record" {"projectId" (get s "projectId")
                                 "occupancy_clearance" true
                                 "authority_signature" (get s "signature" {})}
     "next_node" "end"}))

(defn run-chain [input-state]
  (reduce (fn [s f] (merge s (f s)))
          input-state
          [initialize-state validate-inspections request-authority-signature emit-occupancy-clearance]))
