#!/usr/bin/env python3
"""Deterministic negative controls for repository_policy.py."""

import copy
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import repository_policy as rp  # noqa: E402


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def main():
    policy = json.loads(rp.POLICY_PATH.read_text(encoding="utf-8"))
    require(policy["version"] == 1, "repository policy version must be explicit")
    label_names = [label["name"] for label in policy["labels"]]
    require(len(label_names) == len(set(label_names)), "canonical label names must be unique")
    require(all(label["description"].strip() for label in policy["labels"]), "canonical labels require descriptions")

    redacted_fields = rp.repository_redacted_fields(policy)
    expected_redacted_fields = [
        "allow_squash_merge",
        "allow_merge_commit",
        "allow_rebase_merge",
        "allow_auto_merge",
        "delete_branch_on_merge",
        "allow_update_branch",
    ]
    require(
        redacted_fields == expected_redacted_fields,
        "read-only repository redaction contract must remain explicit and ordered",
    )
    require(
        all(field in policy["repository"] for field in redacted_fields),
        "manual repository redactions must reference repository policy keys",
    )

    manual = policy["manual_live_assertions"]["ruleset_bypass_actors"]
    require(manual["ruleset"] == policy["ruleset"]["name"], "manual bypass assertion must target the governed ruleset")
    require(manual["expected"] == [], "no-bypass assertion must remain explicit and fail closed at the privileged exit gate")

    expected_repo = {
        "full_name": "o/r",
        "default_branch": "main",
        "private": True,
        "allow_squash_merge": True,
        "allow_merge_commit": False,
        "allow_rebase_merge": False,
        "allow_auto_merge": False,
        "delete_branch_on_merge": True,
        "allow_update_branch": True,
    }
    require(
        rp.validate_repository(dict(expected_repo), expected_repo, redacted_fields) == [],
        "healthy privileged repository fixture must pass",
    )

    read_only_repo = {
        key: value for key, value in expected_repo.items() if key not in redacted_fields
    }
    require(
        rp.validate_repository(read_only_repo, expected_repo, redacted_fields) == [],
        "exactly declared privileged repository fields may be redacted in read-only mode",
    )

    for required_field in ("full_name", "default_branch", "private"):
        missing_read_visible = dict(read_only_repo)
        missing_read_visible.pop(required_field)
        require(
            rp.validate_repository(missing_read_visible, expected_repo, redacted_fields),
            f"missing read-visible repository field {required_field} must fail",
        )

    visible_bad_privileged = dict(read_only_repo)
    visible_bad_privileged["allow_merge_commit"] = True
    require(
        rp.validate_repository(visible_bad_privileged, expected_repo, redacted_fields),
        "visible wrong privileged repository value must fail even when redaction is allowed",
    )

    incomplete_redaction_contract = redacted_fields[:-1]
    require(
        rp.validate_repository(read_only_repo, expected_repo, incomplete_redaction_contract),
        "undeclared missing repository field must fail",
    )

    bad_policy = copy.deepcopy(policy)
    bad_policy["manual_live_assertions"]["repository_redacted_fields"]["fields"].append("not_a_repository_policy_key")
    try:
        rp.repository_redacted_fields(bad_policy)
    except ValueError:
        pass
    else:
        raise AssertionError("unknown manual repository redaction field must fail policy-shape validation")

    duplicate_policy = copy.deepcopy(policy)
    duplicate_policy["manual_live_assertions"]["repository_redacted_fields"]["fields"].append(redacted_fields[0])
    try:
        rp.repository_redacted_fields(duplicate_policy)
    except ValueError:
        pass
    else:
        raise AssertionError("duplicate manual repository redaction field must fail policy-shape validation")

    bad_repo = dict(expected_repo)
    bad_repo["allow_merge_commit"] = True
    require(
        rp.validate_repository(bad_repo, expected_repo, redacted_fields),
        "merge-method drift must fail",
    )

    expected_ruleset = {
        "name": "main protection",
        "target": "branch",
        "source_type": "Repository",
        "source": "o/r",
        "enforcement": "active",
        "include": ["~DEFAULT_BRANCH"],
        "exclude": [],
        "required_rule_types": ["deletion", "non_fast_forward", "required_linear_history", "pull_request", "required_status_checks"],
        "pull_request": {
            "required_approving_review_count": 0,
            "dismiss_stale_reviews_on_push": True,
            "require_code_owner_review": False,
            "require_last_push_approval": False,
            "required_review_thread_resolution": True,
            "require_extra_approval_for_unattributed_changes": True,
            "allowed_merge_methods": ["squash"],
        },
        "required_status_checks": {"strict_required_status_checks_policy": True, "do_not_enforce_on_create": False, "integration_id": 15368},
    }
    good_summary = [{
        "id": 1,
        "name": "main protection",
        "target": "branch",
        "source_type": "Repository",
        "source": "o/r",
        "enforcement": "active",
    }]
    require(rp.validate_ruleset_collection(good_summary, expected_ruleset) == [], "healthy ruleset collection must pass")
    extra_summary = copy.deepcopy(good_summary)
    extra_summary.append({"id": 2, "name": "unexpected", "target": "branch", "source_type": "Repository", "source": "o/r", "enforcement": "active"})
    require(rp.validate_ruleset_collection(extra_summary, expected_ruleset), "unexpected repository ruleset must fail")

    good_ruleset = {
        "name": "main protection",
        "target": "branch",
        "source_type": "Repository",
        "source": "o/r",
        "enforcement": "active",
        "conditions": {"ref_name": {"include": ["~DEFAULT_BRANCH"], "exclude": []}},
        "rules": [
            {"type": "deletion"},
            {"type": "non_fast_forward"},
            {"type": "required_linear_history"},
            {"type": "pull_request", "parameters": copy.deepcopy(expected_ruleset["pull_request"])},
            {"type": "required_status_checks", "parameters": {"strict_required_status_checks_policy": True, "do_not_enforce_on_create": False, "required_status_checks": [{"context": "Build", "integration_id": 15368}, {"context": "Test", "integration_id": 15368}]}},
        ],
    }
    require(rp.validate_ruleset(good_ruleset, expected_ruleset, ["Build", "Test"], []) == [], "read-only ruleset fixture with redacted bypass actors must pass")

    exposed_bypass = copy.deepcopy(good_ruleset)
    exposed_bypass["bypass_actors"] = [{"actor_type": "RepositoryRole", "actor_id": 5}]
    require(rp.validate_ruleset(exposed_bypass, expected_ruleset, ["Build", "Test"], []), "visible bypass drift must fail")
    exposed_empty = copy.deepcopy(good_ruleset)
    exposed_empty["bypass_actors"] = []
    require(rp.validate_ruleset(exposed_empty, expected_ruleset, ["Build", "Test"], []) == [], "privileged no-bypass fixture must pass")
    missing = copy.deepcopy(good_ruleset)
    missing["rules"][-1]["parameters"]["required_status_checks"] = [{"context": "Build", "integration_id": 15368}]
    require(rp.validate_ruleset(missing, expected_ruleset, ["Build", "Test"], []), "missing required context must fail")
    spoofed = copy.deepcopy(good_ruleset)
    spoofed["rules"][-1]["parameters"]["required_status_checks"][0]["integration_id"] = 1
    require(rp.validate_ruleset(spoofed, expected_ruleset, ["Build", "Test"], []), "required-check integration drift must fail")
    extra = copy.deepcopy(good_ruleset)
    extra["rules"].append({"type": "required_signatures"})
    require(rp.validate_ruleset(extra, expected_ruleset, ["Build", "Test"], []), "unexpected ruleset rule must fail")
    excluded = copy.deepcopy(good_ruleset)
    excluded["conditions"]["ref_name"]["exclude"] = ["refs/heads/main"]
    require(rp.validate_ruleset(excluded, expected_ruleset, ["Build", "Test"], []), "ruleset exclusion drift must fail")

    wanted = [{"name": "area:ci", "color": "5319e7", "description": "CI"}]
    require(rp.validate_labels([{"name": "area:ci", "color": "5319E7", "description": "CI"}], wanted) == [], "healthy label fixture must pass")
    require(rp.validate_labels([], wanted), "missing canonical label must fail")
    require(rp.validate_labels([{"name": "area:ci", "color": "ffffff", "description": "CI"}], wanted), "label metadata drift must fail")

    print("repository policy negative controls OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
