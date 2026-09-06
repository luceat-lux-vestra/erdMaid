# erdMaid Product and Fidelity Contract

This document is the authoritative product contract for schema-to-Mermaid export work under
Leap Track #33. Architecture may change; these user-visible and fidelity rules do not change
unless #33 is deliberately revised with new evidence.

The current implementation is evidence, not authority. Where current behavior conflicts with
this contract, the conflict is a known migration obligation for #34–#38 rather than a reason to
weaken the contract.

## Product rule

**Wrong-but-plausible output is worse than explicit failure or omission.**

A diagram must never look complete by silently guessing identity, keys, relationships,
cardinality, selection intent, or metadata that erdMaid could not establish.

The exporter never executes SQL and never mutates database metadata.

## Outcome vocabulary

| Outcome | Meaning | Clipboard |
| --- | --- | --- |
| **SUPPORTED / complete** | The requested export is within product scope and every structural fact required for trustworthy output is known. | Publish only after the complete diagram is built successfully. |
| **UNSUPPORTED** | The user requested a selection or host shape outside the maintained product contract. | Do not change it. Surface an explicit unsupported outcome when invocation is possible. |
| **DEGRADED** | The request is understood, but some non-fabricable metadata could not be read or validated. The result must identify what is incomplete. | Do not publish. Any future degraded-copy UX requires an explicit product-contract revision. |
| **UNKNOWN** | Available platform evidence cannot distinguish the semantic state. Unknown is not equivalent to absent, false, empty, or unsupported. | If the unknown affects structural truth, do not publish a complete diagram. |
| **FAILURE** | Selection/platform access, identity resolution, invariant validation, rendering, or publication failed. | Do not change it. |
| **CANCELLED** | The user cancelled an in-progress export. | Do not change it. |

A legitimately absent optional value is not a degraded state. A platform read failure that is
currently represented by the same API value as absence is **UNKNOWN** until an adapter can prove
which case occurred.

## Selection and export-scope matrix

| Input / selection shape | Contract | Required behavior |
| --- | --- | --- |
| One physical table | **SUPPORTED** | Export when required structural metadata is available. |
| Multiple physical tables in one schema | **SUPPORTED** | Export as one diagram. |
| Schema `Tables` node | **SUPPORTED** | Authoritatively expand to the tables represented by that node before snapshotting. Expansion failure is not an empty selection. |
| Tables across multiple schemas in one datasource/origin | **SUPPORTED** | Preserve exact identity and qualify rendered names only as needed to disambiguate. |
| Tables across multiple catalogs in one datasource/origin | **SUPPORTED** | Preserve exact identity and qualify rendered names only as needed to disambiguate. |
| Tables from more than one datasource/origin in one invocation | **UNSUPPORTED** | Reject the whole export. Do not split implicitly and do not merge same-named objects across origins. A future cross-origin feature requires an explicit product/UX decision. |
| Exact duplicate UI selections of the same authoritative object | **SUPPORTED** | Normalize duplicates before snapshotting. Distinct objects that merely collapse to the same incomplete identity are not assumed equivalent. |
| Views, materialized views, routines, or other non-table objects | **UNSUPPORTED** | Do not pretend they were exported. No view implementation is implied by this contract. |
| Mixed supported tables + unsupported objects | **UNSUPPORTED** | Reject the whole mixed export. Never silently drop the unsupported part. Any future partial-export UX requires an explicit product-contract revision and an explicit user choice. |
| Legitimate empty selection | **SUPPORTED UI state; no export** | Distinguish it from platform/selection failure. No clipboard mutation and no fabricated empty ERD. |
| Selection API/reflection/platform-access failure | **FAILURE** | Surface selection failure. Never convert it to empty selection. |

### Origin boundary

One export contains exactly one datasource/origin. Selection authority must therefore retain
enough origin information to prove that all selected tables belong to the same origin. The
canonical metadata model may still carry origin identity even though cross-origin export is
unsupported; doing so prevents accidental collisions and makes the single-origin invariant
provable.

## Metadata-fidelity matrix

