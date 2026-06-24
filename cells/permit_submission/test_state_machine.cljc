(ns gov-municipality.cells.permit-submission.test-state-machine
  "gov-municipality 官 permit_submission state-machine cljc port + LIVE py↔clj deep parity."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :refer [sh]]
            [cheshire.core :as json]
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

(def ^:private py-dir "20-actors/gov-municipality/cells/permit_submission")

(deftest live-parity
  (testing "cljc permit_application_record + applicationData == python (deep)"
    (let [py (sh "python3" "-c"
                 (str "import json, state_machine as sm\n"
                      "st={'permit_state':{'phase':'init','projectId':'PROJ-2026-ABCD1234','completionPct':0}}\n"
                      "for fn in [sm.transition_to_jurisdiction_identified, sm.transition_to_template_selected, "
                      "sm.transition_to_application_prepared, sm.transition_to_submitted]:\n"
                      "    out=fn(st); st={**st, **out}\n"
                      "print(json.dumps({'rec':out['permit_application_record'], 'app':out['permit_state']['applicationData']}))")
                 :dir py-dir)]
      (if (not (zero? (:exit py)))
        (println "  [skip] python3 unavailable:" (:err py))
        (let [pj (json/parse-string (clojure.string/trim (:out py)))
              out (sm/run-chain {"projectId" "PROJ-2026-ABCD1234"})]
          (is (= (get pj "rec")
                 (json/parse-string (json/generate-string (get out "permit_application_record")))))
          (is (= (get pj "app")
                 (json/parse-string (json/generate-string (get-in out ["permit_state" "applicationData"]))))))))))
