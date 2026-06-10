# TODO

Remaining roadmap, in rough priority order. Rationale for most items lives in
`docs/memgraph-handoff.md` §7.

## Next up

- [ ] **Pluggable LLM judge on the semantic-conflict path.** `assert-fact`
      already returns `:candidates` on flag; add an optional
      `judge(fact-a, fact-b) -> {relation, confidence}` that enriches flagged
      conflicts, defaulting to the same subscription-as-judge mechanism as the
      session extractor.
- [ ] **Consolidation (Dreaming-style).** `consolidate` is a stub: episode
      summarization on close, repeated-pattern promotion to procedural memory,
      reconcile + decay in one offline pass. Depends on the LLM judge.
- [ ] **Entity resolution.** `ensure-entity` is exact name+scope match by
      design. Aliases, renames, and split identities (`UserService` →
      `UserReadService` + `UserWriteService`) belong behind that abstraction.
- [ ] **Failure ingester.** When agent work is rejected or reverted, extract
      why — this is where procedural memory grows from.
- [ ] **Decision-record (ADR) ingester.** Highest-authority source; parse ADR
      files into `supersedes` / `decided-against` / `has-status` commitments.
- [ ] **Predicate promotion command.** `x/*` → `core/*`: register the stable
      twin, rewrite facts, deprecate the staging term with `:replaced-by`
      (never delete). Usage counts already exist (`predicates --usage`).
- [ ] **`load` command.** Restore a store from `dump` JSONL — completes the
      portability loop (dump is currently one-way).
- [ ] **MCP front-end.** Thin second front-end over `memgraph.core` when
      cold-start per query hurts or per-turn call counts grow.
- [ ] **Vector/semantic search.** Datalevin has SIMD vector search; defer
      until FTS + graph retrieval proves insufficient.
- [ ] **ACL tier.** `read-acl`/`write-acl` are carried in the schema but
      unenforced; activate when multi-user.
- [ ] **Multi-language code ingesters.** `ingest-code` is Clojure-only
      (edamame ns-form parsing); tree-sitter would generalize it.
- [ ] **Codebase-memory benchmark.** No LongMemEval/LoCoMo equivalent exists
      for codebase memory — both a validation obstacle and an opportunity.

## Decided against

- **dump-to-SQLite migration path** (2026-06-10). JSONL dump suffices for
  portability; a SQLite backend remains possible behind the `Store` protocol
  if ever actually needed.

## Done

- [x] v0: schema, Store protocol, two backends, conflict machinery, predicate
      registry, bi-temporal reads, BFS, FTS, episodes, JSONL ingest, Clojure
      code ingester, decay, dump, CLI, skill.
- [x] Functional-core/imperative-shell refactor: pure decision logic in
      `memgraph.logic`, effects concentrated in `memgraph.core` + store impls.
- [x] Session-log extractor (`session-extract`): pluggable LLM extractor over
      transcripts (plain text or Claude Code session JSONL), dry-run mode,
      confidence capped at 0.7, source-type `:session-log`.
- [x] Mechanical invalidation on git events: every `ingest-code` pass
      reconciles the store against the new analysis and invalidates
      code-sourced facts it no longer produces (deleted files, removed
      requires, dropped namespaces). Non-lossy; history retains them.
