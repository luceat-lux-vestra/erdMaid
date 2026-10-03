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


def repository_redacted_fields(policy):
    declaration = policy["manual_live_assertions"]["repository_redacted_fields"]
    fields = declaration["fields"]
    if not isinstance(fields, list) or any(not isinstance(field, str) or not field for field in fields):
        raise TypeError("manual repository redacted fields must be a list of non-empty strings")
    if len(fields) != len(set(fields)):
        raise ValueError("manual repository redacted fields must be unique")
    unknown = sorted(set(fields) - set(policy["repository"]))
    if unknown:
        raise ValueError(f"manual repository redacted fields are not policy keys: {unknown!r}")
    return fields


def manual_security_features(policy):
    declaration = policy["manual_live_assertions"].get("security_features")
    if not isinstance(declaration, dict):
        raise TypeError("manual security features must be a mapping")
    expected = {
        "dependency_graph": True,
        "dependabot_alerts": True,
        "dependabot_security_updates": "enabled",
        "secret_scanning": "enabled",
        "secret_scanning_push_protection": "enabled",
        "private_vulnerability_reporting": True,
    }
    if declaration != expected:
        raise ValueError(
            f"manual security feature assertions must equal {expected!r}; observed {declaration!r}"
        )
    return declaration


def validate_repository(actual, expected, redacted_fields=()):
    errors = []
    allowed_redactions = set(redacted_fields)
    for key, value in expected.items():
        if key not in actual:
            if key in allowed_redactions:
                continue
            errors.append(f"repository.{key}: expected {value!r}, observed <missing>")
            continue
        observed = actual[key]
        if observed != value:
            errors.append(f"repository.{key}: expected {value!r}, observed {observed!r}")
    return errors


def _rule(rules, rule_type):
    matches = [rule for rule in rules if rule.get("type") == rule_type]
    return matches[0] if len(matches) == 1 else None


def validate_ruleset_collection(actual, expected_main, expected_publication):
    errors = []
    expected = [expected_main, expected_publication]
    if len(actual) != len(expected):
        errors.append(
            f"repository ruleset topology: expected exactly {len(expected)} rulesets, observed {len(actual)}"
        )
        return errors

    keys = ("name", "target", "source_type", "source", "enforcement")
    actual_by_identity = {
        (item.get("name"), item.get("target")): item
        for item in actual
        if isinstance(item, dict)
    }
    expected_by_identity = {
        (item["name"], item["target"]): item
        for item in expected
    }
    if set(actual_by_identity) != set(expected_by_identity):
        errors.append(
            "repository ruleset identities: "
            f"expected {sorted(expected_by_identity)!r}, observed {sorted(actual_by_identity)!r}"
        )
        return errors

    for identity, wanted in expected_by_identity.items():
        summary = actual_by_identity[identity]
        for key in keys:
            if summary.get(key) != wanted[key]:
                errors.append(
                    f"ruleset summary {identity!r}.{key}: expected {wanted[key]!r}, observed {summary.get(key)!r}"
                )
        if "id" in wanted and summary.get("id") != wanted["id"]:
            errors.append(
                f"ruleset summary {identity!r}.id: expected {wanted['id']!r}, observed {summary.get('id')!r}"
            )
    return errors


