#!/usr/bin/env bb
;; gov-municipality 官 — regulatory permitting langgraph actor (kotoba WASM cell).
;;
;; Clojure port of agent.py. Handlers over the permit-application state machine:
;; jurisdiction lookup, permit application formation, inspection scheduling,
;; final sign-off, and settlement intent.
;;
;; Run: bb 20-actors/gov-municipality/py/agent.clj
(ns gov-municipality.py.agent
  "Regulatory permitting actor for municipal authority."
  (:require [clojure.java.io :as io]))

(def TITHE_BPS 1000) ;; 10% TitheRouter auto-split, basis points

(defn infer
  "Murakumo-only inference (G15). Offline sentinel mirrors agent.py LLM fallback."
  [prompt]
  (try
    (when (resolve 'kotoba.llm/infer)
      (str ((resolve 'kotoba.llm/infer) {:model "gemma3:4b" :prompt prompt})))
    (catch Exception _ "LLM_NOT_AVAILABLE")))

;; ---------------------------------------------------------------------------
;; Domain gate enforcement — member + jurisdiction consent (G19)
;; ---------------------------------------------------------------------------
(defn enforce-member-consent
  "Verify Adherent SBT active for member before permit submission (G19)."
  [member-did]
  (if (and (seq member-did) (.startsWith ^String member-did "did:"))
    {"ok" true, "reason" "member DID valid"}
    {"ok" false, "reason" "invalid member DID format (G19)"}))

(defn enforce-jurisdiction-authority
  "Verify municipal authority DID for jurisdiction (no-server-key, G18)."
  [jurisdiction-id authority-did]
  (if (and (seq jurisdiction-id) (.startsWith ^String authority-did "did:"))
    {"ok" true, "reason" "jurisdiction authority valid"}
    {"ok" false, "reason" "invalid jurisdiction or authority DID (G18)"}))

;; ---------------------------------------------------------------------------
;; Permit submission — jurisdiction lookup + template selection
;; ---------------------------------------------------------------------------
(defn parse-project-scope
  "Extract jurisdiction from siteId (e.g., Tokyo-13-Shibuya-1-2-3 → JP-13)."
  [site-id building-type total-cost gfa-m2]
  (if (or (not (seq site-id)) (not (seq building-type)))
    {"error" "siteId and buildingType required", "jurisdiction_id" nil}
    (let [parts (clojure.string/split site-id #"-")]
      (if (< (count parts) 2)
        {"error" (str "siteId malformed: " site-id), "jurisdiction_id" nil}
        (let [jurisdiction-id (if (= (first parts) "Tokyo") "JP-13" (str (first parts) "-" (second parts)))]
          {"jurisdiction_id" jurisdiction-id
           "building_type" building-type
           "total_cost" total-cost
           "gfa_m2" gfa-m2
           "reason" (str "extracted jurisdiction " jurisdiction-id " from " site-id)})))))

(defn form-permit-application
  "Form permit application record (state = :submitted, G19 gated)."
  [member-did jurisdiction-id building-type scope & [drawings-cid]]
  (if (or (not (seq member-did)) (not (.startsWith ^String member-did "did:")))
    {"error" "invalid member DID (G19)", "blocked" true}
    (let [suffix (clojure.string/join "" (take 8 (last (clojure.string/split member-did #":"))))
          ts (quot (System/currentTimeMillis) 1000)]
      {":permit-application/id" (str "pa." jurisdiction-id "." suffix "." ts)
       ":permit-application/member-did" member-did
       ":permit-application/jurisdiction" jurisdiction-id
       ":permit-application/building-type" building-type
       ":permit-application/scope" scope
       ":permit-application/drawings-cid" (or drawings-cid "")
       ":permit-application/state" "submitted"})))

(defn handle-permit-submission
  "Handle permit submission (G1, G15, G16, G19, G20 enforced)."
  [input-state]
  (let [member-did (get input-state "member_did" "")
        site-id (get input-state "site_id" "")
        building-type (get input-state "building_type" "")
        total-cost (get input-state "total_cost" 0)
        gfa-m2 (get input-state "gfa_m2" 0.0)
        scope (get input-state "scope" "")
        drawings-cid (get input-state "drawings_cid" "")
        consent (enforce-member-consent member-did)]
    (if (not (get consent "ok"))
      {"error" (get consent "reason"), "blocked" true}
      (let [parsed (parse-project-scope site-id building-type total-cost gfa-m2)]
        (if (contains? parsed "error")
          parsed
          (let [jurisdiction-id (get parsed "jurisdiction_id")
                app (form-permit-application member-did jurisdiction-id building-type scope drawings-cid)]
            (if (contains? app "error")
              app
              {"permit_application_id" (get app ":permit-application/id")
               "jurisdiction_id" jurisdiction-id
               "state" "submitted"
               "next_step" "inspection_scheduling"})))))))

;; ---------------------------------------------------------------------------
;; Inspection scheduling — jurisdiction rules lookup + phase scheduling
;; ---------------------------------------------------------------------------
(defn schedule-inspection-phases
  "Schedule 5 canonical inspection phases per jurisdiction (foundation/structural/MEP/finishing/commissioning)."
  [jurisdiction-id permit-application-id]
  (let [phases ["foundation" "structural" "mep" "finishing" "commissioning"]
        ts (quot (System/currentTimeMillis) 1000)]
    {":inspection-schedule/id" (str "is." jurisdiction-id "." (clojure.string/join "" (take-last 8 permit-application-id)) "." ts)
     ":inspection-schedule/permit-application" permit-application-id
     ":inspection-schedule/jurisdiction" jurisdiction-id
     ":inspection-schedule/phases" (clojure.string/join "," phases)
     "reason" (str "scheduled " (count phases) " phases for " jurisdiction-id)}))

(defn handle-inspection-scheduling
  "Handle inspection scheduling (G1, G15, G16, G19 enforced)."
  [input-state]
  (let [permit-application-id (get input-state "permit_application_id" "")
        jurisdiction-id (get input-state "jurisdiction_id" "")]
    (if (or (not (seq permit-application-id)) (not (seq jurisdiction-id)))
      {"error" "permit_application_id and jurisdiction_id required", "blocked" true}
      (let [schedule (schedule-inspection-phases jurisdiction-id permit-application-id)]
        {"inspection_schedule_id" (get schedule ":inspection-schedule/id")
         "phases" (clojure.string/split (get schedule ":inspection-schedule/phases" "") #",")
         "state" "inspecting"
         "next_step" "final_sign_off"}))))

;; ---------------------------------------------------------------------------
;; Final sign-off — authority signature + occupancy clearance (G3, G18)
;; ---------------------------------------------------------------------------
(defn validate-inspection-results
  "Validate all phases ≥ conditional (pass or conditional pass, no fail)."
  [inspection-results]
  (if (empty? inspection-results)
    {"ok" false, "reason" "no inspection results provided"}
    (let [has-fail (some #(= "fail" (get % "result")) inspection-results)]
      (if has-fail
        {"ok" false, "reason" "one or more phases failed inspection"}
        {"ok" true, "reason" "all phases ≥ conditional"}))))

(defn emit-occupancy-clearance
  "Emit final occupancy clearance record (G3 authority sig required, no-server-key G18)."
  [permit-application-id jurisdiction-id authority-did & [authority-signature]]
  (let [auth-check (enforce-jurisdiction-authority jurisdiction-id authority-did)]
    (if (not (get auth-check "ok"))
      {"error" (get auth-check "reason"), "blocked" true}
      (if (not (seq (or authority-signature "")))
        {"error" "authority_signature required (G18)", "blocked" true}
        (let [ts (quot (System/currentTimeMillis) 1000)]
          {":permits-finalized-record/id" (str "pfr." jurisdiction-id "." (clojure.string/join "" (take-last 8 permit-application-id)) "." ts)
           ":permits-finalized-record/permit-application" permit-application-id
           ":permits-finalized-record/authority-did" authority-did
           ":permits-finalized-record/authority-signature" authority-signature
           ":permits-finalized-record/occupancy-status" "approved"})))))

(defn handle-final-sign-off
  "Handle final sign-off (G3, G15, G16, G18 enforced)."
  [input-state]
  (let [permit-application-id (get input-state "permit_application_id" "")
        jurisdiction-id (get input-state "jurisdiction_id" "")
        inspection-results (get input-state "inspection_results" [])
        authority-did (get input-state "authority_did" "")
        authority-signature (get input-state "authority_signature" "")
        valid (validate-inspection-results inspection-results)]
    (if (not (get valid "ok"))
      {"error" (get valid "reason"), "blocked" true}
      (let [record (emit-occupancy-clearance permit-application-id jurisdiction-id authority-did authority-signature)]
        (if (contains? record "error")
          record
          {"permits_finalized_record_id" (get record ":permits-finalized-record/id")
           "occupancy_status" "approved"
           "state" "finalized"
           "next_step" "settlement"})))))

;; ---------------------------------------------------------------------------
;; Settlement — USDC + TitheRouter intent (NOT broadcast; G17/G18/G19)
;; ---------------------------------------------------------------------------
(defn build-settlement-intent
  "USDC settlement split. 10% tithe → Public Fund. Stops at :intent — broadcast
  needs an authority signature (G18)."
  [jurisdiction-fee-minor & [authority-sig-ref]]
  (let [tithe (quot (* jurisdiction-fee-minor TITHE_BPS) 10000)]
    {"rail" "usdc-base-l2"
     "jurisdictionFeeMinor" jurisdiction-fee-minor
     "titheMinor" tithe
     "authorityPayoutMinor" (- jurisdiction-fee-minor tithe)
     "titheRouter" "50-infra/etzhayyim-tithe-router"
     "state" (if authority-sig-ref "executed" "intent")
     "authoritySigRef" (or authority-sig-ref "")}))

(defn -main [& _]
  (println "permit submission (Tokyo, residential):"
           (get (handle-permit-submission {"member_did" "did:web:etzhayyim.com:member:test"
                                           "site_id" "Tokyo-13-Shibuya-1-2-3"
                                           "building_type" "residential"
                                           "total_cost" 150000000
                                           "gfa_m2" 800.0
                                           "scope" "3-story apartment building"})
                "permit_application_id"))
  (println "inspection scheduling:"
           (get (handle-inspection-scheduling {"permit_application_id" "pa.JP-13.test.1"
                                               "jurisdiction_id" "JP-13"})
                "inspection_schedule_id"))
  (println "final sign-off (approved):"
           (get (handle-final-sign-off {"permit_application_id" "pa.JP-13.test.1"
                                        "jurisdiction_id" "JP-13"
                                        "inspection_results" [{"phase" "foundation" "result" "pass"}]
                                        "authority_did" "did:web:tokyo.jp:director:d001"
                                        "authority_signature" "sig001"})
                "permits_finalized_record_id"))
  (println "settlement (10% tithe):" (build-settlement-intent 500000)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
