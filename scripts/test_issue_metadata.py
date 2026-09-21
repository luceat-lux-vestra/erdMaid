#!/usr/bin/env python3
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
POLICY = ROOT / ".github" / "issue-metadata-policy.json"
REPOSITORY_POLICY = ROOT / ".github" / "repository-policy.json"

def classify(title, rules):
    value = title.strip()
    for pattern, label in rules:
        if pattern.search(value):
            return label
    return None

def main():
    policy = json.loads(POLICY.read_text())
    repository = json.loads(REPOSITORY_POLICY.read_text())
    canonical = {entry["name"] for entry in repository["labels"]}
    managed = set(policy["managed_types"])
    failures = []
    workflow = (ROOT / ".github" / "workflows" / "issue-metadata.yml").read_text()
    if not re.search(r"(?ms)^      dry_run:\n.*?^        default: true\s*$", workflow):
        failures.append("manual issue reconciliation must default dry_run=true")
    if not re.search(r"(?ms)^      backfill:\n.*?^        default: false\s*$", workflow):
        failures.append("manual issue reconciliation must default backfill=false")
    for fragment in (
        "const defaultBranchRef = `refs/heads/${context.payload.repository.default_branch}`;",
        'context.eventName === "workflow_dispatch" && backfill && !dryRun && context.ref !== defaultBranchRef',
        "Mutating backfill must run from",
        "persist-credentials: false",
        "ref: ${{ github.event.repository.default_branch }}",
    ):
        if fragment not in workflow:
            failures.append(f"issue metadata mutation boundary missing: {fragment}")

    if not managed <= canonical:
        failures.append(f"managed types missing from repository label catalog: {sorted(managed - canonical)}")

    rules = [(re.compile(entry["pattern"], re.I), entry["label"]) for entry in policy["rules"]]
    for _, label in rules:
        if label not in managed:
            failures.append(f"rule references unmanaged label: {label}")

    cases = {
        "bug(metadata): wrong relation": "type:bug",
        "fix(renderer): escaping": "type:bug",
        "feat(export): add option": "type:feature",
        "task(proof): reconcile docs": "type:maintenance",
        "test(runtime): live flow": "type:maintenance",
        "build(deps): update Kotlin": "type:maintenance",
        "security: tighten workflow": "type:maintenance",
        "docs: clarify contract": "type:maintenance",
        "hardening(reassessment): refresh controls": "type:maintenance",
        "governance: classify failure signals": "type:maintenance",
        "Investigate DataGrip behavior": None,
    }
    for title, expected in cases.items():
        actual = classify(title, rules)
        if actual != expected:
            failures.append(f"{title!r}: expected {expected!r}, got {actual!r}")

    if failures:
        print("issue metadata policy violations:")
        for failure in failures:
            print(f"  - {failure}")
        return 1
    print("issue metadata policy: PASS")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
