(ns gov-municipality.cells.permit-submission.test-state-machine
  "gov-municipality 官 permit_submission state-machine CLJC tests."
  (:require [clojure.test :refer [deftest is]]
            [gov-municipality.cells.permit-submission.state-machine :as sm]))

(deftest chain-reaches-submitted-at-100pct
  (let [out (sm/run-chain {"projectId" "PROJ-2026-ABCD1234"})]
    (is (= "submitted" (get-in out ["permit_state" "phase"])))
    (is (= 100 (get-in out ["permit_state" "completionPct"])))
    (is (= "end" (get out "next_node")))
    (is (contains? out "permit_application_record"))))

(deftest permit-id-uses-last-8-of-project-id
  (is (= "TOKYO-2026-ABCD1234"
         (get-in (sm/run-chain {"projectId" "PROJ-2026-ABCD1234"})
                 ["permit_application_record" "permitApplicationId"])))
  ;; short projectId (< 8 chars) → whole string (Python s[-8:] semantics)
  (is (= "TOKYO-2026-unknown"
         (get-in (sm/run-chain {}) ["permit_application_record" "permitApplicationId"]))))

(deftest application-data-accumulates
  (let [ad (get-in (sm/run-chain {"projectId" "P12345678"}) ["permit_state" "applicationData"])]
    (is (= "japan-tokyo-residential-2026" (get ad "template_id")))   ;; from template_selected
    (is (= "Developer" (get ad "applicant_name")))                   ;; merged in application_prepared
    (is (= "under_review" (get ad "status")))))                      ;; merged in submitted
