#!/usr/bin/env python3

from __future__ import annotations

import tempfile
import unittest
import zipfile
from pathlib import Path

from database_host_api_inventory import (
    EXACT_ANCHOR_CLASSES,
    InventoryError,
    class_entry,
    collect_inventory,
)


def _write_jar(path: Path, entries: dict[str, bytes]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as archive:
        for name, content in entries.items():
            archive.writestr(name, content)


class DatabaseHostApiInventoryTests(unittest.TestCase):
    def _ide(self, root: Path) -> Path:
        ide = root / "IDE"
        (ide / "plugins" / "DatabaseTools" / "lib").mkdir(parents=True)
        return ide

    def _anchor_entries(self) -> dict[str, bytes]:
        return {class_entry(fqcn): b"class-bytes" for fqcn in EXACT_ANCHOR_CLASSES}

    def test_exact_anchor_inventory_is_ordered_and_records_markers(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            ide = self._ide(Path(temp))
            entries = self._anchor_entries()
            entries[class_entry("com.intellij.database.psi.DbDataSource")] = (
                b"class-bytes ApiStatus$Internal"
            )
            _write_jar(ide / "plugins" / "DatabaseTools" / "lib" / "database.jar", entries)

            inventory = collect_inventory(
                ide,
                javap_runner=lambda _jar, fqcn: f"signature:{fqcn}",
            )

            classes = [item["class"] for item in inventory["anchorClasses"]]
            self.assertEqual(list(EXACT_ANCHOR_CLASSES), classes)
            self.assertEqual(len(EXACT_ANCHOR_CLASSES), inventory["anchorCount"])
            data_source = next(
                item for item in inventory["anchorClasses"]
                if item["class"] == "com.intellij.database.psi.DbDataSource"
            )
            self.assertTrue(data_source["markers"]["containsApiStatusInternalMarker"])

    def test_missing_anchor_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            ide = self._ide(Path(temp))
            entries = self._anchor_entries()
            entries.pop(class_entry(EXACT_ANCHOR_CLASSES[0]))
            _write_jar(ide / "plugins" / "DatabaseTools" / "lib" / "database.jar", entries)

            with self.assertRaisesRegex(InventoryError, "found 0"):
                collect_inventory(ide, javap_runner=lambda _jar, fqcn: f"signature:{fqcn}")

    def test_duplicate_anchor_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            ide = self._ide(Path(temp))
            _write_jar(
                ide / "plugins" / "DatabaseTools" / "lib" / "a.jar",
                self._anchor_entries(),
            )
            _write_jar(
                ide / "plugins" / "DatabaseTools" / "lib" / "b.jar",
                {class_entry(EXACT_ANCHOR_CLASSES[0]): b"duplicate"},
            )

            with self.assertRaisesRegex(InventoryError, "found 2"):
                collect_inventory(ide, javap_runner=lambda _jar, fqcn: f"signature:{fqcn}")

    def test_corrupt_jar_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            ide = self._ide(Path(temp))
            (ide / "plugins" / "DatabaseTools" / "lib" / "broken.jar").write_bytes(b"not-a-zip")

            with self.assertRaisesRegex(InventoryError, "Could not inspect"):
                collect_inventory(ide, javap_runner=lambda _jar, fqcn: f"signature:{fqcn}")

    def test_javap_failure_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            ide = self._ide(Path(temp))
            _write_jar(
                ide / "plugins" / "DatabaseTools" / "lib" / "database.jar",
                self._anchor_entries(),
            )

            def fail(_jar: Path, fqcn: str) -> str:
                raise InventoryError(f"javap failed for {fqcn}")

            with self.assertRaisesRegex(InventoryError, "javap failed"):
                collect_inventory(ide, javap_runner=fail)


if __name__ == "__main__":
    unittest.main(verbosity=2)
