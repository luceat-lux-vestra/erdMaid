#!/usr/bin/env python3
"""Validate production-pinned CodeQL init/analyze in an untrusted PR lane."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

from release_codeql_gate import eligible_run
from release_codeql_verdict import check

SHA = re.compile(r"[0-9a-f]{40}\Z")
JOB = re.compile(r"^  ([A-Za-z0-9_-]+):\s*$", re.MULTILINE)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def job_block(workflow: str, job_id: str) -> str:
    marker = re.search(rf"^  {re.escape(job_id)}:\s*$", workflow, re.MULTILINE)
    require(marker is not None, f"missing workflow job {job_id}")
    tail = workflow[marker.end():]
    end = JOB.search(tail)
    return tail[:end.start()] if end else tail


def step_block(job: str, step_name: str) -> str:
    marker = re.search(
        rf"^\s{{6}}- name: {re.escape(step_name)}\s*$",
        job,
        re.MULTILINE,
    )
    require(marker is not None, f"missing step {step_name}")
    tail = job[marker.end():]
    end = re.search(r"^\s{6}- name: ", tail, re.MULTILINE)
    return tail[:end.start()] if end else tail


def single_pin(text: str, action: str) -> str:
    pins = re.findall(
        rf"uses:\s*{re.escape(action)}@([0-9a-f]{{40}})(?:\s+#.*)?$",
        text,
        re.MULTILINE,
    )
    require(len(pins) == 1, f"expected exactly one {action} pin")
    return pins[0]


def check_contract(codeql_workflow: str, pr_workflow: str) -> None:
    prod_init = single_pin(codeql_workflow, "github/codeql-action/init")
    prod_analyze = single_pin(codeql_workflow, "github/codeql-action/analyze")
    require(prod_init == prod_analyze,
            "production CodeQL init/analyze pins must match")

    job = job_block(pr_workflow, "staticAnalysis")
    header = job.split("\n    steps:", 1)[0]
    require("      contents: read" in header,
            "PR CodeQL runtime job must retain contents:read")
    require("      pull-requests: read" in header,
            "PR CodeQL runtime job must retain pull-requests:read")
    require("security-events: write" not in header,
            "PR CodeQL runtime must not have security-events:write")
    require(re.search(r"^\s{6}[A-Za-z0-9_-]+:\s*write\s*$", header, re.MULTILINE) is None,
            "PR CodeQL runtime job gained write authority")

    init = step_block(job, "Initialize PR-only CodeQL runtime")
    analyze = step_block(job, "Analyze PR-only CodeQL runtime without upload")
    verify = step_block(job, "Verify local PR-only CodeQL runtime SARIF and reject promotion")

    require(single_pin(init, "github/codeql-action/init") == prod_init,
            "PR CodeQL init pin does not match production")
    require(single_pin(analyze, "github/codeql-action/analyze") == prod_analyze,
            "PR CodeQL analyze pin does not match production")
    require("          languages: actions" in init,
            "PR CodeQL runtime must use representative Actions language")
    require("          build-mode: none" in init,
            "PR CodeQL runtime must stay build-mode:none")
    require('          category: "/language:actions"' in analyze,
            "PR CodeQL runtime category drifted")
    require("          upload: never" in analyze,
            "PR CodeQL runtime must never upload security evidence")
    require("          output: ${{ runner.temp }}/pr-codeql-runtime" in analyze,
            "PR CodeQL runtime must write local SARIF under runner temp")
    require("trusted_codeql_runtime_validation.py verify" in verify,
            "PR CodeQL runtime lost local SARIF verification")


def verify_runtime(directory: Path, source_sha: str) -> dict:
    require(SHA.fullmatch(source_sha or "") is not None,
            "invalid PR source SHA")
    verdict = check(directory, "actions", source_sha)
    require(verdict["high_or_critical"] == 0,
            "PR CodeQL runtime verdict failed")
    require(
        not eligible_run(
            {
                "workflow_id": 77,
                "path": ".github/workflows/codeql.yml",
                "event": "pull_request",
                "head_sha": source_sha,
                "display_title": "CodeQL source " + source_sha,
            },
            source_sha,
            77,
        ),
        "PR CodeQL runtime was admitted as trusted production evidence",
    )
    return {
        "schema": 1,
        "trust_domain": "untrusted-pr-codeql-runtime",
        "production_evidence": False,
        "event": "pull_request",
        "source_sha": source_sha,
        "language": "actions",
        "sarif_sha256": verdict["sarif_sha256"],
        "rules": verdict["rules"],
        "results": verdict["results"],
        "high_or_critical": verdict["high_or_critical"],
    }


def self_test() -> None:
    pin = "a" * 40
    prod = (
        "jobs:\n  analyze:\n    steps:\n"
        f"      - uses: github/codeql-action/init@{pin}\n"
        f"      - uses: github/codeql-action/analyze@{pin}\n"
    )
    pr = (
        "jobs:\n  staticAnalysis:\n"
        "    permissions:\n"
        "      contents: read\n"
        "      pull-requests: read\n"
        "    steps:\n"
        "      - name: Initialize PR-only CodeQL runtime\n"
        f"        uses: github/codeql-action/init@{pin}\n"
        "        with:\n"
        "          languages: actions\n"
        "          build-mode: none\n"
        "      - name: Analyze PR-only CodeQL runtime without upload\n"
        f"        uses: github/codeql-action/analyze@{pin}\n"
        "        with:\n"
        '          category: \"/language:actions\"\n'
        "          upload: never\n"
        "          output: ${{ runner.temp }}/pr-codeql-runtime\n"
        "      - name: Verify local PR-only CodeQL runtime SARIF and reject promotion\n"
        "        run: trusted_codeql_runtime_validation.py verify\n"
    )
    check_contract(prod, pr)

    mutations = (
        pr.replace("upload: never", "upload: always"),
        pr.replace("pull-requests: read", "security-events: write"),
        pr.replace("github/codeql-action/init@" + pin,
                   "github/codeql-action/init@" + "b" * 40),
        pr.replace("languages: actions", "languages: java-kotlin"),
        pr.replace("trusted_codeql_runtime_validation.py verify", "echo skip"),
    )
    for mutated in mutations:
        try:
            check_contract(prod, mutated)
        except ValueError:
            pass
        else:
            raise AssertionError("unsafe CodeQL PR runtime contract was accepted")

    bad_prod = prod.replace(
        "github/codeql-action/analyze@" + pin,
        "github/codeql-action/analyze@" + "b" * 40,
    )
    try:
        check_contract(bad_prod, pr)
    except ValueError:
        pass
    else:
        raise AssertionError("drifted production CodeQL pins were accepted")

    print("trusted CodeQL PR runtime contract self-tests passed")


def main() -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)

    contract = sub.add_parser("check-contract")
    contract.add_argument("--codeql", type=Path, required=True)
    contract.add_argument("--pr-workflow", type=Path, required=True)

    verify = sub.add_parser("verify")
    verify.add_argument("--dir", type=Path, required=True)
    verify.add_argument("--head", required=True)

    sub.add_parser("self-test")
    args = parser.parse_args()

    try:
        if args.command == "check-contract":
            check_contract(
                args.codeql.read_text(encoding="utf-8"),
                args.pr_workflow.read_text(encoding="utf-8"),
            )
        elif args.command == "verify":
            print(json.dumps(
                verify_runtime(args.dir, args.head),
                sort_keys=True,
            ))
        else:
            self_test()
    except (ValueError, OSError, KeyError, TypeError, json.JSONDecodeError) as exc:
        print(
            f"::error::Trusted CodeQL PR runtime validation rejected: {exc}",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
