# erdMaid — Engineering Contract

Authoritative engineering and review contract for `erdMaid`, an IntelliJ Platform plugin
that exports database tables selected in the Database tool window as Mermaid `erDiagram`
text.

This document describes **correctness boundaries**, not a roadmap. It states what the
plugin is required to get right, where it is currently known to be wrong or fragile, and
what a change must prove before it is merged. The original phase-oriented implementation
plan is retained only as history at
[`docs/history/2026-04-initial-implementation-plan.md`](docs/history/2026-04-initial-implementation-plan.md)
and is not authoritative.

Keep this file current. If a change alters any boundary below, the change updates this
file in the same pull request.

---

## 0. Scope

**In scope:** exporting tables and their columns, primary keys, and foreign-key relations
from the IntelliJ Database model into Mermaid `erDiagram` syntax, placing the result on the
clipboard, and reporting success or failure to the user.

**Out of scope**, and not to be added without a separate, justified issue: views and other
non-table objects, diagram types other than `erDiagram`, MarkFlow or any other downstream
integration, writing to files, and modifying anything in the user's database.

---

## 1. Source layout

| Path | Responsibility |
|---|---|
| `src/main/kotlin/.../actions/ErdMaidExportActions.kt` | Action lifecycle, selection resolution, clipboard, notifications, error reporting |
| `src/main/kotlin/.../generator/MermaidGenerator.kt` | IntelliJ Database model → internal specs → Mermaid text |
| `src/main/kotlin/.../generator/RelationResolver.kt` | Foreign-key discovery, filtering, and de-duplication |
| `src/main/resources/META-INF/plugin.xml` | Action registration, notification group, `com.intellij.database` dependency |

The `TableSpec` / `ColumnSpec` / `RelationSpec` boundary exists so that string generation is
testable without a live IDE or database. **Preserve it.** Any new rendering logic goes on
the spec side of that line and gets a unit test; any new metadata reading goes on the
platform side of it.

---

## 2. Database metadata extraction correctness

Extraction reads the IntelliJ *model* (`DasTable`, `DasColumn`, `DasUtil`,
`ModelRelationManager`). It never opens a connection and never issues SQL. The model may be
partially loaded, stale, or introspected from a dialect that does not populate every field.

Requirements:

- **Absent metadata is not an error.** A null or blank comment, an unknown data type length,
  or a table with zero foreign keys must all produce valid output, not an exception and not a
  placeholder that looks like real data.
- **Never fabricate.** If a length, precision, or scale cannot be read, omit the suffix
  entirely rather than emitting a guess or a zero.
- **Never mutate.** Extraction is read-only with respect to both the database and the
  IntelliJ model.
- **Do not assume dialect.** Type-name matching is substring-based and case-insensitive
  (`isLengthType`, `isPrecisionScaleType`, `isPrecisionOnlyType`). Adding a dialect-specific
  type must not change how an unrelated dialect renders.

### Known fragility — reflective and deprecated access

Three places reach the platform through interfaces that can silently stop working:

1. `MermaidGenerator.toTableSpec` calls `DasType.toDataType()` under
   `@Suppress("DEPRECATION")`.
2. `MermaidGenerator.readIntProperty` reflects over `length` / `size` / `precision` / `scale`
   getters on the data-type object.
3. `BaseErdMaidExportAction.selectedDbElements` reflects
   `com.intellij.database.view.DatabaseContextFun#getSelectedDbElementsExpandingGroups`.

All three currently degrade to "no value" on failure, which is **indistinguishable from
"the database genuinely has no value"**. That is a deliberate trade for resilience, and it is
also the plugin's largest silent-wrongness risk. Rules:

- Do not add a fourth reflective access point without recording it here.
- Any reflective lookup that fails must be *logged at debug/info level* with the symbol it
  was looking for, so that a platform break is diagnosable from a log rather than only
  visible as quietly missing output.
- When a supported non-reflective API becomes available, replace the reflective path rather
  than accumulating both.

---

## 3. Identity correctness — tables, columns, primary keys, foreign keys

This is the area where erdMaid is most likely to be *wrong rather than broken*, so it gets
the strictest treatment.

### Table identity

`RelationResolver.resolve` builds `selectedTableNames` from `DasTable.name`, and
`MermaidGenerator.generate` looks relations up with `relationMap[it.name]`. **Table identity
is therefore the unqualified table name.**

