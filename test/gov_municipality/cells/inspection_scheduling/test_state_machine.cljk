(ns gov-municipality.cells.inspection-scheduling.test-state-machine
  "Tests for gov-municipality inspection_scheduling state machine (ADR-2605250800)."
  (:require [clojure.test :refer [deftest is testing]]
            [gov-municipality.cells.inspection-scheduling.state-machine :as sm]))

(deftest chain-reaches-complete
  (let [out (sm/run-chain {"projectId" "PROJ-2026-SCHED"})]
    (testing "phase after full chain is complete"
      (is (= "complete" (get-in out ["inspection_state" "phase"]))))
    (testing "completionPct reaches 100"
      (is (= 100 (get-in out ["inspection_state" "completionPct"]))))
    (testing "next_node is end"
      (is (= "end" (get out "next_node"))))
    (testing "inspection_schedule_record is emitted"
      (is (contains? out "inspection_schedule_record")))))

(deftest project-id-propagates
  (let [out (sm/run-chain {"projectId" "SCHED-007"})]
    (is (= "SCHED-007" (get-in out ["inspection_schedule_record" "projectId"])))))

(deftest schedule-has-five-inspections
  (let [out (sm/run-chain {"projectId" "P1"})
        sched (get-in out ["inspection_schedule_record" "schedule"])]
    (is (= 5 (count sched)))
    (is (contains? sched "foundation_inspection"))
    (is (contains? sched "structural_inspection"))
    (is (contains? sched "mep_inspection"))
    (is (contains? sched "finishing_inspection"))
    (is (contains? sched "final_inspection"))))

(deftest initialize-state-defaults-project-id
  (let [out (sm/initialize-state {})]
    (is (= "unknown" (get-in out ["inspection_state" "projectId"])))
    (is (= 0 (get-in out ["inspection_state" "completionPct"])))))

(deftest individual-transitions-are-pure
  (testing "fetch-permit-status sets permit_verified at 25"
    (let [state {"inspection_state" {"phase" "init" "projectId" "P1" "completionPct" 0}}
          out (sm/fetch-permit-status state)]
      (is (= "permit_verified" (get-in out ["inspection_state" "phase"])))
      (is (= 25 (get-in out ["inspection_state" "completionPct"])))))
  (testing "jurisdiction-rules sets schedule_ready at 75"
    (let [state {"inspection_state" {"phase" "permit_verified" "projectId" "P1" "completionPct" 25}}
          out (sm/jurisdiction-rules state)]
      (is (= "schedule_ready" (get-in out ["inspection_state" "phase"])))
      (is (= 75 (get-in out ["inspection_state" "completionPct"]))))))
