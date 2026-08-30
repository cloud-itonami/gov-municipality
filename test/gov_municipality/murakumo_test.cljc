(ns gov_municipality.murakumo-test
  "Invariants of the pure cljc actor boundary in `src/gov_municipality/murakumo.cljc`.

  The one this suite exists for is fail-closed planning: `cell-plan` must not
  emit a single `:mst/put-record` effect unless every gate in `common-gates` is
  attested. Everything else here — the attestation shapes, the rkey derivation,
  the record assembly — is a way that invariant can be lost quietly, so each is
  pinned by name rather than by the aggregate outcome.

  Before this file the namespace had never been executed on any runtime. The
  repo's only runner was `run_tests.sh`, which listed four namespaces and this
  was not one of them, so every claim about this gate was true by inspection
  only."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [gov_municipality.murakumo :as m]))

(def all-attested
  "Every gate the specs require, attested as a keyword-keyed map."
  (zipmap m/common-gates (repeat true)))

;; ── fail-closed planning ────────────────────────────────────────────────────

(deftest blocked-plan-emits-no-effects
  (testing "with no attestations at all, every cell is blocked and writes nothing"
    (doseq [cell (keys m/cell-specs)]
      (let [p (m/cell-plan cell {})]
        (is (= :blocked (:status p)) (str cell " should be blocked"))
        (is (= [] (:effects p)) (str cell " should carry no effects"))
        (is (not (contains? p :records))
            (str cell " should not carry planned records while blocked"))
        (is (= (vec m/common-gates) (:missing-gates p))
            (str cell " should report every gate as missing"))))))

(deftest withholding-any-single-gate-blocks
  (testing "each gate is load-bearing on its own, not only as part of the set"
    (doseq [withheld m/common-gates
            cell     (keys m/cell-specs)]
      (let [atts (zipmap (remove #{withheld} m/common-gates) (repeat true))
            p    (m/cell-plan cell {:attestations atts})]
        (is (= :blocked (:status p))
            (str "withholding " withheld " should block " cell))
        (is (= [withheld] (:missing-gates p))
            (str "withholding " withheld " should name exactly that gate"))
        (is (empty? (:effects p))
            (str "withholding " withheld " should emit no effects"))))))

(deftest falsey-attestation-is-a-refusal-not-a-signature
  (testing "a gate recorded as false or nil does not satisfy it"
    (doseq [falsey [false nil]]
      (let [atts (assoc all-attested :no-probing-baseline falsey)
            p    (m/cell-plan :null {:attestations atts})]
        (is (= :blocked (:status p))
            (str "attestation value " (pr-str falsey) " should not pass the gate"))
        (is (= [:no-probing-baseline] (:missing-gates p)))
        (is (empty? (:effects p)))))))

(deftest fully-attested-plan-is-ready-and-emits-one-effect-per-collection
  (let [spec (get m/cell-specs :null)
        p    (m/cell-plan :null {:attestations all-attested
                                 :computed-at "2026-08-31T00:00:00Z"
                                 :request-id "req-1"})]
    (is (= :ready (:status p)))
    (is (= [] (:missing-gates p)))
    (is (= (count (:collections spec)) (count (:effects p)))
        "one effect per declared collection")
    (is (= (count (:effects p)) (count (:records p)))
        "records and effects are the same plan, counted twice")
    (is (every? #(= :mst/put-record (:op %)) (:effects p)))
    (is (every? #(= m/actor-did (:actor %)) (:effects p))
        "every effect is attributed to this actor and no other")))

(deftest all-cell-plans-covers-every-cell-and-is-fail-closed
  (let [plans (m/all-cell-plans {})]
    (is (= (set (keys m/cell-specs)) (set (keys plans)))
        "all-cell-plans answers for exactly the declared cells")
    (is (every? #(= :blocked (:status %)) (vals plans)))
    (is (every? #(empty? (:effects %)) (vals plans))))
  (let [plans (m/all-cell-plans {:attestations all-attested :request-id "req-2"})]
    (is (every? #(= :ready (:status %)) (vals plans)))
    (is (every? #(seq (:effects %)) (vals plans)))))

(deftest unknown-cell-is-refused-not-silently-empty
  (testing "asking for a cell that does not exist throws rather than planning nothing"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"unknown cell"
                          (m/cell-plan :no-such-cell {:attestations all-attested})))))

;; ── gate-value: the four attestation shapes ─────────────────────────────────

(deftest gate-value-accepts-keyword-and-string-shapes
  (testing "maps keyed by keyword or by string, and sets of either, all attest"
    (let [g :no-probing-baseline]
      (is (m/gate-value {g true} g)                "keyword-keyed map")
      (is (m/gate-value {(name g) true} g)         "string-keyed map")
      (is (m/gate-value #{g} g)                    "set of keywords")
      (is (m/gate-value #{(name g)} g)             "set of strings")
      (is (nil? (m/gate-value {} g))               "empty map attests nothing")
      (is (nil? (m/gate-value #{} g))              "empty set attests nothing")
      (is (nil? (m/gate-value nil g))              "no attestations at all"))))

(deftest gate-value-set-clauses-three-and-four-are-unreachable
  (testing "a set answers on the first two clauses, so the two `set?` clauses below them never run"
    ;; This is pinned, not repaired. `(get #{:x} :x)` already returns :x and
    ;; `(get #{\"x\"} \"x\")` already returns \"x\", so clauses 3 and 4 of
    ;; `gate-value` are dead code. Deleting them changes the boundary's
    ;; contract surface, which is not this loop's call to make silently — the
    ;; point of the assertion is that whoever makes it cannot make it quietly.
    (let [g :no-probing-baseline]
      (is (= g (m/gate-value #{g} g))
          "clause 1 (`get` on a set) already answers for a keyword set")
      (is (= (name g) (m/gate-value #{(name g)} g))
          "clause 2 (`get` on a set by name) already answers for a string set"))))

;; ── rkey derivation ─────────────────────────────────────────────────────────

(deftest safe-rkey-strips-did-web-and-unsafe-characters
  (is (= "etzhayyim.com-member-abc" (m/safe-rkey "did:web:etzhayyim.com:member:abc")))
  (is (= "a.b_c~d-e" (m/safe-rkey "a.b_c~d-e")) "the safe alphabet passes through untouched")
  (is (= "a-b" (m/safe-rkey "a/b")) "a path separator cannot survive into an rkey")
  (is (= "unknown" (m/safe-rkey nil)))
  (is (= "unknown" (m/safe-rkey "")))
  (is (= "---" (m/safe-rkey "   "))
      "whitespace is replaced before blankness is tested, so spaces become dashes and never reach the \"unknown\" fallback")
  (is (= "unknown" (m/safe-rkey "did:web:")) "stripping the prefix can empty the string"))

(deftest rkey-precedence-prefers-explicit-keys-over-the-generated-fallback
  (let [plan (fn [rec] (-> (m/cell-plan :null {:attestations all-attested
                                               :request-id "req-3"
                                               :record rec})
                           :records first :rkey))]
    (is (= "explicit" (plan {:rkey "explicit"}))            ":rkey wins")
    (is (= "explicit" (plan {"rkey" "explicit"}))           "string \"rkey\" is honoured too")
    (is (= "tid-1" (plan {:tid "tid-1"}))                   ":tid is next")
    (is (= "req-3" (plan {}))                               "then the request id")
    (is (= "null-0" (-> (m/cell-plan :null {:attestations all-attested})
                        :records first :rkey))
        "with nothing to go on, the legacy cell name and the index")))

;; ── record assembly ─────────────────────────────────────────────────────────

(deftest records-carry-the-scaffold-provenance-and-the-collection-type
  (let [coll (first (:collections (get m/cell-specs :null)))
        rec  (-> (m/cell-plan :null {:attestations all-attested
                                     :computed-at "2026-08-31T00:00:00Z"
                                     :request-id "req-4"})
                 :records first :record)]
    (is (= coll (:$type rec)) "the record names the collection it is bound for")
    (is (= m/actor-did (:actorDid rec)))
    (is (= "2026-08-31T00:00:00Z" (:computedAt rec)))
    (is (= "req-4" (:requestId rec)))
    (is (true? (:scaffold rec))
        "the record says of itself that it is a scaffold, so a reader does not take it for production output")
    (is (= "attested-plan" (:constitutionalStatus rec)))))

(deftest caller-supplied-fields-override-the-scaffold-base
  (let [coll (first (:collections (get m/cell-specs :null)))]
    (testing "records keyed by collection name"
      (let [rec (-> (m/cell-plan :null {:attestations all-attested
                                        :records {coll {:computedAt "overridden"}}})
                    :records first :record)]
        (is (= "overridden" (:computedAt rec)))))
    (testing "records keyed by index"
      (let [rec (-> (m/cell-plan :null {:attestations all-attested
                                        :records {0 {:computedAt "by-index"}}})
                    :records first :record)]
        (is (= "by-index" (:computedAt rec)))))
    (testing "a single :record applies to the first collection"
      (let [rec (-> (m/cell-plan :null {:attestations all-attested
                                        :record {:computedAt "singular"}})
                    :records first :record)]
        (is (= "singular" (:computedAt rec)))))))

;; ── the catalogue itself ────────────────────────────────────────────────────

(deftest collections-live-under-this-actors-namespace
  (doseq [[cell spec] m/cell-specs
          coll (:collections spec)]
    (is (str/starts-with? coll "com.etzhayyim.gov-municipality.")
        (str cell " writes outside this actor's collection namespace: " coll))))

(deftest every-spec-requires-the-full-common-gate-set
  (doseq [[cell spec] m/cell-specs]
    (is (= (vec m/common-gates) (vec (:required-gates spec)))
        (str cell " must not require a narrower gate set than the actor's baseline"))))

(deftest scaffold-catalogue-is-degenerate-and-that-is-a-known-gap
  (testing "the migration scaffold generated one placeholder cell, not the four the manifest declares"
    ;; manifest.edn :actor/cells declares permit_submission, inspection_scheduling,
    ;; final_sign_off and ledger. `cell-specs` here knows only `:null`, whose
    ;; :legacy-cell is the literal string "null". That is drift between the
    ;; actor's manifest and its cljc boundary, and it is recorded here rather
    ;; than repaired: generating the four real specs is a change to what this
    ;; actor writes to the MST, which needs the deploy path to be exercised
    ;; first. The assertion exists so the gap cannot close or widen unnoticed.
    (is (= #{:null} (set (keys m/cell-specs))))
    (is (= "null" (:legacy-cell (get m/cell-specs :null))))
    (is (= 1 (count (:collections (get m/cell-specs :null)))))))
