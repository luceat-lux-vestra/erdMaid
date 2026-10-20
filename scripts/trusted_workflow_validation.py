#!/usr/bin/env python3
"""Read-only PR CodeQL artifact compatibility fixture; never production evidence.

This is a bounded CodeQL reference adapter. Other privileged workflow classes
must get their own reviewed adapters before claiming dynamic compatibility.
"""
import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

from release_codeql_gate import eligible_run
from release_codeql_verdict import check

SHA = re.compile(r"[0-9a-f]{40}\Z")
CODEQL_INPUTS = frozenset({
    ".github/workflows/codeql.yml",
    "scripts/release_codeql_verdict.py",
    "scripts/release_codeql_gate.py",
    "scripts/trusted_workflow_validation.py",
    "scripts/test_trusted_workflow_validation.py",
})
DOMAIN = "untrusted-pr-fixture-only"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def classify(pull, pages, expected_head):
    require(isinstance(expected_head, str) and SHA.fullmatch(expected_head), "invalid expected PR HEAD")
    require(isinstance(pull, dict) and isinstance(pull.get("head"), dict), "missing PR metadata")
    require(pull["head"].get("sha") == expected_head, "PR HEAD changed during classification")
    expected_count = pull.get("changed_files")
    require(type(expected_count) is int and 0 < expected_count <= 3000, "invalid or unsupported PR file count")
    require(isinstance(pages, list) and pages and all(isinstance(p, list) for p in pages), "invalid paginated file listing")
    files = [entry for page in pages for entry in page]
    require(len(files) == expected_count, "incomplete PR file listing")
    paths = set()
    seen = set()
    for f in files:
        require(isinstance(f, dict), "malformed PR file entry")
        name = f.get("filename")
        status = f.get("status")
        require(isinstance(name, str) and name and not name.startswith("/") and ".." not in name.split("/"), "unsafe filename")
        require(name not in seen, "duplicate PR file entry")
        seen.add(name)
        require(status in {"added", "modified", "removed", "renamed", "copied", "changed", "unchanged"}, "unknown PR file status")
        paths.add(name)
        # The reference adapter only proves the upload action's runtime path.
        # Other CodeQL YAML changes require a separately reviewed adapter.
        if name == ".github/workflows/codeql.yml":
            require(status == "modified", "unsupported CodeQL workflow change type")
            patch = f.get("patch")
            require(isinstance(patch, str) and patch, "CodeQL change has no complete reviewable patch")
            changes = [line[1:].strip() for line in patch.splitlines()
                       if line.startswith(("+", "-")) and not line.startswith(("+++", "---"))]
            require(changes and all(re.fullmatch(
                r"uses: actions/upload-artifact@[0-9a-f]{40}(?:\s+# v[0-9A-Za-z.+-]+)?", line)
                for line in changes), "CodeQL YAML changes beyond upload-action pin are not proven by fixture")
        if status == "renamed":
            old = f.get("previous_filename")
            require(isinstance(old, str) and old and not old.startswith("/") and ".." not in old.split("/"), "rename missing previous path")
            paths.add(old)
    return bool(paths & CODEQL_INPUTS)


def validate_action_pins(codeql_workflow, pr_workflow):
    prod = set(re.findall(r"uses:\s*actions/upload-artifact@([0-9a-f]{40})", codeql_workflow))
    step = re.search(r"- name: Upload PR-only CodeQL artifact fixture\s*\n"
                     r"\s+if: .*?\n\s+uses:\s*actions/upload-artifact@([0-9a-f]{40})", pr_workflow)
    require(len(prod) == 1 and step is not None and step.group(1) in prod,
            "PR fixture upload pin does not match production CodeQL upload pin")


def sarif_document():
    return {"version": "2.1.0", "runs": [{
        "tool": {"driver": {"name": "CodeQL"}, "extensions": [{
            "name": "codeql/actions-queries", "rules": [{"id": "actions/fixture",
                "properties": {"tags": ["correctness"]}}]}]},
        "automationDetails": {"id": "/language:actions/"},
        "invocations": [{"executionSuccessful": True, "toolExecutionNotifications": [{
            "descriptor": {"id": "actions/diagnostics/successfully-extracted-files"},
            "locations": [{"physicalLocation": {"artifactLocation": {"uri": ".github/workflows/codeql.yml"}}}]
        }]}], "results": []}]}


def prepare(folder, sha):
    require(SHA.fullmatch(sha) is not None, "invalid PR source SHA")
    folder.mkdir(parents=True, exist_ok=True)
    payload = (json.dumps(sarif_document(), sort_keys=True, separators=(",", ":")) + "\n").encode()
    (folder / "fixture.sarif").write_bytes(payload)
    verdict = check(folder, "actions", sha)
    require(verdict["high_or_critical"] == 0, "fixture verdict failed")
    manifest = {"schema": 1, "trust_domain": DOMAIN, "production_evidence": False,
                "event": "pull_request", "source_sha": sha,
                "sarif_sha256": hashlib.sha256(payload).hexdigest()}
    (folder / "fixture-manifest.json").write_text(json.dumps(manifest, sort_keys=True) + "\n", encoding="utf-8")


