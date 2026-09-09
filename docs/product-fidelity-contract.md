# erdMaid Product and Fidelity Contract

This document is the authoritative product contract for schema-to-Mermaid export. Architecture may change; these user-visible and fidelity rules change only through an explicit product-policy revision backed by evidence.

Implementation is evidence, not product authority. The maintained implementation must fail closed when it cannot establish a fact this contract requires.

## Product rule

**Wrong-but-plausible output is worse than explicit failure or omission.**

A diagram must never look complete by silently guessing identity, keys, relationships, cardinality, selection intent, provenance, or metadata that erdMaid could not establish.

The exporter never executes SQL and never mutates database metadata.

## Outcome vocabulary

| Outcome | Meaning | Clipboard |
| --- | --- | --- |
| **SUPPORTED / complete** | The requested export is within product scope and every structural fact required for trustworthy output is known. | Publish exactly once after the complete diagram is built and final freshness/lifecycle checks pass. |
| **UNSUPPORTED** | The requested selection or host shape is outside the maintained product contract. | Do not change it. Surface an explicit unsupported outcome when invocation is possible. |
| **DEGRADED** | The request is understood, but required non-fabricable metadata or rendering evidence is unavailable. | Do not publish. |
| **UNKNOWN** | Available evidence cannot distinguish the semantic state. Unknown is not equivalent to absent, false, empty, or unsupported. | If it affects structural truth, do not publish a complete diagram. |
| **FAILURE** | Selection/platform access, identity resolution, invariant validation, rendering, or publication failed. | Do not change it. |
| **CANCELLED** | The export was cancelled. | Do not change it. |

A legitimately absent optional value is not degraded. A read failure that cannot be distinguished from absence remains unknown/unavailable until evidence proves otherwise.

## Selection and export scope

| Input / selection shape | Contract | Required behavior |
| --- | --- | --- |
| One physical table | **SUPPORTED** | Export when required structural metadata is available. |
| Multiple physical tables in one schema | **SUPPORTED** | Export as one diagram. |
| Schema `Tables` node | **SUPPORTED** | Authoritatively expand the represented tables before snapshotting. Expansion failure is not empty selection. |
| Tables across multiple schemas/catalogs in one datasource/origin | **SUPPORTED** | Preserve exact identity and qualify display names only as needed to disambiguate. |
| Tables from more than one datasource/origin | **UNSUPPORTED** | Reject the whole export. Do not split implicitly or merge same-named objects across origins. |
| Exact duplicate UI selections of the same authoritative object | **SUPPORTED** | Normalize duplicates before snapshotting. Name coincidence alone is not identity proof. |
| Views, materialized views, routines, or other non-table objects | **UNSUPPORTED** | Do not silently treat them as exported tables. |
| Mixed supported tables + unsupported objects | **UNSUPPORTED** | Reject the whole mixed export. Never silently filter the unsupported portion. |
| Legitimate empty selection | **SUPPORTED UI state; no export** | Distinguish it from selection/platform failure; do not fabricate an empty ERD. |
| Selection/platform-access failure | **FAILURE** | Surface failure and leave the clipboard unchanged. |

One export contains exactly one datasource/origin. Canonical identity therefore carries origin evidence even though cross-origin export is unsupported.

## Metadata fidelity

| Metadata fact | Contract | Fidelity rule |
| --- | --- | --- |
| Datasource/origin | **Required structural fact** | Establishes the single-origin invariant and canonical identity; never inferred from display text. |
| Catalog / schema | **SUPPORTED, may be authoritatively absent** | Preserve exact value when present. Absence is not replaced with an invented default. |
| Table / column identity | **Required structural fact** | Preserve exact case and whitespace. Render encoding never changes canonical identity. Ambiguity fails closed. |
| Column order | **SUPPORTED** | Preserve authoritative metadata order; do not alphabetize. |
| Raw type name | **Required for current Mermaid attribute output** | Preserve the available database-model type fact; formatting is renderer-only. |
| Length / precision / scale | **Optional** | Render only when authoritatively available. Unknown/read failure is not zero. |
| Table / column comments | **Optional** | Preserve when available and encode safely. Proven read failure degrades instead of pretending absence. |
| Primary key existence and ordered columns | **Structural** | Known-none and unavailable are distinct. Never infer keys from names. |
| Foreign key existence and ordered mappings | **Structural** | Preserve ordered child/parent mappings and relation provenance. Known-none and unavailable are distinct. |
| Nullability | **Semantic evidence** | Used only where authoritative evidence justifies a multiplicity rule. It is never generalized into unrelated referential-integrity semantics. |
| Uniqueness | **Semantic evidence** | Used to derive relation maxima only when exact key/unique evidence supports the tuple claim. |
| Default value | **No current Mermaid output claim** | Do not invent or expose a product promise without a contract revision. |

