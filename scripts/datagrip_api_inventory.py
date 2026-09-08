#!/usr/bin/env python3
"""Deterministic binary API inventory for the pinned DataGrip relation surface."""

from __future__ import annotations

import json
import re
import shutil
import subprocess
import zipfile
from pathlib import Path
from typing import Callable

# Exact anchors are grounded in the IU 2026.2.0.1 shipped-binary probe (#81/#82), including the
# nested provider/extra-relation types that the earlier top-level simple-name assumption missed.
# DataGrip evidence must prove these exact consumer/model surfaces independently rather than
# generalizing from the IU patch.
EXACT_ANCHOR_CLASSES = (
    "com.intellij.database.model.ModelRelationManager",
    "com.intellij.database.model.ModelRelationManager$ModelRelationProvider",
    "com.intellij.database.model.ModelRelationManager$ExtraRelation",
    "com.intellij.database.model.DasForeignKey",
    "com.intellij.database.model.DasConstraint",
    "com.intellij.database.model.DasTypedObject",
    "com.intellij.database.model.DasColumn",
    "com.intellij.database.model.DasTable",
)

DATABASE_CLASS_PREFIX = "com/intellij/database/"
RELATION_CANDIDATE = re.compile(r"(?:relation|foreign.?key)", re.IGNORECASE)
API_MARKERS = {
    "containsApiStatusInternalMarker": b"ApiStatus$Internal",
    "containsApiStatusExperimentalMarker": b"ApiStatus$Experimental",
    "containsApiStatusObsoleteMarker": b"ApiStatus$Obsolete",
    "containsDeprecatedMarker": b"java/lang/Deprecated",
}


class InventoryError(RuntimeError):
    """The maintained relation API surface could not be inventoried exactly."""


def class_entry(fqcn: str) -> str:
    return fqcn.replace(".", "/") + ".class"


def _database_tools_jars(ide_home: Path) -> list[Path]:
    root = ide_home.resolve()
    plugin_lib = (root / "plugins" / "DatabaseTools" / "lib").resolve()
    if not plugin_lib.is_dir() or not plugin_lib.is_relative_to(root):
        raise InventoryError(
            f"Expected DatabaseTools lib directory under pinned IDE: {plugin_lib}"
        )
    jars = sorted(path for path in plugin_lib.rglob("*.jar") if path.is_file())
    if not jars:
        raise InventoryError(f"No DatabaseTools jars found under {plugin_lib}")
    for jar in jars:
        resolved = jar.resolve()
        if not resolved.is_relative_to(root):
            raise InventoryError(f"DatabaseTools jar escapes IDE root: {jar}")
    return jars


def _entry_is_candidate(entry: str) -> bool:
    if not entry.startswith(DATABASE_CLASS_PREFIX) or not entry.endswith(".class"):
        return False
    simple = entry.rsplit("/", 1)[-1]
    return RELATION_CANDIDATE.search(simple) is not None


def _fqcn(entry: str) -> str:
    return entry[:-6].replace("/", ".")


def _default_javap_runner(jar: Path, fqcn: str) -> str:
    javap = shutil.which("javap")
    if javap is None:
        raise InventoryError("javap executable is not available")
    completed = subprocess.run(
        [javap, "-protected", "-s", "-classpath", str(jar), fqcn],
        check=False,
        capture_output=True,
        text=True,
    )
    if completed.returncode != 0:
        detail = (completed.stderr or completed.stdout).strip()
        raise InventoryError(
            f"javap failed for {fqcn} in {jar}: exit={completed.returncode}: {detail}"
        )
    signature = completed.stdout.strip()
    if not signature:
        raise InventoryError(f"javap produced empty output for {fqcn} in {jar}")
    return signature


def _candidate_summary(candidates: dict[str, set[str]]) -> str:
    names = sorted(candidates)
    if not names:
        return "<none>"
    return ",".join(names)


def collect_inventory(
    ide_home: Path,
    *,
    javap_runner: Callable[[Path, str], str] = _default_javap_runner,
) -> dict[str, object]:
    root = ide_home.resolve()
    jars = _database_tools_jars(root)
    exact_anchors = {fqcn: [] for fqcn in EXACT_ANCHOR_CLASSES}
    candidates: dict[str, set[str]] = {}
    marker_bytes: dict[tuple[str, str], bytes] = {}

    for jar in jars:
        relative_jar = jar.resolve().relative_to(root).as_posix()
        try:
            with zipfile.ZipFile(jar) as archive:
                names = archive.namelist()
                name_set = set(names)

                for fqcn in EXACT_ANCHOR_CLASSES:
                    entry = class_entry(fqcn)
                    if entry in name_set:
                        exact_anchors[fqcn].append((jar, relative_jar, entry))
                        marker_bytes[(fqcn, relative_jar)] = archive.read(entry)

                for entry in names:
                    if _entry_is_candidate(entry):
                        candidates.setdefault(_fqcn(entry), set()).add(relative_jar)
        except (OSError, KeyError, zipfile.BadZipFile) as exc:
            raise InventoryError(f"Could not inspect DatabaseTools jar {jar}: {exc}") from exc

    resolved: list[tuple[Path, str, str, str]] = []
    for fqcn in EXACT_ANCHOR_CLASSES:
        locations = exact_anchors[fqcn]
        if len(locations) != 1:
            raise InventoryError(
                f"Expected exactly one {fqcn} class in DatabaseTools jars, found {len(locations)}; "
                f"relation-candidates={_candidate_summary(candidates)}"
            )
        jar, relative_jar, entry = locations[0]
        resolved.append((jar, relative_jar, entry, fqcn))

    anchor_output: list[dict[str, object]] = []
    for jar, relative_jar, entry, fqcn in resolved:
        class_bytes = marker_bytes[(fqcn, relative_jar)]
        markers = {
            name: marker in class_bytes
            for name, marker in sorted(API_MARKERS.items())
        }
        anchor_output.append(
            {
                "class": fqcn,
                "entry": entry,
                "jar": relative_jar,
                "resolution": "exact-fqcn",
                "markers": markers,
                "javapProtectedSignature": javap_runner(jar, fqcn),
            }
        )

    candidate_output = [
        {
            "class": fqcn,
            "jars": sorted(jars_for_class),
        }
        for fqcn, jars_for_class in sorted(candidates.items())
    ]

    return {
        "scope": "pinned-DataGrip-DatabaseTools-binary-relation-surface",
        "anchors": anchor_output,
        "candidateClasses": candidate_output,
        "candidateClassCount": len(candidate_output),
    }


def write_inventory(path: Path, inventory: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(inventory, indent=2, sort_keys=True, ensure_ascii=True) + "\n",
        encoding="utf-8",
    )


def render_log(inventory: dict[str, object]) -> str:
    lines = [
        "Pinned DataGrip relation API inventory:",
        f"candidateClassCount={inventory['candidateClassCount']}",
    ]
    for anchor in inventory["anchors"]:
        assert isinstance(anchor, dict)
        lines.append(
            f"ANCHOR {anchor['class']} @ {anchor['jar']} resolution={anchor['resolution']}"
        )
        markers = anchor["markers"]
        assert isinstance(markers, dict)
        lines.append(
            "MARKERS "
            + " ".join(
                f"{name}={str(value).lower()}"
                for name, value in sorted(markers.items())
            )
        )
        lines.append(str(anchor["javapProtectedSignature"]))
    lines.append("CANDIDATE_CLASSES")
    for candidate in inventory["candidateClasses"]:
        assert isinstance(candidate, dict)
        lines.append(
            f"{candidate['class']} @ {','.join(candidate['jars'])}"
        )
    return "\n".join(lines)
