#!/usr/bin/env python3
"""Adversarial negative controls for scripts/gradle_check_gate.py."""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gradle_check_gate as gate  # noqa: E402

# Literal representative excerpts from the two 2026-09-06 post-merge failures.
# Keep these fixtures independent of production signature constants so
# classifier drift cannot silently rewrite its own test oracle.
TEST_KNOWN_LOG = """
Unable to read descriptor [plugin.xml] from [/home/runner/.gradle/caches/transforms/example/transformed/ideaIU-2025.2.6/plugins/DatabaseTools/lib/database-plugin.jar]
java.nio.file.ClosedFileSystemException
    at com.jetbrains.plugin.structure.fs.FsHandlerFileSystemProvider.checkAccess(FsHandlerFileSystemProvider.kt:103)
Following 1 plugins could not be created: plugins/DatabaseTools
> Could not resolve all dependencies for configuration ':intellijPlatformTestClasspath'.
   > Could not find bundled plugin with ID: 'com.intellij.database'. See https://jb.gg/ij-plugin-dependencies.
""".strip()

VERIFY_KNOWN_LOG = """
Unable to read descriptor [plugin.xml] from [/home/runner/.gradle/caches/transforms/example/transformed/ideaIU-2025.2.6/plugins/DatabaseTools/lib/database-plugin.jar]
java.nio.file.ClosedFileSystemException
    at com.jetbrains.plugin.structure.fs.FsHandlerFileSystemProvider.checkAccess(FsHandlerFileSystemProvider.kt:103)
Following 1 plugins could not be created: plugins/DatabaseTools
Could not determine the dependencies of task ':compileJava'.
> Could not resolve all dependencies for configuration ':compileClasspath'.
   > Could not find bundled plugin with ID: 'com.intellij.database'. See https://jb.gg/ij-plugin-dependencies.
""".strip()


class FakeRun:
    def __init__(self, results: list[tuple[int, str]]):
        self.results = list(results)
        self.calls: list[tuple[str, ...]] = []

    def __call__(self, command: tuple[str, ...]) -> tuple[int, str]:
        if len(self.calls) >= len(self.results):
            raise AssertionError("gate performed an unexpected extra retry")
        self.calls.append(command)
        return self.results[len(self.calls) - 1]


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def exercise_spec(mode: str, known_log: str, other_log: str) -> None:
    spec = gate.VALIDATIONS[mode]
    retry_command = gate.isolated_retry_command(spec.command)
    require(gate.is_known_upstream_closed_fs_failure(known_log, spec), f"{mode} literal oracle must match")
    require(
        not gate.is_known_upstream_closed_fs_failure(other_log, spec),
        f"{mode} must reject the other validation mode's failure",
    )

    require(retry_command != spec.command, f"{mode} retry must be isolated from the first command")
    require(retry_command[0] == spec.command[0], f"{mode} retry must use the same Gradle wrapper")
    require(retry_command[1] == "--no-daemon", f"{mode} retry must force a single-use Gradle daemon")
    require(retry_command[2:] == spec.command[1:], f"{mode} retry must preserve the requested Gradle task")

    for signature in spec.required_signatures:
        require(signature in known_log, f"{mode} signature {signature!r} drifted away from literal oracle")
        partial = known_log.replace(signature, "<deliberately removed signature>")
        require(
            not gate.is_known_upstream_closed_fs_failure(partial, spec),
            f"{mode} fixture missing {signature!r} must not match",
        )

    ordinary_failure = "There were failing tests. See the report at build/reports/tests/test/index.html"
    ordinary = FakeRun([(7, ordinary_failure)])
    require(gate.execute(spec, ordinary) == 7, f"{mode} ordinary failure must propagate")
    require(ordinary.calls == [spec.command], f"{mode} ordinary failure must not retry")

    recovered = FakeRun([(1, known_log), (0, "BUILD SUCCESSFUL")])
    require(gate.execute(spec, recovered) == 0, f"{mode} exact upstream flake may recover")
    require(
        recovered.calls == [spec.command, retry_command],
        f"{mode} may retry exactly once and retry must be isolated",
    )

    retry_failed = FakeRun([(1, known_log), (9, ordinary_failure)])
    require(gate.execute(spec, retry_failed) == 9, f"{mode} retry failure must propagate")
    require(
        retry_failed.calls == [spec.command, retry_command],
        f"{mode} failed retry must stop after the isolated attempt",
    )

    repeated_flake = FakeRun([(1, known_log), (1, known_log)])
    require(gate.execute(spec, repeated_flake) == 1, f"{mode} repeated flake remains failure")
    require(
        repeated_flake.calls == [spec.command, retry_command],
        f"{mode} must never receive a third attempt",
    )


def main() -> int:
    require(set(gate.VALIDATIONS) == {"check", "verifyPlugin"}, "validation mode allowlist drifted")

    clean = FakeRun([(0, "BUILD SUCCESSFUL")])
    check_spec = gate.VALIDATIONS["check"]
    require(gate.execute(check_spec, clean) == 0, "successful validation must pass")
    require(clean.calls == [check_spec.command], "successful validation must run exactly once")

    exercise_spec("check", TEST_KNOWN_LOG, VERIFY_KNOWN_LOG)
    exercise_spec("verifyPlugin", VERIFY_KNOWN_LOG, TEST_KNOWN_LOG)

    java_plugin = TEST_KNOWN_LOG.replace("com.intellij.database", "com.intellij.java")
    require(
        not gate.is_known_upstream_closed_fs_failure(java_plugin, check_spec),
        "another bundled plugin must not match",
    )

    verifier_finding = """
Plugin verification failed:
Compatibility problems: 1
Deprecated API usages: 2
""".strip()
    verify_spec = gate.VALIDATIONS["verifyPlugin"]
    require(
        not gate.is_known_upstream_closed_fs_failure(verifier_finding, verify_spec),
        "real plugin-verifier findings must not be retried",
    )

    require(gate.main([]) == 2, "missing mode must fail before Gradle execution")
    require(gate.main(["verify"]) == 2, "unknown mode must fail before Gradle execution")
    require(gate.main(["check", "extra"]) == 2, "extra arguments must fail before Gradle execution")

    print("Gradle validation gate negative controls OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
