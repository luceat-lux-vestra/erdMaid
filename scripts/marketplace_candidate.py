#!/usr/bin/env python3
"""Fail-closed validation for manually uploaded JetBrains Marketplace candidates."""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import sys
import zipfile
from pathlib import Path
from xml.etree import ElementTree

EXPECTED_PLUGIN_ID = "com.algorist.erdmaid"
EXPECTED_VENDOR = "algorist"
EXPECTED_SOURCE_URL = "https://github.com/luceat-lux-vestra/erdMaid"
EXPECTED_LICENSE = "Apache-2.0"
STABLE_SEMVER = re.compile(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$")
HEX_SHA = re.compile(r"^[0-9a-f]{40}$")


class CandidateError(RuntimeError):
    """A release-candidate proof obligation was not met."""


def validate_version(version: str) -> str:
    if not STABLE_SEMVER.fullmatch(version):
        raise CandidateError(
            "release version must be stable SemVer MAJOR.MINOR.PATCH "
            "with no leading zeroes, prerelease, build metadata, or whitespace"
        )
    return version


def validate_commit(commit: str) -> str:
    if not HEX_SHA.fullmatch(commit):
        raise CandidateError("candidate commit must be an exact lowercase 40-hex Git SHA")
    return commit


def _safe_names(names: list[str], *, label: str) -> None:
    for name in names:
        path = Path(name)
        if name.startswith("/") or "\\" in name or ".." in path.parts:
            raise CandidateError(f"{label} contains unsafe path {name!r}")


def _read_plugin_jar(archive: zipfile.ZipFile) -> tuple[str, bytes]:
    candidates: list[tuple[str, bytes]] = []
    for name in archive.namelist():
        if not name.endswith(".jar"):
            continue
        data = archive.read(name)
        try:
            with zipfile.ZipFile(io.BytesIO(data)) as jar:
                _safe_names(jar.namelist(), label=f"nested JAR {name}")
                if "META-INF/plugin.xml" in jar.namelist():
                    candidates.append((name, data))
        except zipfile.BadZipFile as exc:
            raise CandidateError(f"plugin archive contains invalid JAR {name!r}: {exc}") from exc
    if len(candidates) != 1:
        raise CandidateError(
            f"expected exactly one plugin JAR containing META-INF/plugin.xml, found {len(candidates)}"
        )
    return candidates[0]


def inspect_archive(
    archive_path: Path,
    *,
    version: str,
    commit: str,
    license_path: Path,
) -> dict[str, object]:
    version = validate_version(version)
    commit = validate_commit(commit)
    if not archive_path.is_file():
        raise CandidateError(f"candidate archive does not exist: {archive_path}")
    expected_name = f"erdMaid-{version}.zip"
    if archive_path.name != expected_name:
        raise CandidateError(
            f"candidate filename mismatch: expected {expected_name!r}, got {archive_path.name!r}"
        )

    try:
        expected_license = license_path.read_bytes()
    except OSError as exc:
        raise CandidateError(f"could not read repository LICENSE: {exc}") from exc

    try:
        with zipfile.ZipFile(archive_path) as archive:
            _safe_names(archive.namelist(), label="plugin archive")
            jar_name, jar_bytes = _read_plugin_jar(archive)
    except (OSError, zipfile.BadZipFile) as exc:
        raise CandidateError(f"could not inspect plugin archive: {exc}") from exc

    with zipfile.ZipFile(io.BytesIO(jar_bytes)) as jar:
        try:
            plugin_xml = jar.read("META-INF/plugin.xml")
        except KeyError as exc:
            raise CandidateError("plugin JAR is missing META-INF/plugin.xml") from exc
        try:
            packaged_license = jar.read("META-INF/LICENSE")
        except KeyError as exc:
            raise CandidateError("plugin JAR is missing META-INF/LICENSE") from exc

    if packaged_license != expected_license:
        raise CandidateError("packaged META-INF/LICENSE is not byte-identical to repository LICENSE")

    try:
        root = ElementTree.fromstring(plugin_xml)
    except ElementTree.ParseError as exc:
        raise CandidateError(f"packaged plugin.xml is invalid XML: {exc}") from exc

    def text(name: str) -> str:
        element = root.find(name)
        return (element.text or "").strip() if element is not None else ""

    plugin_id = text("id")
    if plugin_id != EXPECTED_PLUGIN_ID:
        raise CandidateError(
            f"packaged plugin ID mismatch: expected {EXPECTED_PLUGIN_ID!r}, got {plugin_id!r}"
        )
    packaged_version = text("version")
    if packaged_version != version:
        raise CandidateError(
            f"packaged version mismatch: expected {version!r}, got {packaged_version!r}"
        )
    vendor = text("vendor")
    if vendor != EXPECTED_VENDOR:
        raise CandidateError(
            f"packaged vendor mismatch: expected {EXPECTED_VENDOR!r}, got {vendor!r}"
        )
    if root.find("product-descriptor") is not None:
        raise CandidateError("free/open-source candidate unexpectedly contains product-descriptor")

    idea_version = root.find("idea-version")
    if idea_version is None or idea_version.attrib.get("since-build") != "262":
        observed = None if idea_version is None else idea_version.attrib.get("since-build")
        raise CandidateError(f"packaged since-build mismatch: expected '262', got {observed!r}")

    digest = hashlib.sha256(archive_path.read_bytes()).hexdigest()
    return {
        "archive": archive_path.name,
        "archiveSha256": digest,
        "authorSigned": False,
        "commit": commit,
        "hiddenInitialUpload": True,
        "license": EXPECTED_LICENSE,
        "pluginJar": jar_name,
        "pluginXmlId": EXPECTED_PLUGIN_ID,
        "releaseChannel": "default",
        "sourceCodeUrl": EXPECTED_SOURCE_URL,
        "version": version,
    }


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)

    validate = sub.add_parser("validate-version")
    validate.add_argument("version")

    inspect = sub.add_parser("inspect")
    inspect.add_argument("--archive", required=True, type=Path)
    inspect.add_argument("--version", required=True)
    inspect.add_argument("--commit", required=True)
    inspect.add_argument("--license", default=Path("LICENSE"), type=Path)
    inspect.add_argument("--output", required=True, type=Path)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        if args.command == "validate-version":
            validate_version(args.version)
            print(f"Marketplace release version OK: {args.version}")
            return 0

        evidence = inspect_archive(
            args.archive,
            version=args.version,
            commit=args.commit,
            license_path=args.license,
        )
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(
            json.dumps(evidence, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        print(json.dumps(evidence, sort_keys=True))
        return 0
    except (CandidateError, OSError) as exc:
        print(f"MARKETPLACE CANDIDATE ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
