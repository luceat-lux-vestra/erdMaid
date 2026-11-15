#!/usr/bin/env python3
"""Fail-closed PR compatibility adapter for the trusted Gradle dependency publisher."""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

SHA = re.compile(r"[0-9a-f]{40}\Z")
JOB = re.compile(r"^  ([A-Za-z0-9_-]+):\s*$", re.MULTILINE)
PIN_ACTIONS = (
    "actions/checkout",
    "actions/setup-java",
    "gradle/actions/dependency-submission",
)
COORDINATE_KEY = "org.jetbrains.intellij.platform:intellij-platform-gradle-plugin"
SUPPORTED_COORDINATE_KEYS = frozenset({COORDINATE_KEY})
PLUGIN_DECLARATION = re.compile(
    r'id\("org\.jetbrains\.intellij\.platform"\)\s+version\s+"(?P<version>[^"\s]+)"'
)

RELEVANT_PATHS = frozenset({
    ".github/workflows/dependency-submission.yml",
    ".github/workflows/build.yml",
    "scripts/trusted_dependency_submission_validation.py",
    "scripts/test-dependency-submission.py",
})

def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def safe_path(value: object) -> str:
    require(isinstance(value, str) and value, "missing changed-file path")
    require(not value.startswith("/") and ".." not in value.split("/"),
            "unsafe changed-file path")
    return value


def required_coordinates(build_gradle: str) -> frozenset[str]:
    matches = PLUGIN_DECLARATION.findall(build_gradle)
    require(len(matches) == 1,
            "expected exactly one IntelliJ Platform Gradle Plugin declaration")
    version = matches[0]
    require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9.+_-]*", version) is not None,
            "invalid IntelliJ Platform Gradle Plugin version")
    return frozenset({f"{COORDINATE_KEY}:{version}"})


def require_reviewable_publisher_change(entry: dict) -> None:
    require(entry.get("status") == "modified",
            "unsupported dependency-submission workflow change type")
    patch = entry.get("patch")
    require(isinstance(patch, str) and patch,
            "dependency-submission change has no reviewable patch")


def classify(pull: dict, pages: list, expected_head: str) -> bool:
    require(SHA.fullmatch(expected_head or "") is not None,
            "invalid expected PR HEAD")
    require(isinstance(pull, dict) and isinstance(pull.get("head"), dict),
            "missing PR metadata")
    require(pull["head"].get("sha") == expected_head,
            "PR HEAD changed during classification")
    expected_count = pull.get("changed_files")
    require(type(expected_count) is int and 0 < expected_count <= 3000,
            "invalid or unsupported PR changed-file count")
    require(isinstance(pages, list) and pages
            and all(isinstance(page, list) for page in pages),
            "invalid paginated changed-file listing")
    entries = [entry for page in pages for entry in page]
    require(len(entries) == expected_count,
            "incomplete PR changed-file listing")
    seen: set[str] = set()
    paths: set[str] = set()
    for entry in entries:
        require(isinstance(entry, dict), "malformed changed-file entry")
        name = safe_path(entry.get("filename"))
        require(name not in seen, "duplicate changed-file entry")
        seen.add(name)
        status = entry.get("status")
        require(status in {
            "added", "modified", "removed", "renamed",
            "copied", "changed", "unchanged",
        }, "unknown changed-file status")
        paths.add(name)
        if status == "renamed":
            paths.add(safe_path(entry.get("previous_filename")))
        if name == ".github/workflows/dependency-submission.yml":
            require_reviewable_publisher_change(entry)
    return bool(paths & RELEVANT_PATHS)


def extract_job(workflow: str, job_id: str) -> str:
    marker = re.search(rf"^  {re.escape(job_id)}:\s*$", workflow, re.MULTILINE)
    require(marker is not None, f"missing PR validation job {job_id}")
    tail = workflow[marker.end():]
    end = JOB.search(tail)
    return tail[:end.start()] if end else tail


def action_pin(text: str, action: str) -> str:
    pins = re.findall(
        rf"uses:\s*{re.escape(action)}@([0-9a-f]{{40}})(?:\s+#.*)?$",
        text,
        re.MULTILINE,
    )
    require(len(pins) == 1, f"expected exactly one {action} pin")
    return pins[0]


