#!/usr/bin/env python3
"""Negative/positive controls for scripts/marketplace_candidate.py."""

from __future__ import annotations

import io
import tempfile
import unittest
import zipfile
from pathlib import Path

import marketplace_candidate as candidate


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
CANDIDATE_WORKFLOW = REPOSITORY_ROOT / ".github" / "workflows" / "marketplace-candidate.yml"


class MarketplaceCandidateTest(unittest.TestCase):
    def test_stable_semver_accepts_only_three_numeric_components(self):
        for version in ("0.1.0", "1.0.0", "10.20.30"):
            self.assertEqual(version, candidate.validate_version(version))
        for version in (
            "26.09.10.0742",
            "01.0.0",
            "1.00.0",
            "1.0.00",
            "1.0",
            "1.0.0-beta.1",
            "1.0.0+build",
            " 1.0.0",
            "1.0.0 ",
            "1.0.0; echo pwned",
        ):
            with self.subTest(version=version):
                with self.assertRaises(candidate.CandidateError):
                    candidate.validate_version(version)

    def test_commit_must_be_exact_lowercase_sha(self):
        good = "a" * 40
        self.assertEqual(good, candidate.validate_commit(good))
        for value in ("a" * 39, "A" * 40, "main", good + "0"):
            with self.subTest(value=value):
                with self.assertRaises(candidate.CandidateError):
                    candidate.validate_commit(value)

    def test_first_upload_workflow_is_manual_read_only_non_publishing_and_secret_free(self):
        text = CANDIDATE_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("on:\n  workflow_dispatch:\n", text)
        for forbidden_trigger in ("\n  push:", "\n  pull_request:", "\n  schedule:"):
            self.assertNotIn(forbidden_trigger, text)
        self.assertIn("permissions:\n  contents: read\n", text)
        self.assertNotIn("secrets.", text)
        self.assertNotIn("PUBLISH_TOKEN", text)
        self.assertNotIn("publishPlugin", text)
        self.assertNotIn("signPlugin", text)
        self.assertNotIn("verifyPluginSignature", text)
        self.assertIn('if [ "$GITHUB_REF" != "refs/heads/main" ]; then', text)
        self.assertIn("persist-credentials: false", text)

    def _archive(
        self,
        root: Path,
        *,
        version: str = "1.2.3",
        plugin_id: str = candidate.EXPECTED_PLUGIN_ID,
        vendor: str = candidate.EXPECTED_VENDOR,
        since_build: str = "262",
        license_bytes: bytes = b"license\n",
        product_descriptor: bool = False,
        archive_name: str | None = None,
    ) -> tuple[Path, Path]:
        license_path = root / "LICENSE"
        license_path.write_bytes(b"license\n")
        product = '<product-descriptor code="NOPE"/>\n' if product_descriptor else ""
        plugin_xml = f"""<idea-plugin>
  <id>{plugin_id}</id>
  <name>erdMaid</name>
  <version>{version}</version>
  <vendor>{vendor}</vendor>
  <idea-version since-build="{since_build}"/>
  {product}
</idea-plugin>
""".encode()
        jar_buffer = io.BytesIO()
        with zipfile.ZipFile(jar_buffer, "w") as jar:
            jar.writestr("META-INF/plugin.xml", plugin_xml)
            jar.writestr("META-INF/LICENSE", license_bytes)
        path = root / (archive_name or f"erdMaid-{version}.zip")
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr(f"erdMaid/lib/erdMaid-{version}.jar", jar_buffer.getvalue())
        return path, license_path

    def test_valid_archive_produces_fixed_marketplace_metadata(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            archive, license_path = self._archive(root)
            evidence = candidate.inspect_archive(
                archive,
                version="1.2.3",
                commit="a" * 40,
                license_path=license_path,
            )
            self.assertEqual(candidate.EXPECTED_PLUGIN_ID, evidence["pluginXmlId"])
            self.assertEqual(candidate.EXPECTED_SOURCE_URL, evidence["sourceCodeUrl"])
            self.assertEqual(candidate.EXPECTED_LICENSE, evidence["license"])
            self.assertEqual("default", evidence["releaseChannel"])
            self.assertIs(True, evidence["hiddenInitialUpload"])
            self.assertIs(False, evidence["authorSigned"])
            self.assertEqual(64, len(evidence["archiveSha256"]))

    def test_wrong_identity_version_license_and_paid_descriptor_fail_closed(self):
        cases = (
            {"plugin_id": "wrong.id"},
            {"vendor": "wrong"},
            {"since_build": "263"},
            {"license_bytes": b"drifted\n"},
            {"product_descriptor": True},
            {"archive_name": "erdMaid-1.2.3-signed.zip"},
        )
        for kwargs in cases:
            with self.subTest(kwargs=kwargs), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                archive, license_path = self._archive(root, **kwargs)
                with self.assertRaises(candidate.CandidateError):
                    candidate.inspect_archive(
                        archive,
                        version="1.2.3",
                        commit="a" * 40,
                        license_path=license_path,
                    )

    def test_packaged_version_mismatch_fails_closed(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            archive, license_path = self._archive(
                root,
                version="2.0.0",
                archive_name="erdMaid-1.2.3.zip",
            )
            with self.assertRaises(candidate.CandidateError):
                candidate.inspect_archive(
                    archive,
                    version="1.2.3",
                    commit="a" * 40,
                    license_path=license_path,
                )

    def test_unsafe_outer_archive_path_fails_closed(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            license_path = root / "LICENSE"
            license_path.write_text("license\n", encoding="utf-8")
            path = root / "erdMaid-1.2.3.zip"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("../escape.jar", b"not-a-jar")
            with self.assertRaises(candidate.CandidateError):
                candidate.inspect_archive(
                    path,
                    version="1.2.3",
                    commit="a" * 40,
                    license_path=license_path,
                )


if __name__ == "__main__":
    unittest.main()
