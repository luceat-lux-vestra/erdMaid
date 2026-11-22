#!/usr/bin/env python3
from pathlib import Path
import re
import tempfile

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github" / "workflows" / "dependency-submission.yml"
VALIDATOR = ROOT / "scripts" / "trusted_dependency_submission_validation.py"
RUNTIME_CONFIGURATION = "intellijPlatformSandboxRuntimeClasspath"
RUNTIME_PATTERN = f"^{RUNTIME_CONFIGURATION}$"
FILTER_LINE = (
    f"dependency-graph-include-configurations: '{RUNTIME_PATTERN}'"
)
RUNTIME_SCOPE_ENV_LINE = (
    f"DEPENDENCY_GRAPH_RUNTIME_INCLUDE_CONFIGURATIONS: '{RUNTIME_PATTERN}'"
)


def require(text: str, needle: str, label: str) -> None:
    if needle not in text:
        raise AssertionError(f"{label}: missing {needle!r}")


def check(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    header = text.split("\njobs:", 1)[0]
    require(header, "  push:\n", "push trigger")
    require(header, "    branches: [main]\n", "main scope")
    require(header, "  workflow_dispatch:\n", "manual recovery")
    for forbidden in (
        "  pull_request:\n",
        "  pull_request_target:\n",
        "  workflow_run:\n",
        "  repository_dispatch:\n",
    ):
        if forbidden in header:
            raise AssertionError(
                f"untrusted trigger present: {forbidden.strip()}"
            )
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
        raise AssertionError(
            f"dependency-input paths drifted: {paths!r} != {expected!r}"
        )
    require(text, "permissions: {}", "empty workflow permissions")
    require(
        text,
        "github.ref == 'refs/heads/main'",
        "main-only job guard",
    )
    runner = ARM64_RUNNER.search(text)
    if runner is None:
        raise AssertionError("ARM64 runner: expected an explicit versioned Ubuntu ARM64 runner")
    require(text, "contents: write", "submission permission")
    require(text, "cache-read-only: true", "read-only Gradle cache")
    require(
        text,
        FILTER_LINE,
        "packaged runtime configuration filter",
    )
    require(
        text,
        RUNTIME_SCOPE_ENV_LINE,
        "packaged runtime scope classification",
    )
    require(
        text,
        "dependency-graph: generate-and-submit",
        "submission mode",
    )
    require(
        text,
        "dependency-graph-continue-on-failure: false",
        "fail-closed submission",
    )
    require(
        text,
        "trusted_dependency_submission_validation.py verify-main",
        "shared graph verifier",
    )
    if "--build-gradle" in text:
        raise AssertionError(
            "workflow restored aggregate build-tool coordinate validation"
        )
    validator = VALIDATOR.read_text(encoding="utf-8")
    require(
        validator,
        'RUNTIME_CONFIGURATION = "intellijPlatformSandboxRuntimeClasspath"',
        "runtime configuration contract",
    )
    require(
        validator,
        'require(resolved, "packaged runtime dependency graph is empty")',
        "non-empty runtime graph gate",
    )
    require(
        validator,
        "pkg:maven/org.gradle/gradle-core@",
        "single development build-tool identity",
    )
    if "len(resolved) >= 750" in validator or "direct >= 45" in validator:
        raise AssertionError(
            "obsolete broad aggregate-graph coverage floor returned"
        )
    require(validator, '"refs/heads/main"', "exact-main provenance")
    require(
        text,
        "gradle/actions/dependency-submission@"
        "8da46b539cd8fe6ab27ca75128919a18d757604c",
        "action pin",
    )


def expect_fail(name: str, old: str, new: str) -> None:
    with tempfile.TemporaryDirectory() as tmp:
        candidate = Path(tmp) / "dependency-submission.yml"
        text = WORKFLOW.read_text(encoding="utf-8")
        if old not in text:
            raise AssertionError(f"{name}: fixture anchor missing")
        candidate.write_text(
            text.replace(old, new, 1),
            encoding="utf-8",
        )
        try:
            check(candidate)
        except AssertionError:
            print(f"ok: {name}")
            return
        raise AssertionError(f"{name}: invalid workflow was accepted")


def main() -> int:
    check(WORKFLOW)
    runner = ARM64_RUNNER.search(WORKFLOW.read_text(encoding="utf-8"))
    if runner is None:
        raise AssertionError("ARM64 runner fixture anchor missing")
    expect_fail(
        "ARM64 runner",
        runner.group(0).strip(),
        "runs-on: ubuntu-latest",
    )
    expect_fail(
        "cache writer",
        "cache-read-only: true",
        "cache-read-only: false",
    )
    expect_fail(
        "no submit",
        "generate-and-submit",
        "generate-and-upload",
    )
    expect_fail(
        "broad configuration filter",
        FILTER_LINE,
        "dependency-graph-include-configurations: '.*'",
    )
    expect_fail(
        "broad runtime scope classification",
        RUNTIME_SCOPE_ENV_LINE,
        "DEPENDENCY_GRAPH_RUNTIME_INCLUDE_CONFIGURATIONS: '.*'",
    )
    print("dependency submission policy passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
