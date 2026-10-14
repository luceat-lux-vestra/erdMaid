# Gradle dependency-submission cutover

Status: **STAGED / INACTIVE** until administrator cutover.

This supersedes the previous NO-GO in #187. The fixed-source JDK25 graph
already matched the managed snapshot exactly at 814 resolved / 53 direct /
4,752 edges / 814 purls. The maintainer has chosen to move the production
publisher directly to JDK25 ARM64 instead of preserving x64. The first live
main submission is therefore the architecture acceptance point and remains
fully rollbackable.

Cutover:
1. Merge while ERDMAID_ADS_CUTOVER_DONE is absent/false.
2. Disable managed Automatic dependency submission; keep Dependency Graph, Dependabot and Dependency Review enabled.
3. Set ERDMAID_ADS_CUTOVER_DONE=true.
4. Dispatch Gradle Dependency Submission on main.
5. Require SUCCESS, exact-main SHA/ref and graph coverage. Historical reference is 814/53; fail-closed floor is 750/45.
6. Confirm later feature/research pushes no longer create managed ADS runs.

The ARM64 publisher uses cache-read-only=true, so a cache miss cannot create a new ARM64 cache producer.

Rollback: set ERDMAID_ADS_CUTOVER_DONE=false first, re-enable managed ADS, and require a fresh managed main snapshot. Never leave both publishers active.
