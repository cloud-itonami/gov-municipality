(ns gov-municipality.cells.final-sign-off.test-state-machine
  "Tests for gov-municipality final_sign_off state machine (ADR-2605250800)."
  (:require [clojure.test :refer [deftest is testing]]
            [gov-municipality.cells.final-sign-off.state-machine :as sm]))

(deftest chain-reaches-end-at-100pct
  (let [out (sm/run-chain {"projectId" "PROJ-2026-TEST"})]
    (testing "phase after full chain is 'signed' (last explicit phase)"
      (is (= "signed" (get-in out ["signoff_state" "phase"]))))
    (testing "completionPct reaches 100"
      (is (= 100 (get-in out ["signoff_state" "completionPct"]))))
    (testing "final next_node is end"
      (is (= "end" (get out "next_node"))))
    (testing "permits_finalized_record is emitted"
      (is (contains? out "permits_finalized_record")))))

(deftest project-id-propagates
  (let [out (sm/run-chain {"projectId" "BUILD-42"})]
    (is (= "BUILD-42" (get-in out ["permits_finalized_record" "projectId"])))))

(deftest occupancy-clearance-is-true
  (let [out (sm/run-chain {"projectId" "TEST-001"})]
    (is (true? (get-in out ["permits_finalized_record" "occupancy_clearance"])))))

(deftest authority-signature-present
  (let [out (sm/run-chain {"projectId" "TEST-002"})]
    (let [sig (get-in out ["permits_finalized_record" "authority_signature"])]
      (is (= "did:web:tokyo.lg.jp:building" (get sig "authority_did")))
      (is (true? (get sig "occupancy_clearance"))))))

(deftest initialize-state-defaults-project-id
  (let [out (sm/initialize-state {})]
    (is (= "unknown" (get-in out ["signoff_state" "projectId"])))))

(deftest individual-transitions-are-pure
  (testing "validate-inspections increments to 40"
    (let [state {"signoff_state" {"phase" "init" "projectId" "P1" "completionPct" 0}}
          out (sm/validate-inspections state)]
      (is (= "inspections_validated" (get-in out ["signoff_state" "phase"])))
      (is (= 40 (get-in out ["signoff_state" "completionPct"])))))
  (testing "request-authority-signature sets phase to signed at 100"
    (let [state {"signoff_state" {"phase" "inspections_validated" "projectId" "P1" "completionPct" 40}}
          out (sm/request-authority-signature state)]
      (is (= "signed" (get-in out ["signoff_state" "phase"])))
      (is (= 100 (get-in out ["signoff_state" "completionPct"]))))))
