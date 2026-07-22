(ns claimgraph.config-test
  "The configuration precedence chain as pure functions over passed
  opts/env/config maps — no real environment, no real config file."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [claimgraph.config :as config]))

(deftest precedence-chain
  (let [ctx {:env {"CLAIMGRAPH_DB" "/from-env/db"}
             :config {:db "/from-file/db"}}]
    (testing "flag beats env beats config beats default"
      (is (= {:value "/flag/db" :source :flag}
             (config/resolve-setting :db (assoc ctx :opts {:db "/flag/db"}))))
      (is (= {:value "/from-env/db" :source :env}
             (config/resolve-setting :db (assoc ctx :opts {}))))
      (is (= {:value "/from-file/db" :source :config}
             (config/resolve-setting :db {:opts {} :env {}
                                          :config {:db "/from-file/db"}})))
      (is (= {:value ".claimgraph/db" :source :default}
             (config/resolve-setting :db {:opts {} :env {} :config nil}))))))

(deftest unset-without-default-is-nil
  (is (= {:value nil :source nil}
         (config/resolve-setting :notes-dir {:opts {} :env {} :config nil}))))

(deftest numeric-coercion-from-env-and-config
  (is (= {:value 3 :source :env}
         (config/resolve-setting :consolidate-days
                                 {:opts {} :env {"CLAIMGRAPH_CONSOLIDATE_DAYS" "3"}})))
  (is (= {:value 5 :source :config}
         (config/resolve-setting :consolidate-days
                                 {:opts {} :env {} :config {:consolidate-days 5}}))
      "a JSON number needs no coercion"))

(deftest opt-key-mapping
  (testing "--dir is the CLI spelling of the notes-dir setting"
    (is (= {:value "/n" :source :flag}
           (config/resolve-setting :notes-dir {:opts {:dir "/n"} :env {} :config nil})))
    (is (= {:dir "/cfg/notes"}
           (config/merge-defaults {} {:env {} :config {:notes-dir "/cfg/notes"}}
                                  [:notes-dir]))
        "merge-defaults fills the option key the commands read")))

(deftest merge-defaults-respects-flags-and-skips-static-defaults
  (let [ctx {:env {"CLAIMGRAPH_HARNESS" "codex"} :config {:harness "claude-code"}}]
    (is (= {:harness "codex"} (config/merge-defaults {} ctx [:harness]))
        "env layer fills an absent flag")
    (is (= {:harness "x"} (config/merge-defaults {:harness "x"} ctx [:harness]))
        "an explicit flag is never overwritten"))
  (is (= {} (config/merge-defaults {} {:env {} :config nil} [:harness :consolidate-days]))
      "static defaults stay owned by each consumer — merge fills nothing"))

(deftest config-file-path-override
  (is (= "/elsewhere/cfg.json"
         (config/config-file-path {"CLAIMGRAPH_CONFIG" "/elsewhere/cfg.json"})))
  (is (= ".claimgraph/config.json" (config/config-file-path {}))))

(deftest read-config-file-roundtrip
  (let [dir (fs/create-temp-dir {:prefix "claimgraph-config-test"})
        path (str (fs/path dir "config.json"))]
    (is (nil? (config/read-config-file path)) "absent file reads as nil")
    (spit path "{\"harness\": \"codex\", \"consolidate-days\": 3}")
    (is (= {:harness "codex" :consolidate-days 3} (config/read-config-file path)))))

(deftest describe-reports-every-setting-with-provenance
  (let [d (config/describe {:db "/flag/db"})]
    (is (= "flag > env > config-file > default" (:precedence d)))
    (is (= (set (keys config/settings)) (set (keys (:settings d)))))
    (is (= :flag (get-in d [:settings :db :source])))
    (is (every? (fn [[_ v]] (and (:flag v) (:env v) (:config-key v) (:desc v)))
                (:settings d))
        "each setting documents how to set it at every layer")))
