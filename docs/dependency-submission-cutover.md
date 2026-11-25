# Gradle dependency submission

Status: **ACTIVE repository-owned ARM64 publisher**.

[Production workflow](../.github/workflows/dependency-submission.yml) owns
the protected-main publisher; managed Automatic Dependency Submission must
not run alongside it. The PR adapter in [Build](../.github/workflows/build.yml)
uses the same immutable action pins and packaged-runtime configuration in
read-only generate-and-upload mode, not submission. The
[shared graph verifier](../scripts/trusted_dependency_submission_validation.py)
owns exact-source/ref, graph and scope checks.

Current product graph is the developed plugin's packaged sandbox runtime
configuration. Require a nonempty graph with direct dependency evidence,
valid package identities and runtime classification. The sole admitted
development node is the dependency-graph plugin's direct Gradle core tool
node. Host, Starter, test and build-tool graphs are not product dependencies.
No fixed aggregate graph count is a correctness threshold. The earlier
aggregate counts and ARM64 cutover experiment are historical observations,
not current coverage or runtime requirements.

The production publisher runs on protected-main dependency-input pushes or
explicit main dispatch. Cache use is read-only. Verify exact submitted
main provenance; PR evidence is not production submission evidence.
Replace a publisher through an authorized coherent migration; do not retain
parallel publishers or invent a rollback fallback merely to keep legacy
behavior.
