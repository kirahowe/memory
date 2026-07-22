(ns claimgraph.setup-test
  "One-shot onboarding against a temp project: every step idempotent and
  dry-runnable, no real store backend (init-fn injected), no LLM, no real
  ~/.claude."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [claimgraph.setup :as setup]))

(defn- temp-project []
  (str (fs/create-temp-dir {:prefix "claimgraph-setup-test"})))

(def fake-init (constantly {:status :initialized :predicates 23}))

(deftest full-pass-is-idempotent
  (let [project (temp-project)
        r1 (setup/run! {:project project :init-fn fake-init})]
    (testing "first pass installs everything"
      (is (= :ready (:status r1)))
      (is (= "claim" (:bin r1)) "no repo-local bin/claim -> PATH")
      (is (= :initialized (get-in r1 [:steps :store :status])))
      (is (= :updated (get-in r1 [:steps :gitignore :status])))
      (is (= :installed (get-in r1 [:steps :skill :status])))
      (is (= :installed (get-in r1 [:steps :hooks :status])))
      (is (= :skipped (get-in r1 [:steps :mcp :status])) "MCP is opt-in")
      (is (seq (:next r1)) "the agent gets its next steps"))
    (testing "the artifacts landed"
      (let [skill (slurp (str (fs/path project ".claude" "skills" "claimgraph" "SKILL.md")))
            ignore (slurp (str (fs/path project ".gitignore")))
            settings (json/parse-string
                      (slurp (str (fs/path project ".claude" "settings.json"))) true)]
        (is (str/includes? skill "claim facts --entity"))
        (is (not (str/includes? skill "{{CLAIM}}")) "template fully rendered")
        (is (str/includes? ignore ".claimgraph/db/"))
        (is (str/includes? ignore ".claimgraph/db.oplog/"))
        (is (some? (get-in settings [:hooks :SessionEnd])))))
    (testing "second pass changes nothing"
      (let [r2 (setup/run! {:project project :init-fn fake-init})]
        (is (= :unchanged (get-in r2 [:steps :gitignore :status])))
        (is (= :unchanged (get-in r2 [:steps :skill :status])))
        (is (= :updated (get-in r2 [:steps :hooks :status]))
            "hooks re-install updates in place (never duplicates)")))))

(deftest dry-run-writes-nothing
  (let [project (temp-project)
        r (setup/run! {:project project :dry-run true :mcp true
                       :init-fn (fn [] (throw (ex-info "must not run" {})))})]
    (is (= :dry-run (:status r)))
    (is (every? #(contains? #{:dry-run :skipped :unchanged} (:status %))
                (vals (:steps r))))
    (is (empty? (fs/list-dir project)) "not a single file written")))

(deftest chosen-settings-persist-to-project-config
  (let [project (temp-project)
        r (setup/run! {:project project :init-fn fake-init
                       :harness "codex" :chosen {:harness "codex" :consolidate-days 3}})
        cfg (json/parse-string
             (slurp (str (fs/path project ".claimgraph" "config.json"))) true)]
    (is (= :installed (get-in r [:steps :config :status])))
    (is (= {:harness "codex" :consolidate-days 3} cfg))
    (testing "re-running with new choices merges, preserving earlier ones"
      (setup/run! {:project project :init-fn fake-init
                   :chosen {:extractor "llm -m small"}})
      (is (= {:harness "codex" :consolidate-days 3 :extractor "llm -m small"}
             (json/parse-string
              (slurp (str (fs/path project ".claimgraph" "config.json"))) true))))
    (testing "all defaults + no file -> nothing persisted"
      (is (= :skipped (get-in (setup/run! {:project (temp-project) :init-fn fake-init})
                              [:steps :config :status]))))))

(deftest gitignore-respects-existing-coverage-and-external-dbs
  (testing "a repo already ignoring the whole directory is left alone"
    (let [project (temp-project)]
      (spit (str (fs/path project ".gitignore")) ".claimgraph/\n")
      (is (= :unchanged (:status (setup/ensure-gitignore! {:project project}))))))
  (testing "a db outside the project is not our gitignore to manage"
    (let [r (setup/ensure-gitignore! {:project (temp-project) :db "/elsewhere/db"})]
      (is (= :skipped (:status r)))))
  (testing "appending preserves existing content"
    (let [project (temp-project)]
      (spit (str (fs/path project ".gitignore")) "node_modules/\n")
      (setup/ensure-gitignore! {:project project})
      (let [content (slurp (str (fs/path project ".gitignore")))]
        (is (str/starts-with? content "node_modules/\n"))
        (is (str/includes? content ".claimgraph/db/"))))))

(deftest skill-honors-bin-and-skills-dir
  (let [project (temp-project)
        skills-dir (str (fs/path project "custom-skills"))]
    (setup/install-skill! {:project project :bin "bin/claim" :skills-dir skills-dir})
    (let [skill (slurp (str (fs/path skills-dir "claimgraph" "SKILL.md")))]
      (is (str/includes? skill "bin/claim facts --entity"))
      (is (not (fs/exists? (fs/path project ".claude"))) "default location untouched"))))

(deftest mcp-registration-merges
  (let [project (temp-project)]
    (spit (str (fs/path project ".mcp.json"))
          "{\"mcpServers\": {\"other\": {\"command\": \"x\"}}}")
    (setup/install-mcp! {:project project :bin "claim"})
    (let [mcp (json/parse-string (slurp (str (fs/path project ".mcp.json"))) true)]
      (is (= {:command "x"} (get-in mcp [:mcpServers :other])) "foreign servers preserved")
      (is (= {:command "claim" :args ["mcp"]} (get-in mcp [:mcpServers :claimgraph]))))))

(deftest failed-step-degrades-to-partial
  (let [r (setup/run! {:project (temp-project)
                       :init-fn (fn [] (throw (ex-info "no dtlv on PATH" {})))})]
    (is (= :partial (:status r)))
    (is (= :error (get-in r [:steps :store :status])))
    (is (= :installed (get-in r [:steps :skill :status]))
        "a failed store init never blocks the file-side steps")))

(deftest repo-dogfood-skill-is-in-sync-with-the-template
  ;; the repo's own .claude/skills/claimgraph/SKILL.md is the template
  ;; rendered for its repo-local bin — regenerate via `claim setup` here
  (when (fs/exists? ".claude/skills/claimgraph/SKILL.md")
    (is (= (setup/skill-content "bin/claim")
           (slurp ".claude/skills/claimgraph/SKILL.md")))))
