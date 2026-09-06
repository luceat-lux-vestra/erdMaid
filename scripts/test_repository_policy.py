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
    require(rp.validate_repository(dict(expected_repo), expected_repo) == [], "healthy repository fixture must pass")
    bad_repo = dict(expected_repo)
    bad_repo["allow_merge_commit"] = True
    require(rp.validate_repository(bad_repo, expected_repo), "merge-method drift must fail")

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
