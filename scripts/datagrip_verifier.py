#!/usr/bin/env python3
"""Fail-closed standalone DataGrip compatibility gate.

IJPGP 2.18.1 currently cannot resolve DataGrip because the JetBrains release
feed uses product code DG while the IntelliJ Platform type uses DB. This gate
resolves one exact DataGrip 2026.2 release from JetBrains' release feed,
verifies the vendor-published archive checksum and extracted product identity,
then runs one pinned Plugin Verifier CLI release against the plugin artifact.

The process exit code of Plugin Verifier alone is not an authoritative
compatibility verdict. We therefore inspect the generated reports and enforce
the same failure levels as IJPGP 2.18.1's default verifyPlugin task:
COMPATIBILITY_PROBLEMS, INTERNAL_API_USAGES, and OVERRIDE_ONLY_API_USAGES.
Invalid plugin reports are always fatal as they are in verifyPlugin.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any

DATAGRIP_FEED_CODE = "DG"
DATAGRIP_PRODUCT_CODE = "DB"
DATAGRIP_VERSION = "2026.2.4"
DATAGRIP_BUILD = "262.10315.24"
DATAGRIP_RELEASE_API = (
    "https://data.services.jetbrains.com/products/releases"
    "?code=DG&latest=false&type=release"
)

PLUGIN_VERIFIER_VERSION = "1.410"
PLUGIN_VERIFIER_URL = (
    "https://github.com/JetBrains/intellij-plugin-verifier/releases/download/"
    "1.410/verifier-cli-1.410-all.jar"
)
PLUGIN_VERIFIER_SHA256 = (
    "59a5ef05cbdf0584cbfd6cb6ca802c74ecf340fdeadc5a73eac24af622c22010"
)

REPORTS_DIR = Path("build/reports/pluginVerifierDataGrip")
DISTRIBUTIONS_DIR = Path("build/distributions")
FAILURE_REPORTS = (
    "compatibility-problems.txt",
    "internal-api-usages.txt",
    "override-only-usages.txt",
    "invalid-plugin.txt",
)
FATAL_VERDICT_PATTERNS = (
    re.compile(r"\bmissing mandatory dependenc(?:y|ies)\b", re.IGNORECASE),
    re.compile(r"\bcompatibility problems?\b", re.IGNORECASE),
    re.compile(r"\busages? of internal API\b", re.IGNORECASE),
    re.compile(r"\boverride-only API usage violations?\b", re.IGNORECASE),
    re.compile(r"^Plugin is invalid:", re.IGNORECASE),
    re.compile(r"^Plugin is not found:", re.IGNORECASE),
    re.compile(r"^Failed to download plugin:", re.IGNORECASE),
)
COMPATIBILITY_WARNING_VERDICT = re.compile(
    r"^[1-9]\d* compatibility warnings?$"
)
NON_BLOCKING_VERDICT_DETAIL = re.compile(
    r"^(?:"
    r"[1-9]\d* usages? of scheduled for removal API"
    r"(?: and [1-9]\d* usages? of deprecated API)?"
    r"|[1-9]\d* usages? of deprecated API"
    r"|[1-9]\d* usages? of experimental API"
    r"|[1-9]\d* non-extendable API usage violations?"
    r"|[1-9]\d* plugin configuration defects?"
    r")$"
)
HEX_SHA256 = re.compile(r"^[0-9a-fA-F]{64}$")
USER_AGENT = "erdMaid-DataGrip-Verifier/1"


class GateError(RuntimeError):
    """A fail-closed gate violation."""


@dataclass(frozen=True)
class DataGripRelease:
    version: str
    build: str
    download_url: str
    checksum_url: str
    size: int


def _require_https_url(url: str, *, hosts: set[str], path_prefix: str) -> None:
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme != "https" or parsed.hostname not in hosts:
        raise GateError(f"Untrusted download URL: {url}")
    if not parsed.path.startswith(path_prefix):
        raise GateError(f"Unexpected download path: {url}")
    if parsed.username or parsed.password or parsed.fragment:
        raise GateError(f"Unexpected URL components: {url}")


def select_datagrip_release(payload: dict[str, Any]) -> DataGripRelease:
    releases = payload.get(DATAGRIP_FEED_CODE)
    if not isinstance(releases, list):
        raise GateError(f"Release feed has no {DATAGRIP_FEED_CODE!r} list")

    matches = [
        item
        for item in releases
        if isinstance(item, dict)
        and item.get("type") == "release"
        and item.get("version") == DATAGRIP_VERSION
        and item.get("build") == DATAGRIP_BUILD
    ]
    if len(matches) != 1:
        raise GateError(
            "Expected exactly one DataGrip release "
            f"{DATAGRIP_VERSION} / {DATAGRIP_BUILD}, found {len(matches)}"
        )

    linux = matches[0].get("downloads", {}).get("linux")
    if not isinstance(linux, dict):
        raise GateError("Pinned DataGrip release has no Linux download")

    download_url = linux.get("link")
    checksum_url = linux.get("checksumLink")
    size = linux.get("size")
    if not isinstance(download_url, str) or not isinstance(checksum_url, str):
        raise GateError("DataGrip Linux download metadata is incomplete")
    if not isinstance(size, int) or size <= 0:
        raise GateError(f"Invalid DataGrip Linux archive size: {size!r}")

    trusted_hosts = {"download.jetbrains.com", "download-cdn.jetbrains.com"}
    _require_https_url(download_url, hosts=trusted_hosts, path_prefix="/datagrip/")
    _require_https_url(checksum_url, hosts=trusted_hosts, path_prefix="/datagrip/")
    if not checksum_url.endswith(".sha256"):
        raise GateError(f"Unexpected DataGrip checksum URL: {checksum_url}")

    return DataGripRelease(
        version=DATAGRIP_VERSION,
        build=DATAGRIP_BUILD,
        download_url=download_url,
        checksum_url=checksum_url,
        size=size,
    )


def parse_sha256(text: str) -> str:
    fields = text.strip().split()
    if not fields or not HEX_SHA256.fullmatch(fields[0]):
        raise GateError("Checksum response does not start with a SHA-256 digest")
    return fields[0].lower()


def fetch_bytes(url: str, *, timeout: int = 60) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read()


def fetch_json(url: str) -> dict[str, Any]:
    try:
        payload = json.loads(fetch_bytes(url).decode("utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise GateError(f"Could not read JetBrains release feed: {exc}") from exc
    if not isinstance(payload, dict):
        raise GateError("JetBrains release feed root is not an object")
    return payload


def download_with_sha256(
    url: str,
    destination: Path,
    *,
    expected_sha256: str,
    expected_size: int | None = None,
) -> str:
    if not HEX_SHA256.fullmatch(expected_sha256):
        raise GateError("Expected SHA-256 is malformed")

    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    digest = hashlib.sha256()
    total = 0
    try:
        with urllib.request.urlopen(request, timeout=60) as response, destination.open("wb") as output:
            while True:
                chunk = response.read(1024 * 1024)
                if not chunk:
                    break
                output.write(chunk)
                digest.update(chunk)
                total += len(chunk)
    except OSError as exc:
        destination.unlink(missing_ok=True)
        raise GateError(f"Download failed for {url}: {exc}") from exc

    actual = digest.hexdigest()
    if expected_size is not None and total != expected_size:
        destination.unlink(missing_ok=True)
        raise GateError(
            f"Downloaded size mismatch for {url}: expected {expected_size}, got {total}"
        )
    if actual != expected_sha256.lower():
        destination.unlink(missing_ok=True)
        raise GateError(
            f"SHA-256 mismatch for {url}: expected {expected_sha256.lower()}, got {actual}"
        )
    return actual


def extract_datagrip(archive: Path, destination: Path) -> Path:
    destination.mkdir(parents=True, exist_ok=True)
    root = destination.resolve()
    try:
        with tarfile.open(archive, "r:gz") as bundle:
            for member in bundle.getmembers():
                target = (destination / member.name).resolve()
                if not target.is_relative_to(root):
                    raise GateError(f"DataGrip archive escapes extraction root: {member.name}")
                if member.isdev():
                    raise GateError(f"DataGrip archive contains a device entry: {member.name}")
                if member.issym():
                    link_target = (target.parent / member.linkname).resolve()
                    if not link_target.is_relative_to(root):
                        raise GateError(
                            f"DataGrip archive symlink escapes extraction root: {member.name}"
                        )
                if member.islnk():
                    link_target = (destination / member.linkname).resolve()
                    if not link_target.is_relative_to(root):
                        raise GateError(
                            f"DataGrip archive hardlink escapes extraction root: {member.name}"
                        )
            bundle.extractall(destination)
    except (OSError, tarfile.TarError) as exc:
        raise GateError(f"Could not extract DataGrip archive: {exc}") from exc

    candidates = [
        info.parent
        for info in destination.rglob("product-info.json")
        if (info.parent / "bin" / "datagrip.sh").is_file()
    ]
    if len(candidates) != 1:
        raise GateError(f"Expected one extracted DataGrip home, found {len(candidates)}")
    return candidates[0]


def validate_product_info(ide_home: Path) -> dict[str, Any]:
    info_path = ide_home / "product-info.json"
    try:
        info = json.loads(info_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise GateError(f"Could not read {info_path}: {exc}") from exc
    if not isinstance(info, dict):
        raise GateError("DataGrip product-info.json root is not an object")

    expected = {
        "productCode": DATAGRIP_PRODUCT_CODE,
        "version": DATAGRIP_VERSION,
        "buildNumber": DATAGRIP_BUILD,
    }
    for key, value in expected.items():
        if info.get(key) != value:
            raise GateError(
                f"DataGrip product identity mismatch for {key}: "
                f"expected {value!r}, got {info.get(key)!r}"
            )
    return info


def find_plugin_archive(directory: Path = DISTRIBUTIONS_DIR) -> Path:
    archives = sorted(path for path in directory.glob("*.zip") if path.is_file())
    if len(archives) != 1:
        raise GateError(
            f"Expected exactly one plugin distribution ZIP in {directory}, found {len(archives)}"
        )
    return archives[0]


def is_recognized_non_blocking_verdict(verdict: str) -> bool:
    parts = verdict.split(". ")
    if not parts:
        return False
    if parts[0] == "Compatible":
        details = parts[1:]
    elif COMPATIBILITY_WARNING_VERDICT.fullmatch(parts[0]):
        details = parts[1:]
    else:
        return False
    return all(NON_BLOCKING_VERDICT_DETAIL.fullmatch(detail) for detail in details)


def evaluate_reports(reports_dir: Path) -> str:
    verdicts = sorted(reports_dir.rglob("verification-verdict.txt"))
    if len(verdicts) != 1:
        raise GateError(
            f"Expected exactly one DataGrip verification verdict, found {len(verdicts)}"
        )

    fatal: list[str] = []
    for filename in FAILURE_REPORTS:
        for report in reports_dir.rglob(filename):
            try:
                if report.stat().st_size > 0:
                    fatal.append(str(report))
            except OSError as exc:
                raise GateError(f"Could not inspect verifier report {report}: {exc}") from exc
    if fatal:
        raise GateError(
            "DataGrip verification hit a blocking IJPGP-default failure level: "
            + ", ".join(sorted(fatal))
        )

    try:
        verdict = verdicts[0].read_text(encoding="utf-8").strip()
    except (OSError, UnicodeError) as exc:
        raise GateError(f"Could not read verifier verdict: {exc}") from exc
    if not verdict:
        raise GateError("DataGrip verification verdict is empty")

    # Plugin Verifier 1.410 writes a verdict for Verified, InvalidPlugin,
    # NotFound, and FailedToDownload results. Detailed report files are still
    # the primary failure-level oracle above, but the verdict is an independent
    # fail-closed backstop in case a blocking detail file is absent or could not
    # be produced. Compatibility warnings and other non-blocking IJPGP-default
    # findings remain allowed. Any verdict outside the complete pinned 1.410
    # non-blocking grammar is UNKNOWN and therefore fails closed.
    for pattern in FATAL_VERDICT_PATTERNS:
        if pattern.search(verdict):
            raise GateError(f"DataGrip verification verdict is blocking: {verdict}")
    if not is_recognized_non_blocking_verdict(verdict):
        raise GateError(f"Unrecognized DataGrip verification verdict: {verdict}")
    return verdict


def write_evidence(reports_dir: Path, data: dict[str, Any]) -> None:
    reports_dir.mkdir(parents=True, exist_ok=True)
    target = reports_dir / "gate-evidence.json"
    target.write_text(
        json.dumps(data, indent=2, sort_keys=True, ensure_ascii=True) + "\n",
        encoding="utf-8",
    )


def run() -> None:
    REPORTS_DIR.mkdir(parents=True, exist_ok=True)
    static_evidence: dict[str, Any] = {
        "dataGripFeedCode": DATAGRIP_FEED_CODE,
        "dataGripProductCode": DATAGRIP_PRODUCT_CODE,
        "dataGripVersion": DATAGRIP_VERSION,
        "dataGripBuild": DATAGRIP_BUILD,
        "pluginVerifierVersion": PLUGIN_VERIFIER_VERSION,
        "pluginVerifierSha256": PLUGIN_VERIFIER_SHA256,
        "failureReports": list(FAILURE_REPORTS),
        "state": "target-pinned",
    }
    write_evidence(REPORTS_DIR, static_evidence)

    release = select_datagrip_release(fetch_json(DATAGRIP_RELEASE_API))
    try:
        checksum = parse_sha256(fetch_bytes(release.checksum_url).decode("utf-8"))
    except (OSError, UnicodeError) as exc:
        raise GateError(f"Could not read DataGrip checksum: {exc}") from exc

    static_evidence.update(
        {
            "dataGripDownloadUrl": release.download_url,
            "dataGripChecksumUrl": release.checksum_url,
            "dataGripArchiveSha256": checksum,
            "dataGripArchiveSize": release.size,
            "state": "release-resolved",
        }
    )
    write_evidence(REPORTS_DIR, static_evidence)

    with tempfile.TemporaryDirectory(prefix="erdmaid-datagrip-verifier-") as temp:
        work = Path(temp)
        archive = work / "datagrip.tar.gz"
        verifier_jar = work / f"verifier-cli-{PLUGIN_VERIFIER_VERSION}-all.jar"
        ide_extract = work / "ide"

        download_with_sha256(
            release.download_url,
            archive,
            expected_sha256=checksum,
            expected_size=release.size,
        )
        ide_home = extract_datagrip(archive, ide_extract)
        product_info = validate_product_info(ide_home)

        download_with_sha256(
            PLUGIN_VERIFIER_URL,
            verifier_jar,
            expected_sha256=PLUGIN_VERIFIER_SHA256,
        )

        plugin_archive = find_plugin_archive()
        java = shutil.which("java")
        if java is None:
            raise GateError("java executable is not available")

        command = [
            java,
            "-jar",
            str(verifier_jar),
            "check-plugin",
            "-verification-reports-dir",
            str(REPORTS_DIR),
            "-verification-reports-formats",
            "plain",
        ]
        java_home = os.environ.get("JAVA_HOME")
        if java_home:
            command.extend(["-runtime-dir", java_home])
        command.extend([str(plugin_archive), str(ide_home)])

        print(
            "Running pinned DataGrip compatibility verification: "
            f"DataGrip {DATAGRIP_VERSION} ({DATAGRIP_PRODUCT_CODE}-{DATAGRIP_BUILD}), "
            f"Plugin Verifier {PLUGIN_VERIFIER_VERSION}",
            flush=True,
        )
        completed = subprocess.run(command, check=False)
        if completed.returncode != 0:
            raise GateError(
                f"Plugin Verifier process exited with {completed.returncode}"
            )

        verdict = evaluate_reports(REPORTS_DIR)
        static_evidence.update(
            {
                "pluginArchive": str(plugin_archive),
                "verifiedProductName": product_info.get("name"),
                "verdict": verdict,
                "state": "verified",
            }
        )
        write_evidence(REPORTS_DIR, static_evidence)
        print(f"DataGrip verifier verdict: {verdict}", flush=True)


def main() -> int:
    try:
        run()
    except GateError as exc:
        print(f"::error::DataGrip verifier gate failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
