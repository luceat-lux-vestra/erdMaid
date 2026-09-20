<!-- failure-triage:v1:start -->
## Failure remediation

Select exactly one. Required for human-authored PRs.

- [ ] Not remediation for an observed failure
- [ ] Remediation for an observed failure

If this PR is remediation, replace every placeholder. If root cause is still UNKNOWN / UNVERIFIED / INSUFFICIENT EVIDENCE, stop remediation and investigate first.

Observed:
<!-- What failed, where, and on which exact revision/run? -->

Classification:
<!-- Exactly one: implementation defect | test defect | evidence defect | workflow-policy drift | environment failure -->

Basis:
<!-- Why is this responsibility layer proven? Which plausible alternatives were rejected or remain unresolved? -->

Root cause:
<!-- Established cause; UNKNOWN / UNVERIFIED / INSUFFICIENT EVIDENCE / TBD are not remediation states. -->

Remediation:
<!-- Which owning layer changes, and why is this the minimum justified change? -->

Proof:
<!-- What will prove the cause is resolved without weakening tests/evidence/policy? -->
<!-- failure-triage:v1:end -->

## Purpose

<!-- One independently reviewable purpose. Link the owning issue/Track. -->

Refs #

## Provenance

- Fresh base `main` SHA: `TODO`
- Final PR HEAD SHA: recorded only after implementation is frozen

## Scope / non-goals

- In scope:
- Explicitly out of scope:

## Proof obligations

- [ ] Correctness and deterministic behavior
- [ ] Failure/recovery behavior
- [ ] Regression and compatibility impact
- [ ] Edge cases and ownership/lifecycle boundaries
- [ ] Adversarial/negative evidence where applicable
- [ ] Workflow/security permissions and trust boundary where applicable
- [ ] Documentation matches the final behavior
- [ ] No unrelated diff or dead compatibility code

## Evidence

<!-- Commands, CI jobs, fixtures, exact versions/builds, and any manual/runtime evidence. -->

## Exact-HEAD merge gate

- [ ] Required PR CI is green for the current PR HEAD/base pair; when GitHub uses a synthetic `pull_request` merge ref, that ref corresponds to this current head and base
- [ ] Independent manual diff review is complete on the exact final PR HEAD SHA
- [ ] Any HEAD movement invalidates previous review/evidence
- [ ] Merge-time PR HEAD and fresh `main` are re-read before squash merge
