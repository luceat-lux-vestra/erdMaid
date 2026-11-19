#!/usr/bin/env python3
"""Negative fixtures proving the erdMaid workflow policy fails closed."""

import os
import re
import shutil
import subprocess
import sys
import tempfile
import textwrap

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
CHECKER = os.path.join(HERE, "check-workflow-policy.py")


def run(root):
    return subprocess.run(
        [sys.executable, CHECKER, "--root", root],
        capture_output=True, text=True, check=False)


def copy_tree(destination):
    shutil.copytree(os.path.join(ROOT, ".github"), os.path.join(destination, ".github"))


def expect_failure(name, mutate, token):
    with tempfile.TemporaryDirectory() as temp:
        root = os.path.join(temp, "repo")
        os.makedirs(root)
        copy_tree(root)
        mutate(root)
        result = run(root)
        output = result.stdout + result.stderr
        if result.returncode == 0 or token not in output:
            raise AssertionError(f"{name}: expected {token!r}, got {result.returncode}: {output}")
        print(f"ok: {name}")


def replace(path, old, new):
    with open(path, encoding="utf-8") as handle:
        text = handle.read()
    if old not in text:
        raise AssertionError(f"fixture anchor not found: {path}: {old!r}")
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(text.replace(old, new, 1))

def expect_success(name, mutate):
    with tempfile.TemporaryDirectory() as temp:
        root = os.path.join(temp, "repo")
        os.makedirs(root)
        copy_tree(root)
        mutate(root)
        result = run(root)
        output = result.stdout + result.stderr
        if result.returncode != 0:
            raise AssertionError(f"{name}: expected success, got {result.returncode}: {output}")
        print(f"ok: {name}")


def write_ephemeral_fixture(root, owner="issue #201", retire="remove when bounded research concludes"):
    path = os.path.join(root, ".github", "workflows", "research-lifecycle-fixture.yml")
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(
            f"# lifecycle-owner: {owner}\n"
            f"# lifecycle-retire: {retire}\n"
            "name: Research Lifecycle Fixture\n\n"
            "on:\n"
            "  workflow_dispatch:\n\n"
            "permissions:\n"
            "  contents: read\n\n"
            "concurrency:\n"
            "  group: research-lifecycle-fixture\n"
            "  cancel-in-progress: true\n\n"
            "jobs:\n"
            "  probe:\n"
            "    runs-on: ubuntu-latest\n"
            "    timeout-minutes: 5\n"
            "    steps:\n"
            "      - run: echo fixture\n"
        )



def check_gradle_seed_input_paths(root):
    """Only inputs that can affect the protected-main Gradle cache trigger a seed."""
    seed_path = os.path.join(root, ".github", "workflows", "gradle-cache-seed.yml")
    with open(seed_path, encoding="utf-8") as handle:
        workflow = handle.read()
    header = workflow.split("\njobs:", 1)[0]
    assert "    branches: [ main ]" in header
    assert "  workflow_dispatch:" in header
    paths = re.findall(r"(?m)^      - '([^']+)'$", header)
    expected = [
        ".github/workflows/gradle-cache-seed.yml",
        "build.gradle.kts", "settings.gradle.kts",
        "gradle.properties", "gradle/**",
    ]
    if paths != expected:
        raise AssertionError(f"cache seed input path drift: {paths!r} != {expected!r}")
    for forbidden in ("gradlew", "src/**", "docs/**", ".github/workflows/build.yml", "**"):
        if forbidden in paths:
            raise AssertionError(f"cache seed should not run on unrelated inputs: {forbidden}")
    print("ok: dependency-only Gradle cache seed trigger")



