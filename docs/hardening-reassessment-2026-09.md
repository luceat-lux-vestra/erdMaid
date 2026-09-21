# Hardening Reassessment — 2026-09-20

Owning issue: #111

The repository was reassessed against current external GitHub/OpenSSF guidance and current operating practice. Existing hardening remains authoritative where evidence still applies; this pass closes only demonstrated gaps.

## Existing controls retained

- checked-in repository/ruleset policy and scheduled live drift;
- squash-only / linear-history merge governance;
- immutable action references;
- bounded jobs and concurrency;
- actionlint + zizmor with negative controls;
- Dependabot for Gradle and GitHub Actions;
- trusted-base PR metadata automation;
- required trusted-base `failure-triage` gate for classification-before-remediation;
- Marketplace candidate boundary and current manual publication authority.

## GAP — issue metadata

The repository already defines `type:bug`, `type:feature`, and `type:maintenance`, but API/direct-created issues can bypass metadata.

The reconciler reuses those existing labels:

- `fix|bug` -> `type:bug`
- `feat|feature` -> `type:feature`
- maintenance-oriented explicit prefixes -> `type:maintenance`

Unknown titles are left unchanged. No body/NLP or area inference is performed. Backfill defaults to dry-run.

## STAGED — Dependency Review

Dependency Review is added as a staged required-context candidate. It is not part of the live required list until:

1. ordinary PR execution is proven;
2. Dependency Graph support is verified live;
3. the checked-in required list and live ruleset are updated together;
4. authoritative policy/ruleset readback passes.

Dependency Review is PR-diff-scoped and therefore has no merged-main execution proof.

## ADVISORY — CodeQL Actions; Kotlin upstream-blocked

GitHub Actions CodeQL analysis runs on exact pull-request heads, `main`, and schedule.

The attempted Java/Kotlin leg produced a useful hardening result: the stable CodeQL extractor rejects this repository's Kotlin 2.4.20 compile path as too recent, while GitHub's current supported-language documentation lists Kotlin 2.4.20. This is treated as an external scanner-boundary mismatch, not hidden as a repository success.

The reassessment therefore keeps stable Actions analysis, removes the persistently red Kotlin advisory leg, and tracks restoration in #114. It does not downgrade the product Kotlin version or adopt a nightly CodeQL bundle just to obtain a green advisory signal.

Build, Test, Plugin Verifier, Workflow Static Analysis, and failure-triage remain authoritative.

## MACHINE-READABLE MANUAL ASSERTIONS — live security features

The reassessment does not treat prose or a green repository-owned workflow as proof of GitHub-hosted security settings. The checked-in policy now declares the exact privileged live assertions that must hold:

- Dependency Graph: enabled;
- Dependabot vulnerability alerts: enabled;
- Dependabot security updates: enabled;
- secret scanning: enabled;
- secret scanning push protection: enabled;
- private vulnerability reporting: enabled.

These assertions are schema-tested fail-closed. They are intentionally not inferred by a low-privilege scheduled audit when GitHub withholds admin-only fields. Final reassessment evidence requires an administrator-authorized readback of the actual repository settings/endpoints; missing or unavailable evidence remains UNVERIFIED.

## Distribution provenance

erdMaid has a real Marketplace distribution path, but the existing repository contract still treats Marketplace publication as a separate boundary. Artifact attestations are not added mechanically in this pass. Reassess provenance/attestation if repository automation becomes the authority that produces and publishes end-user release artifacts.

## Exit criteria

- exact final PR HEAD passes all currently required contexts, including failure-triage;
- Workflow Static Analysis proves the new workflows and issue policy;
- Dependency Review support is classified from live evidence, not assumed;
- merged-main behavior is read back;
- any required-context promotion is atomic with live ruleset state;
- issue backfill is reviewed in dry-run before mutation.

`UNKNOWN`, `UNVERIFIED`, and `INSUFFICIENT EVIDENCE` remain FAIL for claimed controls.


## LIVE BLOCKER — GitHub pull_request_target event policy

GitHub's public-repository default Actions event policy is currently evaluating
`pull_request_target` and is scheduled for enforcement on 2026-11-02.

This repository still deliberately uses that trigger on the following audited
trusted-base / metadata-only workflows:

- `.github/workflows/pr-metadata.yml`
- `.github/workflows/failure-triage.yml`

The workflows must not be migrated to ordinary `pull_request` merely to avoid
the platform policy: doing so would move governance/metadata execution authority
onto PR-controlled workflow definitions. Instead, issue #120 owns one
administrative live prerequisite:

- read the repository Actions policies;
- add an active workflow-path-scoped event policy for only the audited paths;
- allow only `pull_request_target` for those paths;
- read the policy back and retain its id, path condition, enforcement, and event set;
- exercise the workflow on a real PR after activation.

A repository-wide `pull_request_target` allow rule is not accepted.
Any future checkout or execution of PR-controlled code under these workflows
invalidates the allow decision and requires a new security review.
