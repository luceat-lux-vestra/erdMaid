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
