#!/usr/bin/env python3
"""Run the authoritative Gradle check with one fail-closed upstream-flake retry.

JetBrains IJ Platform Gradle Plugin 2.18.1 can intermittently lose a bundled
plugin while building the IDE layout index because a cached jar FileSystem is
closed during descriptor/XInclude resolution. See JetBrains issue #2192 and
MP-8217. A generic retry would hide real regressions, so this gate retries only
when every erdMaid-specific signature from that upstream failure is present.
"""

from __future__ import annotations

import subprocess
import sys
from collections.abc import Callable

GRADLE_CHECK = ("./gradlew", "check")
KNOWN_UPSTREAM_SIGNATURES = (
    "java.nio.file.ClosedFileSystemException",
    "plugins/DatabaseTools/lib/database-plugin.jar",
    "Following 1 plugins could not be created: plugins/DatabaseTools",
    "Could not resolve all dependencies for configuration ':intellijPlatformTestClasspath'.",
    "Could not find bundled plugin with ID: 'com.intellij.database'",
)

RunCheck = Callable[[], tuple[int, str]]


def is_known_upstream_closed_fs_failure(output: str) -> bool:
    return all(signature in output for signature in KNOWN_UPSTREAM_SIGNATURES)


def run_gradle_check() -> tuple[int, str]:
    process = subprocess.Popen(
        GRADLE_CHECK,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        bufsize=1,
    )
    assert process.stdout is not None
    chunks: list[str] = []
    for line in process.stdout:
        print(line, end="", flush=True)
        chunks.append(line)
    return process.wait(), "".join(chunks)


def execute(run_check: RunCheck = run_gradle_check) -> int:
    first_status, first_output = run_check()
    if first_status == 0:
        return 0
    if not is_known_upstream_closed_fs_failure(first_output):
        return first_status

    print(
        "::warning::Matched the exact known JetBrains ClosedFileSystemException "
        "signature while resolving com.intellij.database; retrying ./gradlew check once."
    )
    second_status, _ = run_check()
    return second_status


def main() -> int:
    return execute()


if __name__ == "__main__":
    sys.exit(main())
