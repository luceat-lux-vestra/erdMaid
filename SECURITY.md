# Security Policy

## Supported code

Security fixes target the current `main` branch. erdMaid has no maintained release/distribution channel, so this repository does not claim security support for historical tags or published binaries.

## Reporting

Use a private repository collaboration channel to report a security-sensitive finding to the repository owner. Do not place credentials, database contents, proprietary schema details, local paths, access tokens, or other sensitive material in an issue body, log excerpt, fixture, or screenshot.

A report should include the affected commit/build, the trust boundary involved, a minimal sanitized reproduction, the expected fail-closed behavior, and whether the flaw can produce a plausible but incorrect schema/diagram or escape the intended Mermaid context.

## Relevant trust boundaries

High-risk areas include database-controlled metadata rendered as Mermaid, qualified table/relation identity, IntelliJ/DataGrip platform/reflection failures, GitHub Actions permissions and untrusted-PR handling, and any future publication path. Validation must fail closed when a security scanner or authoritative live readback cannot run.
