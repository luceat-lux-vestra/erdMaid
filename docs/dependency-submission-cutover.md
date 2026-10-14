# Gradle dependency submission

Status: **ACTIVE repository-owned ARM64 publisher**.

The repository-owned workflow replaces GitHub-managed Automatic Dependency
Submission. Managed ADS should remain disabled while this workflow is active.

The publisher:
- runs automatically only on protected `main` pushes that change Gradle dependency/build-definition inputs;
- supports manual dispatch on `main` for recovery or explicit refresh;
- uses JDK25 on `ubuntu-24.04-arm`;
- keeps Gradle cache access read-only;
- submits with `dependency-graph: generate-and-submit`;
- verifies exact-main provenance and fail-closed graph coverage after submission.

Current reference graph: 814 resolved / 53 direct / 4,752 edges / 814 purls.
The ARM64 pre-cutover probe produced canonical resolved SHA256
`0ae9aef365152e7c17500fa324e0b73be94ec29a1bfe2dc95e9f662290eb967c`,
identical to the earlier x64 graph. The workflow fails closed below 750
resolved or 45 direct dependencies.

A cold ARM64 graph generation succeeded with zero cache restores and zero
cache saves, so this publisher does not depend on a pre-existing ARM64 cache.

Rollback:
1. disable or remove the repository-owned publisher;
2. re-enable GitHub-managed Automatic Dependency Submission;
3. require a successful fresh managed main snapshot before considering the rollback complete.

Do not intentionally keep both publishers active.