| Metadata fact | Contract | Fidelity rule |
| --- | --- | --- |
| Datasource/origin | **Required structural fact** | Used to establish the single-origin invariant and canonical identity. Never inferred from display text. |
| Catalog | **SUPPORTED, may be authoritatively absent** | Preserve exact value when available. Absence is not replaced with an invented default. |
| Schema | **SUPPORTED, may be authoritatively absent** | Preserve exact value when available. Absence is not replaced with an invented default. |
| Table identity/name | **Required structural fact** | Exact, case- and whitespace-preserving identity. Ambiguous identity fails closed. |
| Column identity/name | **Required structural fact** | Exact, case- and whitespace-preserving identity. Render sanitization never changes canonical identity. |
| Column order | **SUPPORTED** | Preserve authoritative metadata order. Do not alphabetize columns. |
| Raw type name | **Required for current Mermaid attribute output** | Preserve the available database-model type fact; renderer formatting is separate. Missing/unreadable required type metadata cannot be replaced by a guessed token. |
| Length / precision / scale | **Optional** | Render only when authoritatively available. Unknown/read failure is not zero and is not a fabricated default. |
| Table / column comments | **Optional** | Preserve when available and render safely. Authoritative absence is complete; a proven read failure produces a degraded outcome rather than a fabricated absence. |
| Primary key existence and ordered columns | **Structural** | `none` and `unknown` are different states. Mark PK only from authoritative key evidence; never infer from names. |
| Foreign key existence and ordered mappings | **Structural** | Preserve exact ordered child/parent mappings and source provenance. `none` and `unknown` are different states. |
| Nullability | **UNKNOWN / not currently promised as output** | May be captured when authoritative evidence exists, but no user-visible claim is made merely because an API exposes a value. |
| Uniqueness beyond PK | **UNKNOWN / not currently promised as output** | Required only when a later relation/cardinality rule needs it; never infer it. |
| Default value | **UNKNOWN / not currently promised as output** | No current Mermaid output claim. |

Structural metadata read failure cannot be converted into a plausible complete diagram. Optional
presentation metadata may be omitted only when its absence is authoritative; a proven read
failure produces a degraded outcome and therefore no clipboard publication under this contract.

## Relation-fidelity matrix

| Relation case | Contract | Required behavior |
| --- | --- | --- |
| Database-declared FK between selected tables | **SUPPORTED** | Preserve endpoints, ordered mappings, identity, and physical provenance. |
| IDE/DataGrip virtual relation between selected tables | **SUPPORTED** | Preserve virtual provenance distinctly from a database-declared FK. Do not merge provenance away merely because endpoints/mappings match. |
| Multiple FKs between the same table pair | **SUPPORTED** | Preserve each distinct ordered mapping/relation identity. |
| Composite FK | **SUPPORTED** | Preserve ordered child/parent column pairs. |
| Self-reference | **SUPPORTED** | Preserve exact self endpoint and mapping. |
| Relation whose other endpoint is outside the selected snapshot | **SUPPORTED omission policy** | Omit it from this selected-subgraph diagram. This is deliberate scope filtering, not missing metadata and not permission to auto-include the other table. |
| Referenced endpoint cannot be resolved uniquely inside the selected snapshot | **UNKNOWN structural fact → DEGRADED** | Never rebind by display name or best guess. Do not publish a complete diagram. |
| Relation source/provenance cannot be established | **UNKNOWN relation fact → DEGRADED** | Do not present it as definitively physical or virtual and do not publish a complete diagram. |
| Cardinality/optionality without uniqueness/nullability evidence | **UNKNOWN** | Do not claim a Mermaid cardinality as database truth. Connectivity may still be complete if the renderer uses a representation that does not fabricate cardinality. |

### Cardinality rule

Current code renders every relation as `||--o{`. The current relation model does not contain
sufficient uniqueness/nullability evidence to prove that semantic claim. Therefore
`||--o{` is **legacy rendering behavior, not an approved product-fidelity contract**.

Tracks #35 and #36 must either derive a Mermaid cardinality only when the required facts are
known or choose an output representation that does not fabricate cardinality. They may not
re-label the legacy marker as “good enough” connectivity.

## Determinism and identity

For the same authoritative selection snapshot plus the same options, output must be
byte-identical.

- Table ordering is canonical and independent of incidental UI iteration/selection order.
- Column ordering preserves authoritative database metadata order.
- Relation ordering is canonical from exact endpoints, provenance, ordered mappings, and a
  stable relation identifier/name where available.
- Identifier comparison is locale-independent and exact; no case folding or whitespace
  normalization is allowed in canonical identity.
- Display qualification is separate from canonical identity. Prefer an unqualified table name
  when unique in the export, then schema qualification when sufficient, then catalog + schema
  qualification when required. Sanitization happens only after that display decision.
- A rendered-name collision fails closed before any clipboard publication.
- Duplicate canonical objects are normalized only when authoritative identity proves they are
  the same selected object; ambiguity is not de-duplication.

## Mermaid and presentation boundary

Mermaid syntax is a renderer concern, not database truth.

- Canonical metadata is never rewritten to satisfy Mermaid grammar.
- Context-specific escaping/sanitization is deterministic and idempotent.
- Hostile metadata must not create additional Mermaid statements, comments, entities,
  attributes, or relations.
