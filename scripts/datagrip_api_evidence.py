#!/usr/bin/env python3
"""Collect exact DataGrip 2026.2 relation API evidence without changing plugin behavior."""

from __future__ import annotations

import json
import sys
import tempfile
from pathlib import Path

import datagrip_verifier as gate
from datagrip_api_inventory import InventoryError, collect_inventory, render_log, write_inventory

REPORTS_DIR = Path("build/reports/datagripApiEvidence")


def run() -> None:
    REPORTS_DIR.mkdir(parents=True, exist_ok=True)
    release = gate.select_datagrip_release(gate.fetch_json(gate.DATAGRIP_RELEASE_API))
    try:
        checksum = gate.parse_sha256(
            gate.fetch_bytes(release.checksum_url).decode("utf-8")
        )
    except (OSError, UnicodeError) as exc:
        raise gate.GateError(f"Could not read DataGrip checksum: {exc}") from exc

    with tempfile.TemporaryDirectory(prefix="erdmaid-datagrip-api-evidence-") as temp:
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
        product_info = gate.validate_product_info(ide_home)
        try:
            inventory = collect_inventory(ide_home)
        except InventoryError as exc:
            raise gate.GateError(f"Relation API inventory failed: {exc}") from exc

        write_inventory(REPORTS_DIR / "api-inventory.json", inventory)
        target = {
            "dataGripVersion": gate.DATAGRIP_VERSION,
            "dataGripBuild": gate.DATAGRIP_BUILD,
            "dataGripProductCode": gate.DATAGRIP_PRODUCT_CODE,
            "dataGripArchiveSha256": checksum,
            "dataGripArchiveSize": release.size,
            "dataGripDownloadUrl": release.download_url,
            "verifiedProductName": product_info.get("name"),
            "inventoryScope": inventory["scope"],
            "candidateClassCount": inventory["candidateClassCount"],
        }
        (REPORTS_DIR / "target-evidence.json").write_text(
            json.dumps(target, indent=2, sort_keys=True, ensure_ascii=True) + "\n",
            encoding="utf-8",
        )

        print(
            "Exact DataGrip API evidence target: "
            f"{gate.DATAGRIP_VERSION} ({gate.DATAGRIP_PRODUCT_CODE}-{gate.DATAGRIP_BUILD}) "
            f"sha256={checksum}",
            flush=True,
        )
        print(render_log(inventory), flush=True)


def main() -> int:
    try:
        run()
    except gate.GateError as exc:
        print(f"::error::DataGrip API evidence failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
