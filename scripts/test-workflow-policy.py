#!/usr/bin/env python3
"""Negative fixtures proving the erdMaid workflow policy fails closed."""

import json
import os
import shutil
import subprocess
import sys
import tempfile


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


def main():
    baseline = run(ROOT)
    if baseline.returncode != 0:
        print(baseline.stdout + baseline.stderr, file=sys.stderr)
        return 1

    expect_failure(
        "renamed required job",
        lambda root: replace(
            os.path.join(root, ".github/workflows/build.yml"),
            "    name: Build\n",
            "    name: Build renamed\n",
        ),
        "does not match job name",
    )
    expect_failure(
        "missing required job",
        lambda root: replace(
            os.path.join(root, ".github/merge-gate-policy.json"),
            '"job": "verify"',
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
        "mutable action ref",
        lambda root: replace(
            os.path.join(root, ".github/workflows/build.yml"),
            "actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1",
            "actions/checkout@v7",
        ),
        "mutable action ref",
    )
    print("all workflow-policy fixtures passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