def verify(folder, sha):
    require(SHA.fullmatch(sha) is not None, "invalid PR source SHA")
    require(folder.is_dir(), "missing downloaded artifact")
    require({p.name for p in folder.iterdir()} == {"fixture.sarif", "fixture-manifest.json"}, "unexpected artifact files")
    manifest = json.loads((folder / "fixture-manifest.json").read_text(encoding="utf-8"))
    require(manifest == {"schema": 1, "trust_domain": DOMAIN, "production_evidence": False,
                         "event": "pull_request", "source_sha": sha,
                         "sarif_sha256": hashlib.sha256((folder / "fixture.sarif").read_bytes()).hexdigest()},
            "PR artifact manifest or SHA-256 mismatch")
    check(folder, "actions", sha)
    # Even a synthetic clean SARIF cannot become authenticated production proof.
    require(not eligible_run({"workflow_id": 77, "path": ".github/workflows/codeql.yml",
                              "event": "pull_request", "head_sha": sha,
                              "display_title": "CodeQL source " + sha}, sha, 77),
            "PR result was admitted as trusted CodeQL evidence")


def self_test():
    import tempfile
    sha = "a" * 40
    pull = {"head": {"sha": sha}, "changed_files": 1}
    initial_patch = "@@ -1 +1 @@\n-        uses: actions/upload-artifact@" + "a" * 40 + " # v7.0.1\n+        uses: actions/upload-artifact@" + "b" * 40 + " # v7.0.2"
    assert classify(pull, [[{"filename": ".github/workflows/codeql.yml", "status": "modified", "patch": initial_patch}]], sha)
    assert not classify(pull, [[{"filename": "README.md", "status": "modified"}]], sha)
    assert classify(pull, [[{"filename": "docs/codeql-old.yml", "previous_filename": ".github/workflows/codeql.yml", "status": "renamed"}]], sha)
    for badpull, badpages, badsha in [
        (pull, [[]], sha), (pull, [[{"filename": "foo", "status": "unknown"}]], sha),
        (pull, [[{"filename": "foo", "status": "renamed"}]], sha),
        (pull, [[{"filename": "foo", "status": "modified"}]], "b" * 40),
        (pull, [[{"filename": ".github/workflows/codeql.yml", "status": "modified"}]], sha),
        ({"head": {"sha": sha}, "changed_files": 2}, [[{"filename": "foo", "status": "modified"}]], sha),
        (pull, [[{"filename": "../bad", "status": "added"}]], sha),
    ]:
        try:
            classify(badpull, badpages, badsha)
        except ValueError:
            pass
        else:
            raise AssertionError("unsafe changed-file listing was accepted")
    valid_patch = "@@ -171 +171 @@\n-        uses: actions/upload-artifact@" + "a" * 40 + " # v7.0.1\n+        uses: actions/upload-artifact@" + "b" * 40 + " # v7.0.2"
    assert classify(pull, [[{"filename": ".github/workflows/codeql.yml", "status": "modified", "patch": valid_patch}]], sha)
    bad_patch = "@@ -1 +1 @@\n-name: CodeQL\n+name: Not CodeQL"
    try:
        classify(pull, [[{"filename": ".github/workflows/codeql.yml", "status": "modified", "patch": bad_patch}]], sha)
    except ValueError:
        pass
    else:
        raise AssertionError("unsupported CodeQL YAML mutation was accepted")
    validate_action_pins("uses: actions/upload-artifact@" + "a" * 40,
                         "- name: Upload PR-only CodeQL artifact fixture\n        if: true\n        uses: actions/upload-artifact@" + "a" * 40)
    try:
        validate_action_pins("uses: actions/upload-artifact@" + "b" * 40,
                             "- name: Upload PR-only CodeQL artifact fixture\n        if: true\n        uses: actions/upload-artifact@" + "a" * 40)
    except ValueError:
        pass
    else:
        raise AssertionError("drifted upload action was accepted")
    with tempfile.TemporaryDirectory() as tmp:
        folder = Path(tmp)
        prepare(folder, sha)
        verify(folder, sha)
        (folder / "fixture.sarif").write_text("{}", encoding="utf-8")
        try:
            verify(folder, sha)
        except ValueError:
            pass
        else:
            raise AssertionError("tampered artifact was accepted")
    print("trusted-workflow PR fixture self-tests passed")


def main():
    p = argparse.ArgumentParser()
    subs = p.add_subparsers(dest="command", required=True)
    c = subs.add_parser("classify")
    c.add_argument("--pull", required=True, type=Path)
    c.add_argument("--pages", required=True, type=Path)
    c.add_argument("--head", required=True)
    for name in ("prepare", "verify"):
        cmd = subs.add_parser(name)
        cmd.add_argument("--dir", required=True, type=Path)
        cmd.add_argument("--head", required=True)
    pin = subs.add_parser("check-pins")
    pin.add_argument("--codeql", type=Path, required=True)
    pin.add_argument("--pr-workflow", type=Path, required=True)
    subs.add_parser("self-test")
    args = p.parse_args()
    try:
        if args.command == "classify":
            required = classify(json.loads(args.pull.read_text()), json.loads(args.pages.read_text()), args.head)
            print("required=" + str(required).lower())
        elif args.command == "prepare":
            prepare(args.dir, args.head)
        elif args.command == "verify":
            verify(args.dir, args.head)
        elif args.command == "check-pins":
            validate_action_pins(args.codeql.read_text(encoding="utf-8"),
                                 args.pr_workflow.read_text(encoding="utf-8"))
        else:
            self_test()
    except (ValueError, OSError, KeyError, TypeError, json.JSONDecodeError) as exc:
        print("::error::Trusted workflow fixture rejected: " + str(exc), file=sys.stderr)
        raise SystemExit(1) from exc


if __name__ == "__main__":
    main()
