#!/usr/bin/env python3
"""Deterministic negative controls for repository_policy.py."""

import copy
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import repository_policy as rp  # noqa: E402


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def main():
    expected_repo = {
        "full_name": "o/r",
        "default_branch": "main",
        "private": True,
        "allow_squash_merge": True,
        "allow_merge_commit": False,
        "allow_rebase_merge": False,
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
        "enforcement": "active",
        "include": ["~DEFAULT_BRANCH"],
        "bypass_actors": [],
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
        "required_status_checks": {"strict_required_status_checks_policy": True},
    }
    good_ruleset = {
        "name": "main protection",
        "target": "branch",
        "enforcement": "active",
        "conditions": {"ref_name": {"include": ["~DEFAULT_BRANCH"], "exclude": []}},
        "bypass_actors": [],
        "rules": [
            {"type": "deletion"},
            {"type": "non_fast_forward"},
            {"type": "required_linear_history"},
            {"type": "pull_request", "parameters": copy.deepcopy(expected_ruleset["pull_request"])},
            {"type": "required_status_checks", "parameters": {"strict_required_status_checks_policy": True, "required_status_checks": [{"context": "Build"}, {"context": "Test"}]}},
        ],
    }
    require(rp.validate_ruleset(good_ruleset, expected_ruleset, ["Build", "Test"]) == [], "healthy ruleset fixture must pass")
    bypass = copy.deepcopy(good_ruleset)
    bypass["bypass_actors"] = [{"actor_type": "RepositoryRole", "actor_id": 5}]
    require(rp.validate_ruleset(bypass, expected_ruleset, ["Build", "Test"]), "bypass drift must fail")
    missing = copy.deepcopy(good_ruleset)
    missing["rules"][-1]["parameters"]["required_status_checks"] = [{"context": "Build"}]
    require(rp.validate_ruleset(missing, expected_ruleset, ["Build", "Test"]), "missing required context must fail")

    wanted = [{"name": "area:ci", "color": "5319e7", "description": "CI"}]
    require(rp.validate_labels([{"name": "area:ci", "color": "5319E7", "description": "CI"}], wanted) == [], "healthy label fixture must pass")
    require(rp.validate_labels([], wanted), "missing canonical label must fail")
    require(rp.validate_labels([{"name": "area:ci", "color": "ffffff", "description": "CI"}], wanted), "label metadata drift must fail")

    print("repository policy negative controls OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
