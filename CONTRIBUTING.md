# Contributing to erdMaid

erdMaid is currently solo-maintained, but changes still follow the same proof obligations as external contributions.

## Before changing code

1. Start from a freshly read `main` SHA and keep the change tied to one owning issue/Track.
2. Read `AGENTS.md` and its mandatory local policy/engineering authorities.
3. Do not widen product, IDE/DataGrip, metadata-fidelity, or distribution claims without evidence.

## Pull requests

Keep one independently reviewable purpose. Record the base SHA, scope/non-goals, automated/manual evidence, and known gaps. CI green is necessary but insufficient: the exact final PR HEAD is manually reviewed for correctness, failure/recovery, regression, compatibility, edge cases, ownership/lifecycle, adversarial behavior, workflow security, documentation, and diff scope. Any HEAD movement invalidates prior review evidence.

Merges are squash-only. Merge-time PR HEAD and fresh `main` are re-read before merge, and merged `main` plus live repository controls are verified afterward when the change affects governance.

## Tests

Documentation-only changes use the bounded CI fast path only when every changed file is `README.md`, `CONTRIBUTING.md`, `AGENTS.md`, or a Markdown file under `docs/**`. The scope check is syntactic, not semantic: reviewers remain responsible for validating behavioral/contract claims. Build/Test/Qodana/Plugin Verifier runtime work is skipped, while Workflow Static Analysis, Dependency submission PR validation, Dependency Review, and the required `Merge Gate` remain active. Mixed or unprovable scope runs full validation.

Use full deterministic output assertions for rendering/identity behavior. Add literal hostile fixtures for sanitization defects. Do not weaken or skip a test to obtain green CI.

The internal `Test`, `Inspect code`, and `Verify plugin` component jobs remain distinct authorities: Test requires successful `./gradlew check`, Inspect code requires a valid Qodana SARIF report with zero High/Critical findings, and Verify plugin requires the maintained IDEA/DataGrip compatibility gates. The required `Merge Gate` fails if any applicable component fails. Run each Gradle validation directly once with `--no-daemon`; preserve its exit status. A failed invocation is not repaired by a log-signature retry. Investigate the whole failure set before remediation.


## Repository automation

Research, experiment, and temporary workflows should stay off protected default branch where practical. If an exceptional ephemeral workflow must land in `.github/workflows` on the default branch, filenames beginning with `research-`, `temporary-`, `tmp-`, or `experiment-` must declare non-empty `# lifecycle-owner:` and `# lifecycle-retire:` header metadata. The owner identifies the issue/authority responsible for the workflow; the retirement field states the concrete condition for deletion or consolidation. Historical Actions runs/artifacts are not automatically evidence. Preserve them when a concrete issue, PR, R2A campaign, security/release decision, benchmark, or migration record consumes them as immutable proof. Orphaned temporary/research/one-off history with no such consumer may be deleted after exact workflow-ID/path/run-count validation; never delete evidence-bearing history solely for sidebar cosmetics. Old run history is not a reason to keep or recreate obsolete workflow source.

Validation workflows are read-only. Dedicated metadata automation may only mutate issue/PR labels with narrowly scoped permissions and trusted default-branch configuration; it must never execute untrusted PR-head code. The protected-main Gradle Dependency Submission publisher is validated separately on PRs through the same pinned runtime in non-submitting `generate-and-upload` mode; PR dependency graphs have read-only authority and cannot become production submission evidence. Marketplace/GitHub Release mutation is isolated to the release-event-only `Marketplace Release` workflow, which executes an immutable reviewed tag, uses the `jetbrains-marketplace` environment, and is never reachable from pull-request or ordinary main validation.

Issue metadata automation owns only explicit repository title protocol. `bug|fix` maps to `type:bug`, `feat|feature` maps to `type:feature`, and repository/maintenance prefixes including `task|track|epic|build|ci|test|refactor|chore|perf|release|docs|security|audit|design|architecture|maintenance|hardening|governance` map to `type:maintenance`. Titles outside that protocol are left unchanged; issue body text and area ownership are never inferred heuristically.

The scheduled drift audit verifies only policy surfaces visible to a read-only GitHub token. GitHub deliberately redacts ruleset `bypass_actors` unless the caller has write access to the ruleset, so the no-bypass assertion is never inferred from a missing field; it remains a separate privileged live readback at the merge/exit gate.