def check_contract(production: str, pr_workflow: str, build_gradle: str) -> None:
    require("  pull_request:" not in production
            and "  pull_request_target:" not in production,
            "production dependency publisher must not run on PR events")
    require("      contents: write" in production,
            "production publisher lost contents:write authority")
    require("dependency-graph: generate-and-submit" in production,
            "production publisher is not in generate-and-submit mode")
    require("cache-read-only: true" in production,
            "production publisher may write Gradle cache")
    require("dependency-graph-continue-on-failure: false" in production,
            "production publisher no longer fails closed")
    required_coordinates(build_gradle)
    require("trusted_dependency_submission_validation.py verify-main" in production,
            "production publisher must delegate graph verification to the trusted validator")
    require("--build-gradle build.gradle.kts" in production,
            "production publisher must derive required coordinates from build.gradle.kts")
    require(COORDINATE_KEY + ":" not in production,
            "production publisher must not hard-code the current plugin coordinate")
    job = extract_job(pr_workflow, "dependencySubmissionValidation")
    require("    permissions:\n      contents: read\n      pull-requests: read\n" in job,
            "PR dependency adapter permissions must be explicitly read-only")
    require(re.search(r"^\s+\S+:\s*write(?:\s+#.*)?$", job, re.MULTILINE) is None,
            "PR dependency adapter gained write authority")
    require("dependency-graph: generate-and-upload" in job,
            "PR dependency adapter must use generate-and-upload")
    require("dependency-graph: generate-and-submit" not in job,
            "PR dependency adapter must never submit")
    require("cache-read-only: true" in job,
            "PR dependency adapter may write Gradle cache")
    require("dependency-graph-continue-on-failure: false" in job,
            "PR dependency adapter no longer fails closed")
    require("dependency-graph-report-dir: dependency-graph-reports-pr" in job,
            "PR dependency adapter report directory drifted")
    require("--build-gradle build.gradle.kts" in job,
            "PR dependency adapter must derive required coordinates from build.gradle.kts")
    require(
        "repository: ${{ github.event.pull_request.head.repo.full_name }}" in job
        and "ref: ${{ github.event.pull_request.head.sha }}" in job,
        "PR dependency adapter must checkout the exact PR-head repository/SHA",
    )
    require("persist-credentials: false" in job,
            "PR dependency adapter checkout credentials must not persist")
    for action in PIN_ACTIONS:
        require(action_pin(production, action) == action_pin(job, action),
                f"PR adapter {action} pin does not match production publisher")


def production_eligible(graph: dict, source_sha: str) -> bool:
    return graph.get("sha") == source_sha and graph.get("ref") == "refs/heads/main"


def _verify_graph(
    directory: Path,
    expected_head: str,
    expected_ref: str,
    required: frozenset[str],
    production_evidence: bool,
) -> dict:
    files = sorted(directory.rglob("*.json"))
    require(len(files) == 1,
            f"expected exactly one dependency graph report, got {len(files)}")
    data = json.loads(files[0].read_text(encoding="utf-8"))
    require(isinstance(data, dict),
            "dependency graph report is not an object")
    require(data.get("sha") == expected_head,
            "dependency graph source SHA is not exact expected HEAD")
    require(data.get("ref") == expected_ref,
            "dependency graph ref is not the expected ref")
    manifests = data.get("manifests")
    require(isinstance(manifests, dict) and manifests,
            "dependency graph has no manifests")
    resolved: dict[str, dict] = {}
    for manifest in manifests.values():
        require(isinstance(manifest, dict),
                "malformed dependency graph manifest")
        nodes = manifest.get("resolved")
        require(isinstance(nodes, dict),
                "dependency graph manifest lacks resolved dependencies")
        resolved.update(nodes)
    require(len(resolved) >= 750,
            f"dependency graph lost required coverage: {len(resolved)} < 750")
    missing = required - resolved.keys()
    require(not missing,
            f"dependency graph lost required coordinates: {sorted(missing)}")
    direct = sum(
        isinstance(node, dict) and node.get("relationship") == "direct"
        for node in resolved.values()
    )
    require(direct >= 45,
            f"dependency graph lost direct coverage: {direct} < 45")
    return {
        "source_sha": expected_head,
        "event_ref": expected_ref,
        "manifests": len(manifests),
        "resolved": len(resolved),
        "direct": direct,
        "production_evidence": production_evidence,
    }


