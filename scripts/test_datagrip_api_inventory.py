#!/usr/bin/env python3

from __future__ import annotations

import tempfile
import unittest
import zipfile
from pathlib import Path

from datagrip_api_inventory import (
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


class DataGripApiInventoryTests(unittest.TestCase):
    def _ide(self, root: Path) -> Path:
        ide = root / "DataGrip"
        (ide / "plugins" / "DatabaseTools" / "lib").mkdir(parents=True)
        return ide

    def _anchor_entries(self) -> dict[str, bytes]:
        return {
            class_entry(fqcn): (
                b"class-bytes ApiStatus$Internal"
                if fqcn.endswith("$ModelRelationProvider")
                else b"class-bytes"
            )
            for fqcn in EXACT_ANCHOR_CLASSES
        }

    def test_unique_exact_anchors_are_deterministic(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            ide = self._ide(Path(temp))
            entries = self._anchor_entries()
            entries.update(
                {
                    "com/intellij/database/model/VirtualRelation.class": b"x",
                    "com/intellij/database/model/ZForeignKeyImpl.class": b"x",
                    "com/intellij/database/model/NotRelevant.class": b"x",
                }
            )
            _write_jar(ide / "plugins" / "DatabaseTools" / "lib" / "database.jar", entries)

            inventory = collect_inventory(
                ide,
                javap_runner=lambda jar, fqcn: f"signature:{fqcn}",
            )

            self.assertEqual(len(EXACT_ANCHOR_CLASSES), len(inventory["anchors"]))
            provider = next(
                item
                for item in inventory["anchors"]
                if item["class"].endswith("$ModelRelationProvider")
            )
            self.assertEqual("exact-fqcn", provider["resolution"])
            self.assertEqual(
                "com.intellij.database.model.ModelRelationManager$ModelRelationProvider",
                provider["class"],
            )
            self.assertTrue(provider["markers"]["containsApiStatusInternalMarker"])
            candidate_classes = [item["class"] for item in inventory["candidateClasses"]]
            self.assertIn("com.intellij.database.model.VirtualRelation", candidate_classes)
            self.assertIn("com.intellij.database.model.ZForeignKeyImpl", candidate_classes)
            self.assertIn(provider["class"], candidate_classes)

    def test_missing_exact_anchor_fails_closed_with_candidates(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            ide = self._ide(Path(temp))
            entries = self._anchor_entries()
            entries.pop(class_entry(EXACT_ANCHOR_CLASSES[0]))
            _write_jar(ide / "plugins" / "DatabaseTools" / "lib" / "database.jar", entries)
            with self.assertRaisesRegex(InventoryError, "found 0; relation-candidates="):
                collect_inventory(ide, javap_runner=lambda jar, fqcn: f"signature:{fqcn}")

    def test_duplicate_exact_anchor_fails_closed(self) -> None:
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
                collect_inventory(ide, javap_runner=lambda jar, fqcn: f"signature:{fqcn}")

    def test_nested_provider_exact_name_is_not_replaced_by_top_level_guess(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            ide = self._ide(Path(temp))
            entries = self._anchor_entries()
            nested = "com.intellij.database.model.ModelRelationManager$ModelRelationProvider"
            entries.pop(class_entry(nested))
            entries[class_entry("com.intellij.database.model.ModelRelationProvider")] = b"wrong"
            _write_jar(ide / "plugins" / "DatabaseTools" / "lib" / "database.jar", entries)
            with self.assertRaisesRegex(InventoryError, "ModelRelationManager\\$ModelRelationProvider.*found 0"):
                collect_inventory(ide, javap_runner=lambda jar, fqcn: f"signature:{fqcn}")

    def test_corrupt_jar_and_javap_failure_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            ide = self._ide(Path(temp))
            (ide / "plugins" / "DatabaseTools" / "lib" / "broken.jar").write_bytes(b"not-a-zip")
            with self.assertRaisesRegex(InventoryError, "Could not inspect"):
                collect_inventory(ide, javap_runner=lambda jar, fqcn: f"signature:{fqcn}")

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