- Type-display normalization and optional FK-reference comments are presentation choices.
- A Mermaid grammar limitation is not permission to invent a database fact.

## User workflow and terminal outcomes

The target workflow for #37 is:

1. perform a bounded, side-effect-free UI eligibility check;
2. capture one authoritative selection for the invocation;
3. distinguish empty, unsupported, mixed-origin, and platform-failure states;
4. snapshot metadata without retaining live platform objects longer than required;
5. derive relations/diagram/render output away from the UI hot path where platform contracts
   permit;
6. check cancellation/disposal before publication;
7. publish the clipboard exactly once only for a complete successful export;
8. surface exactly one terminal complete / degraded / unsupported / failure / cancelled outcome.

Failure, cancellation, unsupported input, structural unknowns, and degraded output do not
overwrite existing clipboard content.

## Host support and compatibility evidence

Host/product/version claims are bounded to exact maintained evidence targets. Plugin descriptor
eligibility and a shared platform build number are not evidence that every product or patch on
that line is supported. Runtime-test evidence and binary Plugin Verifier evidence are distinct
classes and must not be presented as equivalent.

| Exact host/version | Contract | Maintained evidence |
| --- | --- | --- |
| IntelliJ IDEA Ultimate 2026.2.0.1 / `262.8665.337` | **SUPPORTED runtime-test target** | `buildPlugin` + `check` execute the plugin build and test suite on this exact target. |
| IntelliJ IDEA Ultimate 2026.2.2 / `IU-262.10315.125` | **SUPPORTED binary-compatibility target; runtime-test status UNKNOWN** | IJPGP 2.18.1 Plugin Verifier: `Compatible`. Ordinary test runtime currently fails during unrelated `com.intellij.modules.ultimate` startup before erdMaid tests can execute, so runtime behavior is not claimed as tested. |
| DataGrip 2026.2.4 / `DB-262.10315.24` | **SUPPORTED binary-compatibility target; live integration runtime status UNKNOWN** | Standalone pinned Plugin Verifier 1.410: `Compatible`. The current CI does not provide a live database-tool-window integration runtime test for this target. |
| Other IntelliJ IDEA Ultimate or DataGrip versions, including other build-262 patches | **UNKNOWN / not a maintained version claim** | No exact maintained target evidence in the current matrix. |
| Other IntelliJ-based IDE products with database tooling | **UNKNOWN / not a maintained product claim** | Loading may be possible through `com.intellij.database`, but no maintained product-specific evidence exists. |
| Platform builds newer than the maintained exact targets | **UNKNOWN / not a maintained support claim** | The plugin descriptor may permit loading, but descriptor permissiveness is not compatibility evidence. |
| Platform builds older than 262 | **UNSUPPORTED by declared load baseline** | Below `since-build="262"`. |

Adding or widening a maintained host/version claim requires explicit evidence of the claimed
class in the same change. A Plugin Verifier result proves binary compatibility for its exact
target; it does not by itself prove live Database-tool-window behavior.

## Baseline-scenario authority

`docs/product-baseline-scenarios.yaml` is an architecture-neutral set of falsification
scenarios for #34–#38. It intentionally describes inputs, facts, and obligations without
naming proposed production classes. Scenario-specific `facts` are intentionally lightweight;
consumers must evaluate each scenario's explicit obligations rather than infer a uniform
production data schema from this YAML.

Downstream Tracks must cite both this contract and the relevant scenario IDs. A downstream
architecture that cannot represent an applicable scenario is incomplete even if its happy-path
tests are green.

## Known current implementation deviations

These are migration obligations, not approved behavior:

1. reflected selection failure currently collapses to an empty/fallback selection;
2. mixed/unsupported objects are silently removed by `filterIsInstance<DbTable>()`;
3. `update()` performs the same reflected group-expansion path used for authoritative capture;
4. canonical identity does not yet include datasource/origin;
5. physical/virtual relation provenance is discarded;
6. every rendered relation currently claims `||--o{` without proven cardinality evidence;
7. reflective type-detail reads can collapse read failure to absent metadata;
8. table/relation output order follows current input/provider iteration rather than an explicit
   canonical product ordering rule;
9. expected export failures are currently logged through `Logger.error`, which can present as
   an IDE fatal-error report;
10. action execution is synchronous and can perform metadata/render work on the UI thread.

No downstream Track may cite one of these deviations as a compatibility requirement merely
because it exists today.

## Change control

Any change to this contract must:

- be owned by #33 or an explicitly approved successor product-policy issue;
- state which user-visible/fidelity rule changes and why;
- update affected baseline scenarios in the same PR;
- identify downstream Tracks/implementations that become invalid;
- pass the repository proof-obligation merge gate on the exact final HEAD.
