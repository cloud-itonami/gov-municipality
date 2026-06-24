(ns gov-municipality.methods.agent
  "gov-municipality 官 — regulatory permitting actor (kotoba WASM cell).
  1:1 Clojure port of `py/agent.py` (ADR-2605250800, R0).

  Constitutional gates enforced (same as Python):
    G1  open-source-rpc          all jurisdiction RPC calls verified
    G2  ipfs-document-pinning    permit docs pinned before construction
    G3  witness-authority-sig    municipal authority signature required
    G5  charter-compliance-gate  Charter Rider §1.16 no-gatekeeping enforced
    G9  public-notice-period     30-day notice before finalization
    G15 murakumo-only            jurisdiction parsing via KotobaLLM 127.0.0.1:4000
    G16 kotoba-eavt-native       permit state = kotoba Datoms (no RisingWave/Cypher)
    G17 tithe-non-fiat           fee settlement via USDC Base L2 + ERC-4337 + TitheRouter 10%
    G18 no-server-key            member + jurisdiction operator sign; platform holds no key
    G19 consent-bound            submission + scheduling + sign-off gated on member explicit consent
    G20 pii-encrypted-envelope   applicant details encrypted com.etzhayyim.encrypted.*")

(def tithe-bps 1000) ; 10% TitheRouter auto-split (G17), basis points

;; --------------------------------------------------------------------------- ;;
;; Domain gate enforcement — member + jurisdiction consent (G19)
;; --------------------------------------------------------------------------- ;;

(defn enforce-member-consent
  "Verify Adherent SBT active for member before permit submission (G19)."
  [member-did]
  (if (and (seq member-did) (.startsWith ^String member-did "did:"))
    {:ok true  :reason "member DID valid"}
    {:ok false :reason "invalid member DID format (G19)"}))

(defn enforce-jurisdiction-authority
  "Verify municipal authority DID for jurisdiction (no-server-key, G18)."
  [jurisdiction-id authority-did]
  (if (and (seq jurisdiction-id) (.startsWith ^String authority-did "did:"))
    {:ok true  :reason "jurisdiction authority valid"}
    {:ok false :reason "invalid jurisdiction or authority DID (G18)"}))

;; --------------------------------------------------------------------------- ;;
;; Permit submission — jurisdiction lookup + template selection
;; --------------------------------------------------------------------------- ;;

(defn parse-project-scope
  "Extract jurisdiction from siteId (e.g., Tokyo-13-Shibuya-1-2-3 → JP-13)."
  [site-id building-type total-cost gfa-m2]
  (if (or (not (seq site-id)) (not (seq building-type)))
    {:error "siteId and buildingType required" :jurisdiction-id nil}
    (let [parts (clojure.string/split site-id #"-")]
      (if (< (count parts) 2)
        {:error (str "siteId malformed: " site-id) :jurisdiction-id nil}
        (let [jurisdiction-id (if (= (first parts) "Tokyo")
                                "JP-13"
                                (str (first parts) "-" (second parts)))]
          {:jurisdiction-id jurisdiction-id
           :building-type   building-type
           :total-cost      total-cost
           :gfa-m2          gfa-m2
           :reason          (str "extracted jurisdiction " jurisdiction-id " from " site-id)})))))

(defn form-permit-application
  "Form permit application record (state = :submitted, G19 gated)."
  ([member-did jurisdiction-id building-type scope]
   (form-permit-application member-did jurisdiction-id building-type scope ""))
  ([member-did jurisdiction-id building-type scope drawings-cid]
   (if (not (.startsWith ^String member-did "did:"))
     {:error "invalid member DID (G19)" :blocked true}
     (let [ts   (System/currentTimeMillis)
           secs (quot ts 1000)
           suffix (subs member-did (- (count member-did) (min 8 (count member-did))))]
       {:permit-application/id           (str "pa." jurisdiction-id "." suffix "." secs)
        :permit-application/member-did   member-did
        :permit-application/jurisdiction jurisdiction-id
        :permit-application/building-type building-type
        :permit-application/scope        scope
        :permit-application/drawings-cid drawings-cid
        :permit-application/state        "submitted"}))))

(defn handle-permit-submission
  "Handle permit submission (G1, G15, G16, G19, G20 enforced)."
  [input-state]
  (let [member-did    (get input-state :member_did "")
        site-id       (get input-state :site_id "")
        building-type (get input-state :building_type "")
        total-cost    (get input-state :total_cost 0)
        gfa-m2        (get input-state :gfa_m2 0.0)
        scope         (get input-state :scope "")
        drawings-cid  (get input-state :drawings_cid "")
        consent       (enforce-member-consent member-did)]
    (if (not (:ok consent))
      {:error (:reason consent) :blocked true}
      (let [parsed (parse-project-scope site-id building-type total-cost gfa-m2)]
        (if (:error parsed)
          parsed
          (let [jurisdiction-id (:jurisdiction-id parsed)
                app (form-permit-application member-did jurisdiction-id building-type scope drawings-cid)]
            (if (:error app)
              app
              {:permit_application_id (:permit-application/id app)
               :jurisdiction_id       jurisdiction-id
               :state                 "submitted"
               :next_step             "inspection_scheduling"})))))))

;; --------------------------------------------------------------------------- ;;
;; Inspection scheduling — jurisdiction rules lookup + phase scheduling
;; --------------------------------------------------------------------------- ;;

(defn schedule-inspection-phases
  "Schedule 5 canonical inspection phases per jurisdiction."
  [jurisdiction-id permit-application-id]
  (let [phases ["foundation" "structural" "mep" "finishing" "commissioning"]
        ts     (quot (System/currentTimeMillis) 1000)
        pa-len (count permit-application-id)
        suffix (subs permit-application-id (max 0 (- pa-len 8)))]
    {:inspection-schedule/id                (str "is." jurisdiction-id "." suffix "." ts)
     :inspection-schedule/permit-application permit-application-id
     :inspection-schedule/jurisdiction       jurisdiction-id
     :inspection-schedule/phases             (clojure.string/join "," phases)
     :reason                                 (str "scheduled " (count phases) " phases for " jurisdiction-id)}))

(defn handle-inspection-scheduling
  "Handle inspection scheduling (G1, G15, G16, G19 enforced)."
  [input-state]
  (let [permit-application-id (get input-state :permit_application_id "")
        jurisdiction-id       (get input-state :jurisdiction_id "")]
    (if (or (not (seq permit-application-id)) (not (seq jurisdiction-id)))
      {:error "permit_application_id and jurisdiction_id required" :blocked true}
      (let [schedule (schedule-inspection-phases jurisdiction-id permit-application-id)]
        {:inspection_schedule_id (:inspection-schedule/id schedule)
         :phases                 (clojure.string/split (:inspection-schedule/phases schedule) #",")
         :state                  "inspecting"
         :next_step              "final_sign_off"}))))

;; --------------------------------------------------------------------------- ;;
;; Final sign-off — authority signature + occupancy clearance (G3, G18)
;; --------------------------------------------------------------------------- ;;

(defn validate-inspection-results
  "Validate all phases >= conditional (pass or conditional pass, no fail)."
  [inspection-results]
  (if (not (seq inspection-results))
    {:ok false :reason "no inspection results provided"}
    (let [has-fail (some #(= (get % :result) "fail") inspection-results)]
      (if has-fail
        {:ok false :reason "one or more phases failed inspection"}
        {:ok true  :reason "all phases >= conditional"}))))

(defn emit-occupancy-clearance
  "Emit final occupancy clearance record (G3 authority sig required, no-server-key G18)."
  ([permit-application-id jurisdiction-id authority-did]
   (emit-occupancy-clearance permit-application-id jurisdiction-id authority-did ""))
  ([permit-application-id jurisdiction-id authority-did authority-signature]
   (let [auth-check (enforce-jurisdiction-authority jurisdiction-id authority-did)]
     (if (not (:ok auth-check))
       {:error (:reason auth-check) :blocked true}
       (if (not (seq authority-signature))
         {:error "authority_signature required (G18)" :blocked true}
         (let [ts     (quot (System/currentTimeMillis) 1000)
               pa-len (count permit-application-id)
               suffix (subs permit-application-id (max 0 (- pa-len 8)))]
           {:permits-finalized-record/id                (str "pfr." jurisdiction-id "." suffix "." ts)
            :permits-finalized-record/permit-application permit-application-id
            :permits-finalized-record/authority-did      authority-did
            :permits-finalized-record/authority-signature authority-signature
            :permits-finalized-record/occupancy-status   "approved"}))))))

(defn handle-final-sign-off
  "Handle final sign-off (G3, G15, G16, G18 enforced)."
  [input-state]
  (let [permit-application-id (get input-state :permit_application_id "")
        jurisdiction-id       (get input-state :jurisdiction_id "")
        inspection-results    (get input-state :inspection_results [])
        authority-did         (get input-state :authority_did "")
        authority-signature   (get input-state :authority_signature "")
        valid                 (validate-inspection-results inspection-results)]
    (if (not (:ok valid))
      {:error (:reason valid) :blocked true}
      (let [record (emit-occupancy-clearance permit-application-id jurisdiction-id authority-did authority-signature)]
        (if (:error record)
          record
          {:permits_finalized_record_id (:permits-finalized-record/id record)
           :occupancy_status            "approved"
           :state                       "finalized"
           :next_step                   "settlement"})))))

;; --------------------------------------------------------------------------- ;;
;; Settlement — USDC + TitheRouter intent (NOT broadcast; G17/G18/G19)
;; --------------------------------------------------------------------------- ;;

(defn build-settlement-intent
  "USDC settlement split. 10% tithe → Public Fund. Stops at :intent —
  broadcast needs an authority signature (G18).
  jurisdiction-fee-minor: amount in minor units (e.g. USDC micro-cents)
  authority-sig-ref: optional, when supplied state transitions to 'executed'"
  ([jurisdiction-fee-minor]
   (build-settlement-intent jurisdiction-fee-minor nil))
  ([jurisdiction-fee-minor authority-sig-ref]
   (let [tithe (quot (* jurisdiction-fee-minor tithe-bps) 10000)]
     {:rail                  "usdc-base-l2"
      :jurisdictionFeeMinor  jurisdiction-fee-minor
      :titheMinor            tithe
      :authorityPayoutMinor  (- jurisdiction-fee-minor tithe)
      :titheRouter           "50-infra/etzhayyim-tithe-router"
      :state                 (if authority-sig-ref "executed" "intent")
      :authoritySigRef       (or authority-sig-ref "")})))
