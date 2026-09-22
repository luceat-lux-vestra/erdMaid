#!/usr/bin/env python3
"""Fail-closed static contract tests for the Marketplace release workflow."""

from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github" / "workflows" / "release.yml"
BUILD = ROOT / "build.gradle.kts"


class MarketplaceReleaseTest(unittest.TestCase):
    def setUp(self) -> None:
        self.workflow = WORKFLOW.read_text(encoding="utf-8")
        self.build = BUILD.read_text(encoding="utf-8")

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
        self.assertIn("pending Marketplace publication identity exists", self.workflow)
        self.assertIn("Published signed asset digest does not match release identity", self.workflow)
        self.assertIn("Signed GitHub Release asset exists without a completed publication identity", self.workflow)
        self.assertIn('publication_state: "pending"', self.workflow)


if __name__ == "__main__":
    unittest.main()
