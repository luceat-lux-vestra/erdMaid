# erdMaid Engineering Contract

This document owns the current architecture, implementation and review
obligations for erdMaid, a JetBrains Database Tools table-to-Mermaid exporter.
[Product fidelity](../product-fidelity-contract.md) and
[baseline scenarios](../product-baseline-scenarios.yaml) own user-visible
semantics. [Portable execution](agent-execution-policy.md) and
[recovery](agent-recovery.md) own agent procedures. Internal design is subject
to authorized replacement when a better design meets the required contract.

## 1. Current architecture

The maintained export architecture established by completed Epic #32 is a **functional core + JetBrains host shell**:

`JetBrains selection -> immutable schema snapshot -> ER semantic graph -> Mermaid renderer -> typed result -> UI publication`

### Functional core

Pure Kotlin. It must not depend on `com.intellij.*`, `Project`, PSI, `DbElement`, `Das*`, action
threads, clipboard APIs, notifications, reflection handles, coroutine scopes, or other platform
objects.

The core owns:

- canonical datasource/origin, table, and column identity;
- immutable metadata snapshots and explicit evidence/availability states;
- ordered primary/unique/foreign-key facts;
- relation semantics and deterministic ordering;
- typed semantic/export outcomes;
- Mermaid rendering from already-established pure facts.

The core is not a framework. Do not add a generic database-provider SPI, repository layer, DI
framework, event bus, cache, service graph, generic renderer SPI, or second renderer merely for
future extensibility.

### JetBrains host shell

All IntelliJ/DatabaseTools dependencies belong on this side of the boundary. It owns:

- Database tool-window selection and group expansion;
- datasource/origin discovery;
- `Db*` / `Das*` metadata reads and relation-source collection;
- any unavoidable version-specific/reflected DatabaseTools access;
- action update-thread policy, cancellable coroutine/read-action execution, progress, lifecycle,
  and disposal/invalidation checks;
- final clipboard publication and user notification.

Live platform objects are copied into an immutable core snapshot and must not escape that capture
boundary into semantic or renderer code.

### Production ownership

The maintained export path is split by responsibility rather than by legacy generator types:

| Path | Current role | Ownership |
|---|---|---|
| `src/main/kotlin/.../actions/ErdMaidExportActions.kt` | stateless action entry point, bounded `update()`, invocation coroutine, terminal UI publication/notification | JetBrains host shell |
| `src/main/kotlin/.../host/JetBrainsDatabaseHost.kt` | DatabaseTools selection compatibility boundary, origin/metadata/relation capture, freshness validation | JetBrains host shell |
| `src/main/kotlin/.../host/HostExportPipeline.kt` | platform-free outcome orchestration and structural publication gate | host/core handoff |
| `src/main/kotlin/.../core/*` | immutable canonical snapshot/evidence model | functional core |
| `src/main/kotlin/.../semantic/*` | relation/constraint/multiplicity semantic compilation | functional core |
| `src/main/kotlin/.../renderer/*` | deterministic Mermaid compilation and serialization | functional core |

The former synchronous `MermaidGenerator`, `RelationResolver`, `MermaidSanitizer`, their
renderer-internal specs, and their obsolete tests are not parallel authorities and must not be
reintroduced as compatibility shims. The only tolerated reflective DatabaseTools access is the
explicitly reviewed compatibility boundary in the host shell; reflection must not leak into the
action, core, semantic, or renderer layers.

---

## 2. Canonical snapshot boundary

Completed Track #34 established the core model that preserves facts before semantic interpretation or Mermaid formatting.

Rules:

- Canonical identity includes datasource/origin even though cross-origin export is currently
  unsupported. This makes the one-origin invariant provable rather than assumed.
- Table identity is origin + authoritatively available catalog + schema + exact table name.
- Column identity is scoped to the canonical table identity and preserves the exact column name.
- Canonical strings are not trimmed, case-folded, localized, escaped, or Mermaid-normalized.
- Catalog/schema absence is represented as absence; unavailable/read-failure is represented as
  unavailable. Neither becomes an invented default.
- Source column order is explicit and preserved. Core construction never silently sorts columns.
- Raw type facts remain raw metadata; Mermaid type-token formatting is a renderer concern.
- Optional values that are known absent and values that could not be read have different type
  states.
- Key/relation collections distinguish known-empty from unavailable evidence.
- Proven grouped unique keys and unverified unique-index/candidate-marker coverage are separate immutable facts; an unrelated unverified index cannot poison a FK tuple, while overlapping or unreadable index coverage never becomes a guessed NON_UNIQUE.
- Collection-valued canonical facts own immutable snapshots; caller-owned mutable collections are
  never retained or exposed as writable canonical state.
- Composite PK/FK and unique-key column order is preserved exactly.
- Platform exceptions may be summarized into plain diagnostics, but the core never retains a
  `Throwable` or platform object.

