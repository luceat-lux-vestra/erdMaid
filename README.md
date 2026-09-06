# erdMaid - Mermaid ERD Export for Database Tables

erdMaid is a Mermaid ERD export plugin for database tables in the Database tool window.

## What it does

- Exports either the schema's `Tables` node itself or individual tables selected under that node in the Database tool window.
- Works from the schema-level table list, where all tables for that schema are visible and can be exported together.
- Preserves the actual column order from database metadata.
- Includes primary keys, foreign key relationships, column types, and column comments when the required metadata is available.
- Renders column size details in a Mermaid-safe format when those details are available.
- Copies the diagram to the clipboard only after a complete successful export and shows a completion notification.

The authoritative supported-selection and metadata-fidelity rules are in
[`docs/product-fidelity-contract.md`](docs/product-fidelity-contract.md). In particular, one export is a single-datasource/origin operation; cross-datasource and mixed table/non-table selections are not supported product behavior and must not be silently treated as a successful partial export. The contract is the target product authority for Leap work, not an assertion that every legacy path already enforces it; known current deviations are listed in the contract.

<!-- Plugin description -->
erdMaid is a Mermaid ERD export plugin for the IntelliJ Database tool window.

It is designed for developers who want a fast, accurate way to turn selected database tables into Mermaid `erDiagram` syntax without manually rewriting table metadata.

Key benefits:

- Exports tables selected under a schema's `Tables` node in the Database tool window.
- Works from the schema-level table list, where all tables for that schema are visible.
- Preserves the actual column order from database metadata.
- Includes primary keys, foreign key relationships, column types, and column comments when available.
- Renders available size information in a Mermaid-safe format.
- Copies a complete successful diagram to the clipboard and shows a completion notification.

Typical workflow:

1. In the Database tool window, expand a schema and open its `Tables` node.
2. Select the `Tables` node to export every table in that schema, or select one or more individual tables under it to export only those tables.
3. Right-click and choose `Export as Mermaid ERD (erdMaid)`.
4. Paste the generated Mermaid code into Markdown, docs, or any Mermaid-enabled editor.

Output details:

- Table comments are emitted as `%%` Mermaid comments.
- Column lines follow `type name [PK] ["comment"]`.
- Type names with whitespace are normalized to underscores for Mermaid rendering.
- Column comments are sanitized so Mermaid can parse the output safely.
- Numeric precision and scale are rendered in a Mermaid-safe format when available.
- Current relation lines use the legacy Mermaid marker `||--o{`. The current metadata path does not prove the uniqueness/nullability facts needed to treat that marker as authoritative database cardinality; it is a known migration obligation under Leap Tracks #35/#36, not a fidelity claim.

Requirements:

- IntelliJ IDEA Ultimate or DataGrip with database tooling. The maintained version claims are limited to the exact evidence targets in the compatibility matrix below; descriptor load eligibility is not a broader support guarantee.
- A database connection with table metadata available in the Database tool window.

Notes:

- `classDiagram` is intentionally not supported.
- Views and other non-table objects are not supported export targets.
- Other IntelliJ-based products or versions not listed in the maintained compatibility matrix are unverified, even if plugin descriptor metadata permits loading.
<!-- Plugin description end -->

## Usage

1. Open the Database tool window in IntelliJ IDEA Ultimate or DataGrip. See the compatibility matrix below for the exact maintained evidence targets.
2. Expand a schema and open its `Tables` node.
3. Select one or more tables from that schema-level table list.
4. Right-click and choose `Export as Mermaid ERD (erdMaid)`.
5. Paste the generated Mermaid code into Markdown, docs, or any Mermaid-enabled editor.

The product contract supports tables across multiple schemas/catalogs only when they belong to one datasource/origin. Cross-datasource export, views, and mixed table/non-table selections are unsupported. The current implementation still has known selection-boundary gaps while Leap Track #37 replaces the legacy reflection/filtering path; do not rely on silent filtering of unsupported objects as product behavior.

## Output format

- Table comments are emitted as `%%` Mermaid comments.
- Column lines follow `type name [PK] ["comment"]`.
- Type names with whitespace are normalized to underscores for Mermaid rendering.
- Column comments are sanitized so Mermaid can parse the output safely.
- Numeric precision and scale are rendered with Mermaid-safe separators when available.
- Relationship existence and ordered FK mappings are product facts; cardinality/optionality is not claimed unless future metadata evidence can prove it. The current `||--o{` renderer marker is legacy behavior and should not be interpreted as verified cardinality.