def verify_graph(
    directory: Path,
    expected_head: str,
    event_ref: str,
    build_gradle: str,
) -> dict:
    require(SHA.fullmatch(expected_head or "") is not None,
            "invalid expected PR HEAD")
    require(isinstance(event_ref, str) and event_ref.startswith("refs/pull/"),
            "dependency fixture did not run in pull_request ref context")
    require(event_ref != "refs/heads/main",
            "PR dependency fixture cannot use protected-main provenance")
    return _verify_graph(
        directory,
        expected_head,
        event_ref,
        required_coordinates(build_gradle),
        False,
    )


def verify_main_graph(
    directory: Path,
    expected_head: str,
    build_gradle: str,
) -> dict:
    require(SHA.fullmatch(expected_head or "") is not None,
            "invalid expected main HEAD")
    return _verify_graph(
        directory,
        expected_head,
        "refs/heads/main",
        required_coordinates(build_gradle),
        True,
    )


def self_test() -> None:
    import tempfile
    sha = "a" * 40
    fixture_version = "9.9.9-test"
    build_gradle = (
        'plugins {\n'
        f'    id("org.jetbrains.intellij.platform") version "{fixture_version}"\n'
        '}\n'
    )
    required = required_coordinates(build_gradle)
    assert required == frozenset({f"{COORDINATE_KEY}:{fixture_version}"})

    pull = {"head": {"sha": sha}, "changed_files": 1}
    assert classify(pull, [[{
        "filename": "scripts/trusted_dependency_submission_validation.py",
        "status": "modified",
    }]], sha)
    assert not classify(pull, [[{"filename": "README.md", "status": "modified"}]], sha)
    assert classify(pull, [[{
        "filename": ".github/workflows/dependency-submission.yml",
        "status": "modified",
        "patch": "@@ -1 +1 @@\n-old\n+new",
    }]], sha)

    bad_cases = [
        ({"head": {"sha": sha}, "changed_files": 2},
         [[{"filename": "README.md", "status": "modified"}]], sha),
        (pull, [[{"filename": "../bad", "status": "modified"}]], sha),
        (pull, [[{"filename": "new", "status": "renamed"}]], sha),
        (pull, [[{
            "filename": ".github/workflows/dependency-submission.yml",
            "status": "removed",
            "patch": "@@ -1 +0 @@\n-old",
        }]], sha),
        (pull, [[{"filename": "README.md", "status": "modified"}]], "d" * 40),
    ]
    for bad_pull, bad_pages, bad_sha in bad_cases:
        try:
            classify(bad_pull, bad_pages, bad_sha)
        except ValueError:
            pass
        else:
            raise AssertionError("unsafe changed-file classification was accepted")

    prod = (
        "on:\n  push:\npermissions: {}\njobs:\n  submitGraph:\n"
        "    permissions:\n      contents: write\n    steps:\n"
        "      - uses: actions/checkout@" + "1" * 40 + "\n"
        "      - uses: actions/setup-java@" + "2" * 40 + "\n"
        "      - uses: gradle/actions/dependency-submission@" + "3" * 40 + "\n"
        "        with:\n          cache-read-only: true\n"
        "          dependency-graph: generate-and-submit\n"
        "          dependency-graph-continue-on-failure: false\n"
        "      - run: python3 scripts/trusted_dependency_submission_validation.py verify-main "
        "--dir dependency-graph-reports --head \"$GITHUB_SHA\" "
        "--build-gradle build.gradle.kts\n"
    )
    pr = (
        "jobs:\n  dependencySubmissionValidation:\n"
        "    permissions:\n      contents: read\n      pull-requests: read\n"
        "    steps:\n"
        "      - uses: actions/checkout@" + "1" * 40 + "\n"
        "        with:\n"
        "          repository: ${{ github.event.pull_request.head.repo.full_name }}\n"
        "          ref: ${{ github.event.pull_request.head.sha }}\n"
        "          persist-credentials: false\n"
        "      - uses: actions/setup-java@" + "2" * 40 + "\n"
        "      - uses: gradle/actions/dependency-submission@" + "3" * 40 + "\n"
        "        with:\n          cache-read-only: true\n"
        "          dependency-graph: generate-and-upload\n"
        "          dependency-graph-report-dir: dependency-graph-reports-pr\n"
        "          dependency-graph-continue-on-failure: false\n"
        "      - run: python3 scripts/trusted_dependency_submission_validation.py verify "
        "--dir dependency-graph-reports-pr --head \"$EXPECTED_HEAD\" "
        "--event-ref \"$EVENT_REF\" --build-gradle build.gradle.kts\n"
    )
    check_contract(prod, pr, build_gradle)

    for mutated in (
        pr.replace("dependency-graph: generate-and-upload",
                   "dependency-graph: generate-and-submit"),
        pr.replace("contents: read", "contents: write", 1),
        pr.replace("cache-read-only: true", "cache-read-only: false"),
        pr.replace("--build-gradle build.gradle.kts", "--build-gradle other.gradle.kts"),
        pr.replace("gradle/actions/dependency-submission@" + "3" * 40,
                   "gradle/actions/dependency-submission@" + "4" * 40),
    ):
        try:
            check_contract(prod, mutated, build_gradle)
        except ValueError:
            pass
        else:
            raise AssertionError("unsafe PR dependency adapter contract was accepted")

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        resolved = {
            f"example:indirect:{i}": {"relationship": "indirect"}
            for i in range(705)
        }
        for i in range(44):
            resolved[f"example:direct:{i}"] = {"relationship": "direct"}
        resolved[next(iter(required))] = {"relationship": "direct"}
        graph = {
            "sha": sha,
            "ref": "refs/pull/7/merge",
            "manifests": {"root": {"resolved": resolved}},
        }
        (root / "graph.json").write_text(json.dumps(graph), encoding="utf-8")
        result = verify_graph(root, sha, "refs/pull/7/merge", build_gradle)
        assert result["resolved"] == 750
        assert result["direct"] == 45
        assert result["production_evidence"] is False

        graph["ref"] = "refs/heads/main"
        (root / "graph.json").write_text(json.dumps(graph), encoding="utf-8")
        result = verify_main_graph(root, sha, build_gradle)
        assert result["production_evidence"] is True

        graph["sha"] = "b" * 40
        (root / "graph.json").write_text(json.dumps(graph), encoding="utf-8")
        try:
            verify_main_graph(root, sha, build_gradle)
        except ValueError:
            pass
        else:
            raise AssertionError("wrong source SHA was accepted")
    print("trusted dependency-submission PR adapter self-tests passed")


