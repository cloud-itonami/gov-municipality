(ns gov_municipality.manifest-test
  "Cross-file invariants. This actor describes itself in six places — manifest.edn,
  kotoba.app.edn, cells/*.edn, lex/*.edn, .well-known/did.json and the cljc
  boundary — and until now nothing checked that any two of them agreed.

  Two kinds of assertion live here and they are not the same claim:

  * agreement that **must** hold, e.g. every `:src` in kotoba.app.edn naming a
    file that exists. These fail when the repo drifts.
  * divergence that **does** hold today and is pinned by name, e.g. the actor's
    DID being spelled three different ways. These are recorded rather than
    repaired: reconciling an actor's identity is not a refactor, and the point
    of pinning them is that whoever does reconcile them cannot do it silently.

  Run from the repo root — the readers below refuse rather than report a pass
  when a file is not where they expect it."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [gov_municipality.murakumo :as m]))

(def repo-root
  "nbb does not define `js/__filename`, so this is the working directory."
  (path/resolve (js/process.cwd)))

(defn- slurp*
  "Read a tracked file, or refuse. Not finding it is a third answer, not a clean
  one — see `run_tests.cljs` for why a pass has a floor under it."
  [rel]
  (let [p (path/join repo-root rel)]
    (when-not (fs/existsSync p)
      (throw (ex-info (str "refusing to report a pass: " rel " is not readable from "
                           repo-root " — run this suite from the repo root")
                      {:path p})))
    (fs/readFileSync p "utf8")))

(defn- read-edn [rel] (edn/read-string (slurp* rel)))
(defn- read-json [rel] (js->clj (js/JSON.parse (slurp* rel))))
(defn- exists? [rel] (fs/existsSync (path/join repo-root rel)))

(defn- tracked-files
  "Every file in the tree except VCS bookkeeping. Used both to check the shape of
  the repo and as the floor under this suite: a walk that finds nothing must not
  be able to pass."
  ([] (tracked-files ""))
  ([rel]
   (mapcat (fn [entry]
             (let [child (if (= "" rel) entry (str rel "/" entry))]
               (cond
                 (contains? #{".git" "node_modules"} entry) []
                 (.isDirectory (fs/statSync (path/join repo-root child))) (tracked-files child)
                 :else [child])))
           (fs/readdirSync (path/join repo-root (if (= "" rel) "." rel))))))

(def manifest (read-edn "manifest.edn"))
(def app (read-edn "kotoba.app.edn"))
(def did-doc (read-json ".well-known/did.json"))
(def readme (slurp* "README.md"))

(def manifest-cells (mapv :cell/id (:actor/cells manifest)))
(def manifest-lex (mapv :lex/id (:actor/lex manifest)))
(def manifest-gates (set (map :gate/id (:actor/gates manifest))))
(def components (:kotoba.app/components app))

;; ── the deploy manifest points at real files ────────────────────────────────

(deftest kotoba-app-src-paths-exist
  (testing "every component names a file that is actually in the tree"
    (is (seq components) "the app manifest declares no components at all")
    (doseq [{:keys [name src]} components]
      (is (exists? src)
          (str "component " name " names a :src that is not in the tree: " src)))))

(deftest kotoba-app-src-files-declare-the-namespace-their-path-implies
  (testing "the ns form in each component's source matches where the file sits"
    (doseq [{:keys [name src]} components]
      (when (exists? src)
        (let [ns-sym (second (edn/read-string (slurp* src)))
              ;; `src` is the one classpath root the runner passes, so a
              ;; component's path is that root plus the ns->path mapping. Both
              ;; halves matter: a file outside src/ is not on the classpath at
              ;; all, and a file inside it under the wrong name will not load.
              implied (str "src/"
                           (-> (str ns-sym)
                               (str/replace "-" "_")
                               (str/replace "." "/")
                               (str ".cljc")))]
          (is (= implied src)
              (str "component " name " declares ns " ns-sym
                   ", which maps to " implied ", but the file is at " src)))))))

(deftest kotoba-app-component-names-and-routes-are-unique
  (is (= (count components) (count (set (map :name components))))
      "two components share a name")
  (is (= (count components)
         (count (set (mapcat #(map :route (:triggers %)) components))))
      "two components share an HTTP route"))

(deftest kotoba-app-covers-exactly-the-langgraph-cells
  (testing "the wasm-deployed components are the manifest's langgraph cells, no more and no less"
    (let [langgraph (set (map :cell/id (filter #(= :langgraph (:cell/kind %))
                                               (:actor/cells manifest))))
          declared  (set (map #(-> (:name %)
                                   (str/replace (str (:actor/id manifest) "-") "")
                                   (str/replace "-" "_"))
                              components))]
      (is (= langgraph declared)))))

;; ── manifest ↔ sidecars ─────────────────────────────────────────────────────

(deftest every-manifest-cell-has-a-sidecar-and-every-sidecar-a-manifest-entry
  (let [on-disk (set (map #(str/replace % #"\.edn$" "")
                          (filter #(str/ends-with? % ".edn") (fs/readdirSync (path/join repo-root "cells")))))]
    (is (seq on-disk) "no cell sidecars found — this suite would otherwise pass vacuously")
    (is (= (set manifest-cells) on-disk)
        (str "manifest declares " (pr-str (sort manifest-cells))
             " but cells/ holds " (pr-str (sort on-disk))))))

(deftest each-cell-sidecar-names-itself-after-its-file
  (doseq [id manifest-cells]
    (let [d (first (read-edn (str "cells/" id ".edn")))]
      (is (= id (:cell/id d))
          (str "cells/" id ".edn declares :cell/id " (pr-str (:cell/id d)))))))

(deftest cells-only-cite-gates-the-manifest-declares
  (testing "a cell cannot claim to enforce a gate this actor does not have"
    (is (seq manifest-gates))
    (doseq [id manifest-cells
            g  (:cell/gates (first (read-edn (str "cells/" id ".edn"))))]
      (is (contains? manifest-gates g)
          (str "cell " id " cites gate " g ", which manifest.edn does not declare")))))

(deftest every-manifest-lex-entry-has-a-file-and-every-file-a-manifest-entry
  (let [on-disk (set (map #(str/replace % #"\.edn$" "") (fs/readdirSync (path/join repo-root "lex"))))]
    (is (seq on-disk))
    (is (= (set manifest-lex) on-disk))))

(deftest lex-records-share-one-root-and-end-with-their-declared-name
  (let [ids (map #(:lex/id (first (read-edn (str "lex/" % ".edn")))) manifest-lex)
        roots (set (map #(str/join "." (butlast (str/split % #"\."))) ids))]
    (is (= 1 (count roots))
        (str "the four lexicon records do not share a root: " (pr-str roots)))
    (doseq [[name id] (map vector manifest-lex ids)]
      (is (str/ends-with? id (str "." name))
          (str "lex/" name ".edn declares :lex/id " id)))))

;; ── references that dangle ──────────────────────────────────────────────────

(deftest file-references-in-the-manifest-dangle-only-where-known
  (testing "the manifest points at one file that is not here, and only that one"
    ;; :actor/legacy names actor-manifest.jsonld, kept 'for the transition
    ;; period'. The file is not in the tree, so the reference has outlived the
    ;; thing it points at. Listing it here rather than deleting it keeps the
    ;; decommission a decision someone makes, while a *new* dangling reference
    ;; fails immediately.
    (let [known-missing #{"actor-manifest.jsonld"}
          referenced    (into #{} (remove nil?) [(get-in manifest [:actor/legacy :manifest])])
          dangling      (into #{} (remove exists?) referenced)]
      (is (= known-missing dangling)
          (str "manifest file references changed: dangling now " (pr-str dangling))))))

;; ── divergence pinned, not repaired ─────────────────────────────────────────

(deftest this-actor-is-identified-by-three-different-dids
  (testing "the DID document, the cljc boundary and the prose each name a different subject"
    (is (= "did:web:etzhayyim.com:actor:gov-municipality" (get did-doc "id"))
        "the DID document")
    (is (= "did:web:gov-municipality.etzhayyim.com" m/actor-did)
        "the cljc boundary, derived from :actor/domain")
    (is (str/includes? readme "did:web:etzhayyim.com:gov-municipality")
        "the README")
    (is (= 3 (count #{(get did-doc "id")
                      m/actor-did
                      "did:web:etzhayyim.com:gov-municipality"}))
        "three spellings, still three — reconciling them is a decision, not a refactor")))

(deftest the-lexicon-root-is-spelled-three-different-ways
  (let [from-lex (str/join "." (butlast (str/split (:lex/id (first (read-edn "lex/permitApplication.edn"))) #"\.")))
        from-cljc (str/replace (m/collection "x") #"\.x$" "")]
    (is (= "com.etzhayyim.govmunicipality" from-lex) "the lexicon files")
    (is (= "com.etzhayyim.gov-municipality" from-cljc) "the cljc boundary")
    (is (str/includes? readme "com.etzhayyim.gov.*") "the README")
    (is (= 3 (count #{from-lex from-cljc "com.etzhayyim.gov"})))))

;; ── the shape of the repo ───────────────────────────────────────────────────

(deftest shell-scripts-are-confined-to-the-one-that-is-known
  (testing "ADR-2607173000 retires bb and the workspace prohibits new .sh; this pins what is left"
    ;; kotoba/deploy.sh still drives `bb kotoba/ingest_mcp.cljc` and a
    ;; componentize-py build out of a py/ directory that is no longer in the
    ;; tree. Porting it needs a live kotoba node to test against, so it is
    ;; named here instead of quietly left to grow company.
    (let [files (tracked-files)
          shells (set (filter #(str/ends-with? % ".sh") files))]
      (is (< 20 (count files))
          (str "the tree walk found only " (count files)
               " files — too few to conclude anything about the repo"))
      (is (= #{"kotoba/deploy.sh"} shells)
          (str "shell scripts in the tree changed: " (pr-str shells))))))

(deftest the-test-suite-covers-both-source-roots
  (testing "src/ and test/ mirror each other well enough that neither is empty"
    (let [files (tracked-files)
          src  (filter #(str/starts-with? % "src/") files)
          tst  (filter #(str/starts-with? % "test/") files)]
      (is (seq src))
      (is (seq tst))
      (is (empty? (filter #(re-find #"(?i)(^|/)test_|_test\." %) src))
          "a test file is sitting under src/, where the suite will not look for it"))))
