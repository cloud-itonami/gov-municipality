#!/usr/bin/env bb
;; Clojure port of test_agent.py for gov-municipality agent.
(ns gov-municipality.py.test-agent
  "gov-municipality — agent gate tests (offline, no kotoba host, no network, no LLM).

  ADR-2605250800. Exercises the permitting constitutional gates: member consent
  (G19), jurisdiction authority (G18), permit submission (G1/G15/G16), inspection
  scheduling, final sign-off (G3/G18), and the USDC + tithe settlement (G17).

  Run: bb --classpath 20-actors 20-actors/gov-municipality/py/test_agent.clj"
  (:require [clojure.test :refer [deftest is run-tests testing]]
            [clojure.java.io :as io]))

;; NOTE: bb resolves ns hyphens -> underscores in classpath path lookup, but the
;; actor dir is named gov-municipality (hyphenated). We load-file the agent
;; directly so it is registered in its own ns, then require it by ns name.
(load-file (.getCanonicalPath
            (io/file (io/file (System/getProperty "babashka.file")) ".." "agent.clj")))
(require '[gov-municipality.py.agent :as agent])

(deftest test-member-consent-valid
  (testing "valid member DID accepted (G19)"
    (let [result (agent/enforce-member-consent "did:web:etzhayyim.com:member:test")]
      (is (true? (get result "ok"))))))

(deftest test-member-consent-invalid
  (testing "invalid DID rejected (G19)"
    (let [result (agent/enforce-member-consent "not-a-did")]
      (is (false? (get result "ok"))))))

(deftest test-jurisdiction-authority-valid
  (testing "valid authority DID accepted (G18)"
    (let [result (agent/enforce-jurisdiction-authority "JP-13" "did:web:tokyo.jp:director:d001")]
      (is (true? (get result "ok"))))))

(deftest test-jurisdiction-authority-invalid
  (testing "invalid authority DID rejected (G18)"
    (let [result (agent/enforce-jurisdiction-authority "JP-13" "not-a-did")]
      (is (false? (get result "ok"))))))

(deftest test-parse-project-scope-tokyo
  (testing "Tokyo site parsed to JP-13 (G1)"
    (let [result (agent/parse-project-scope "Tokyo-13-Shibuya-1-2-3" "residential" 150000000 800.0)]
      (is (= "JP-13" (get result "jurisdiction_id"))))))

(deftest test-parse-project-scope-invalid
  (testing "invalid siteId rejected"
    (let [result (agent/parse-project-scope "" "residential" 0 0.0)]
      (is (contains? result "error")))))

(deftest test-form-permit-application-valid
  (testing "permit application formed"
    (let [result (agent/form-permit-application "did:web:etzhayyim.com:member:test"
                                                "JP-13" "residential"
                                                "3-story apartment building")]
      (is (contains? result ":permit-application/id")))))

(deftest test-form-permit-application-invalid-did
  (testing "invalid member DID in application rejected (G19)"
    (let [result (agent/form-permit-application "not-a-did" "JP-13" "residential" "scope")]
      (is (true? (get result "blocked"))))))

(deftest test-handle-permit-submission-valid
  (testing "permit submission created (G1/G15/G16/G19)"
    (let [result (agent/handle-permit-submission
                  {"member_did" "did:web:etzhayyim.com:member:test"
                   "site_id" "Tokyo-13-Shibuya-1-2-3"
                   "building_type" "residential"
                   "total_cost" 150000000
                   "gfa_m2" 800.0
                   "scope" "3-story apartment building"})]
      (is (contains? result "permit_application_id"))
      (is (= "submitted" (get result "state"))))))

(deftest test-handle-permit-submission-consent-fail
  (testing "permit submission blocked on invalid member DID (G19)"
    (let [result (agent/handle-permit-submission
                  {"member_did" "invalid"
                   "site_id" "Tokyo-13-Shibuya-1-2-3"
                   "building_type" "residential"
                   "total_cost" 150000000
                   "gfa_m2" 800.0
                   "scope" "scope"})]
      (is (true? (get result "blocked"))))))

(deftest test-schedule-inspection-phases
  (testing "5 inspection phases scheduled (G1/G15/G16)"
    (let [result (agent/schedule-inspection-phases "JP-13" "pa.JP-13.test.1")
          phases (clojure.string/split (get result ":inspection-schedule/phases" "") #",")]
      (is (= 5 (count phases)))
      (is (some #{"foundation"} phases)))))

(deftest test-handle-inspection-scheduling-valid
  (testing "inspection schedule created"
    (let [result (agent/handle-inspection-scheduling
                  {"permit_application_id" "pa.JP-13.test.1"
                   "jurisdiction_id" "JP-13"})]
      (is (contains? result "inspection_schedule_id"))
      (is (= "inspecting" (get result "state"))))))

(deftest test-validate-inspection-results-all-pass
  (testing "all phases ≥ conditional accepted"
    (let [results [{"phase" "foundation" "result" "pass"}
                   {"phase" "structural" "result" "pass"}
                   {"phase" "mep" "result" "conditional"}]
          valid (agent/validate-inspection-results results)]
      (is (true? (get valid "ok"))))))

(deftest test-validate-inspection-results-has-fail
  (testing "phase failure rejected"
    (let [results [{"phase" "foundation" "result" "pass"}
                   {"phase" "structural" "result" "fail"}]
          valid (agent/validate-inspection-results results)]
      (is (false? (get valid "ok"))))))

(deftest test-emit-occupancy-clearance-valid
  (testing "occupancy clearance issued (G3/G18)"
    (let [result (agent/emit-occupancy-clearance "pa.JP-13.test.1" "JP-13"
                                                "did:web:tokyo.jp:director:d001"
                                                "sig_base64_001")]
      (is (contains? result ":permits-finalized-record/id")))))

(deftest test-emit-occupancy-clearance-no-signature
  (testing "occupancy clearance blocked without signature (G18)"
    (let [result (agent/emit-occupancy-clearance "pa.JP-13.test.1" "JP-13"
                                                "did:web:tokyo.jp:director:d001"
                                                "")]
      (is (true? (get result "blocked"))))))

(deftest test-handle-final-sign-off-approved
  (testing "final sign-off approved (G3/G16/G18)"
    (let [result (agent/handle-final-sign-off
                  {"permit_application_id" "pa.JP-13.test.1"
                   "jurisdiction_id" "JP-13"
                   "inspection_results" [{"phase" "foundation" "result" "pass"}]
                   "authority_did" "did:web:tokyo.jp:director:d001"
                   "authority_signature" "sig001"})]
      (is (= "finalized" (get result "state")))
      (is (contains? result "permits_finalized_record_id")))))

(deftest test-settlement-tithe-split
  (testing "10% tithe + stops at intent (G17/G18)"
    (let [s (agent/build-settlement-intent 500000)]
      (is (= 50000 (get s "titheMinor")))
      (is (= "intent" (get s "state")))
      (is (= "usdc-base-l2" (get s "rail"))))))

(deftest test-settlement-executed-with-sig
  (testing "settlement executes only with authority signature (G18)"
    (let [s (agent/build-settlement-intent 1000000 "0xsig")]
      (is (= "executed" (get s "state"))))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [{:keys [fail error]} (clojure.test/run-tests (quote gov-municipality.py.test-agent))]
    (System/exit (if (zero? (+ fail error)) 0 1))))
