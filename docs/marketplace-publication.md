# JetBrains Marketplace publication

This document is the distribution authority for erdMaid's public JetBrains Marketplace channel.

## Current state

- plugin ID: `com.algorist.erdmaid`;
- public Marketplace listing: existing and approved;
- first public version: `1.0.0`;
- license: Apache-2.0;
- source: `https://github.com/luceat-lux-vestra/erdMaid`;
- production channel: default / Stable.

The historical first-upload bootstrap is complete. Repository automation may publish updates, but only
through the release-only authority defined below.

## Publication authorities

There are two deliberately separate workflows.

### Marketplace Candidate

`.github/workflows/marketplace-candidate.yml` remains a read-only, manually dispatched staging and
diagnostic path. It:

- runs only from exact `main`;
- accepts an explicit stable SemVer;
- builds, tests, verifies IDEA/DataGrip compatibility, and inspects the ZIP;
- has no Marketplace token or signing secrets;
- never invokes `publishPlugin`, `signPlugin`, or `verifyPluginSignature`;
- emits `publicationMode=candidate-only`, `authorSigned=false`, and
  `authorSigningDisposition=required-for-automated-release`.

A green candidate is evidence only. It is not production publication authority.

### Marketplace Release

`.github/workflows/release.yml` is the only automated production publication authority.

It runs only for a published GitHub Release whose tag is stable `vMAJOR.MINOR.PATCH`. The effective
plugin version is the tag with the leading `v` removed. The tag commit must be reachable from
reviewed `main`.

For a new publication the workflow, in order:

1. proves release/tag identity and reviewed-main ancestry;
2. proves no completed/pending publication identity conflicts with the release;
3. rebuilds the exact tagged source with the exact SemVer;
4. runs the repository test gate and IDEA/DataGrip verifier gates;
5. inspects the unsigned candidate identity and packaged license;
6. requires all Marketplace/signing credentials before any signing or publication mutation;
7. author-signs the exact ZIP;
8. runs `verifyPluginSignature`;
9. captures one exact `*-signed.zip` and SHA-256;
10. creates a GitHub build-provenance attestation for that signed ZIP;
11. uploads a pending publication-identity lock to the GitHub Release;
12. invokes `publishPlugin -x signPlugin`, so the already-verified signed artifact is not re-signed;
13. rechecks the signed SHA-256 after Marketplace publication;
14. uploads the same signed ZIP as the GitHub Release asset;
15. uploads a completed publication identity.

Ordinary PR/main workflows have no Marketplace or signing credentials and cannot publish.

## Release environment and secrets

The release job uses the GitHub environment `jetbrains-marketplace`. That environment must contain:

- `PUBLISH_TOKEN` — JetBrains Marketplace publication token;
- `CERTIFICATE_CHAIN` — author-signing certificate chain;
- `PRIVATE_KEY` — author-signing private key;
- `PRIVATE_KEY_PASSWORD` — private-key password.

The private key and password are release-only secrets. They must not be copied into repository files,
PR workflows, logs, artifacts, issue comments, or local fixtures.

Key lifecycle:

- custody: GitHub environment secret storage plus the owner's offline backup;
- rotation: create a new signing identity deliberately, update all four release secrets atomically,
  then prove signature verification before publishing;
- revocation/compromise: stop releases, revoke/replace the affected identity where supported, rotate
  secrets, and use a new plugin version rather than rewriting an already published artifact;
- loss: do not bypass signing. Recover from the offline backup or establish a replacement signing
  identity before the next release.

## Tag governance

Before the automated path is considered production-ready, the live repository must protect
`refs/tags/v*` against update and deletion with no routine bypass while allowing new release-tag
creation. A release workflow run also checks that the tag resolves to reviewed `main`, but that
runtime ancestry check is not a substitute for tag immutability.

## Recovery

The workflow intentionally distinguishes three states:

- no identity: a new publication may proceed;
- `erdmaid-release-identity.json` only: publication is ambiguous/pending and automatic rerun stops;
- `erdmaid-release-published.json`: rerun is a verified no-op only after the recorded signed
  GitHub Release asset digest is revalidated.

A pending identity may mean Marketplace publication succeeded before a later workflow step failed.
Do not rerun or republish blindly. Check Marketplace version state and the GitHub Release assets
authoritatively. If the Marketplace version exists, complete/reconcile the GitHub evidence without
re-uploading the same Marketplace version. If publication did not occur, determine why before any
new attempt. Never move/reuse the existing tag to different source.

## Release procedure

1. choose the next stable SemVer greater than the current Marketplace version;
2. require the target commit to be reviewed and present on `main`;
3. create protected tag `vMAJOR.MINOR.PATCH` on that exact commit;
4. create/publish the GitHub Release for that tag;
5. the Marketplace Release workflow performs the signed automated publication;
6. verify the Marketplace update and the GitHub Release signed asset after the workflow completes.

Do not manufacture a production release solely as CI evidence.
