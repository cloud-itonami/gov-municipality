(ns gov-municipality.cells.permit-submission.state-machine
  "Permit-submission state machine — ADR-2605250800. 1:1 cljc port of
  `cells/permit_submission/state_machine.py`. Identify jurisdiction → select
  template → prepare application (applicationData dict-merge) → submit. String keys
  mirror the Python dataclass __dict__ so the emitted record is byte-identical.")

(def phases
  {:init "init" :jurisdiction-identified "jurisdiction_identified" :template-selected "template_selected"
   :application-prepared "application_prepared" :submitted "submitted"})

(defn init [state]
  {"permit_state" {"phase" (:init phases)
                   "projectId" (get state "projectId" "unknown")
                   "completionPct" 0}})

(defn transition-to-jurisdiction-identified [state]
  (let [ps (-> (get state "permit_state" {})
               (assoc "jurisdiction" "Japan-Tokyo"
                      "siteLocation" {"jurisdiction_type" "Japan" "prefecture" "Tokyo"}
                      "phase" (:jurisdiction-identified phases) "completionPct" 20))]
    {"permit_state" ps "next_node" "template"}))

(defn transition-to-template-selected [state]
  (let [ps (-> (get state "permit_state" {})
               (assoc "buildingType" "residential"
                      "applicationData" {"template_id" "japan-tokyo-residential-2026"
                                         "building_type_enum" ["residential" "commercial"]
                                         "required_forms" ["Form1" "Form2"]}
                      "phase" (:template-selected phases) "completionPct" 40))]
    {"permit_state" ps "next_node" "prepare"}))

(defn transition-to-application-prepared [state]
  (let [ps0 (get state "permit_state" {})
        ps (assoc ps0 "applicationData" (merge (get ps0 "applicationData" {})
                                               {"applicant_name" "Developer" "site_address" "Tokyo" "gfa_m2" 2400})
                  "phase" (:application-prepared phases) "completionPct" 70)]
    {"permit_state" ps "next_node" "submit"}))

(defn- last8 [s] (subs s (max 0 (- (count s) 8))))

(defn transition-to-submitted [state]
  (let [ps0 (get state "permit_state" {})
        permit-id (str "TOKYO-2026-" (last8 (get ps0 "projectId")))
        mock-submission {"permitApplicationId" permit-id
                         "submissionDate" "2026-05-26T10:00:00Z"
                         "status" "under_review"}
        ps (assoc ps0 "permitApplicationId" permit-id
                  "submissionTimestamp" "2026-05-26T10:00:00Z"
                  "applicationData" (merge (get ps0 "applicationData") mock-submission)
                  "phase" (:submitted phases) "completionPct" 100)]
    {"permit_state" ps
     "permit_application_record" {"projectId" (get ps "projectId")
                                  "permitApplicationId" (get ps "permitApplicationId")}
     "next_node" "end"}))

(defn run-chain [input-state]
  (reduce (fn [s f] (f s))
          (merge input-state (init input-state))
          [transition-to-jurisdiction-identified transition-to-template-selected
           transition-to-application-prepared transition-to-submitted]))