Consequences that must be understood before touching this code:

- Two selected tables named `orders` in different schemas collapse into one identity. A
  foreign key belonging to `sales.orders` can be attached to `archive.orders`.
- The same collision affects the `parentTableName` lookup, so a relation may be *silently
  emitted between the wrong pair of entities* rather than dropped.

This is an accepted, documented limitation of the current implementation, **not** a fixed
behaviour. Any change that touches selection, relation resolution, or entity naming must
either preserve this behaviour deliberately or fix it properly by introducing a qualified
identity (catalog/schema/name) used consistently in `selectedTableNames`, the `groupBy` key,
the `relationMap` lookup, and the rendered entity name. Fixing it halfway — qualifying the
rendered name but not the lookup key — is worse than not fixing it.

### Column identity and ordering

- Column order is taken from `DasUtil.getColumns(table)` and is required to be the
  database's ordinal position. Output order is part of the contract: two exports of an
  unchanged schema must produce byte-identical text.
- Do not sort, re-order, or de-duplicate columns.

### Primary keys

- PK membership is decided by matching `DasColumn.name` against the names yielded by
  `DasUtil.getPrimaryKey(table)?.columnsRef?.iterate()`.
- `columnsRef.iterate()` returns IntelliJ's own cursor (`MultiRef.It`), not a
  `java.util.Iterator`; the `while (hasNext()) add(next())` loop is required, and `next()`
  yields the column *name* as a `String`.
- Composite primary keys must work: every participating column is marked `PK`.
- The match is exact and case-sensitive. Case-insensitive dialects and quoted identifiers
  are a known gap — if a dialect reports the PK column as `ID` and the column as `id`, the
  `PK` marker is silently lost.

### Foreign keys

- `RelationResolver.collectOutgoingRelations` prefers
  `ModelRelationManager.getForeignKeys(project, table)` — which includes IDE-maintained
  *virtual* relations as well as real database FKs — and falls back to
  `DasUtil.getForeignKeys(table)` only when no `Project` is available (headless/tests).
  These two sources are not equivalent; the fallback is a degraded mode, not a synonym.
- Only relations whose **both** endpoints are in the selected table set are emitted.
  Dangling relations are dropped silently, by design.
- De-duplication key is `child -> parent : childColumns | parentColumns`. A named relation
  wins over an unnamed one for the same key; otherwise first-seen wins and `LinkedHashMap`
  keeps the result deterministic.
- Cardinality is currently hard-coded to `||--o{`. erdMaid does **not** infer optionality or
  one-to-one-ness. Do not introduce inferred cardinality without a way to verify it against
  real nullability and uniqueness metadata.

---

## 4. Mermaid output generation correctness

The output is text that a Mermaid parser must accept. Wrong output that still parses is the
worst outcome, because the user sees a plausible diagram that misrepresents their schema.

- The document starts with a single `erDiagram` line.
- Entity blocks are indented one level (4 spaces); column lines two levels.
- Column line shape is exactly `type name [PK] ["comment"]`, in that order, joined by single
  spaces. Optional parts are omitted entirely when absent — never emitted empty.
- Table comments are emitted as a `%%` Mermaid comment on the line *before* the entity block,
  and only when the comment is non-blank.
- `MermaidRenderOptions.includeColumnReferences` adds a `%% FK: child.cols -> parent.cols`
  comment line before each relation. It is a comment, so it must never be able to change how
  the diagram parses.
- Type suffix rules: length types get `(n)`, precision/scale types get `(p_s)` (underscore,
  because `,` is unsafe in this position), precision-only types get `(p)`. A type name that
  already contains `(` is passed through untouched.
- **Determinism is required.** Same input, same output — no timestamps, no map iteration
  order dependence, no locale-dependent case conversion in identifier handling.

---

## 5. Escaping and sanitization of generated Mermaid text

The generated text is derived from database-controlled strings (table names, column names,
comments, type names, FK names). Treat all of them as untrusted input to the Mermaid parser.

Current behaviour, stated precisely so that gaps are visible:

- `sanitizeMermaidText` replaces `"` with `'`. That is all it does.
- `sanitizeMermaidComment` additionally maps ASCII `(` and `)` to fullwidth `（` `）`.
- `normalizeIdentifier` = `sanitizeMermaidText` plus space → `_`.
- `renderEntityName` emits the name bare if it matches `^[A-Za-z_][A-Za-z0-9_]*$`, otherwise
  wraps it in double quotes.
