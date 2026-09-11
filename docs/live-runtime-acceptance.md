# Live Runtime Acceptance

This document defines the pre-publication acceptance gate for proving that erdMaid works in an actual maintained JetBrains IDE process. Build, unit/integration tests, Plugin Verifier, archive inspection, and host API inventory are necessary but do not by themselves prove the end-user flow.

## Release gate

The first Marketplace publication remains blocked until the live runtime scenarios below are executed against an exact reviewed plugin artifact and the observed evidence is recorded.

`UNKNOWN`, `UNVERIFIED`, and `INSUFFICIENT EVIDENCE` are failures for this gate.

## Authority

The artifact under test must be identified by all of:

- repository commit SHA;
- plugin ZIP filename and SHA-256;
- plugin version from patched `META-INF/plugin.xml`;
- host product name/version/build.

Do not rebuild between evidence collection steps. If the artifact changes, previous runtime evidence is invalid for the replacement artifact.

## Maintained hosts

At minimum execute the user flow on:

1. IntelliJ IDEA Ultimate 2026.2.2 / `IU-262.10315.125`;
2. DataGrip 2026.2.4 / `DB-262.10315.24`.

Passing one host must not be generalized to the other.

## Fixture

Use a real disposable database datasource visible in the Database tool window. The fixture must contain at least:

- two ordinary tables from one datasource/origin;
- stable column order;
- a primary key;
- a composite key or composite foreign-key mapping;
- a physical foreign key between the selected tables;
- nullable and non-null columns;
- at least one comment and representative type metadata.

Record the fixture DDL or an equivalent reproducible fixture definition with the evidence. erdMaid itself must not execute fixture DDL; fixture setup is external test preparation.

## Positive user flow

For each maintained host:

1. Install the exact plugin ZIP under test and restart/reload as required by the host.
2. Confirm the installed plugin identity is `com.algorist.erdmaid` and the expected version is shown.
3. Open the Database tool window and connect/synchronize the disposable datasource.
4. Select the intended table fixture from one datasource.
5. Open the Database view context menu and confirm `Export as Mermaid ERD (erdMaid)` is present and enabled.
6. Put a unique sentinel value in the system clipboard.
7. Invoke the erdMaid export action through the visible UI action.
8. Wait for the terminal erdMaid notification.
9. Read the system clipboard and verify:
   - the sentinel was replaced exactly once by a Mermaid `erDiagram` document;
   - every selected table appears exactly once;
   - source column order is preserved;
   - PK/FK annotations and the physical relation match the fixture evidence;
   - comments/type rendering are syntactically valid for the maintained Mermaid grammar contract;
   - the output contains no fabricated relation/cardinality claim;
   - repeating the same export without metadata changes produces byte-identical clipboard text.
10. Inspect the IDE log for the invocation window and confirm there is no erdMaid fatal error or uncaught exception.

## Negative/fail-closed flows

For each maintained host, place a fresh sentinel in the clipboard before each case and verify the clipboard remains unchanged when the export is not complete:

- empty/no database-object selection;
- mixed unsupported selection if the host UI allows it;
- cross-datasource selection if the host UI allows it;
- invalidated/stale datasource object when reproducible without mutating production code;
- cancellation while an export is in progress when reproducible with a sufficiently large fixture.

Expected warnings/diagnostics must be non-fatal. A crash, fatal IDE error, stale clipboard publication, wrong-but-plausible Mermaid output, or silent publication after cancellation is a gate failure.

## Evidence record

For each run record:

- exact host product/version/build;
- exact plugin ZIP SHA-256 and plugin version;
- repository commit SHA;
- fixture identity/DDL hash;
- selected objects;
- resulting clipboard document SHA-256 plus retained text artifact where safe;
- repeat-render equality result;
- terminal notification text/type;
- relevant erdMaid IDE log excerpt or a statement that no erdMaid error/exception was emitted;
- PASS/FAIL per scenario.

Screenshots or a short screen recording are useful corroboration but are not substitutes for the exact artifact/output/log evidence above.

## Automation boundary

Where practical, mirror this acceptance flow with JetBrains Starter + Driver / `testIdeUi` so an actual IDE process installs the plugin and exercises UI behavior. Automated UI evidence supplements, but does not retroactively convert an unexecuted maintained-host scenario into PASS.

The first Marketplace publication must not proceed while either maintained host's positive user flow is UNVERIFIED.
