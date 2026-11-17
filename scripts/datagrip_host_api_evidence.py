#!/usr/bin/env python3
"""Collect exact 2026.2.5 and 2026.3 DataGrip host-boundary API evidence."""

from __future__ import annotations

import argparse
import json
import sys
import tempfile
from pathlib import Path

import datagrip_verifier as gate
from database_host_api_inventory import InventoryError, collect_inventory, render_log, write_inventory

REPORTS_DIR = Path("build/reports/hostApiEvidence/datagrip")


def run(target_version: str = gate.DATAGRIP_VERSION) -> None:
    targets = {
        gate.DATAGRIP_VERSION: gate.DATAGRIP_BUILD,
        gate.DATAGRIP_263_VERSION: gate.DATAGRIP_263_BUILD,
    }
    try:
        target_build = targets[target_version]
    except KeyError as exc:
        raise gate.GateError(f"Unsupported exact DataGrip inventory target: {target_version}") from exc
    reports_dir = (
        REPORTS_DIR if target_version == gate.DATAGRIP_VERSION
        else REPORTS_DIR / target_version
    )
    reports_dir.mkdir(parents=True, exist_ok=True)
    release = gate.select_datagrip_release(
        gate.fetch_json(gate.DATAGRIP_RELEASE_API),
        version=target_version,
        build=target_build,
    )
    try:
        checksum = gate.parse_sha256(
            gate.fetch_bytes(release.checksum_url).decode("utf-8")
        )
    except (OSError, UnicodeError) as exc:
        raise gate.GateError(f"Could not read DataGrip checksum: {exc}") from exc

    with tempfile.TemporaryDirectory(prefix="erdmaid-datagrip-host-api-") as temp:
        work = Path(temp)
        archive = work / "datagrip.tar.gz"
        extracted = work / "ide"
        gate.download_with_sha256(
            release.download_url,
            archive,
            expected_sha256=checksum,
            expected_size=release.size,
        )
        ide_home = gate.extract_datagrip(archive, extracted)
        product_info = gate.validate_product_info(
            ide_home, version=target_version, build=target_build
        )
        try:
            inventory = collect_inventory(ide_home)
        except InventoryError as exc:
            raise gate.GateError(f"Host API inventory failed: {exc}") from exc

        write_inventory(reports_dir / "api-inventory.json", inventory)
        target = {
            "dataGripVersion": target_version,
            "dataGripBuild": target_build,
            "dataGripProductCode": gate.DATAGRIP_PRODUCT_CODE,
            "dataGripArchiveSha256": checksum,
            "dataGripArchiveSize": release.size,
            "verifiedProductName": product_info.get("name"),
            "inventoryScope": inventory["scope"],
            "anchorCount": inventory["anchorCount"],
        }
        (reports_dir / "target-evidence.json").write_text(
            json.dumps(target, indent=2, sort_keys=True, ensure_ascii=True) + "\n",
            encoding="utf-8",
        )

        print(
            "Exact DataGrip host API evidence target: "
            f"{target_version} ({gate.DATAGRIP_PRODUCT_CODE}-{target_build}) "
            f"sha256={checksum}",
            flush=True,
        )
        print(render_log(inventory), flush=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--release", choices=[gate.DATAGRIP_VERSION, gate.DATAGRIP_263_VERSION],
        default=gate.DATAGRIP_VERSION,
    )
    args = parser.parse_args()
    try:
        run(args.release)
    except gate.GateError as exc:
        print(f"::error::DataGrip host API evidence failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
