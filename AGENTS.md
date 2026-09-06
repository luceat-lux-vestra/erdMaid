# erdMaid — Engineering Contract

Authoritative engineering and review contract for `erdMaid`, an IntelliJ Platform plugin that
exports database-table metadata from the Database tool window as Mermaid `erDiagram` text.

This document describes correctness and ownership boundaries. It is not a phase checklist and it
does not make the current implementation an architecture constraint. The original implementation
plan remains historical only at
[`docs/history/2026-04-initial-implementation-plan.md`](docs/history/2026-04-initial-implementation-plan.md).

The product/fidelity authority is
[`docs/product-fidelity-contract.md`](docs/product-fidelity-contract.md), with falsification
scenarios in [`docs/product-baseline-scenarios.yaml`](docs/product-baseline-scenarios.yaml).
When this file, legacy code, and the product contract disagree, do not preserve legacy code by
default: establish the intended product behavior from the contract and current evidence.

Keep this file current in the same PR whenever an engineering boundary changes.

---

## 0. Product invariants

- **Wrong-but-plausible output is worse than explicit failure or omission.**
- erdMaid never executes SQL and never mutates database metadata.
- One export contains exactly one datasource/origin unless the product contract is explicitly
  revised.
- Missing, absent, unknown, unavailable, unsupported, failed, and cancelled states are not
  interchangeable.
- Canonical database identity is never derived from rendered/sanitized Mermaid text.
- Relation endpoints, provenance, uniqueness, nullability, and cardinality are claimed only from
  evidence the maintained JetBrains Database APIs can actually establish.
- Same authoritative snapshot plus the same options produces byte-identical output.
- Clipboard publication happens exactly once and only after a complete successful export.
- Cancellation, failure, degraded output, and unsupported input leave existing clipboard contents
  untouched.
- Expected user/data/platform failures are not IntelliJ fatal errors.

Views and other non-table objects, alternate diagram formats, file output, persistent database
indexing, and database mutation are out of scope unless separately approved.

---

## 1. Leap architecture

The target architecture under Epic #32 is a **functional core + JetBrains host shell**:

`JetBrains selection -> immutable schema snapshot -> ER semantic graph -> Mermaid renderer -> typed result -> UI publication`

The current implementation is migration input only. Internal classes and package boundaries are
not compatibility contracts.

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

### Migration status

Until Tracks #35-#37 replace the production path, these files remain **temporary legacy code**:

| Path | Current role | Leap disposition |
|---|---|---|
| `src/main/kotlin/.../actions/ErdMaidExportActions.kt` | synchronous action, selection, publication | replace in #37 |
| `src/main/kotlin/.../generator/MermaidGenerator.kt` | mixed metadata extraction + rendering | replace/delete across #35-#37 |
| `src/main/kotlin/.../generator/RelationResolver.kt` | legacy FK discovery/resolution | replace/delete in #35/#37 |
| `src/main/kotlin/.../generator/MermaidSanitizer.kt` | current rendering hardening | retain or replace based on #36 design |
| `src/main/kotlin/.../core/*` | pure canonical snapshot model | forward architecture from #34 |

`TableSpec`, `ColumnSpec`, `RelationSpec`, legacy `TableIdentity`, reflection helpers, and current
method signatures are **not** to be preserved for internal compatibility. Do not make new core
models implement, wrap, or adapt those obsolete types. Temporary coexistence is allowed only until
the replacement path owns the behavior.

---

## 2. Canonical snapshot boundary

The #34 core model preserves facts before semantic interpretation or Mermaid formatting.

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

Track #35 converts the canonical snapshot into the minimal pure ER semantic graph needed by the
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

Track #36 owns only Mermaid presentation and serialization from pure semantic input.

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

1. inspect the actual maintained 2026.2 consumer API;
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

No additional reflection point is accepted merely because the current code already uses reflection.

---

## 6. Action lifecycle and threading

The final action is a stateless entry point. Do not introduce a project service just to obtain a
coroutine scope; add a service only when real project-lifetime state needs an owner.

For the maintained 2026.2 platform:

- declare `getActionUpdateThread()` explicitly;
- keep `update()` bounded and side-effect free;
- `update()` must not expand schemas/groups, traverse metadata/relations, render, or perform work
  proportional to selected schema size;
- capture authoritative selection only after invocation;
- run expensive work in current cancellable coroutine/read-action APIs, off the EDT where platform
  contracts permit;
- check cancellation and project/database-object validity at meaningful asynchronous boundaries;
- return to the proper UI/EDT context only for final clipboard and notification work;
- never publish after cancellation, disposal, invalidation, or stale completion;
- no global/static mutable state may couple concurrent projects or exports.

The current synchronous/reflected action is a known migration defect, not a compatibility behavior
to preserve.

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

---

## 9. Performance and resource ownership

Do not add caching, background indexing, or preloading speculatively.

Scale evidence under #38 separates snapshot extraction, semantic compilation, Mermaid rendering,
string size, cancellation responsiveness, and EDT blocking. Optimize measured bottlenecks only.

No `Project`, `DbElement`, `Das*`, PSI object, action context, large snapshot, or generated string is
retained beyond the invocation unless a separately reviewed owner and benefit are demonstrated.

---

## 10. CI and repository policy

- `Build`, `Test`, `Verify plugin`, and `Workflow Static Analysis` are authoritative required gates.
- CI green is necessary but not sufficient for merge.
- Third-party actions remain pinned to full commit SHAs.
- Validation workflows remain read-only and fail closed.
- `actions/checkout` keeps `persist-credentials: false` unless a separately isolated write job
  demonstrably requires credentials.
- Metadata automation remains a distinct narrowly-permissioned trust boundary.
- Scheduled repository-drift checks fail when required live policy cannot be verified; lack of
  evidence is not success.
- erdMaid currently has no release/publishing trust boundary; do not reintroduce release machinery
  without a separately approved distribution path.
- Issue #51 (project license selection) is separate repository/distribution hygiene and does not
  change architecture proof obligations.

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

Squash merge only after those obligations pass. Re-read post-merge `main` to confirm the expected
commit/tree/signature and close the owning Task/Track only from merged evidence.

---

## 12. Legacy deletion rule

The Leap is not complete while two semantic authorities remain.

When a replacement slice owns a behavior, remove the corresponding legacy implementation and tests
in that Track or its immediately following cleanup Task. Do not leave deprecated aliases, forwarding
wrappers, old specs, or adapter-on-adapter bridges merely to preserve plugin-internal APIs.

The final #38 deletion gate explicitly removes the legacy synchronous action path,
`MermaidGenerator`, `RelationResolver`, renderer-internal specs, reflected renderer type probing, and
obsolete tests unless current evidence independently justifies retaining a specific artifact.