Structural read failure cannot become a plausible complete diagram. Optional presentation metadata may be omitted only when absence is authoritative.

## Relation fidelity

| Relation case | Contract | Required behavior |
| --- | --- | --- |
| Database-declared FK between selected tables | **SUPPORTED when required evidence is complete** | Preserve endpoints, ordered mappings, identity, physical provenance, identification and multiplicity evidence. |
| IDE/DataGrip virtual relation between selected tables | **SUPPORTED semantic fact; Mermaid publication evidence-bounded** | Preserve virtual provenance distinctly. Do not treat a virtual relation as database-enforced merely because mappings/nullability exist. |
| Multiple FKs between the same table pair | **SUPPORTED** | Preserve each distinct relation/mapping identity. |
| Composite FK | **SUPPORTED** | Preserve ordered child/parent column pairs. |
| Self-reference | **SUPPORTED** | Preserve exact self endpoint and mapping. |
| Relation whose other endpoint is outside the selected snapshot | **SUPPORTED omission policy** | Omit it from the selected-subgraph diagram; do not auto-include the other table. |
| Referenced endpoint cannot be resolved uniquely | **UNKNOWN structural fact -> DEGRADED** | Never rebind by display name or best guess. |
| Relation provenance cannot be established | **UNKNOWN relation fact -> DEGRADED** | Never label it physical/virtual by guess. |
| Required multiplicity or identification fact is unavailable | **UNKNOWN structural fact -> DEGRADED** | Do not fabricate a Mermaid marker/connector or publish a complete diagram. |

### Cardinality and identification

The maintained semantic pipeline derives renderer-neutral multiplicity before Mermaid formatting:

- `parentsPerChild.minimum` for a physical FK is derived from authoritative child-tuple nullability when the mapping shape makes that conclusion valid;
- a virtual relation does not itself prove referential integrity, so its required parent minimum may remain unavailable;
- relation maxima are derived from authoritative tuple uniqueness evidence;
- `childrenPerParent.minimum` is zero because a parent row does not imply that a child row exists;
- partial evidence remains partial rather than collapsing to a default.

The Mermaid renderer maps only fully known `{minimum ZERO|ONE, maximum ONE|MANY}` bounds to Mermaid end tokens. It never falls back to the removed legacy `||--o{` default. Identifying versus non-identifying semantics similarly produce `--` versus `..` only when the required semantic evidence is known.

Mermaid `erDiagram` requires explicit end cardinalities. If a known relation lacks any required bound, the terminal export is degraded and unpublished. This is especially important for IDE/DataGrip virtual relations: column nullability is not proof that a virtual relationship is referentially enforced.

## Determinism and identity

For the same authoritative snapshot plus the same options, output must be byte-identical.

- Table ordering is canonical and independent of incidental UI/provider/hash iteration order.
- Column ordering preserves authoritative metadata order.
- Relation ordering is canonical from exact endpoints, provenance, ordered mappings, and stable relation identity/name where available.
- Identifier comparison is locale-independent and exact; canonical identity is not case-folded or whitespace-normalized.
- Display qualification is separate from canonical identity.
- Rendered-name collisions fail closed before publication.
- Duplicate canonical objects are normalized only when authoritative identity proves equivalence.

## Mermaid presentation boundary

Mermaid syntax is presentation, not database truth.

- Canonical metadata is never rewritten to create a database fact.
- Context-specific escaping/encoding is deterministic and injective for the maintained contexts.
- Hostile database-controlled text must not create extra Mermaid statements, comments, entities, attributes, or relations.
- Type-display normalization and FK-reference comments are presentation choices.
- Current generated `erDiagram` syntax is bound to executable Mermaid 11.17.2 parser evidence in CI. The evidence claim is exact-version bounded, not a promise for every Mermaid release.
- A Mermaid grammar limitation is never permission to invent a database fact.

