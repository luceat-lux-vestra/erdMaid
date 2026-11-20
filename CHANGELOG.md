<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# erdMaid Changelog

## [Unreleased]

## [1.1.0] - 2026-11-21
### Fixed
- Suppress erdMaid's Database popup action for a provably empty selection or directly unsupported object without expanding groups on the EDT; validate group/mixed selection authoritatively only when invoked.
- Prevent unrelated SQLite-style `UNIQUE` indices or ungrouped candidate-key markers from suppressing otherwise valid foreign-key ERDs; still fail closed when index coverage may constrain the FK tuple or cannot be resolved.
- Restore readable SQL column and type names in exported Mermaid attributes (for example `INT user_id`, `VARCHAR(255) customer_name`, `NUMERIC(18,2) unit_price`) instead of opaque `c_...` / `t_...` tokens. Preserve grammar-safe quoting and an injective fallback for unrepresentable source text.
- Make SQL precision/scale type rendering portable across Mermaid ER parsers by collapsing duplicate JetBrains type metadata and backtick-quoting comma-bearing type tokens such as `NUMERIC(18,2)`.

### Added
- Mermaid `erDiagram` export for physical database-table selections from the IntelliJ Database tool window.
- Immutable canonical schema snapshots with datasource/origin-aware table and column identity, ordered PK/FK mappings, relation provenance, explicit unavailable/unknown states, and deterministic ordering.
- Pure semantic compilation for relation constraints, multiplicity, and identifying/non-identifying semantics before Mermaid rendering.
- Deterministic Mermaid serialization with context-specific hostile-text encoding, canonical entity IDs/aliases, PK/comments, physical/virtual provenance labels, and evidence-derived cardinality markers.
- Executable Mermaid 11.17.2 grammar evidence bound to production fixtures and exact package integrity.
- Exact maintained compatibility evidence split into compile/test runtime, Plugin Verifier binary compatibility, and DatabaseTools Host API Inventory classes for the documented IntelliJ IDEA/DataGrip targets.
- Reproducible large-selection proof for 1000 tables x 40 columns / 3000 relations, including separate stage measurements, deterministic repeated output, bounded cooperative cancellation, no-publication, recovery, and concurrent-invocation isolation evidence.

### Changed
- Replaced the legacy synchronous generator/resolver/sanitizer path with a JetBrains host shell -> immutable snapshot -> pure semantic graph -> pure Mermaid serializer -> typed outcome -> guarded publication pipeline.
- Selection handling now fails closed for mixed unsupported objects, cross-datasource selections, expansion/platform failures, stale completion, and unavailable structural evidence instead of silently filtering or guessing.
- Relationship rendering no longer uses a universal legacy `||--o{` marker. Cardinality and identification connectors are emitted only from proven semantic evidence; unavailable required facts produce a degraded unpublished outcome.
- Clipboard publication occurs only after complete success and final lifecycle/freshness checks; cancellation, failure, unsupported/degraded results, disposal/invalidation, and stale completion leave the clipboard untouched.
- Consolidated repository/build identity now uses `luceat-lux-vestra/erdMaid` / `erdMaid` instead of the former `erdMaid-private` / `erdMaid-public` split names.

### Evidence limits
- DataGrip 2026.2.5 has maintained Plugin Verifier and exact DatabaseTools Host API Inventory evidence, but live GUI/database-tool-window runtime integration remains unverified.
- IntelliJ IDEA Ultimate 2026.2.2 has maintained Plugin Verifier evidence, but a full Host API Inventory/runtime-test claim is not inferred from it.
- Invocation-owned heap/GC retention after terminal completion remains unverified because no deterministic hosted-CI observation mechanism is claimed.