def check_ready_for_review_fast_evidence_fallback(root):
    """Exercise the actual workflow shell, not a duplicated policy predicate."""
    path = os.path.join(root, ".github", "workflows", "build.yml")
    with open(path, encoding="utf-8") as handle:
        workflow = handle.read()
    scripts = re.findall(
        r"(?ms)^      - name: Reuse exact-SHA Fast evidence\n"
        r".*?^        run: \|\n(.*?)"
        r"(?=^      - name: Fetch Sources)",
        workflow,
    )
    if len(scripts) != 2:
        raise AssertionError(f"expected Build and Test evidence steps; found {len(scripts)}")

    fake_gh = r'''#!/usr/bin/env python3
import json
import os
import sys

args = " ".join(sys.argv[1:])
if "/contents/.github/workflows/build.yml?" in args:
    print("same-workflow-blob")
elif "/actions/workflows/build.yml/runs?" in args:
    print(json.dumps({"workflow_runs": [{
        "id": 1234,
        "head_sha": os.environ["EXPECTED_SHA"],
        "event": "pull_request",
        "status": "completed",
        "conclusion": os.environ["MOCK_RUN_CONCLUSION"]
    }]}))
elif "/actions/runs/1234/jobs?" in args:
    print(json.dumps({"jobs": [{
        "name": os.environ["EXPECTED_JOB"],
        "status": "completed",
        "conclusion": os.environ.get("MOCK_JOB_CONCLUSION", "success")
    }]}))
else:
    print(f"unexpected mock gh command: {args}", file=sys.stderr)
    sys.exit(2)
'''

    for name, source in zip(("Build", "Test"), scripts):
        body = textwrap.dedent(source)
        with tempfile.TemporaryDirectory() as temp:
            gh = os.path.join(temp, "gh")
            with open(gh, "w", encoding="utf-8") as handle:
                handle.write(fake_gh)
            os.chmod(gh, 0o755)
            for conclusion, job_conclusion, expected_rc, expected_output in (
                ("cancelled", "success", 0, "reuse=false"),
                ("success", "success", 0, "reuse=true"),
                ("failure", "success", 1, ""),
                ("success", "failure", 1, ""),
            ):
                output_path = os.path.join(temp, "result")
                with open(output_path, "w", encoding="utf-8"):
                    pass
                env = os.environ.copy()
                env.update({
                    "PATH": temp + os.pathsep + env.get("PATH", ""),
                    "GH_TOKEN": "mock",
                    "BASE_SHA": "b" * 40,
                    "EXPECTED_SHA": "a" * 40,
                    "EXPECTED_JOB": name,
                    "SAME_REPOSITORY": "true",
                    "GITHUB_REPOSITORY": "luceat-lux-vestra/erdMaid",
                    "GITHUB_RUN_ID": "9999",
                    "GITHUB_OUTPUT": output_path,
                    "MOCK_RUN_CONCLUSION": conclusion,
                    "MOCK_JOB_CONCLUSION": job_conclusion,
                })
                result = subprocess.run(
                    ["bash", "-c", body],
                    env=env,
                    capture_output=True,
                    text=True,
                    check=False,
                    timeout=15,
                )
                with open(output_path, encoding="utf-8") as handle:
                    outputs = handle.read()
                if (result.returncode == 0) != (expected_rc == 0):
                    raise AssertionError(
                        f"{name}/{conclusion}/{job_conclusion}: rc={result.returncode}\n"
                        + result.stdout + result.stderr
                    )
                if expected_output and expected_output not in outputs:
                    raise AssertionError(
                        f"{name}/{conclusion}/{job_conclusion}: expected {expected_output!r}, "
                        f"got {outputs!r}"
                    )
                if expected_rc != 0 and "reuse=true" in outputs:
                    raise AssertionError(
                        f"{name}/{conclusion}/{job_conclusion}: failed evidence was reused"
                    )
        print(f"ok: {name} cancelled-run fallback, success reuse, failure fail-closed")

