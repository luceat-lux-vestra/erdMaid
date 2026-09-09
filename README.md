# erdMaid - Mermaid ERD Export for Database Tables

erdMaid exports database-table metadata from the IntelliJ Database tool window as Mermaid `erDiagram` text.

## What it does

- Exports the schema-level `Tables` node or individual physical tables selected beneath it.
- Supports tables across schemas/catalogs when they belong to one datasource/origin.
- Preserves authoritative column order, canonical table/column identity, PK/FK mappings, relation provenance, available type details, and comments.
- Derives relationship multiplicity only from explicit uniqueness/nullability/referential-integrity evidence.
- Fails closed when required identity, relation, cardinality, selection, or metadata evidence is unavailable instead of fabricating a plausible diagram.
- Serializes deterministic Mermaid text with context-specific encoding and copies it to the clipboard only after a complete successful export.
- Cancellation, degraded/unsupported input, platform failure, disposal/invalidation, or stale completion does not publish.

The authoritative product and fidelity rules are in
[`docs/product-fidelity-contract.md`](docs/product-fidelity-contract.md).

<!-- Plugin description -->
erdMaid is a Mermaid ERD export plugin for the IntelliJ Database tool window.

It is designed for developers who want to turn selected database tables into deterministic Mermaid `erDiagram` syntax without manually rewriting table metadata.

Key behavior:

- Export a schema's `Tables` node or one or more physical tables from the Database tool window.
- Preserve database metadata order and exact canonical identity before rendering.
- Include PK/FK metadata, available type details and comments, and distinguish physical from IDE virtual relation provenance.
- Derive Mermaid cardinality only when the maintained metadata evidence proves it; an unknown required cardinality fails closed and is not replaced with a default marker.
- Encode database-controlled text for its exact Mermaid grammar context.
- Copy to the clipboard only after a complete successful export.

Typical workflow:

1. In the Database tool window, expand a schema and open its `Tables` node.
2. Select the `Tables` node to export every represented table, or select one or more individual physical tables.
3. Right-click and choose `Export as Mermaid ERD (erdMaid)`.
4. Paste the generated Mermaid text into Markdown, docs, or another Mermaid-enabled editor.

Output details:

- Table comments are emitted as `%%` Mermaid comments.
- Column lines follow `type name [PK] ["comment"]`.
- Type names and optional size/precision/scale details are normalized only at the renderer boundary.
- Relationships preserve ordered FK mappings and provenance labels.
- Mermaid relationship end markers are compiled from proven multiplicity bounds. Unknown required bounds produce a degraded unpublished result rather than a guessed edge.
- Identifying and non-identifying relationships use Mermaid's `--` and `..` connectors when that semantic fact is established.

Requirements:

- IntelliJ IDEA Ultimate or DataGrip with database tooling. Maintained claims are limited to the exact evidence targets documented below.
- A database connection whose required table metadata is available in the Database tool window.

Notes:

- `classDiagram` is intentionally not supported.
- Views and other non-table objects are unsupported export targets.
- Cross-datasource and mixed supported/unsupported selections fail closed instead of being silently filtered.
- Descriptor load eligibility is not a support guarantee for unverified products or versions.
<!-- Plugin description end -->

## Usage

1. Open the Database tool window in a maintained IntelliJ IDEA Ultimate or DataGrip target.
2. Expand a schema and open its `Tables` node.
3. Select the node itself or one or more physical tables.
4. Right-click and choose `Export as Mermaid ERD (erdMaid)`.
5. Paste the generated Mermaid text where needed.

A single export may span multiple schemas/catalogs only within one datasource/origin. Cross-datasource selections, views, and mixed table/non-table selections are unsupported and do not mutate the clipboard.

## Architecture

The maintained export path is:

`Database tool-window selection -> immutable SchemaSnapshot -> pure semantic graph -> pure Mermaid serializer -> typed outcome -> guarded UI publication`

