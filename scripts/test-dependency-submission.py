#!/usr/bin/env python3
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github" / "workflows" / "dependency-submission.yml"

def require(text: str, needle: str, label: str) -> None:
    if needle not in text:
        raise AssertionError(f"{label}: missing {needle!r}")

def check(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    header = text.split("\njobs:", 1)[0]
    require(header, "  push:\n", "push trigger")
    require(header, "    branches: [main]\n", "main scope")
    require(header, "  workflow_dispatch:\n", "manual recovery")
    for forbidden in ("  pull_request:\n", "  pull_request_target:\n", "  workflow_run:\n", "  repository_dispatch:\n"):
        if forbidden in header:
            raise AssertionError(f"untrusted trigger present: {forbidden.strip()}")
    paths = [
        line.strip()[2:].strip("'")
        for line in header.splitlines()
        if line.startswith("      - '")
    ]
    expected = [
        "build.gradle.kts",
        "settings.gradle.kts",
        "gradle.properties",
        "gradle/**",
        ".github/workflows/dependency-submission.yml",
    ]
    if paths != expected:
        raise AssertionError(f"dependency-input paths drifted: {paths!r} != {expected!r}")
    require(text, "permissions: {}", "empty workflow permissions")
    require(text, "github.ref == 'refs/heads/main'", "main-only job guard")
    require(text, "runs-on: ubuntu-24.04-arm", "ARM64 runner")
    require(text, "contents: write", "submission permission")
    require(text, "cache-read-only: true", "read-only Gradle cache")
    require(text, "dependency-graph: generate-and-submit", "submission mode")
    require(text, "dependency-graph-continue-on-failure: false", "fail-closed submission")
    require(text, "len(resolved) < 750", "node coverage floor")
    require(text, "direct < 45", "direct coverage floor")
    require(text, 'graph.get("ref") != "refs/heads/main"', "exact-main provenance")
    require(text, "gradle/actions/dependency-submission@3f5f9adaf7d9fecd50b5935e54106014257a94e6", "action pin")

def expect_fail(name: str, old: str, new: str) -> None:
    with tempfile.TemporaryDirectory() as tmp:
        candidate = Path(tmp) / "dependency-submission.yml"
        text = WORKFLOW.read_text(encoding="utf-8")
        if old not in text:
            raise AssertionError(f"{name}: fixture anchor missing")
        candidate.write_text(text.replace(old, new, 1), encoding="utf-8")
        try:
            check(candidate)
        except AssertionError:
            print(f"ok: {name}")
            return
        raise AssertionError(f"{name}: invalid workflow was accepted")

def main() -> int:
    check(WORKFLOW)
    expect_fail("ARM64 runner", "runs-on: ubuntu-24.04-arm", "runs-on: ubuntu-latest")
    expect_fail("cache writer", "cache-read-only: true", "cache-read-only: false")
    expect_fail("no submit", "generate-and-submit", "generate-and-upload")
    print("dependency submission policy passed")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