## Requirements

- Maintained evidence targets: IntelliJ IDEA Ultimate 2026.2.0.1 for compile/tests, IntelliJ IDEA Ultimate 2026.2.2 for Plugin Verifier compatibility, and DataGrip 2026.2.4 for Plugin Verifier compatibility. Evidence types differ; see the matrix below.
- A database connection with table metadata available in the Database tool window.

## Installation

When the plugin is published to JetBrains Marketplace, install it from:

- <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd>
- Search for `erdMaid`
- Click <kbd>Install</kbd>

For local development builds, install the generated ZIP from `build/distributions`.

## Development

- Build: `./gradlew build`
- Tests: `./gradlew check`
- Development builds use a timestamp-based plugin version by default.
- Release builds can set an explicit version with `-PbuildVersion=x.y.z`.
- CI uses Java 25 for the IntelliJ Platform 2026.2 baseline.
- Product/fidelity authority: [`docs/product-fidelity-contract.md`](docs/product-fidelity-contract.md).
- Architecture-neutral downstream falsification scenarios: [`docs/product-baseline-scenarios.yaml`](docs/product-baseline-scenarios.yaml).

### Automated compatibility matrix

The required CI gates deliberately separate runtime compile/test evidence from binary Plugin Verifier evidence.

| Evidence purpose | Host | Exact version | Platform build | Mechanism |
| --- | --- | --- | --- | --- |
| Compile + test runtime | IntelliJ IDEA Ultimate | 2026.2.0.1 | 262.8665.337 | `buildPlugin` + `check` |
| Binary compatibility | IntelliJ IDEA Ultimate | 2026.2.2 | 262.10315.125 | IJPGP 2.18.1 `verifyPlugin` |
| Binary compatibility | DataGrip | 2026.2.4 | 262.10315.24 | Standalone IntelliJ Plugin Verifier 1.410 |

IntelliJ IDEA 2026.2.0.1 is the compile/test target because it is the earliest stable patch release used by the current build-262 baseline. Running the test suite there catches accidental dependencies on later patch APIs. The packaged plugin descriptor contains `since-build="262"` and no `until-build`, so build 262 is the minimum declared load baseline. The absence of `until-build` permits the IDE to attempt loading on later builds; it does **not** establish maintained compatibility for any unverified product/version.

IntelliJ IDEA 2026.2.2 is verified separately for binary compatibility. Its ordinary IntelliJ test runtime currently fails during unrelated `com.intellij.modules.ultimate` startup before erdMaid tests can execute. That platform-startup failure is not suppressed or converted to success; runtime tests remain authoritative only on the 2026.2.0.1 target, while 2026.2.2 remains an independent blocking Plugin Verifier target.

DataGrip uses `scripts/datagrip_verifier.py` because IJPGP 2.18.1 still cannot translate JetBrains' DataGrip release-catalog code `DG` to its platform code `DB`; that mapping is fixed upstream after 2.18.1 but is not yet available in a stable IJPGP release. The standalone gate selects exactly `DG` 2026.2.4 / build `262.10315.24` from JetBrains' release feed, verifies the vendor-published Linux archive checksum and extracted `product-info.json` identity, and runs the pinned Plugin Verifier 1.410 release whose JAR SHA-256 is checked before execution. It does not select latest or EAP releases.

The DataGrip report is evaluated fail-closed using the same default blocking levels as IJPGP 2.18.1 (`COMPATIBILITY_PROBLEMS`, `INTERNAL_API_USAGES`, and `OVERRIDE_ONLY_API_USAGES`). Detailed failure reports are checked first, and the pinned Plugin Verifier 1.410 verdict is independently checked for missing mandatory dependencies, blocking findings, invalid/not-found/download-failed states, and unknown verdict formats. Any unrecognized verdict fails closed; compatibility warnings and the other IJPGP-default non-blocking categories remain allowed.

No claim is made here that every build-262 patch, every later platform line, or every IntelliJ-based IDE is supported. Adding a maintained host/version requires explicit evidence and a contract update.

## Notes

- `classDiagram` is intentionally not supported.
- Views and other non-table objects are not supported export targets.
- The compatibility matrix is the maintained evidence boundary; wider descriptor compatibility is not a support guarantee.