def validate_ruleset(actual, expected, required_contexts, expected_bypass_actors):
    errors = []
    for key in ("name", "target", "source_type", "source", "enforcement"):
        if actual.get(key) != expected[key]:
            errors.append(f"ruleset.{key}: expected {expected[key]!r}, observed {actual.get(key)!r}")
    ref_name = actual.get("conditions", {}).get("ref_name", {})
    if ref_name.get("include", []) != expected["include"]:
        errors.append(f"ruleset.include: expected {expected['include']!r}, observed {ref_name.get('include', [])!r}")
    if ref_name.get("exclude", []) != expected["exclude"]:
        errors.append(f"ruleset.exclude: expected {expected['exclude']!r}, observed {ref_name.get('exclude', [])!r}")

    # GitHub deliberately redacts bypass_actors unless the caller has write access
    # to the ruleset. The scheduled read-only audit must not treat a missing field
    # as proof of an empty bypass set. If the API does expose it, validate it; the
    # authoritative no-bypass proof remains the privileged merge/exit-gate readback.
    if "bypass_actors" in actual and actual["bypass_actors"] != expected_bypass_actors:
        errors.append(
            f"ruleset.bypass_actors: expected {expected_bypass_actors!r}, observed {actual['bypass_actors']!r}"
        )

    rules = actual.get("rules", [])
    observed_types = [rule.get("type") for rule in rules]
    if sorted(observed_types) != sorted(expected["required_rule_types"]):
        errors.append(f"ruleset rule topology: expected {sorted(expected['required_rule_types'])!r}, observed {sorted(observed_types)!r}")

    pr = _rule(rules, "pull_request")
    if pr:
        params = pr.get("parameters", {})
        for key, value in expected["pull_request"].items():
            if params.get(key) != value:
                errors.append(f"ruleset.pull_request.{key}: expected {value!r}, observed {params.get(key)!r}")

    checks = _rule(rules, "required_status_checks")
    if checks:
        params = checks.get("parameters", {})
        wanted = expected["required_status_checks"]
        for key in ("strict_required_status_checks_policy", "do_not_enforce_on_create"):
            if params.get(key) != wanted[key]:
                errors.append(f"ruleset.required_status_checks.{key}: expected {wanted[key]!r}, observed {params.get(key)!r}")
        observed_checks = params.get("required_status_checks", [])
        contexts = sorted(item.get("context") for item in observed_checks)
        if contexts != sorted(required_contexts):
            errors.append(f"ruleset required contexts: expected {sorted(required_contexts)!r}, observed {contexts!r}")
        wrong_sources = [item for item in observed_checks if item.get("integration_id") != wanted["integration_id"]]
        if wrong_sources:
            errors.append(f"ruleset required check source drift: expected integration {wanted['integration_id']}")
    return errors


def validate_publication_ruleset(actual, expected, expected_bypass_actors):
    errors = []
    for key in ("id", "name", "target", "source_type", "source", "enforcement"):
        if actual.get(key) != expected[key]:
            errors.append(
                f"publication_ruleset.{key}: expected {expected[key]!r}, observed {actual.get(key)!r}"
            )

    ref_name = actual.get("conditions", {}).get("ref_name", {})
    if ref_name.get("include", []) != expected["include"]:
        errors.append(
            f"publication_ruleset.include: expected {expected['include']!r}, observed {ref_name.get('include', [])!r}"
        )
    if ref_name.get("exclude", []) != expected["exclude"]:
        errors.append(
            f"publication_ruleset.exclude: expected {expected['exclude']!r}, observed {ref_name.get('exclude', [])!r}"
        )

    if "bypass_actors" in actual and actual["bypass_actors"] != expected_bypass_actors:
        errors.append(
            "publication_ruleset.bypass_actors: "
            f"expected {expected_bypass_actors!r}, observed {actual['bypass_actors']!r}"
        )

    rules = actual.get("rules", [])
    observed_types = [rule.get("type") for rule in rules]
    if sorted(observed_types) != sorted(expected["required_rule_types"]):
        errors.append(
            "publication ruleset rule topology: "
            f"expected {sorted(expected['required_rule_types'])!r}, observed {sorted(observed_types)!r}"
        )
    return errors


def validate_actions_event_policy_contract(policy):
    errors = []
    expected = policy.get("actions_event_policy")
    manual = policy.get("manual_live_assertions", {}).get("actions_event_policy")
    if not isinstance(expected, dict) or not isinstance(manual, dict):
        return ["actions event-policy contract is missing"]

    if expected.get("id") != 5151:
        errors.append(f"actions_event_policy.id: expected 5151, observed {expected.get('id')!r}")
    if expected.get("workflow_paths") != [".github/workflows/pr-metadata.yml"]:
        errors.append(
            "actions_event_policy.workflow_paths must equal ['.github/workflows/pr-metadata.yml']"
        )
    if expected.get("allowed_events") != ["pull_request_target"]:
        errors.append("actions_event_policy.allowed_events must equal ['pull_request_target']")
    if expected.get("retired_workflow_paths") != [".github/workflows/failure-triage.yml"]:
        errors.append(
            "actions_event_policy.retired_workflow_paths must equal ['.github/workflows/failure-triage.yml']"
        )

    if manual.get("policy_id") != expected.get("id"):
        errors.append("manual Actions event-policy assertion targets a different policy id")
    if manual.get("expected_workflow_paths") != expected.get("workflow_paths"):
        errors.append("manual Actions event-policy workflow paths drifted from canonical policy")
    if manual.get("expected_allowed_events") != expected.get("allowed_events"):
        errors.append("manual Actions event-policy allowed events drifted from canonical policy")
    if manual.get("forbidden_workflow_paths") != expected.get("retired_workflow_paths"):
        errors.append("manual Actions event-policy forbidden paths drifted from canonical policy")

    for rel in expected.get("workflow_paths", []):
        if not (ROOT / rel).is_file():
            errors.append(f"active Actions event-policy workflow is missing: {rel}")
    for rel in expected.get("retired_workflow_paths", []):
        if (ROOT / rel).exists():
            errors.append(f"retired Actions event-policy workflow unexpectedly exists: {rel}")
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
        if not isinstance(batch, list):
            raise TypeError("GitHub labels response is not a list")
        labels.extend(batch)
        if len(batch) < 100:
            return labels
        page += 1


