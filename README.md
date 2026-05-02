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

- IntelliJ IDEA Ultimate, DataGrip, or another IntelliJ-based IDE with database tooling.
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

- IntelliJ IDEA Ultimate, DataGrip, or another IntelliJ-based IDE with database tooling.
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

## Notes

- `classDiagram` is intentionally not supported.
- View support is out of scope for now.
