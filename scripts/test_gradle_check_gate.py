#!/usr/bin/env python3
"""Adversarial negative controls for scripts/gradle_check_gate.py."""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gradle_check_gate as gate  # noqa: E402

# Literal representative excerpt from the 2026-09-06 post-merge failure.
# Keep this fixture independent of KNOWN_UPSTREAM_SIGNATURES so a production
# signature drift cannot silently rewrite its own test oracle.
KNOWN_LOG = """
Unable to read descriptor [plugin.xml] from [/home/runner/.gradle/caches/transforms/example/transformed/ideaIU-2025.2.6/plugins/DatabaseTools/lib/database-plugin.jar]
java.nio.file.ClosedFileSystemException
    at com.jetbrains.plugin.structure.fs.FsHandlerFileSystemProvider.checkAccess(FsHandlerFileSystemProvider.kt:103)
Following 1 plugins could not be created: plugins/DatabaseTools
> Could not resolve all dependencies for configuration ':intellijPlatformTestClasspath'.
   > Could not find bundled plugin with ID: 'com.intellij.database'. See https://jb.gg/ij-plugin-dependencies.
""".strip()


class FakeRun:
    def __init__(self, results: list[tuple[int, str]]):
        self.results = list(results)
        self.calls = 0

    def __call__(self) -> tuple[int, str]:
        if self.calls >= len(self.results):
            raise AssertionError("gate performed an unexpected extra retry")
        result = self.results[self.calls]
        self.calls += 1
        return result


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def main() -> int:
    require(gate.is_known_upstream_closed_fs_failure(KNOWN_LOG), "literal upstream failure fixture must match")

    for signature in gate.KNOWN_UPSTREAM_SIGNATURES:
        require(signature in KNOWN_LOG, f"production signature {signature!r} drifted away from the literal oracle")
        partial = KNOWN_LOG.replace(signature, "<deliberately removed signature>")
        require(
            not gate.is_known_upstream_closed_fs_failure(partial),
            f"fixture missing {signature!r} must not match",
        )

    java_plugin = KNOWN_LOG.replace("com.intellij.database", "com.intellij.java")
    require(not gate.is_known_upstream_closed_fs_failure(java_plugin), "another bundled plugin must not match")
    ordinary_test_failure = "There were failing tests. See the report at build/reports/tests/test/index.html"
    require(not gate.is_known_upstream_closed_fs_failure(ordinary_test_failure), "ordinary test failure must not match")

    clean = FakeRun([(0, "BUILD SUCCESSFUL")])
    require(gate.execute(clean) == 0 and clean.calls == 1, "successful check must run exactly once")

    ordinary = FakeRun([(7, ordinary_test_failure)])
    require(gate.execute(ordinary) == 7 and ordinary.calls == 1, "ordinary failure must propagate without retry")

    recovered = FakeRun([(1, KNOWN_LOG), (0, "BUILD SUCCESSFUL")])
    require(gate.execute(recovered) == 0 and recovered.calls == 2, "exact upstream flake may retry once and recover")

    retry_failed = FakeRun([(1, KNOWN_LOG), (9, ordinary_test_failure)])
    require(gate.execute(retry_failed) == 9 and retry_failed.calls == 2, "retry failure must propagate")

    repeated_flake = FakeRun([(1, KNOWN_LOG), (1, KNOWN_LOG)])
    require(gate.execute(repeated_flake) == 1 and repeated_flake.calls == 2, "known flake must never receive a third attempt")

    print("Gradle check gate negative controls OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