The JetBrains host shell owns selection, DatabaseTools access, lifecycle/cancellation, freshness validation, and clipboard/notification publication. The functional core owns canonical snapshot values, relation semantics, multiplicity evidence, deterministic ordering, and Mermaid rendering. Live JetBrains objects do not cross into the core/renderer pipeline.

The removed synchronous legacy generator/resolver/sanitizer path is not a compatibility authority and is not used by production export.

## Output and fidelity

- Canonical database identity is kept separate from rendered Mermaid identifiers/aliases.
- Same authoritative snapshot plus the same options produces byte-identical output.
- Physical and IDE virtual relation provenance are kept distinct.
- A known relation is serialized only when every Mermaid-required semantic fact is available.
- Physical FK optionality/maximum bounds are derived from maintained nullability/uniqueness evidence where that evidence is sufficient.
- IDE virtual relation facts do not by themselves prove referential integrity. If the required cardinality remains unavailable, the export is degraded and unpublished.
- Mermaid 11.17.2 grammar acceptance for generated syntax is checked in CI against the exact pinned package/integrity and production-bound fixtures.

## Maintained compatibility evidence

Evidence classes are deliberately separate. A PASS in one class does not imply the others.

| Evidence class | Host | Exact version/build | What it proves |
| --- | --- | --- | --- |
| Compile + automated test runtime | IntelliJ IDEA Ultimate | 2026.2.0.1 / `IU-262.8665.337` | Plugin compilation plus repository automated tests on the maintained baseline runtime. |
| Exact DatabaseTools Host API Inventory | IntelliJ IDEA Ultimate | 2026.2.0.1 / `IU-262.8665.337` | Required DatabaseTools classes/symbols used by the host boundary are present with the reviewed exact-binary shape. |
| Plugin Verifier binary compatibility | IntelliJ IDEA Ultimate | 2026.2.2 / `IU-262.10315.125` | Packaged-plugin binary compatibility for this exact target. |
| Plugin Verifier binary compatibility | DataGrip | 2026.2.4 / `DB-262.10315.24` | Packaged-plugin binary compatibility for this exact target. |
| Exact DatabaseTools Host API Inventory | DataGrip | 2026.2.4 / `DB-262.10315.24` | Reviewed relation/FK DatabaseTools binary surface for this exact target. |

The repository does **not** currently claim a live GUI/database-tool-window integration runtime test on DataGrip 2026.2.4. It also does not claim a full Host API Inventory for IntelliJ IDEA Ultimate 2026.2.2. Those states remain unverified rather than being inferred from Plugin Verifier results.

The packaged plugin descriptor declares `since-build="262"` and no `until-build`. That controls load eligibility only. It does not establish maintained support for every 262 patch, later platform line, or other IntelliJ-based product.

## Installation

When the plugin is published to JetBrains Marketplace:

- <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd>
- Search for `erdMaid`
- Click <kbd>Install</kbd>

For local development builds, install the generated ZIP from `build/distributions`.

## Development

- Build: `./gradlew build`
- Tests: `./gradlew check`
- Plugin compatibility: `./gradlew verifyPlugin` plus the repository's pinned DataGrip verifier gate.
- Development builds use a timestamp-based plugin version by default.
- Release builds can set `-PbuildVersion=x.y.z`.
- CI uses Java 25 for the IntelliJ Platform 2026.2 baseline.
- Product/fidelity authority: [`docs/product-fidelity-contract.md`](docs/product-fidelity-contract.md).
- Architecture-neutral falsification scenarios: [`docs/product-baseline-scenarios.yaml`](docs/product-baseline-scenarios.yaml).
- Mermaid grammar evidence: [`docs/mermaid-grammar-evidence.md`](docs/mermaid-grammar-evidence.md).

## Notes

- `classDiagram` is intentionally unsupported.
- Views and other non-table objects are unsupported export targets.
- Wider descriptor compatibility is not a maintained support claim.
