#!/usr/bin/env python3
"""Negative controls for the standalone DataGrip compatibility gate."""

from __future__ import annotations

import io
import json
import tarfile
import tempfile
import unittest
from pathlib import Path

import datagrip_verifier as gate


def release_payload(
    *,
    version: str = gate.DATAGRIP_VERSION,
    build: str = gate.DATAGRIP_BUILD,
    link: str = "https://download.jetbrains.com/datagrip/datagrip-2026.2.4.tar.gz",
    checksum_link: str = "https://download.jetbrains.com/datagrip/datagrip-2026.2.4.tar.gz.sha256",
    size: int = 123,
) -> dict:
    return {
        "DG": [
            {
                "type": "release",
                "version": version,
                "build": build,
                "downloads": {
                    "linux": {
                        "link": link,
                        "checksumLink": checksum_link,
                        "size": size,
                    }
                },
            }
        ]
    }


def write_verdict(root: Path, verdict: str) -> Path:
    result = root / "DB-262.10315.24" / "plugins" / "erdMaid" / "1"
    result.mkdir(parents=True)
    (result / "verification-verdict.txt").write_text(verdict, encoding="utf-8")
    return result


class DataGripVerifierPolicyTests(unittest.TestCase):
    def test_exact_release_is_selected(self) -> None:
        selected = gate.select_datagrip_release(release_payload())
        self.assertEqual(gate.DATAGRIP_VERSION, selected.version)
        self.assertEqual(gate.DATAGRIP_BUILD, selected.build)
        self.assertEqual(123, selected.size)

    def test_wrong_build_is_rejected(self) -> None:
        with self.assertRaises(gate.GateError):
            gate.select_datagrip_release(release_payload(build="262.0.0"))

    def test_wrong_version_is_rejected(self) -> None:
        with self.assertRaises(gate.GateError):
            gate.select_datagrip_release(release_payload(version="2026.2.5"))

    def test_duplicate_exact_release_is_rejected(self) -> None:
        payload = release_payload()
        payload["DG"].append(dict(payload["DG"][0]))
        with self.assertRaises(gate.GateError):
            gate.select_datagrip_release(payload)

    def test_non_release_channel_is_rejected(self) -> None:
        payload = release_payload()
        payload["DG"][0]["type"] = "eap"
        with self.assertRaises(gate.GateError):
            gate.select_datagrip_release(payload)

    def test_untrusted_download_host_is_rejected(self) -> None:
        with self.assertRaises(gate.GateError):
            gate.select_datagrip_release(
                release_payload(link="https://example.com/datagrip/datagrip.tar.gz")
            )

    def test_untrusted_download_path_is_rejected(self) -> None:
        with self.assertRaises(gate.GateError):
            gate.select_datagrip_release(
                release_payload(link="https://download.jetbrains.com/idea/idea.tar.gz")
            )

    def test_checksum_parser_accepts_standard_checksum_file(self) -> None:
        digest = "a" * 64
        self.assertEqual(digest, gate.parse_sha256(f"{digest}  datagrip.tar.gz\n"))

    def test_checksum_parser_rejects_non_sha256(self) -> None:
        with self.assertRaises(gate.GateError):
            gate.parse_sha256("not-a-sha256 datagrip.tar.gz")

    def test_product_info_must_match_product_version_and_build(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            info = {
                "name": "DataGrip",
                "productCode": gate.DATAGRIP_PRODUCT_CODE,
                "version": gate.DATAGRIP_VERSION,
                "buildNumber": gate.DATAGRIP_BUILD,
            }
            (root / "product-info.json").write_text(json.dumps(info), encoding="utf-8")
            self.assertEqual(info, gate.validate_product_info(root))

            for key, wrong in (
                ("productCode", "IU"),
                ("version", "2026.2.5"),
                ("buildNumber", "262.0.0"),
            ):
                modified = dict(info)
                modified[key] = wrong
                (root / "product-info.json").write_text(
                    json.dumps(modified), encoding="utf-8"
                )
                with self.subTest(key=key), self.assertRaises(gate.GateError):
                    gate.validate_product_info(root)

    def test_known_non_blocking_verdict_grammar_is_allowed(self) -> None:
        verdicts = (
            "Compatible",
            "Compatible. 1 usage of deprecated API",
            "Compatible. 2 usages of scheduled for removal API and 1 usage of deprecated API. 3 usages of experimental API. 2 non-extendable API usage violations. 1 plugin configuration defect",
            "1 compatibility warning",
            "2 compatibility warnings. 1 usage of scheduled for removal API. 2 plugin configuration defects",
        )
        for verdict in verdicts:
            with self.subTest(verdict=verdict), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                write_verdict(root, verdict)
                self.assertEqual(verdict, gate.evaluate_reports(root))

    def test_report_policy_allows_non_blocking_warning_files(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            result = write_verdict(
                root,
                "1 compatibility warning. 2 usages of deprecated API",
            )
            (result / "compatibility-warnings.txt").write_text(
                "warning", encoding="utf-8"
            )
            (result / "deprecated-usages.txt").write_text(
                "deprecated", encoding="utf-8"
            )
            self.assertEqual(
                "1 compatibility warning. 2 usages of deprecated API",
                gate.evaluate_reports(root),
            )

    def test_each_ijpgp_default_failure_report_is_blocking(self) -> None:
        for filename in gate.FAILURE_REPORTS:
            with self.subTest(filename=filename), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                result = write_verdict(root, "Compatible")
                (result / filename).write_text("finding", encoding="utf-8")
                with self.assertRaises(gate.GateError):
                    gate.evaluate_reports(root)

    def test_fatal_verdict_is_blocking_without_detail_report(self) -> None:
        fatal_verdicts = (
            "1 missing mandatory dependency",
            "2 missing mandatory dependencies",
            "1 compatibility problem",
            "2 compatibility problems",
            "1 usage of internal API",
            "2 usages of internal API",
            "Compatible. 1 usage of internal API",
            "1 override-only API usage violation",
            "2 override-only API usage violations",
            "1 compatibility warning. 1 override-only API usage violation",
            "Plugin is invalid: broken descriptor",
            "Plugin is not found: missing plugin",
            "Failed to download plugin: repository unavailable",
        )
        for verdict in fatal_verdicts:
            with self.subTest(verdict=verdict), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                write_verdict(root, verdict)
                with self.assertRaises(gate.GateError):
                    gate.evaluate_reports(root)

    def test_unknown_verdict_is_rejected(self) -> None:
        unknown_verdicts = (
            "Unexpected future verifier state",
            "Compatible with warnings",
            "Compatible. Unexpected future verifier state",
            "1 compatibility warning. Unexpected future verifier state",
        )
        for verdict in unknown_verdicts:
            with self.subTest(verdict=verdict), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                write_verdict(root, verdict)
                with self.assertRaises(gate.GateError):
                    gate.evaluate_reports(root)

    def test_missing_or_ambiguous_verdict_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            with self.assertRaises(gate.GateError):
                gate.evaluate_reports(root)

            for name in ("one", "two"):
                result = root / name
                result.mkdir()
                (result / "verification-verdict.txt").write_text(
                    "Compatible", encoding="utf-8"
                )
            with self.assertRaises(gate.GateError):
                gate.evaluate_reports(root)

    def test_plugin_distribution_must_be_unique(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            with self.assertRaises(gate.GateError):
                gate.find_plugin_archive(root)
            first = root / "erdMaid-1.zip"
            first.write_bytes(b"zip")
            self.assertEqual(first, gate.find_plugin_archive(root))
            (root / "erdMaid-2.zip").write_bytes(b"zip")
            with self.assertRaises(gate.GateError):
                gate.find_plugin_archive(root)

    def test_archive_path_traversal_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            archive = root / "bad.tar.gz"
            with tarfile.open(archive, "w:gz") as bundle:
                entry = tarfile.TarInfo("../escape")
                data = b"escape"
                entry.size = len(data)
                bundle.addfile(entry, io.BytesIO(data))
            with self.assertRaises(gate.GateError):
                gate.extract_datagrip(archive, root / "out")

    def test_verifier_release_pin_is_exact(self) -> None:
        self.assertEqual("1.410", gate.PLUGIN_VERIFIER_VERSION)
        self.assertEqual(
            "59a5ef05cbdf0584cbfd6cb6ca802c74ecf340fdeadc5a73eac24af622c22010",
            gate.PLUGIN_VERIFIER_SHA256,
        )
        self.assertNotIn("latest", gate.PLUGIN_VERIFIER_URL)


if __name__ == "__main__":
    unittest.main(verbosity=2)
