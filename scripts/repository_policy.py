#!/usr/bin/env python3
"""Machine-checkable erdMaid repository policy and canonical label maintenance."""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
POLICY_PATH = ROOT / ".github" / "repository-policy.json"
MERGE_GATE_PATH = ROOT / ".github" / "merge-gate-policy.json"
API = "https://api.github.com"


def load_json(path: Path):
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def api_request(path: str, token: str, method: str = "GET", body=None):
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(
        API + path,
        data=data,
        method=method,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": "2022-11-28",
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            payload = response.read()
            return None if not payload else json.loads(payload)
    except urllib.error.HTTPError as error:
        detail = error.read().decode(errors="replace")
        raise RuntimeError(f"GitHub API {method} {path} failed: HTTP {error.code}: {detail}") from error
    except urllib.error.URLError as error:
        raise RuntimeError(f"GitHub API {method} {path} failed: {error.reason}") from error


def validate_repository(actual, expected):
    errors = []
    for key, value in expected.items():
        if key == "full_name":
            observed = actual.get("full_name")
        else:
            observed = actual.get(key)
        if observed != value:
            errors.append(f"repository.{key}: expected {value!r}, observed {observed!r}")
    return errors


def _rule(rules, rule_type):
    matches = [rule for rule in rules if rule.get("type") == rule_type]
    return matches[0] if len(matches) == 1 else None


def validate_ruleset(actual, expected, required_contexts):
    errors = []
    for key in ("name", "target", "enforcement"):
        if actual.get(key) != expected[key]:
            errors.append(f"ruleset.{key}: expected {expected[key]!r}, observed {actual.get(key)!r}")
    observed_include = actual.get("conditions", {}).get("ref_name", {}).get("include", [])
    if observed_include != expected["include"]:
        errors.append(f"ruleset.include: expected {expected['include']!r}, observed {observed_include!r}")
    if actual.get("bypass_actors", []) != expected["bypass_actors"]:
        errors.append("ruleset.bypass_actors drifted from the no-bypass policy")

    rules = actual.get("rules", [])
    observed_types = [rule.get("type") for rule in rules]
    for rule_type in expected["required_rule_types"]:
        if observed_types.count(rule_type) != 1:
            errors.append(f"ruleset requires exactly one {rule_type!r} rule; observed {observed_types.count(rule_type)}")

    pr = _rule(rules, "pull_request")
    if pr:
        params = pr.get("parameters", {})
        for key, value in expected["pull_request"].items():
            if params.get(key) != value:
                errors.append(f"ruleset.pull_request.{key}: expected {value!r}, observed {params.get(key)!r}")

    checks = _rule(rules, "required_status_checks")
    if checks:
        params = checks.get("parameters", {})
        strict = expected["required_status_checks"]["strict_required_status_checks_policy"]
        if params.get("strict_required_status_checks_policy") != strict:
            errors.append("ruleset required status checks are not strict")
        contexts = sorted(item.get("context") for item in params.get("required_status_checks", []))
        if contexts != sorted(required_contexts):
            errors.append(f"ruleset required contexts: expected {sorted(required_contexts)!r}, observed {contexts!r}")
    return errors


def validate_labels(actual, expected):
    errors = []
    by_name = {label.get("name"): label for label in actual}
    for wanted in expected:
        found = by_name.get(wanted["name"])
        if found is None:
            errors.append(f"label missing: {wanted['name']}")
            continue
        if found.get("color", "").lower() != wanted["color"].lower():
            errors.append(f"label {wanted['name']} color drifted")
        if (found.get("description") or "") != wanted["description"]:
            errors.append(f"label {wanted['name']} description drifted")
    return errors


def read_all_labels(repo: str, token: str):
    labels = []
    page = 1
    while True:
        batch = api_request(f"/repos/{repo}/labels?per_page=100&page={page}", token)
        labels.extend(batch)
        if len(batch) < 100:
            return labels
        page += 1


def required_contexts():
    policy = load_json(MERGE_GATE_PATH)
    return [entry["context"] for entry in policy["required"]]


def audit(policy, token):
    repo = policy["repository"]["full_name"]
    errors = []
    repository = api_request(f"/repos/{repo}", token)
    errors.extend(validate_repository(repository, policy["repository"]))

    rulesets = api_request(f"/repos/{repo}/rulesets", token)
    candidates = [item for item in rulesets if item.get("name") == policy["ruleset"]["name"]]
    if len(candidates) != 1:
        errors.append(f"expected exactly one ruleset named {policy['ruleset']['name']!r}; observed {len(candidates)}")
    else:
        ruleset = api_request(f"/repos/{repo}/rulesets/{candidates[0]['id']}", token)
        errors.extend(validate_ruleset(ruleset, policy["ruleset"], required_contexts()))

    labels = read_all_labels(repo, token)
    errors.extend(validate_labels(labels, policy["labels"]))
    return errors


def sync_labels(policy, token):
    repo = policy["repository"]["full_name"]
    current = {label["name"]: label for label in read_all_labels(repo, token)}
    changed = 0
    for wanted in policy["labels"]:
        existing = current.get(wanted["name"])
        if existing is None:
            api_request(f"/repos/{repo}/labels", token, "POST", wanted)
            print(f"created label {wanted['name']}")
            changed += 1
            continue
        if existing.get("color", "").lower() != wanted["color"].lower() or (existing.get("description") or "") != wanted["description"]:
            api_request(
                f"/repos/{repo}/labels/{urllib.parse.quote(wanted['name'], safe='')}",
                token,
                "PATCH",
                {"new_name": wanted["name"], "color": wanted["color"], "description": wanted["description"]},
            )
            print(f"updated label {wanted['name']}")
            changed += 1
    print(f"label synchronization complete: {changed} change(s); extra maintainer labels preserved")


def main():
    if len(sys.argv) != 2 or sys.argv[1] not in {"audit", "sync-labels"}:
        print("usage: repository_policy.py {audit|sync-labels}", file=sys.stderr)
        return 2
    token = os.environ.get("GITHUB_TOKEN", "").strip()
    if not token:
        print("REPOSITORY POLICY ERROR: GITHUB_TOKEN is required; live readback may not be skipped", file=sys.stderr)
        return 1
    try:
        policy = load_json(POLICY_PATH)
        if sys.argv[1] == "sync-labels":
            sync_labels(policy, token)
            return 0
        errors = audit(policy, token)
    except (OSError, json.JSONDecodeError, RuntimeError, KeyError, TypeError, ValueError) as error:
        print(f"REPOSITORY POLICY ERROR: {error}", file=sys.stderr)
        return 1
    if errors:
        for error in errors:
            print(f"REPOSITORY POLICY ERROR: {error}", file=sys.stderr)
        return 1
    print("repository policy OK: live settings, main ruleset, required contexts, and canonical labels match")
    return 0


if __name__ == "__main__":
    sys.exit(main())
