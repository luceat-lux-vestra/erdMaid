# JetBrains Marketplace publication

This document is the distribution authority for erdMaid's public JetBrains Marketplace channel.
It does not widen the plugin's maintained IDE support claims; those remain defined by
`docs/product-fidelity-contract.md` and exact CI/runtime/verifier evidence.

## Current state

The first Marketplace publication completed under #99:

- plugin ID: `com.algorist.erdmaid`;
- public version established: `1.0.0`;
- license: Apache-2.0;
- source code: `https://github.com/luceat-lux-vestra/erdMaid`;
- channel: default / Stable;
- listing: approved and public.

The initial Hidden bootstrap is historical. Future candidates target the existing public listing and
must not claim `Hidden initial upload` evidence.

## Current publication authority

Marketplace updates are **manual**.

The repository's `Marketplace Candidate` workflow may prepare an uploadable candidate only from
exact `main`. It has read-only repository permissions and deliberately has:

- no Marketplace publishing token;
- no author-signing private key or certificate-chain secret;
- no `publishPlugin` invocation;
- no `signPlugin` / `verifyPluginSignature` authority.

A green candidate workflow is preparation evidence, not publication evidence.

## Candidate preparation

Run `Marketplace Candidate` manually from `main` and provide an explicit stable SemVer version in
`MAJOR.MINOR.PATCH` form. Development timestamp versions are rejected.

Before producing the manual-upload artifact, the workflow:

1. validates stable SemVer and exact `refs/heads/main` / `GITHUB_SHA` identity;
2. builds the plugin with the requested version;
3. runs the repository test gate;
4. runs the maintained IntelliJ IDEA Plugin Verifier gate;
5. runs the separately pinned DataGrip verifier gate;
6. inspects the generated ZIP and nested plugin JAR;
7. requires packaged `META-INF/LICENSE` to be byte-identical to root `LICENSE`;
8. verifies plugin ID, version, vendor, `since-build`, and absence of a paid product descriptor;
9. emits `build/reports/marketplace-candidate.json` containing the exact commit, ZIP SHA-256,
   license/source/channel, `publicationMode=manual`, `listingState=existing-public`,
   `authorSigned=false`, and `authorSigningDisposition=deferred-owner-decision`.

The uploaded Actions artifact contains the candidate ZIP, candidate manifest, and verifier reports
for 14 days. It is evidence/staging only.

## Manual Marketplace update

For each update:

1. use the ZIP whose SHA-256 matches `marketplace-candidate.json`;
2. upload it manually to the existing erdMaid Marketplace listing on the default/Stable channel;
3. verify the submitted version and listing identity before release;
4. wait for Marketplace verification/review as applicable;
5. confirm the public Marketplace version/metadata after approval.

If candidate identity, digest, license/source metadata, Marketplace version, or approval state cannot
be verified with sufficient authority, the update is UNVERIFIED rather than PASS.

## Author signing — owner decision (#134)

JetBrains supports author signing in addition to Marketplace signing. erdMaid does **not** currently
author-sign Marketplace candidates.

Do not add a long-lived private key or GitHub secret merely to satisfy a checklist. Author signing may
be adopted only after the owner accepts and documents:

1. certificate/private-key generation and custody;
2. release-only secret/environment scope;
3. rotation, revocation, and recovery;
4. `signPlugin` on the exact candidate;
5. `verifyPluginSignature` before the ZIP is uploadable;
6. evidence that PR/fork-controlled execution cannot access signing authority.

Until those conditions are accepted and implemented, `authorSigned=false` is the truthful evidence
state.

## GitHub artifact attestation

Artifact attestation is **deferred / not the primary Marketplace control**. The current consumer
installs the Marketplace-hosted artifact, which JetBrains signs in its delivery pipeline; the
short-lived GitHub Actions artifact is staging evidence.

Reassess GitHub provenance attestation if:

- GitHub Releases becomes a consumer distribution surface; or
- the release architecture establishes a stable digest/verification chain from the GitHub-built ZIP
  to the Marketplace-delivered artifact that consumers or operators actually verify.

## Future automated publishing

Automated Marketplace publishing is not authorized by the current boundary. A future PR introducing
`publishPlugin` must separately prove Marketplace-token least privilege, trusted exact-main/ref
guards, secret non-exposure, failure/recovery behavior, and any adopted author-signing lifecycle.

## Historical bootstrap

The first publication used an exact reviewed `1.0.0` candidate, was uploaded manually, passed
Marketplace compatibility/review, was initially Hidden, and was then made public. #99 and #51 retain
that bootstrap and license/source consistency evidence.
