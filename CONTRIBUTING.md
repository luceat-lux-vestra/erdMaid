# Contributing to erdMaid

erdMaid is currently solo-maintained, but changes still follow the same proof obligations as external contributions.

## Before changing code

1. Start from a freshly read `main` SHA and keep the change tied to one owning issue/Track.
2. Read `AGENTS.md`; it is the authoritative engineering/review contract.
3. Do not widen product, IDE/DataGrip, metadata-fidelity, or distribution claims without evidence.

## Pull requests

Keep one independently reviewable purpose. Record the base SHA, scope/non-goals, automated/manual evidence, and known gaps. CI green is necessary but insufficient: the exact final PR HEAD is manually reviewed for correctness, failure/recovery, regression, compatibility, edge cases, ownership/lifecycle, adversarial behavior, workflow security, documentation, and diff scope. Any HEAD movement invalidates prior review evidence.

Merges are squash-only. Merge-time PR HEAD and fresh `main` are re-read before merge, and merged `main` plus live repository controls are verified afterward when the change affects governance.

## Tests

Use full deterministic output assertions for rendering/identity behavior. Add literal hostile fixtures for sanitization defects. Do not weaken or skip a test to obtain green CI.

## Repository automation

Validation workflows are read-only. Dedicated metadata automation may only mutate issue/PR labels with narrowly scoped permissions and trusted default-branch configuration; it must never execute untrusted PR-head code. Release/tag/branch mutation is not part of the current repository contract.
