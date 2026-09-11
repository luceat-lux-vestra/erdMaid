package com.algorist.erdmaid.integration

import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.ci.CIServer
import com.intellij.ide.starter.ci.NoCIServer
import com.intellij.ide.starter.di.di
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.junit5.hyphenateWithClass
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.NoProject
import com.intellij.ide.starter.runner.CurrentTestMethod
import com.intellij.ide.starter.runner.Starter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.extend
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.minutes

/**
 * Minimal live-process acceptance foundation for #101.
 *
 * This deliberately proves only that an exact built erdMaid plugin can be installed
 * into a real IntelliJ IDEA Ultimate 2026.2.2 process and survive startup without a
 * Starter-observed IDE exception/freeze. Database Tool Window interaction and clipboard
 * publication are separate proof obligations and must not be inferred from this test.
 */
class LiveIdeSmokeTest {
    init {
        di = DI {
            extend(di)
            bindSingleton<CIServer>(overrides = true) {
                object : CIServer by NoCIServer {
                    override fun reportTestFailure(
                        testName: String,
                        message: String,
                        details: String,
                        linkToLogs: String?,
                    ) {
                        fail { "$testName failed in the IDE process: $message\n$details" }
                    }
                }
            }
        }
    }

    private val pluginPath = Path(System.getProperty("path.to.build.plugin"))

    @Test
    fun `exact plugin starts in maintained IDEA process without fatal IDE errors`() {
        val testCase = TestCase(IdeInfo.IdeaUltimate, NoProject).withVersion("2026.2.2")

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
