#!/usr/bin/env python3
"""Fail-closed static contract tests for the Marketplace release workflow."""

from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github" / "workflows" / "release.yml"
BUILD = ROOT / "build.gradle.kts"
CODEQL = ROOT / ".github" / "workflows" / "codeql.yml"


class MarketplaceReleaseTest(unittest.TestCase):
    def setUp(self) -> None:
        self.workflow = WORKFLOW.read_text(encoding="utf-8")
        self.build = BUILD.read_text(encoding="utf-8")

    def test_codeql_cadence_weekly_manual_without_routine_pr(self):
        codeql = CODEQL.read_text(encoding="utf-8")
        triggers, remainder = codeql.split("\npermissions:", 1)
        self.assertIn("  workflow_dispatch:", triggers)
        self.assertIn("  schedule:", triggers)
        self.assertIn('    - cron: "47 3 * * 3"', triggers)
        for forbidden in ("pull_request", "pull_request_target", "push",
                          "merge_group", "workflow_run"):
            self.assertNotRegex(triggers, rf"(?m)^  {forbidden}:")
        self.assertNotIn("github.event.pull_request", remainder)
        self.assertIn("candidate_sha:", triggers)
        self.assertIn("Verify exact protected-main candidate", remainder)
        self.assertIn("Fail closed on invalid or High/Critical security evidence", remainder)
        self.assertIn("release_codeql_verdict.py", remainder)
        self.assertIn("retention-days: 90", remainder)
        self.assertIn("- language: actions", remainder)
        self.assertIn("- language: java-kotlin", remainder)
        self.assertIn("build-mode: autobuild", remainder)

    def test_new_marketplace_publication_requires_codeql(self):
        prerequisite, publisher = self.workflow.split("\n  publish:\n", 1)
        self.assertIn("  codeql_preflight:", prerequisite)
        self.assertIn("    needs: codeql_preflight", publisher)
        self.assertIn("Run trusted exact-source CodeQL reuse/dispatch/join/wait", prerequisite)
        self.assertIn("Classify previously completed Marketplace recovery", prerequisite)
        self.assertIn("steps.recovery.outputs.publish == 'true'", prerequisite)
        self.assertIn("ref: main", prerequisite)
        self.assertIn("path: .trusted-codeql-preflight", prerequisite)
        self.assertIn(".trusted-codeql-preflight/scripts/release_codeql_gate.py", prerequisite)
        self.assertIn("actions: write", prerequisite)
        self.assertNotIn("contents: write", prerequisite)
        self.assertNotIn("environment: jetbrains-marketplace", prerequisite)
        self.assertNotIn("id-token: write", prerequisite)
        self.assertNotIn("attestations: write", prerequisite)
        for forbidden in ("signPlugin", "publishPlugin", "gh release upload"):
            self.assertNotIn(forbidden, prerequisite)
        self.assertNotIn("actions: write", publisher)
        self.assertIn("actions: read", publisher)
        self.assertIn("contents: write", publisher)
        self.assertIn("environment: jetbrains-marketplace", publisher)
        for required in ("Resolve publication recovery state", "Sign exact Marketplace artifact",
                         "Lock publication identity", "Publish verified artifact to JetBrains Marketplace"):
            self.assertIn(required, publisher)
        self.assertNotIn("scripts/release_codeql_gate.py", publisher)

    def test_exact_source_codeql_provenance_and_retention(self):
        codeql = CODEQL.read_text(encoding="utf-8")
        self.assertIn("run-name: CodeQL source ${{ inputs.candidate_sha || github.sha }}", codeql)
        self.assertIn("group: codeql-${{ inputs.candidate_sha || github.sha }}", codeql)
        self.assertIn("cancel-in-progress: false", codeql)
        for step in ("Verify exact checkout", "Verify protected-main ancestry",
                     "Record analyzed source", "Verify protected-main ancestry after analysis"):
            self.assertIn(step, codeql)
        self.assertIn("codeql-source-${{ env.EXPECTED_SHA }}-${{ matrix.language }}", codeql)
        self.assertIn("codeql-${{ env.EXPECTED_SHA }}-${{ matrix.language }}", codeql)
        self.assertIn("source-identity.json", codeql)
        self.assertIn("git merge-base --is-ancestor", codeql)
        self.assertIn("if [ \"$GITHUB_EVENT_NAME\" = \"schedule\" ]; then", codeql)

    def test_release_only_trigger_and_environment_boundary(self):
        self.assertIn("release:\n    types: [released]", self.workflow)
        self.assertNotIn("pull_request:", self.workflow)
        self.assertNotIn("pull_request_target:", self.workflow)
        self.assertNotIn("workflow_dispatch:", self.workflow)
        self.assertIn("environment: jetbrains-marketplace", self.workflow)
        self.assertIn("persist-credentials: false", self.workflow)

    def test_release_tag_and_reviewed_main_are_fail_closed(self):
        self.assertIn("Stable release tags must be vMAJOR.MINOR.PATCH", self.workflow)
        self.assertIn('git merge-base --is-ancestor "$tag_sha" origin/main', self.workflow)
        self.assertIn("marketplace_candidate.py validate-version", self.workflow)

    def test_author_signing_verification_attestation_and_publication_order(self):
        required = [
            "Sign exact Marketplace artifact",
            "Verify signed Marketplace artifact",
            "Capture verified signed artifact",
            "Attest verified signed artifact",
            "Lock publication identity",
            "Publish verified artifact to JetBrains Marketplace",
            "Recheck signed artifact identity",
            "Upload verified signed GitHub Release asset",
            "Mark publication complete",
        ]
        positions = [self.workflow.index(value) for value in required]
        self.assertEqual(sorted(positions), positions)
        self.assertIn("publishPlugin -x signPlugin", self.workflow)
        self.assertRegex(self.workflow, r"uses: actions/attest@[0-9a-f]{40}")
        self.assertIn("id-token: write", self.workflow)
        self.assertIn("attestations: write", self.workflow)

    def test_release_credentials_are_explicit_and_candidate_workflow_remains_separate(self):
        for secret in (
            "PUBLISH_TOKEN",
            "CERTIFICATE_CHAIN",
            "PRIVATE_KEY",
            "PRIVATE_KEY_PASSWORD",
        ):
            self.assertIn(f"secrets.{secret}", self.workflow)
        self.assertIn('providers.environmentVariable("PUBLISH_TOKEN")', self.build)
        self.assertIn('providers.environmentVariable("CERTIFICATE_CHAIN")', self.build)
        self.assertIn('providers.environmentVariable("PRIVATE_KEY")', self.build)
        self.assertIn('providers.environmentVariable("PRIVATE_KEY_PASSWORD")', self.build)

    def test_recovery_is_fail_closed(self):
        self.assertIn("Pending Marketplace publication identity exists", self.workflow)
        self.assertIn("Published identity requires its original pending identity", self.workflow)
        self.assertIn("Pending and published release identities conflict", self.workflow)
        self.assertIn('[ "$pending_canonical" = "$published_canonical" ]', self.workflow)
        self.assertIn('.marketplace_channel == "default"', self.workflow)
        self.assertIn(".author_signed == true", self.workflow)
        self.assertIn("Published signed asset digest does not match release identity", self.workflow)
        self.assertIn("Signed GitHub Release asset exists without a completed publication identity", self.workflow)
        self.assertIn('publication_state: "pending"', self.workflow)


if __name__ == "__main__":
    unittest.main()
