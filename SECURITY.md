# Security Policy

## Supported code

Security fixes target the current `main` branch. erdMaid has no maintained release/distribution channel, so this repository does not claim security support for historical tags or published binaries.

## Reporting

Do not disclose security-sensitive details in a public issue, discussion, log excerpt, fixture, screenshot, or pull request. This includes credentials, database contents, proprietary schema details, local paths, access tokens, and other sensitive material.

If GitHub's private vulnerability reporting is available for this repository, use the **Report a vulnerability** flow under the repository's Security tab. If that flow is unavailable, contact the repository owner through a private channel listed on their GitHub profile. If no private channel is available, open only a minimal, non-sensitive issue requesting a private reporting channel; do not include exploit details or secrets there.

A report should include the affected commit/build, the trust boundary involved, a minimal sanitized reproduction, the expected fail-closed behavior, and whether the flaw can produce a plausible but incorrect schema/diagram or escape the intended Mermaid context.

## Relevant trust boundaries

High-risk areas include database-controlled metadata rendered as Mermaid, qualified table/relation identity, IntelliJ/DataGrip platform/reflection failures, GitHub Actions permissions and untrusted-PR handling, and any future publication path. Validation must fail closed when a security scanner or authoritative live readback cannot run.