## Maintained workflow and lifecycle

The shipped workflow is:

1. perform a bounded, side-effect-free UI eligibility check;
2. capture one authoritative selection for the invocation;
3. distinguish empty, unsupported, mixed-origin, and platform-failure states;
4. copy live DatabaseTools facts into an immutable snapshot;
5. compile semantic relations and Mermaid text through the pure pipeline with invocation-local cancellation checkpoints;
6. revalidate cancellation, project/object freshness and lifecycle before publication;
7. publish the clipboard exactly once only for complete success;
8. surface one terminal typed outcome.

Failure, cancellation, unsupported input, degraded output, stale completion, and lifecycle invalidation leave existing clipboard content untouched. Concurrent exports/projects share no mutable production state.

The repository maintains executable large-selection evidence for a deterministic synthetic `1000 tables x 40 columns / 3000 relations` workload. It records snapshot/semantic/render/pipeline timing and output size separately, proves byte-identical repeated output, bounded cancellation observation, no publication after cancellation, recovery publication after a later successful invocation, and concurrent invocation isolation. Those measurements are regression evidence, not a performance SLA.

Invocation-owned heap/GC retention after terminal completion remains **UNVERIFIED**: the repository does not claim deterministic hosted-CI observation of garbage collection. No retention PASS is inferred from the absence of a leak symptom.

## Host support and evidence classes

Support claims are bounded to exact maintained evidence. Compile/test runtime, Plugin Verifier binary compatibility, exact Host API Inventory evidence, and live GUI/database-tool-window runtime evidence are distinct classes.

| Exact host/version | Maintained claim | Evidence |
| --- | --- | --- |
| IntelliJ IDEA Ultimate 2026.2.0.1 / `IU-262.8665.337` | **SUPPORTED compile/test runtime target; exact Host API Inventory target** | `buildPlugin` + `check`, plus deterministic inspection of the resolved DatabaseTools runtime. |
| IntelliJ IDEA Ultimate 2026.2.2 / `IU-262.10315.125` | **SUPPORTED binary-compatibility target; full Host API Inventory/runtime-test status UNVERIFIED** | IJPGP 2.18.1 Plugin Verifier: `Compatible`. No broader runtime or inventory claim is inferred. |
| DataGrip 2026.2.4 / `DB-262.10315.24` | **SUPPORTED binary-compatibility target; exact Host API Inventory target; live GUI/database integration runtime UNVERIFIED** | Pinned standalone Plugin Verifier 1.410: `Compatible`, plus deterministic exact-binary DatabaseTools relation/FK inventory. |
| Other IntelliJ IDEA Ultimate/DataGrip versions | **UNKNOWN / not maintained** | No exact maintained evidence claim. |
| Other IntelliJ-based products | **UNKNOWN / not maintained** | Database tooling/load eligibility is not product-specific proof. |
| Platform builds older than 262 | **UNSUPPORTED by declared load baseline** | Below `since-build="262"`. |

The packaged descriptor has no `until-build`; this permits later hosts to attempt loading but does not prove support.

Maintained binary inventory evidence establishes that virtual/external DatabaseTools relation surfaces can expose relation/mapping/provenance facts without exposing a relation-specific mandatory-parent/cardinality fact. Therefore virtual relation cardinality is not synthesized from class names or column nullability.

## Baseline-scenario authority

[`docs/product-baseline-scenarios.yaml`](product-baseline-scenarios.yaml) is the architecture-neutral falsification set for this contract. Implementations and evidence changes must evaluate each applicable scenario's explicit obligations rather than infer a uniform production data schema from the YAML.

A maintained architecture that cannot represent an applicable scenario is incomplete even if happy-path tests are green.

## Change control

Any product/fidelity change must:

- be owned by #33 or an explicitly approved successor product-policy issue;
- state which user-visible/fidelity rule changes and why;
- update affected baseline scenarios when their product obligation changes;
- identify maintained implementation/evidence that becomes invalid;
- pass the repository proof-obligation merge gate on the exact final HEAD.
