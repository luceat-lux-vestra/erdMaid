# erdMaid - Mermaid ERD Export for Database Tables

erdMaid is a Mermaid ERD export plugin for database tables in the Database tool window.

## What it does

- Exports either the schema's `Tables` node itself or individual tables selected under that node in the Database tool window.
- Works from the schema-level table list, where all tables for that schema are visible and can be exported together.
- Preserves the actual column order from database metadata.
- Includes primary keys, foreign key relationships, column types, and column comments.
- Renders column size details in a Mermaid-safe format.
- Copies the generated diagram to the clipboard and shows a completion notification.

<!-- Plugin description -->
erdMaid is a Mermaid ERD export plugin for the IntelliJ Database tool window.

It is designed for developers who want a fast, accurate way to turn selected database tables into Mermaid `erDiagram` syntax without manually rewriting table metadata.

Key benefits:

- Exports tables selected under a schema's `Tables` node in the Database tool window.
- Works from the schema-level table list, where all tables for that schema are visible.
- Preserves the actual column order from database metadata.
- Includes primary keys, foreign key relationships, column types, and column comments.
- Renders size information in a Mermaid-safe format.
- Copies the generated diagram to the clipboard and shows a completion notification.

Typical workflow:

1. In the Database tool window, expand a schema and open its `Tables` node.
2. Select the `Tables` node to export every table in that schema, or select one or more individual tables under it to export only those tables.
3. Right-click and choose `Export as Mermaid ERD (erdMaid)`.
4. Paste the generated Mermaid code into Markdown, docs, or any Mermaid-enabled editor.

Output details:

- Table comments are emitted as `%%` Mermaid comments.
- Column lines follow `type name [PK] ["comment"]`.
- Type names with whitespace are normalized to underscores.
- Column comments are sanitized so Mermaid can parse the output safely.
- Numeric precision and scale are rendered in a Mermaid-safe way.

Requirements:

- IntelliJ Platform 2026.2 (build 262) or newer, with database tooling. The automated compatibility gate covers IntelliJ IDEA and DataGrip on the 2026.2 line.
- A database connection with table metadata available in the Database tool window.

Notes:

- `classDiagram` is intentionally not supported.
- View support is out of scope for now.
<!-- Plugin description end -->

## Usage

1. Open the Database tool window in an IntelliJ-based IDE with database support.
2. Expand a schema and open its `Tables` node.
3. Select one or more tables from that schema-level table list.
4. Right-click and choose `Export as Mermaid ERD (erdMaid)`.
5. Paste the generated Mermaid code into Markdown, docs, or any Mermaid-enabled editor.

## Output format

- Table comments are emitted as `%%` Mermaid comments.
- Column lines follow `type name [PK] ["comment"]`.
- Type names with whitespace are normalized to underscores.
- Column comments are sanitized so Mermaid can parse the output safely.
- Numeric precision and scale are rendered with Mermaid-safe separators.

## Requirements

- IntelliJ Platform 2026.2 (build 262) or newer, with database tooling.
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

### Automated compatibility matrix

The required CI gates deliberately separate minimum-baseline testing from newer-patch binary compatibility verification.

| Purpose | Host | Declared version | Platform build | Mechanism |
| --- | --- | --- | --- | --- |
| Compile + tests | IntelliJ IDEA | 2026.2.0.1 | 262.8665.337 | `buildPlugin` + `check` |
| Latest IDEA compatibility | IntelliJ IDEA | 2026.2.2 | 262.10315.125 | IJPGP 2.18.1 `verifyPlugin` |
| DataGrip compatibility | DataGrip | 2026.2.4 | 262.10315.24 | Standalone IntelliJ Plugin Verifier 1.410 |

IntelliJ IDEA 2026.2.0.1 is the compile/test target because it is the earliest stable patch release on the supported 262 platform line. Running the test suite at the minimum supported baseline catches accidental dependencies on later patch APIs. The packaged plugin descriptor contains `since-build="262"` and no `until-build`, so build 262 is the minimum declared platform baseline and newer platform builds are not blocked by descriptor metadata.

The latest supported IDEA patch is verified separately against IntelliJ IDEA 2026.2.2. This separation is intentional: IntelliJ IDEA 2026.2.2's Plugin Verifier accepts erdMaid as compatible, while its ordinary IntelliJ test runtime currently fails during unrelated `com.intellij.modules.ultimate` startup before erdMaid tests can execute. That platform-startup failure is not suppressed or converted to success; the test suite remains authoritative on the minimum 262 baseline, and the latest patch remains an independent blocking Plugin Verifier target.

DataGrip uses `scripts/datagrip_verifier.py` because IJPGP 2.18.1 still cannot translate JetBrains' DataGrip release-catalog code `DG` to its platform code `DB`; that mapping is fixed upstream after 2.18.1 but is not yet available in a stable IJPGP release. The standalone gate selects exactly `DG` 2026.2.4 / build `262.10315.24` from JetBrains' release feed, verifies the vendor-published Linux archive checksum and extracted `product-info.json` identity, and runs the pinned Plugin Verifier 1.410 release whose JAR SHA-256 is checked before execution. It does not select latest or EAP releases.

The DataGrip report is evaluated fail-closed using the same default blocking levels as IJPGP 2.18.1 (`COMPATIBILITY_PROBLEMS`, `INTERNAL_API_USAGES`, and `OVERRIDE_ONLY_API_USAGES`). Detailed failure reports are checked first, and the pinned Plugin Verifier 1.410 verdict is independently checked for missing mandatory dependencies, blocking findings, invalid/not-found/download-failed states, and unknown verdict formats. Any unrecognized verdict fails closed; compatibility warnings and the other IJPGP-default non-blocking categories remain allowed.

## Notes

- `classDiagram` is intentionally not supported.
- View support is out of scope for now.