### FK endpoint evidence

Do not reproduce the legacy `RelationResolver.resolveTableReference()` behavior that treats missing
catalog/schema qualification like a wildcard and binds an FK to the only same-named selected table.
Selection-local uniqueness is not database identity evidence.

An FK target that is incompletely identified stays reference evidence/unresolved until the host can
supply sufficient authoritative identity. The semantic layer may fail/degrade it later; it may not
guess.

---

## 3. Semantic ER boundary

Completed Track #35 established conversion of the canonical snapshot into the minimal pure ER semantic graph needed by the
product. This boundary exists for correctness, not hypothetical alternate renderers.

Requirements:

- relation endpoints resolve only from authoritative identity evidence;
- unresolved endpoints remain explicit and cannot be hidden by selected-subgraph filtering;
- composite mappings, multiple FKs between the same pair, and self-relations remain distinct;
- physical versus IDE-virtual provenance is preserved only when the host API proves it;
- relation de-duplication never merges distinct provenance or mappings;
- canonical relation ordering is independent of provider/UI/hash iteration order;
- cardinality/optionality is derived only from explicit key/uniqueness/nullability evidence;
- any missing fact required for a cardinality claim leaves that claim unknown.

If maintained DatabaseTools APIs cannot prove a product promise such as virtual-relation provenance,
strong FK target identity, or uniqueness/nullability, amend the product contract with evidence. Do
not fabricate the promise inside an adapter or semantic heuristic.

---

## 4. Mermaid rendering boundary

Completed Track #36 established the Mermaid presentation and serialization boundary from pure semantic input.

Requirements:

- no live `Das*`, `Db*`, `Project`, selection, or relation-provider object reaches renderer code;
- no database metadata lookup or reflective type probing occurs in the renderer;
- display qualification is chosen separately from canonical identity;
- context-specific escaping/encoding is centralized and deterministic;
- hostile database-controlled text cannot escape its entity, attribute, quoted comment, line
  comment, or relation-label context;
- distinct canonical labels that normalize to the same rendered Mermaid label fail closed;
- type formatting consumes explicit snapshot facts and never probes platform getters;
- full output is deterministic;
- an unknown semantic cardinality never becomes the legacy default `||--o{`.

If Mermaid cannot faithfully express an otherwise-supported semantic state, treat that as a
product/semantic decision. The renderer is not allowed to guess or silently omit a known relation
and call the result complete.

---

## 5. JetBrains Database API boundary

The plugin targets exact maintained IntelliJ IDEA Ultimate/DataGrip evidence targets documented in
the product contract and README. Descriptor load eligibility is not a broader support claim.

`com.intellij.database.*` is a closed-source and partly unstable API surface. Before depending on a
symbol:

1. inspect the actual API for the exact target host (2026.3 IDEA SDK and independent 2026.3 DataGrip host inventory under #230; separately bounded 2026.2 backward evidence under #228);
2. prefer maintained/non-reflective access;
3. isolate unavoidable unstable/reflected access behind one host adapter;
4. convert failure into a typed platform outcome rather than `null`, empty collection, or false;
5. prove the dependency with the maintained runtime/verifier evidence appropriate to the claim.

In particular, the host Track must establish rather than assume:

- authoritative Database tool-window selection/group expansion;
- strongest usable datasource/origin identity;
- strongest usable referenced-table identity for FKs;
- physical versus IDE-virtual relation provenance;
- nullability/uniqueness/key metadata needed for cardinality;
- optional type-detail availability versus read failure.

No additional reflection point is accepted merely because an earlier implementation used
reflection. Internal DatabaseTools symbols that remain necessary must stay inside the single host
compatibility boundary and remain covered by exact-host inventory/verifier evidence.

---

## 6. Action lifecycle and threading

The action is a stateless entry point. Do not introduce a project service just to obtain a
coroutine scope; add a service only when real project-lifetime state needs an owner.

For the maintained 2026.3 SDK and separately retained 2026.2 compatibility targets:

- declare `getActionUpdateThread()` explicitly;
- keep `update()` bounded and side-effect free;
- `update()` must not expand schemas/groups, traverse metadata/relations, render, or perform work
  proportional to selected schema size;
- use only a constant-work, nonexpanding DatabaseTools selection preflight in `update()`; an empty tree selection disables the action, directly unsupported elements can be disabled, and an unresolved group selection remains eligible for authoritative invocation-time expansion;
- capture authoritative selection only after invocation;
- run expensive work in current cancellable coroutine/read-action APIs, off the EDT where platform
  contracts permit;
- check cancellation and project/database-object validity at meaningful asynchronous boundaries;
- return to the proper UI/EDT context only for final clipboard and notification work;
- never publish after cancellation, disposal, invalidation, or stale completion;
- no global/static mutable state may couple concurrent projects or exports.

The maintained action uses the action-owned coroutine scope and delegates DatabaseTools reads to the
host capture boundary. It must not regain the removed synchronous generator path or invoke the
reflected group-expansion symbol from `update()`.

---

## 7. Failure and diagnostics

Terminal outcomes are deliberate: complete, degraded, unsupported, failure, cancelled, and a
legitimate no-export/empty state where applicable.

- Exactly one terminal outcome is surfaced per invocation.
- Only complete success writes the clipboard.
- Expected metadata, selection, unsupported-input, cancellation, and platform-access problems use
  non-fatal logging plus an actionable user-facing diagnostic.
- `Logger.error` is reserved for genuine plugin invariant violations that merit IntelliJ fatal-error
  reporting.
- Diagnostics name the failing stage/symbol where useful without retaining unnecessary database
  contents.
- No `Throwable` crosses into or is retained by the pure core.

---

## 8. Testing and evidence

Tests run under `./gradlew check`; compatibility gates are defined by repository CI.

### Pure core

- no IntelliJ proxies or live IDE objects;
- exact structural equality for identity/evidence/key/FK/order cases;
- literal cases derived from `docs/product-baseline-scenarios.yaml`;
- explicit tests that absent and unavailable cannot collapse;
- explicit tests that incomplete FK evidence cannot become canonical parent identity by selected
  name coincidence.

### Semantic layer

- exact graph equality;
- composite/multiple/self/unresolved relation fixtures;
- known versus unknown cardinality truth-table coverage;
- deterministic ordering independent of input iteration order.

### Renderer

- complete-output golden strings, not broad `contains` assertions;
- hostile identifier/comment/type/relation-label fixtures;
- rendered-name collision tests;
- every supported cardinality representation and unknown handling.

### Host/integration

- selection failure differs from empty selection;
- mixed/cross-origin selection behavior;
- group expansion success/failure;
- datasource/FK/provenance metadata evidence;
- cancellation/disposal/stale callback behavior;
- clipboard unchanged on every non-success path;
- expected failures do not use fatal logging;
- maintained IDEA/DataGrip compatibility evidence.

Tests are never weakened, skipped, or made lenient merely to make CI green. If a prior test encodes
an obsolete internal API rather than a product invariant, replace/delete it when the corresponding
legacy path is removed and state why.

## 9. Performance and resource ownership

Do not add caching, background indexing, or preloading speculatively.

Scale evidence completed under #38 separates snapshot extraction, semantic compilation, Mermaid rendering,
string size, cancellation responsiveness, and EDT blocking. Optimize measured bottlenecks only.

No `Project`, `DbElement`, `Das*`, PSI object, action context, large snapshot, or generated string is
retained beyond the invocation unless a separately reviewed owner and benefit are demonstrated.

---

## 10. CI and repository policy

- The live ruleset requires one `Merge Gate`. It succeeds only when `Validation scope`, `Build`, `Test`, `Inspect code` (Qodana), `Verify plugin`, `Workflow Static Analysis`, `Dependency submission PR validation`, and `Dependency Review` succeed, and when each scope-applicable reusable `Host API Evidence` / `Starter E2E` workflow succeeds; known non-applicable evidence must be skipped.
- Documentation-only CI uses a narrow syntactic fast path only when every changed file is `README.md`, `CONTRIBUTING.md`, `AGENTS.md`, or a Markdown file under `docs/**`. This is not semantic classification. Build/Test/Qodana/Plugin Verifier runtime work is skipped, Workflow Static Analysis, Dependency submission PR validation, and Dependency Review still run, and `Merge Gate` remains required. Any mixed, empty, or unreadable changed-file set falls back to full validation.
- `Inspect code` is erdMaid's Kotlin/JVM static-analysis authority. It runs Qodana JVM Community with `qodana.recommended`, requires valid SARIF 2.1.0, and fails on any High/Critical finding. Qodana does not replace Plugin Verifier, Host API Evidence, Starter E2E, Build, or Test. The separate `Qodana Full Scan` workflow retains scheduled/manual exact-protected-main-SHA SARIF/provenance evidence and is nonpublishing; it is not yet Marketplace release authority.
- CI green is necessary but not sufficient for merge. Current ruleset strict status-check enforcement is false; current-composition proof remains mandatory under recovery policy.
- Third-party actions remain pinned to full commit SHAs.
- Validation workflows remain read-only and fail closed. The canonical `Build` PR DAG owns Host API / Starter applicability; incomplete or drifting PR file listings force both specialized evidence surfaces on rather than silently skipping them. `PR Metadata` remains a separate `pull_request_target` trust boundary.
- PR-time trusted-workflow compatibility validation is a non-privileged path inside the existing Workflow Static Analysis job. It retains the synthetic CodeQL artifact round trip and also executes the production-pinned CodeQL `init` / `analyze` runtime against the representative Actions language with `upload: never`. The validator rejects unsupported CodeQL YAML edits and incomplete or drifting PR file listings, the job has no `security-events: write`, and neither local SARIF nor PR artifacts are release/security evidence. The protected-main CodeQL schedule/manual scan and release evidence gate retain their separate authority.
- The repository-owned Gradle Dependency Submission publisher remains protected-main-only with `contents: write` and `generate-and-submit`. Production submission and the separate ARM64 read-only PR adapter both restrict Gradle extraction to `^intellijPlatformSandboxRuntimeClasspath$` and classify that configuration as runtime scope, because it is the developed plugin's packaged `lib/` dependency boundary. IntelliJ host, test, Starter, and build-tool configurations are not submitted as erdMaid product dependencies merely because they are present in the aggregate Gradle graph. The PR adapter keeps the same pinned checkout/setup-java/dependency-submission actions in `generate-and-upload` mode, binds evidence to the exact PR HEAD and `refs/pull/...`, requires a non-empty fail-closed packaged-runtime graph, and never promotes PR output to production evidence. The dependency-graph plugin's unavoidable direct `org.gradle:gradle-core` development node is the only permitted non-runtime entry; package-specific Dependabot ignores are not a substitute for this boundary.
- `actions/checkout` keeps `persist-credentials: false` unless a separately isolated write job
  demonstrably requires credentials.
- Metadata automation remains a distinct narrowly-permissioned trust boundary.
- Scheduled repository-drift checks fail when required live policy cannot be verified; lack of
  evidence is not success.
- Issue #99 completed the first public Marketplace distribution boundary. `Marketplace Candidate`
  remains a read-only exact-main staging/evidence path with no Marketplace or signing secrets and
  must never publish.
- Automated Marketplace updates are authorized only through `.github/workflows/release.yml` on the
  GitHub `release: released` event for a stable protected `vMAJOR.MINOR.PATCH` tag reachable from
  reviewed `main`. PR/fork/main validation must never reach Marketplace authority.
- Completed issue #134 established this release-authority transition. Production publication requires the
  `jetbrains-marketplace` environment and `PUBLISH_TOKEN`, `CERTIFICATE_CHAIN`, `PRIVATE_KEY`,
  and `PRIVATE_KEY_PASSWORD`; missing credentials fail before publication. Author signing,
  `verifyPluginSignature`, and signed-artifact digest identity are mandatory for automated release.
- The automated release uploads the same signature-verified ZIP to GitHub Releases and covers it
  with GitHub build-provenance attestation before Marketplace mutation. Because GitHub Releases is
  now a consumer distribution surface, attestation is part of the release provenance contract.
- Issue #51 completed the Apache-2.0 license/source-metadata reconciliation; those Marketplace and
  repository metadata remain part of the distribution contract.

---

## 11. Proof-obligation merge gate

Every PR is reviewed on its **exact final HEAD**. Any HEAD change invalidates prior PASS.
`UNKNOWN / UNVERIFIED / INSUFFICIENT EVIDENCE = FAIL` for merge decisions.

Before squash merge, actively check:

1. **Product semantics** — no user-visible/fidelity rule changed accidentally.
2. **Identity** — origin/table/column/key/FK identity is evidence-based; no name-only guess.
3. **Unknown handling** — absent, unavailable, unresolved, unsupported, and failure remain distinct.
4. **Ordering/determinism** — source column order and canonical graph/output ordering are proven.
5. **Relations/cardinality** — provenance/mappings/optionality/cardinality are never fabricated.
6. **Rendering safety** — hostile input cannot corrupt Mermaid grammar or canonical identity.
7. **Platform API** — new DatabaseTools usage is justified and verified on maintained targets.
8. **Threading/lifecycle** — update hot path, cancellation, EDT ownership, disposal, stale callbacks,
   and retention are correct for the affected slice.
9. **Diagnostics/publication** — exactly one terminal result; clipboard only on complete success;
   expected failures are non-fatal.
10. **Complexity** — no speculative abstraction, duplicated authority, forwarding wrapper, dead code,
    or compatibility layer exists without a real contract.
11. **Tests/evidence** — adversarial tests can falsify the changed invariants; no test was weakened.
12. **Docs/diff scope** — README/AGENTS/contract claims match shipped state and the diff has one
    independently reviewable purpose.
13. **Repository gate** — all required CI contexts pass on the exact final HEAD and merge-time main,
    ruleset, and unresolved review-thread state are revalidated.

Squash merge only after those obligations pass and the active work unit authorizes merge. Re-read post-merge `main` to confirm the expected commit/tree/signature and close the owning Task/Track only from merged evidence.

---
