#!/usr/bin/env python3
"""Deterministic exact-anchor inventory for the Track #37 DatabaseTools boundary."""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path
from typing import Callable

EXACT_ANCHOR_CLASSES = (
    "com.intellij.database.view.DatabaseContextFun",
    "com.intellij.database.psi.DbElement",
    "com.intellij.database.psi.DbTable",
    "com.intellij.database.psi.DbDataSource",
    "com.intellij.database.psi.DbPsiFacade",
    "com.intellij.database.model.DasDataSource",
    "com.intellij.database.model.DasObject",
    "com.intellij.database.model.ObjectKind",
    "com.intellij.database.model.DasTable",
    "com.intellij.database.model.DasColumn",
    "com.intellij.database.model.DasColumn$Attribute",
    "com.intellij.database.model.DasTypedObject",
    "com.intellij.database.model.DataType",
    "com.intellij.database.types.DasType",
    "com.intellij.database.model.DasConstraint",
    "com.intellij.database.model.DasTableKey",
    "com.intellij.database.model.DasIndex",
    "com.intellij.database.model.DasForeignKey",
    "com.intellij.database.model.DasExternal",
    "com.intellij.database.model.MultiRef",
    "com.intellij.database.model.ModelRelationManager",
    "com.intellij.database.model.ModelRelationManager$ExtraRelation",
    "com.intellij.database.util.DasUtil",
)

API_MARKERS = {
    "containsApiStatusInternalMarker": b"ApiStatus$Internal",
    "containsApiStatusExperimentalMarker": b"ApiStatus$Experimental",
    "containsApiStatusObsoleteMarker": b"ApiStatus$Obsolete",
    "containsDeprecatedMarker": b"java/lang/Deprecated",
}


class InventoryError(RuntimeError):
    """Exact maintained-host evidence could not be established."""


def class_entry(fqcn: str) -> str:
    return fqcn.replace(".", "/") + ".class"


def _database_tools_jars(ide_home: Path) -> list[Path]:
    root = ide_home.resolve()
    plugin_lib = (root / "plugins" / "DatabaseTools" / "lib").resolve()
    if not plugin_lib.is_dir() or not plugin_lib.is_relative_to(root):
        raise InventoryError(f"Expected DatabaseTools lib under pinned IDE: {plugin_lib}")
    jars = sorted(path for path in plugin_lib.rglob("*.jar") if path.is_file())
    if not jars:
        raise InventoryError(f"No DatabaseTools jars found under {plugin_lib}")
    for jar in jars:
        if not jar.resolve().is_relative_to(root):
            raise InventoryError(f"DatabaseTools jar escapes IDE root: {jar}")
    return jars


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


def collect_inventory(
    ide_home: Path,
    *,
    javap_runner: Callable[[Path, str], str] = _default_javap_runner,
) -> dict[str, object]:
    root = ide_home.resolve()
    jars = _database_tools_jars(root)
    anchors = {fqcn: [] for fqcn in EXACT_ANCHOR_CLASSES}
    entries = {class_entry(fqcn): fqcn for fqcn in EXACT_ANCHOR_CLASSES}

    for jar in jars:
        relative_jar = jar.resolve().relative_to(root).as_posix()
        try:
            with zipfile.ZipFile(jar) as archive:
                names = set(archive.namelist())
                for entry, fqcn in entries.items():
                    if entry in names:
                        anchors[fqcn].append((jar, relative_jar, archive.read(entry)))
        except (OSError, KeyError, zipfile.BadZipFile) as exc:
            raise InventoryError(f"Could not inspect DatabaseTools jar {jar}: {exc}") from exc

    classes: list[dict[str, object]] = []
    for fqcn in EXACT_ANCHOR_CLASSES:
        found = anchors[fqcn]
        if len(found) != 1:
            raise InventoryError(
                f"Expected exactly one {fqcn} class in DatabaseTools jars, found {len(found)}"
            )
        jar, relative_jar, raw = found[0]
        classes.append(
            {
                "class": fqcn,
                "entry": class_entry(fqcn),
                "jar": relative_jar,
                "markers": {
                    name: marker in raw for name, marker in sorted(API_MARKERS.items())
                },
                "javapProtectedSignature": javap_runner(jar, fqcn),
            }
        )

    return {
        "scope": "pinned-DatabaseTools-exact-host-boundary-anchors",
        "exactAnchors": list(EXACT_ANCHOR_CLASSES),
        "anchorCount": len(classes),
        "anchorClasses": classes,
    }


def write_inventory(path: Path, inventory: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(inventory, indent=2, sort_keys=True, ensure_ascii=True) + "\n",
        encoding="utf-8",
    )


def render_log(inventory: dict[str, object]) -> str:
    lines = [
        "Pinned DatabaseTools exact host-boundary anchors:",
        f"anchorCount={inventory['anchorCount']}",
    ]
    for item in inventory["anchorClasses"]:
        assert isinstance(item, dict)
        lines.append(f"CLASS {item['class']} @ {item['jar']}")
        markers = item["markers"]
        assert isinstance(markers, dict)
        lines.append(
            "MARKERS "
            + " ".join(f"{name}={str(value).lower()}" for name, value in sorted(markers.items()))
        )
        lines.append(str(item["javapProtectedSignature"]))
    return "\n".join(lines)


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print("usage: database_host_api_inventory.py <ide-home> <output-json>", file=sys.stderr)
        return 2
    try:
        inventory = collect_inventory(Path(argv[1]))
        write_inventory(Path(argv[2]), inventory)
        print(render_log(inventory), flush=True)
    except InventoryError as exc:
        print(f"::error::DatabaseTools host API inventory failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
