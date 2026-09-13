package com.algorist.erdmaid.integration

import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.junit5.hyphenateWithClass
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.NoProject
import com.intellij.ide.starter.report.ErrorReporterToCI
import com.intellij.ide.starter.runner.CurrentTestMethod
import com.intellij.ide.starter.runner.Starter
import com.intellij.tools.ide.starter.product.idea.ultimate.IdeaUltimate
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.io.path.Path

/**
 * Minimal live-process acceptance foundation for #101.
 *
 * When executed, this proves that the exact built erdMaid plugin can be installed
 * into a real IntelliJ IDEA Ultimate 2026.2.2 process, reach an idle state, and
 * terminate without Starter-collected IDE errors or freezes. Database Tool Window
 * interaction and clipboard publication remain separate proof obligations and must
 * not be inferred from this smoke test.
 */
class LiveIdeSmokeTest {
    private val pluginPath = Path(System.getProperty("path.to.build.plugin"))

    @Test
    fun `exact plugin starts in maintained IDEA process without collected IDE errors`() {
        val testCase = TestCase(IdeInfo.IdeaUltimate, NoProject).withVersion("2026.2.2")

        val result = Starter.newContext(CurrentTestMethod.hyphenateWithClass(), testCase)
            .apply {
                System.getenv("LICENSE_KEY")
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::setLicense)
                PluginConfigurator(this).installPluginFromPath(pluginPath)
            }
            .runIdeWithDriver()
            .useDriverAndCloseIde { }

        assertNull(
            result.failureError,
            "Starter reported an IDE process failure: ${result.failureError}",
        )

        val ideErrors = ErrorReporterToCI.collectErrors(result.runContext.logsDir)
        assertTrue(
            ideErrors.isEmpty(),
            buildString {
                appendLine("IDE process produced errors/freezes:")
                ideErrors.forEach { error ->
                    appendLine("[${error.type}] ${error.messageText}")
                    if (error.stackTraceContent.isNotBlank()) {
                        appendLine(error.stackTraceContent)
                    }
                }
            },
        )
    }
}
