#!/usr/bin/env python3
"""Fail-closed checks for erdMaid's required workflow contexts.

This intentionally parses only the small, documented workflow shape used by
this repository. An unfamiliar shape is an error, not an implicit pass.
"""

import argparse
import json
import os
import re
import sys


SHA = re.compile(r"@[0-9a-f]{40}(?:\s+#.*)?$")
JOB = re.compile(r"^  ([A-Za-z0-9_-]+):\s*$", re.MULTILINE)


def fail(message):
    print(f"WORKFLOW POLICY ERROR: {message}", file=sys.stderr)
    return 1


def read_jobs(text):
    marker = re.search(r"^jobs:\s*$", text, re.MULTILINE)
    if not marker:
        raise ValueError("workflow has no top-level jobs mapping")
    tail = text[marker.end():]
    jobs = {}
    matches = list(JOB.finditer(tail))
    for index, match in enumerate(matches):
        start = match.start()
        end = matches[index + 1].start() if index + 1 < len(matches) else len(tail)
        jobs[match.group(1)] = tail[start:end]
    if not jobs:
        raise ValueError("workflow has no parseable jobs")
    return jobs


def workflow_triggered_on_pull_request(text):
    trigger = re.search(r"^on:\s*$", text, re.MULTILINE)
    if not trigger:
        raise ValueError("workflow has no explicit `on:` mapping")
    end = re.search(r"^[^ \t#\r\n].*$", text[trigger.end():], re.MULTILINE)
    block = text[trigger.end(): trigger.end() + end.start() if end else len(text)]
    if not re.search(r"^  pull_request:\s*(?:#.*)?$", block, re.MULTILINE):
        return False
    if re.search(r"^    paths(?:-ignore)?:", block, re.MULTILINE):
        raise ValueError("required workflow uses a path-filtered pull_request trigger")
    return True


def check_required(root, policy):
    for entry in policy["required"]:
        workflow_path = os.path.join(root, entry["workflow"])
        if not os.path.isfile(workflow_path):
            raise ValueError(f"{entry['context']!r} names missing workflow {entry['workflow']}")
        text = open(workflow_path, encoding="utf-8").read()
        jobs = read_jobs(text)
        job = jobs.get(entry["job"])
        if job is None:
            raise ValueError(f"{entry['context']!r} names missing job {entry['job']!r}")
        name = re.search(r"^    name:\s*(.+?)\s*$", job, re.MULTILINE)
        if not name or name.group(1).strip("\"'") != entry["context"]:
            observed = name.group(1).strip() if name else "<missing>"
            raise ValueError(f"{entry['context']!r} does not match job name {observed!r}")
        if re.search(r"^    (?:if|continue-on-error):", job, re.MULTILINE):
            raise ValueError(f"{entry['context']!r} can be skipped or greened by job policy")
        if not workflow_triggered_on_pull_request(text):
            raise ValueError(f"{entry['context']!r} is not emitted on pull_request")


def check_workflow_security(root):
    workflow_dir = os.path.join(root, ".github", "workflows")
    paths = sorted(
        os.path.join(workflow_dir, name)
        for name in os.listdir(workflow_dir)
        if name.endswith((".yml", ".yaml"))
    )
    if not paths:
        raise ValueError("no workflow files found")
    for path in paths:
        text = open(path, encoding="utf-8").read()
        if not re.search(r"^permissions:\s*$", text, re.MULTILINE):
            raise ValueError(f"{os.path.basename(path)} has no top-level permissions block")
        for line in text.splitlines():
            if re.search(r"\buses:\s*", line):
                ref = line.split("uses:", 1)[1].split("#", 1)[0].strip()
                if ref.startswith("./"):
                    continue
                if not SHA.search(ref):
                    raise ValueError(f"{os.path.basename(path)} contains mutable action ref {ref!r}")
        jobs = read_jobs(text)
        for job_id, job in jobs.items():
            if not re.search(r"^    timeout-minutes:\s*[1-9][0-9]*\s*$", job, re.MULTILINE):
                raise ValueError(f"{os.path.basename(path)}:{job_id} has no timeout-minutes")
        if not re.search(r"^concurrency:\s*$", text, re.MULTILINE):
            raise ValueError(f"{os.path.basename(path)} has no concurrency control")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    args = parser.parse_args()
    policy_path = os.path.join(args.root, ".github", "merge-gate-policy.json")
    try:
        with open(policy_path, encoding="utf-8") as handle:
            policy = json.load(handle)
        if not policy.get("required"):
            raise ValueError("policy declares no required contexts")
        check_required(args.root, policy)
        check_workflow_security(args.root)
    except (OSError, json.JSONDecodeError, ValueError) as error:
        return fail(str(error))
    print(f"workflow policy OK: {len(policy['required'])} required context(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
