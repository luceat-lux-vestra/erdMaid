package com.algorist.erdmaid.integration

import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.junit5.hyphenateWithClass
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.NoProject
import com.intellij.ide.starter.runner.CurrentTestMethod
import com.intellij.ide.starter.runner.Starter
import org.junit.jupiter.api.Test
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.minutes

/**
 * Minimal live-process acceptance foundation for #101.
 *
 * This deliberately proves only that an exact built erdMaid plugin can be installed
 * into a real IntelliJ IDEA Ultimate 2026.2.2 process and reach an idle state.
 * Database Tool Window interaction, clipboard publication, and IDE-side exception
 * escalation are separate proof obligations and must not be inferred from this test.
 */
class LiveIdeSmokeTest {
    private val pluginPath = Path(System.getProperty("path.to.build.plugin"))

    @Test
    fun `exact plugin starts in maintained IDEA process`() {
        val testCase = TestCase(IdeProductProvider.IU, NoProject).withVersion("2026.2.2")

        Starter.newContext(CurrentTestMethod.hyphenateWithClass(), testCase)
            .apply {
                System.getenv("LICENSE_KEY")
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::setLicense)
                PluginConfigurator(this).installPluginFromPath(pluginPath)
            }
            .runIdeWithDriver()
            .useDriverAndCloseIde {
                waitForIndicators(5.minutes)
            }
    }
}
