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
GRADLE_PROPERTIES = REPOSITORY_ROOT / "gradle.properties"


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

    def test_first_upload_workflow_has_only_manual_trigger_and_no_secrets(self):
        text = CANDIDATE_WORKFLOW.read_text(encoding="utf-8")
        lines = text.splitlines()
        on_index = lines.index("on:")
        triggers: list[str] = []
        for line in lines[on_index + 1 :]:
            if line and not line.startswith(" "):
                break
            if line.startswith("  ") and not line.startswith("    ") and line.endswith(":"):
                triggers.append(line.strip()[:-1])
        self.assertEqual(["workflow_dispatch"], triggers)
        self.assertIn("permissions:\n  contents: read\n", text)
        self.assertNotIn("secrets.", text)
        self.assertNotIn("PUBLISH_TOKEN", text)
        self.assertNotIn("publishPlugin", text)
        self.assertNotIn("signPlugin", text)
        self.assertNotIn("verifyPluginSignature", text)
        self.assertIn('if [ "$GITHUB_REF" != "refs/heads/main" ]; then', text)
        self.assertIn("persist-credentials: false", text)

    def test_repository_source_url_matches_marketplace_contract(self):
        properties: dict[str, str] = {}
        for raw_line in GRADLE_PROPERTIES.read_text(encoding="utf-8").splitlines():
            line = raw_line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            properties[key.strip()] = value.strip()
        self.assertEqual(candidate.EXPECTED_SOURCE_URL, properties.get("pluginRepositoryUrl"))

    def test_duplicate_archive_entries_are_rejected(self):
        with self.assertRaises(candidate.CandidateError):
            candidate._safe_names(["same", "same"], label="fixture")

    def _archive(
        self,
        root: Path,
        *,
        version: str = "1.2.3",
        plugin_id: str = candidate.EXPECTED_PLUGIN_ID,
        vendor: str = candidate.EXPECTED_VENDOR,
        since_build: str = "262",
        until_build: str | None = None,
        license_bytes: bytes = b"license\n",
        product_descriptor: bool = False,
        archive_name: str | None = None,
    ) -> tuple[Path, Path]:
        license_path = root / "LICENSE"
        license_path.write_bytes(b"license\n")
        product = '<product-descriptor code="NOPE"/>\n' if product_descriptor else ""
        until = f' until-build="{until_build}"' if until_build is not None else ""
        plugin_xml = f"""<idea-plugin>
  <id>{plugin_id}</id>
  <name>erdMaid</name>
  <version>{version}</version>
  <vendor>{vendor}</vendor>
  <idea-version since-build="{since_build}"{until}/>
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

    def test_wrong_identity_version_license_bounds_and_paid_descriptor_fail_closed(self):
        cases = (
            {"plugin_id": "wrong.id"},
            {"vendor": "wrong"},
            {"since_build": "263"},
            {"until_build": "262.*"},
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