def main() -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    classify_p = sub.add_parser("classify")
    classify_p.add_argument("--pull", type=Path, required=True)
    classify_p.add_argument("--pages", type=Path, required=True)
    classify_p.add_argument("--head", required=True)
    contract_p = sub.add_parser("check-contract")
    contract_p.add_argument("--production", type=Path, required=True)
    contract_p.add_argument("--pr-workflow", type=Path, required=True)
    contract_p.add_argument("--build-gradle", type=Path, required=True)
    verify_p = sub.add_parser("verify")
    verify_p.add_argument("--dir", type=Path, required=True)
    verify_p.add_argument("--head", required=True)
    verify_p.add_argument("--event-ref", required=True)
    verify_p.add_argument("--build-gradle", type=Path, required=True)
    verify_main_p = sub.add_parser("verify-main")
    verify_main_p.add_argument("--dir", type=Path, required=True)
    verify_main_p.add_argument("--head", required=True)
    verify_main_p.add_argument("--build-gradle", type=Path, required=True)
    sub.add_parser("self-test")
    args = parser.parse_args()
    try:
        if args.command == "classify":
            required = classify(
                json.loads(args.pull.read_text(encoding="utf-8")),
                json.loads(args.pages.read_text(encoding="utf-8")),
                args.head,
            )
            print("required=" + str(required).lower())
        elif args.command == "check-contract":
            check_contract(
                args.production.read_text(encoding="utf-8"),
                args.pr_workflow.read_text(encoding="utf-8"),
                args.build_gradle.read_text(encoding="utf-8"),
            )
        elif args.command == "verify":
            print(json.dumps(
                verify_graph(
                    args.dir,
                    args.head,
                    args.event_ref,
                    args.build_gradle.read_text(encoding="utf-8"),
                ),
                sort_keys=True,
            ))
        elif args.command == "verify-main":
            print(json.dumps(
                verify_main_graph(
                    args.dir,
                    args.head,
                    args.build_gradle.read_text(encoding="utf-8"),
                ),
                sort_keys=True,
            ))
        else:
            self_test()
    except (ValueError, OSError, KeyError, TypeError, json.JSONDecodeError) as exc:
        print(
            f"::error::Trusted dependency-submission PR validation rejected: {exc}",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