- `renderRelationLabel` trims, sanitizes, and always quotes; a blank label becomes `""`.

Known gaps — do not assume these are handled:

- **Newlines and carriage returns are not stripped.** A multi-line column comment breaks out
  of its column line, and a multi-line table comment escapes the `%%` comment. This is the
  most likely real-world break.
- `%%` appearing inside a comment or identifier is not neutralised.
- `{`, `}`, `|`, `:`, `<`, `>` are not escaped in comments.
- Non-ASCII identifiers do not match `ENTITY_NAME_SAFE` and are therefore always quoted;
  that is correct, but the quoting relies on `"` having already been replaced.

Rules for changes here:

- Sanitization is centralised. Do not inline ad-hoc `replace` calls at a call site — extend
  the sanitize functions so every caller benefits.
- Every sanitization change needs a unit test with the hostile input as a literal.
- Sanitization must be idempotent: `sanitize(sanitize(x)) == sanitize(x)`.
- Prefer neutralising a character over dropping it; never drop content silently in a way
  that makes the diagram look complete when it is not.

---

## 6. IntelliJ / DataGrip Database API compatibility

- The plugin targets IDEs that bundle `com.intellij.database`: **IntelliJ IDEA Ultimate** and
  **DataGrip**. It cannot work on Community editions, and `plugin.xml` declares
  `<depends>com.intellij.database</depends>` accordingly.
- Verification targets are declared explicitly in `build.gradle.kts` under
  `intellijPlatform { pluginVerification { ides { ... } } }`. They are a deliberate list, not
  a default. Do not remove the block — an implicit target set drifts onto EAP builds and both
  makes the gate non-reproducible and exhausts CI disk.
- `com.intellij.database.*` is a closed-source, largely unstable API surface. `DasTable`,
  `DasColumn`, `DasUtil`, and `DbTable` are the comparatively stable parts;
  `ModelRelationManager` and `DatabaseContextFun` are not.
- Widening the supported IDE range means adding the IDE to the verification target list in
  the same change. "It probably still works" is not evidence.
- Do not add a `<idea-version>` range or change the platform version without re-running
  `verifyPlugin` against every declared target.

---

## 7. Action lifecycle, threading, clipboard, and notifications

- `BaseErdMaidExportAction` extends `DumbAwareAction`, so the action is available during
  indexing. Everything it does must be safe without indexes.
- `update()` runs on every popup refresh. It resolves the selection and reflects into
  `DatabaseContextFun`; the `Method` handle is cached in a `by lazy` companion field
  specifically so the hot path does not repeat `Class.forName`. **Keep `update()` cheap** —
  no metadata reads, no I/O, no allocation-heavy work.
- Visibility rule: the action is enabled and visible if and only if the resolved selection
  contains at least one `DbTable`.
