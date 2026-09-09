# JetBrains Marketplace publication

This document is the distribution authority for erdMaid's first JetBrains Marketplace publication.
It does not widen the plugin's maintained IDE support claims; those remain defined by
`docs/product-fidelity-contract.md` and the exact CI/verifier evidence.

## First-publication rule

The first JetBrains Marketplace publication is **manual**. Do not use `publishPlugin` for the first
upload. The repository's `Marketplace Candidate` workflow prepares and verifies the exact ZIP for
manual upload, but it has no Marketplace publishing credential and cannot upload it.

The initial upload must use:

| Marketplace field | Required value |
| --- | --- |
| Plugin XML ID | `com.algorist.erdmaid` |
| License | Apache License 2.0 |
| Source code | `https://github.com/luceat-lux-vestra/erdMaid` |
| Release channel | default / Stable |
| Hidden | yes |
| Paid/freemium product descriptor | none |

The Hidden choice is deliberate. Initial hidden publication goes through Marketplace approval while
keeping the plugin out of normal Marketplace, IDE, and search discovery. Verify the approved listing
metadata before any later public unhide decision.

## Candidate preparation

Run `Marketplace Candidate` manually **from `main`** and provide an explicit stable SemVer version in
`MAJOR.MINOR.PATCH` form. Development timestamp versions are not release versions and are rejected.
The workflow refuses non-`main` refs and records the exact `GITHUB_SHA` in its evidence manifest.

The bootstrap intentionally has no repository or Marketplace secrets. Author-side plugin signing is
not required for the first Marketplace upload and is not part of this boundary. JetBrains
Marketplace performs its own signing in the publication/delivery pipeline. If erdMaid later adopts
an author-signing key lifecycle, that is a separate trust-boundary change requiring independent
review and recovery evidence.

Before producing the manual-upload artifact, the workflow:

1. validates the stable SemVer input and exact `main` checkout identity;
2. builds the plugin with that version via `ORG_GRADLE_PROJECT_buildVersion`;
3. runs the repository test gate;
4. runs the maintained IntelliJ IDEA Plugin Verifier gate;
5. runs the separately pinned DataGrip verifier gate;
6. inspects the generated ZIP and nested plugin JAR;
7. requires packaged `META-INF/LICENSE` to be byte-identical to root `LICENSE`;
8. verifies plugin ID, version, vendor, `since-build`, and absence of a paid product descriptor;
9. emits `build/reports/marketplace-candidate.json` with exact commit, ZIP SHA-256,
   `authorSigned=false`, license/source/channel, and Hidden metadata.

The uploaded Actions artifact contains the candidate ZIP, candidate manifest, and verifier reports.
It is preparation evidence only; artifact creation is not Marketplace publication evidence.

## Manual Marketplace bootstrap

After a candidate workflow run is proven PASS from its raw logs:

1. use the ZIP whose SHA-256 matches `marketplace-candidate.json`;
2. sign in to JetBrains Marketplace using the intended Vendor profile and satisfy the Marketplace
   Developer Agreement requirements;
3. upload the ZIP as a new plugin manually;
4. enter the exact license/source/channel/Hidden values from the table above;
5. wait for Marketplace review/approval;
6. inspect the authoritative plugin page/admin metadata after approval;
7. verify that the Marketplace plugin XML identity, Apache-2.0 license, and source-code URL all match
   this repository before closing #51.

If any field is absent, stale, points at a deleted historical repository, or cannot be read with
sufficient authority, its state is UNVERIFIED and #51 remains open.

## Later automated updates

Automated Marketplace updates are intentionally out of scope for the first upload. Only after the
approved listing exists and its identity has been verified may a separate PR establish a publishing
trust boundary using a permanent Marketplace token. That later change must be separately reviewed
for least privilege, exact-ref guards, secret handling, optional author signing if adopted, channels,
failure recovery, and proof that repository credentials cannot be used to mutate source/release
state unexpectedly.
