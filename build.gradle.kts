import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.18.1"
    id("org.jetbrains.changelog") version "2.5.0"
}

val buildTimestampVersion: String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yy.MM.dd.HHmmss"))
val resolvedPluginVersion = providers.gradleProperty("buildVersion").orElse(buildTimestampVersion)
version = resolvedPluginVersion.get()

// JetBrains Starter 2026.2 splits product descriptors into dedicated artifacts.
// Pin the Starter framework and IDEA product descriptor to the exact baseline
// build instead of LATEST-EAP-SNAPSHOT so integration-test compilation remains
// reproducible and aligned with the project's minimum supported 2026.2 host.
val starterBuild = "262.8665.337"

sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

configurations.getByName("integrationTestImplementation") {
    extendsFrom(configurations.getByName("testImplementation"))
}

// Dependencies are managed with Gradle version catalog - read more: https://docs.gradle.org/current/userguide/version_catalogs.html
dependencies {
    testImplementation("junit:junit:4.13.2")

    add("integrationTestImplementation", "org.junit.jupiter:junit-jupiter:6.1.3")
    add("integrationTestImplementation", "org.kodein.di:kodein-di-jvm:7.33.0")
    add("integrationTestImplementation", "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0")
    add(
        "integrationTestImplementation",
        "com.jetbrains.intellij.tools:ide-starter-product-idea-ultimate:$starterBuild",
    )

    // Security-align only the process-level Starter/E2E tooling graph. These are not plugin
    // runtime dependencies; remove the constraints when JetBrains' Starter graph carries
    // equivalent-or-newer fixed versions natively.
    add("integrationTestImplementation", platform("io.netty:netty-bom:4.2.18.Final"))
    constraints {
        add("integrationTestImplementation", "org.bouncycastle:bcprov-jdk18on:1.86") {
            because("Starter tooling currently resolves a security-affected 1.84")
        }
        add("integrationTestImplementation", "org.bouncycastle:bcpkix-jdk18on:1.86") {
            because("keep Bouncy Castle Starter tooling modules version-aligned")
        }
        add("integrationTestImplementation", "org.bouncycastle:bcutil-jdk18on:1.86") {
            because("keep Bouncy Castle Starter tooling modules version-aligned")
        }
        add("integrationTestImplementation", "at.yawk.lz4:lz4-java:1.11.1") {
            because("1.11.1 fixes the native XXHash range-validation vulnerability")
        }
    }

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        // Run compilation and the IntelliJ test framework against the earliest
        // stable 2026.2 patch release. This is the minimum supported build line,
        // while newer 2026.2 hosts are checked independently by Plugin Verifier.
        // Keeping tests on the baseline also avoids coupling unit-test startup to
        // unrelated commercial-plugin startup changes in later IDEA patch builds.
        intellijIdea("2026.2.0.1")

        bundledPlugins("com.intellij.database")

        testFramework(TestFrameworkType.Platform)
        testFramework(
            TestFrameworkType.Starter,
            starterBuild,
            configurationName = "integrationTestImplementation",
        )
    }
}

intellijPlatformTesting.testIdeUi.register("integrationTest") {
    task {
        val integrationTestSourceSet = sourceSets.getByName("integrationTest")
        testClassesDirs = integrationTestSourceSet.output.classesDirs
        classpath = integrationTestSourceSet.runtimeClasspath
        jvmArgs("--add-opens=java.base/sun.nio.fs=ALL-UNNAMED")
        systemProperty(
            "path.to.build.plugin",
            tasks.prepareSandbox.get().pluginDirectory.get().asFile,
        )
        dependsOn(tasks.prepareSandbox)
        useJUnitPlatform()
    }
}

// Keep the ordinary required Test gate headless, but require the live-process
// test sources to compile so Starter/Driver API drift cannot silently rot.
tasks.named("check") {
    dependsOn("compileIntegrationTestKotlin")
}

// Configure IntelliJ Platform Gradle Plugin - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
intellijPlatform {
    pluginConfiguration {
        // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"

            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        val changelog = project.changelog // local variable for configuration cache compatibility
        // Get the latest available change notes from the changelog file
        changeNotes = resolvedPluginVersion.map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }
    }

    // IJPGP-owned verifier targets are declared explicitly and independently
    // from the compile/test baseline. This lets the test suite exercise the
    // minimum supported 262 release while compatibility verification exercises
    // the latest supported IDEA patch release.
    //
    // DataGrip is intentionally NOT declared here yet. IJPGP 2.18.1 identifies
    // the DataGrip IntelliJPlatformType as `DB`, while JetBrains' product release
    // feed uses `DG`; native DataGrip resolution is fixed upstream after 2.18.1
    // but is not available in a stable IJPGP release yet. The same required
    // `Verify plugin` CI job verifies a separately pinned DataGrip 2026.2 release
    // via scripts/datagrip_verifier.py.
    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdea, "2026.2.2")
        }
    }
}

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
    versionPrefix = ""
}

tasks {
    // Apache-2.0 object-form distribution must carry the license text. Keep one
    // canonical root LICENSE and copy it into the plugin JAR at build time so
    // Marketplace/local-install ZIPs cannot drift from repository licensing.
    processResources {
        from(rootProject.file("LICENSE")) {
            into("META-INF")
        }
    }

    publishPlugin {
        dependsOn(patchChangelog)
    }
}