- Selection resolution order is `DatabaseContextFun.getSelectedDbElementsExpandingGroups`
  first (this is what makes selecting a schema's `Tables` node expand to its tables), then
  `PSI_ELEMENT_ARRAY` as a fallback. Non-`DbTable` elements are dropped by
  `filterIsInstance<DbTable>()`.
- **Known gap:** the class does not override `getActionUpdateThread()`, and both `update()`
  and `actionPerformed()` do their work inline on the calling thread. Metadata extraction for
  a large selection therefore runs on the EDT and can freeze the IDE. If this is fixed, the
  threading contract must be stated here, and the clipboard write and notification must stay
  on the EDT while extraction moves off it.
- **Known gap:** views and other non-table `DbElement`s are dropped with no feedback. A user
  who selects only views gets a disabled menu item and no explanation.
- The clipboard is written exactly once per successful export, via
  `CopyPasteManager.getInstance().setContents(StringSelection(...))`. On failure the
  clipboard must be left untouched — never write partial output.
- Notifications go through the `erdMaidNotification` group declared in `plugin.xml`
  (`displayType="BALLOON"`). Every terminal path — success and failure — notifies exactly
  once. The action must never fail silently.
- No listeners, disposables, or caches hold a `Project` or a `DbElement` beyond the scope of
  a single invocation. The only long-lived state is the reflected `Method` handle, which holds
  no project reference.

---

## 8. Error handling and diagnostics

- `actionPerformed` wraps generation in `try`/`catch(Exception)` and reports failure both to
  the log and to the user.
- **Known defect:** the catch block calls `Logger.error(...)`. In the IntelliJ Platform that
  raises an IDE *fatal error* report — the "report to JetBrains" dialog — for what is very
  often a data problem, not a plugin crash. The user then gets both a crash report and an
  error balloon for one failure. `Logger.warn` with a balloon is the correct shape for
  expected failures; `Logger.error` should be reserved for genuine invariant violations.
- Error notifications include `ex.message`. Keep it that way: the message is the only
  diagnostic a user can copy. Do not replace it with a generic string.
- Never let a `Throwable` escape `actionPerformed` — an uncaught exception there is reported
  as a platform error against the IDE, not against erdMaid.
- Log messages must name the failing symbol or table, not just "failed".

---

## 9. Testing requirements

Tests live in `src/test/kotlin` and run under `./gradlew check`.

- Generation logic is tested through `TableSpec` / `ColumnSpec` / `RelationSpec`, with no live
  IDE or database. Any new generation behaviour is tested this way.
- Every fix to an identity, ordering, escaping, or sanitization boundary in sections 3–5
  ships with a regression test containing the exact triggering input.
- Assert on full expected output strings, not on `contains`. `contains` will not catch
  ordering, indentation, or duplication regressions.
- Tests are never weakened, skipped, or made lenient to get CI green. If a test is wrong,
  fix the assertion and say why in the pull request.

---

## 10. CI and repository policy

- `Build`, `Test`, and `Verify plugin` (`.github/workflows/build.yml`) are authoritative
  gates: compile, `./gradlew check`, plugin packaging, and the IntelliJ Plugin Verifier
  against the declared IDE targets. None of them is advisory.
- `Workflow Static Analysis` (`.github/workflows/workflow-security.yml`) runs `actionlint` and
  `zizmor` fail-closed, with a negative control that fails the job if either scanner reports
  success against a deliberately unsafe fixture. Never add `continue-on-error` or `|| true`
  to a security check — a scanner that fails to run must be distinguishable from one that
  found nothing.
- Every third-party action is pinned to a full-length commit SHA with a trailing `# vX.Y.Z`
  comment. The comment is how Dependabot updates the pin; never replace a SHA with a tag.
- Workflows default to `permissions: contents: read`. A job that needs more declares it at
  job level with a reason.
- `actions/checkout` uses `persist-credentials: false`. If a step ever genuinely needs to
  push, that step gets its own job with its own scoped permissions.
- **Validation never mutates repository state.** No workflow triggered by `push` or
  `pull_request` may create, delete, or modify releases, tags, labels, or branches. erdMaid
  has no active distribution channel — no tags, no published releases, and no signing or
  publishing secrets — so the template's release automation was removed rather than carried
  as dead machinery. Reintroducing it requires a real distribution plan and a separate
  workflow behind a separate trigger.
- CI green is a precondition for review, not a substitute for it.

---

## 11. Review checklist

A change is not ready to merge until each of these has been actively checked against the
final HEAD of the branch, not an earlier revision:

1. **Metadata semantics** — does it read the model correctly, and is absent metadata still
   handled as absent rather than as an error or a fabricated value?
2. **Identity** — table, column, PK, and FK identity, including the unqualified-name
   collision in section 3. Was the boundary preserved deliberately or fixed completely?
3. **Ordering and determinism** — column order preserved; same input still yields
   byte-identical output.
4. **Escaping** — hostile identifiers and comments (quotes, newlines, `%%`, braces, pipes,
   non-ASCII) still produce parseable Mermaid.
5. **API compatibility** — no new reflective or deprecated access without a note in section 2;
   `verifyPlugin` passes against every declared target.
6. **Lifecycle and threading** — `update()` still cheap; nothing new on the EDT; clipboard and
   notification behaviour unchanged on both the success and failure paths.
7. **Diagnostics** — every failure path notifies exactly once and logs something actionable.
8. **Resource retention** — no new long-lived reference to a `Project`, `DbElement`, or PSI.
9. **Tests** — new behaviour tested; no test weakened; regression tests use literal hostile
   inputs.
10. **Workflow and repository security** — SHA pins intact, permissions unchanged or narrowed,
    no validation workflow mutating repository state.
11. **Docs** — README and this file still describe what the code actually does.
12. **Diff scope** — nothing unrelated to the stated purpose of the change.
