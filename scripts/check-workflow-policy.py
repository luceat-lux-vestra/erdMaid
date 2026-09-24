#!/usr/bin/env python3
"""Fail-closed checks for erdMaid's required and staged workflow contexts."""

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


def workflow_triggered_on_pr_event(text, event):
    trigger = re.search(r"^on:\s*$", text, re.MULTILINE)
    if not trigger:
        raise ValueError("workflow has no explicit `on:` mapping")
    end = re.search(r"^[^ \t#\r\n].*$", text[trigger.end():], re.MULTILINE)
    block = text[trigger.end(): trigger.end() + end.start() if end else len(text)]
    event_match = re.search(rf"^  {re.escape(event)}:\s*(?:#.*)?$", block, re.MULTILINE)
    if not event_match:
        return False
    event_tail = block[event_match.end():]
    next_event = re.search(r"^  [A-Za-z0-9_-]+:\s*(?:#.*)?$", event_tail, re.MULTILINE)
    event_block = event_tail[:next_event.start()] if next_event else event_tail
    if re.search(r"^    paths(?:-ignore)?:", event_block, re.MULTILINE):
        raise ValueError(f"required workflow uses a path-filtered {event} trigger")
    return True


def check_entries(root, entries, classification):
    for entry in entries:
        workflow_path = os.path.join(root, entry["workflow"])
        if not os.path.isfile(workflow_path):
            raise ValueError(f"{classification} {entry['context']!r} names missing workflow {entry['workflow']}")
        text = open(workflow_path, encoding="utf-8").read()
        jobs = read_jobs(text)
        job = jobs.get(entry["job"])
        if job is None:
            raise ValueError(f"{classification} {entry['context']!r} names missing job {entry['job']!r}")
        name = re.search(r"^    name:\s*(.+?)\s*$", job, re.MULTILINE)
        if not name or name.group(1).strip("\"'") != entry["context"]:
            observed = name.group(1).strip() if name else "<missing>"
            raise ValueError(f"{entry['context']!r} does not match job name {observed!r}")
        if re.search(r"^    (?:if|continue-on-error):", job, re.MULTILINE):
            raise ValueError(f"{entry['context']!r} can be skipped or greened by job policy")

        event = entry.get("trigger", "pull_request")
        if event not in {"pull_request", "pull_request_target"}:
            raise ValueError(f"{entry['context']!r} declares unsupported trigger {event!r}")
        if event == "pull_request_target":
            raise ValueError(f"{entry['context']!r} must not use pull_request_target")
        if not workflow_triggered_on_pr_event(text, event):
            raise ValueError(f"{entry['context']!r} is not emitted on {event}")


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
        if not re.search(r"^permissions:(?:\s*\{\})?\s*$", text, re.MULTILINE):
            raise ValueError(f"{os.path.basename(path)} has no supported top-level permissions block")
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

    classifier_path = os.path.join(workflow_dir, "failure-classification.yml")
    if os.path.isfile(classifier_path):
        classifier = open(classifier_path, encoding="utf-8").read()
        if not re.search(r"^permissions:\s*\{\}\s*$", classifier, re.MULTILINE):
            raise ValueError("Failure classification must use top-level permissions: {}")
        classifier_jobs = read_jobs(classifier)
        classify = classifier_jobs.get("classify")
        if classify is None:
            raise ValueError("Failure classification has no classify job")
        permission_block = re.search(
            r"(?ms)^    permissions:\s*$\n(?P<body>(?:      [^\n]*(?:\n|$))*)",
            classify,
        )
        if permission_block is None:
            raise ValueError("Failure classification classify job has no permissions block")
        observed = {}
        for raw in permission_block.group("body").splitlines():
            code = raw.split("#", 1)[0].strip()
            if not code:
                continue
            match = re.fullmatch(r"([A-Za-z-]+):\s*(read|write)", code)
            if match is None:
                raise ValueError(f"Failure classification has unsupported permission entry {code!r}")
            observed[match.group(1)] = match.group(2)
        expected = {"actions": "read", "pull-requests": "write"}
        if observed != expected:
            raise ValueError(
                f"Failure classification has unexpected job permissions: {observed!r}"
            )
        if "actions/checkout@" in classifier:
            raise ValueError("Failure classification must not checkout repository content")
        if "actions/download-artifact@" in classifier or "gh run download" in classifier:
            raise ValueError("Failure classification must not download workflow artifacts")


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
        check_entries(args.root, policy["required"], "required")
        check_entries(args.root, policy.get("staged", []), "staged")
        check_workflow_security(args.root)
    except (OSError, json.JSONDecodeError, ValueError) as error:
        return fail(str(error))
    print(
        f"workflow policy OK: {len(policy['required'])} required + "
        f"{len(policy.get('staged', []))} staged context(s)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
