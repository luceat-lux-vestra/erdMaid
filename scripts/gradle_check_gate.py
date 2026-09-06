#!/usr/bin/env python3
"""Run an authoritative Gradle validation with one fail-closed upstream-flake retry.

JetBrains IJ Platform Gradle Plugin 2.18.1 can intermittently lose the bundled
DatabaseTools plugin while building the IDE layout index because a cached jar
FileSystem is closed during descriptor/XInclude resolution. See JetBrains issue
#2192 and MP-8217. A generic retry would hide real regressions, so each allowed
validation mode retries only when every command-specific signature is present.

The retry uses --no-daemon only to isolate it from the Gradle daemon that just
observed the known failure. This does not claim to fix the upstream zipfs bug;
a second failure still fails the gate and there is never a third attempt.
"""

from __future__ import annotations

import subprocess
import sys
from collections.abc import Callable
from dataclasses import dataclass


@dataclass(frozen=True)
class ValidationSpec:
    command: tuple[str, ...]
    required_signatures: tuple[str, ...]


COMMON_UPSTREAM_SIGNATURES = (
    "java.nio.file.ClosedFileSystemException",
    "plugins/DatabaseTools/lib/database-plugin.jar",
    "Following 1 plugins could not be created: plugins/DatabaseTools",
    "Could not find bundled plugin with ID: 'com.intellij.database'",
)

VALIDATIONS = {
    "check": ValidationSpec(
        command=("./gradlew", "check"),
        required_signatures=COMMON_UPSTREAM_SIGNATURES
        + ("Could not resolve all dependencies for configuration ':intellijPlatformTestClasspath'.",),
    ),
    "verifyPlugin": ValidationSpec(
        command=("./gradlew", "verifyPlugin"),
        required_signatures=COMMON_UPSTREAM_SIGNATURES
        + ("Could not resolve all dependencies for configuration ':compileClasspath'.",),
    ),
}

RunCommand = Callable[[tuple[str, ...]], tuple[int, str]]


def is_known_upstream_closed_fs_failure(output: str, spec: ValidationSpec) -> bool:
    return all(signature in output for signature in spec.required_signatures)


def isolated_retry_command(command: tuple[str, ...]) -> tuple[str, ...]:
    return (command[0], "--no-daemon", *command[1:])


def run_gradle(command: tuple[str, ...]) -> tuple[int, str]:
    process = subprocess.Popen(
        command,
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


def execute(spec: ValidationSpec, run_command: RunCommand = run_gradle) -> int:
    first_status, first_output = run_command(spec.command)
    if first_status == 0:
        return 0
    if not is_known_upstream_closed_fs_failure(first_output, spec):
        return first_status

    retry_command = isolated_retry_command(spec.command)
    command_display = " ".join(spec.command)
    print(
        "::warning::Matched the exact known JetBrains ClosedFileSystemException "
        f"signature for {command_display}; retrying once with --no-daemon."
    )
    second_status, _ = run_command(retry_command)
    return second_status


def main(argv: list[str] | None = None) -> int:
    args = sys.argv[1:] if argv is None else argv
    if len(args) != 1 or args[0] not in VALIDATIONS:
        allowed = ", ".join(VALIDATIONS)
        print(f"usage: gradle_check_gate.py <{allowed}>", file=sys.stderr)
        return 2
    return execute(VALIDATIONS[args[0]])


if __name__ == "__main__":
    raise SystemExit(main())