def read_all_repository_rulesets(repo: str, token: str):
    rulesets = []
    page = 1
    while True:
        batch = api_request(
            f"/repos/{repo}/rulesets?per_page=100&page={page}&includes_parents=false",
            token,
        )
        if not isinstance(batch, list):
            raise TypeError("GitHub rulesets response is not a list")
        rulesets.extend(batch)
        if len(batch) < 100:
            return rulesets
        page += 1


def required_contexts():
    policy = load_json(MERGE_GATE_PATH)
    return [entry["context"] for entry in policy["required"]]


def validate_invocation_repository(policy):
    expected = policy["repository"]["full_name"]
    actual = os.environ.get("GITHUB_REPOSITORY", "").strip()
    if actual and actual != expected:
        raise ValueError(f"GITHUB_REPOSITORY mismatch: expected {expected!r}, observed {actual!r}")


def audit(policy, token):
    repo = policy["repository"]["full_name"]
    errors = []
    redacted_fields = repository_redacted_fields(policy)
    manual_security_features(policy)
    errors.extend(validate_actions_event_policy_contract(policy))
    repository = api_request(f"/repos/{repo}", token)
    errors.extend(validate_repository(repository, policy["repository"], redacted_fields))
    missing_redacted_fields = sorted(field for field in redacted_fields if field not in repository)
    if missing_redacted_fields:
        print(
            "repository readback redacted privileged fields: "
            f"{', '.join(missing_redacted_fields)}; separate privileged live readback remains required"
        )

    rulesets = read_all_repository_rulesets(repo, token)
    errors.extend(
        validate_ruleset_collection(
            rulesets,
            policy["ruleset"],
            policy["publication_ruleset"],
        )
    )

    candidates = [item for item in rulesets if item.get("name") == policy["ruleset"]["name"]]
    if len(candidates) == 1:
        ruleset = api_request(f"/repos/{repo}/rulesets/{candidates[0]['id']}?includes_parents=false", token)
        manual = policy["manual_live_assertions"]["ruleset_bypass_actors"]
        if manual["ruleset"] != policy["ruleset"]["name"]:
            errors.append("manual bypass assertion targets a different ruleset")
        errors.extend(
            validate_ruleset(
                ruleset,
                policy["ruleset"],
                required_contexts(),
                manual["expected"],
            )
        )
    else:
        errors.append(f"expected exactly one ruleset named {policy['ruleset']['name']!r}; observed {len(candidates)}")

    publication_candidates = [
        item for item in rulesets
        if item.get("name") == policy["publication_ruleset"]["name"]
        and item.get("target") == policy["publication_ruleset"]["target"]
    ]
    if len(publication_candidates) == 1:
        publication = api_request(
            f"/repos/{repo}/rulesets/{publication_candidates[0]['id']}?includes_parents=false",
            token,
        )
        publication_manual = policy["manual_live_assertions"]["publication_ruleset_bypass_actors"]
        if publication_manual["ruleset"] != policy["publication_ruleset"]["name"]:
            errors.append("manual publication bypass assertion targets a different ruleset")
        errors.extend(
            validate_publication_ruleset(
                publication,
                policy["publication_ruleset"],
                publication_manual["expected"],
            )
        )
    else:
        errors.append(
            f"expected exactly one publication ruleset named {policy['publication_ruleset']['name']!r}; "
            f"observed {len(publication_candidates)}"
        )

    actions_manual = policy["manual_live_assertions"]["actions_event_policy"]
    print(
        "actions event-policy live readback remains a separate privileged assertion: "
        f"policy {actions_manual['policy_id']} must scope exactly to "
        f"{actions_manual['expected_workflow_paths']!r} with events "
        f"{actions_manual['expected_allowed_events']!r}; forbidden paths "
        f"{actions_manual['forbidden_workflow_paths']!r}"
    )

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
        validate_invocation_repository(policy)
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
    print(
        "repository policy OK for read-visible settings, exact main/publication ruleset topology, "
        "required check sources, and canonical labels; declared redacted repository fields, "
        "ruleset bypass actors, and Actions event-policy scope remain separate privileged live assertions"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