def main():
    check_ready_for_review_fast_evidence_fallback(ROOT)
    check_gradle_seed_input_paths(ROOT)
    baseline = run(ROOT)
    if baseline.returncode != 0:
        print(baseline.stdout + baseline.stderr, file=sys.stderr)
        return 1

    expect_success(
        "ephemeral workflow with lifecycle metadata",
        lambda root: write_ephemeral_fixture(root),
    )
    expect_failure(
        "ephemeral workflow missing lifecycle owner",
        lambda root: write_ephemeral_fixture(root, owner=""),
        "lacks non-empty # lifecycle-owner:",
    )
    expect_failure(
        "ephemeral workflow missing retirement condition",
        lambda root: write_ephemeral_fixture(root, retire=""),
        "lacks non-empty # lifecycle-retire:",
    )

    expect_failure(
        "renamed required job",
        lambda root: replace(
            os.path.join(root, ".github/workflows/build.yml"),
            "    name: Merge Gate\n",
            "    name: Merge Gate renamed\n",
        ),
        "does not match job name",
    )
    expect_failure(
        "missing required job",
        lambda root: replace(
            os.path.join(root, ".github/merge-gate-policy.json"),
            '"job": "mergeGate"',
            '"job": "missing"',
        ),
        "missing job",
    )
    expect_failure(
        "path-filtered required workflow",
        lambda root: replace(
            os.path.join(root, ".github/workflows/build.yml"),
            "  pull_request:\n",
            "  pull_request:\n    paths:\n      - 'src/**'\n",
        ),
        "path-filtered",
    )
    expect_failure(
        "merge gate without always",
        lambda root: replace(
            os.path.join(root, ".github/workflows/build.yml"),
            "    if: ${{ always() }}\n",
            "    if: ${{ github.event_name == 'pull_request' }}\n",
        ),
        "must use exact always()",
    )
    expect_failure(
        "merge gate missing component",
        lambda root: replace(
            os.path.join(root, ".github/workflows/build.yml"),
            "    needs: [ scope, build, test, inspect, verify, staticAnalysis, dependencySubmissionValidation, dependencyReview, hostApiEvidence, starterE2E ]\n",
            "    needs: [ scope, build, test, verify, staticAnalysis, dependencySubmissionValidation, dependencyReview, hostApiEvidence, starterE2E ]\n",
        ),
        "expected needs",
    )
    expect_failure(
        "inspect job missing pinned Qodana action",
        lambda root: replace(
            os.path.join(root, ".github/workflows/build.yml"),
            "        uses: JetBrains/qodana-action@3e8d76ff0e0241618abd808682f426afe84bab3f # v2026.2.2\n",
            "        run: echo qodana-removed\n",
        ),
        "Inspect code contract missing",
    )
    expect_failure(
        "inspect job missing SARIF verifier",
        lambda root: replace(
            os.path.join(root, ".github/workflows/build.yml"),
            "          python3 .github/scripts/check-qodana-sarif.py \\\n",
            "          python3 -c 'print(\"verifier removed\")' \\\n",
        ),
        "Inspect code contract missing",
    )
    expect_failure(
        "merge gate missing inspect result env",
        lambda root: replace(
            os.path.join(root, ".github/workflows/build.yml"),
            "          INSPECT_RESULT: ${{ needs.inspect.result }}\n",
            "",
        ),
        "must expose Inspect code result",
    )
    expect_failure(
        "merge gate missing inspect aggregation",
        lambda root: replace(
            os.path.join(root, ".github/workflows/build.yml"),
            '            "Inspect code=$INSPECT_RESULT" \\\n',
            "",
        ),
        "must aggregate Inspect code result",
    )
    expect_failure(
        "merge gate missing dependency-submission result env",
        lambda root: replace(
            os.path.join(root, ".github/workflows/build.yml"),
            "          DEPENDENCY_SUBMISSION_VALIDATION_RESULT: ${{ needs.dependencySubmissionValidation.result }}\n",
            "",
        ),
        "must expose dependencySubmissionValidation result",
    )
    expect_failure(
        "merge gate does not aggregate dependency-submission result",
        lambda root: replace(
            os.path.join(root, ".github/workflows/build.yml"),
            '            "Dependency submission PR validation=$DEPENDENCY_SUBMISSION_VALIDATION_RESULT" \\\n',
            "",
        ),
        "must aggregate dependencySubmissionValidation result",
    )
    expect_failure(
        "unaudited target trigger",
        lambda root: replace(
            os.path.join(root, ".github/merge-gate-policy.json"),
            '"job": "mergeGate"',
            '"job": "mergeGate",\n      "trigger": "pull_request_target"',
        ),
        "must not use pull_request_target",
    )
    expect_failure(
        "mutable action ref",
        lambda root: replace(
            os.path.join(root, ".github/workflows/build.yml"),
            "actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1",
            "actions/checkout@v7",
        ),
        "mutable action ref",
    )
    expect_failure(
        "non-reusable job without timeout",
        lambda root: replace(
            os.path.join(root, ".github/workflows/build.yml"),
            "    uses: ./.github/workflows/host-api-evidence.yml # zizmor: ignore[self-repository] actionlint 1.7.12 rejects GitHub's newer $/ self-repository syntax.\n",
            "    runs-on: ubuntu-latest\n",
        ),
        "has no timeout-minutes",
    )
    print("all workflow-policy fixtures passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
