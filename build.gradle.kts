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
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
    id("org.jetbrains.changelog") version "2.5.0"
}

val buildTimestampVersion: String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yy.MM.dd.HHmmss"))
val resolvedPluginVersion = providers.gradleProperty("buildVersion").orElse(buildTimestampVersion)
version = resolvedPluginVersion.get()

// Starter/Driver follows the maintained 2026.3 IntelliJ Platform dependency.
// Keep process-level evidence on the same released platform as compile/test and Host API gates.

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
    testImplementation(platform("com.fasterxml.jackson:jackson-bom:2.23.0"))
    testImplementation(platform("tools.jackson:jackson-bom:3.3.0"))
    testImplementation(platform("io.opentelemetry:opentelemetry-bom:1.68.0"))

    add("integrationTestImplementation", "org.junit.jupiter:junit-jupiter:6.1.3")
    // The custom integrationTest source set needs Kotlin stdlib explicitly; do not rely on
    // an unrelated library such as kotlinx-coroutines to pull it in transitively.
    add("integrationTestImplementation", kotlin("stdlib"))
    // Real Database Tools E2E fixture only. The H2 driver is copied into Starter's isolated
    // IDE config and registered as a local Database Tools driver; it is never shipped.
    add("integrationTestImplementation", "com.h2database:h2:2.5.252")

    // Security-align only the process-level Starter/E2E tooling graph. These are not plugin
    // runtime dependencies; remove the constraints when JetBrains' Starter graph carries
    // equivalent-or-newer fixed versions natively.
    add("integrationTestImplementation", platform("io.netty:netty-bom:4.2.20.Final"))
    add("integrationTestImplementation", platform("com.fasterxml.jackson:jackson-bom:2.23.0"))
    add("integrationTestImplementation", platform("tools.jackson:jackson-bom:3.3.0"))
    add("integrationTestImplementation", platform("io.opentelemetry:opentelemetry-bom:1.68.0"))
    constraints {
        add("integrationTestImplementation", "org.bouncycastle:bcprov-jdk18on:1.87") {
            because("Starter tooling currently resolves a security-affected 1.84")
        }
        add("integrationTestImplementation", "org.bouncycastle:bcpkix-jdk18on:1.87") {
            because("keep Bouncy Castle Starter tooling modules version-aligned")
        }
        add("integrationTestImplementation", "org.bouncycastle:bcutil-jdk18on:1.87") {
            because("keep Bouncy Castle Starter tooling modules version-aligned")
        }
        add("integrationTestImplementation", "at.yawk.lz4:lz4-java:1.14.0") {
            because("1.11.3 includes the security fixes released in 1.11.2")
        }
        add("integrationTestImplementation", "org.jsoup:jsoup:1.24.1") {
            because("1.23.2 contains the XmlTreeBuilder resource-consumption fix commit 862ba2f")
        }
    }

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        // Compile and run the IntelliJ unit-test framework on released IDEA 2026.3.
        // The 262 minimum remains an independent binary/Starter compatibility claim,
        // never inferred from this newer SDK or the bundled DatabaseTools API.
        intellijIdea("2026.3")

        bundledPlugins("com.intellij.database")

        testFramework(TestFrameworkType.Platform)
        testFramework(
            TestFrameworkType.Starter,
            configurationName = "integrationTestImplementation",
        )
    }
}

val verifyStarterSecurityGraph = tasks.register("verifyStarterSecurityGraph") {
    group = "verification"
    description = "Fail if the executable Starter/E2E runtime resolves security-stale tooling dependencies."
    // This proof intentionally resolves a live Gradle configuration during task execution.
    // Gradle recommends explicitly opting such tasks out of configuration-cache storage
    // rather than hiding serialization problems with configuration-cache warning mode.
    notCompatibleWithConfigurationCache(
        "Resolves integrationTestRuntimeClasspath at execution time for security evidence",
    )

    doLast {
        val declared = configurations.getByName("integrationTestImplementation")
        fun dependencyVersion(group: String, name: String): String =
            declared.dependencies
                .firstOrNull { it.group == group && it.name == name }
                ?.version
                ?: throw GradleException("Missing declared dependency version for $group:$name")
        fun constraintVersion(group: String, name: String): String =
            declared.dependencyConstraints
                .firstOrNull { it.group == group && it.name == name }
                ?.version
                ?: throw GradleException("Missing declared dependency constraint for $group:$name")

        // Keep dependency declarations as the single source of truth. Dependabot updates
        // those declarations; this evidence task verifies the resolved graph against them
        // instead of duplicating current versions in a second hard-coded map.
        val jackson2Version = dependencyVersion("com.fasterxml.jackson", "jackson-bom")
        val jackson3Version = dependencyVersion("tools.jackson", "jackson-bom")
        val nettyVersion = dependencyVersion("io.netty", "netty-bom")
        val expected = mapOf(
            "org.jsoup:jsoup" to constraintVersion("org.jsoup", "jsoup"),
            "com.fasterxml.jackson.core:jackson-core" to jackson2Version,
            "com.fasterxml.jackson.core:jackson-databind" to jackson2Version,
            "tools.jackson.core:jackson-core" to jackson3Version,
            "tools.jackson.core:jackson-databind" to jackson3Version,
            "io.netty:netty-handler" to nettyVersion,
            "io.netty:netty-codec-compression" to nettyVersion,
            "org.bouncycastle:bcprov-jdk18on" to constraintVersion("org.bouncycastle", "bcprov-jdk18on"),
            "org.bouncycastle:bcpkix-jdk18on" to constraintVersion("org.bouncycastle", "bcpkix-jdk18on"),
            "org.bouncycastle:bcutil-jdk18on" to constraintVersion("org.bouncycastle", "bcutil-jdk18on"),
            "at.yawk.lz4:lz4-java" to constraintVersion("at.yawk.lz4", "lz4-java"),
        )
        val resolved = configurations.getByName("integrationTestRuntimeClasspath")
            .incoming.resolutionResult.allComponents
            .mapNotNull { component ->
                component.moduleVersion?.let { id -> "${id.group}:${id.name}" to id.version }
            }
            .toMap()

        expected.forEach { (module, version) ->
            val actual = resolved[module]
                ?: throw GradleException("Starter security graph is missing expected module $module")
            if (actual != version) {
                throw GradleException(
                    "Starter security graph drift for $module: expected $version, resolved $actual",
                )
            }
        }
    }
}

intellijPlatformTesting.testIdeUi.register("integrationTest") {
    task {
        val integrationTestSourceSet = sourceSets.getByName("integrationTest")
        testClassesDirs = integrationTestSourceSet.output.classesDirs
        classpath = integrationTestSourceSet.runtimeClasspath
        // Starter 263 touches JDK-internal BasicFileAttributesHolder while unpacking the IDE.
        jvmArgs("--add-exports=java.base/sun.nio.fs=ALL-UNNAMED")
        systemProperty(
            "path.to.build.plugin",
            tasks.prepareSandbox.get().pluginDirectory.get().asFile,
        )
        dependsOn(tasks.prepareSandbox, verifyStarterSecurityGraph)
        useJUnitPlatform()
        testLogging {
            events("passed", "failed")
            showStandardStreams = true
        }
    }
}

// Keep the ordinary required Test gate headless, but require the live-process
// test sources to compile so Starter/Driver API drift cannot silently rot.
tasks.named("check") {
    dependsOn("compileIntegrationTestKotlin", verifyStarterSecurityGraph)
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

    // IJPGP-owned verifier targets are declared explicitly and independently.
    // Verify the retained 262 runtime line as well as the new 263 compile baseline;
    // a newer compile SDK does not establish backward binary compatibility.
    //
    // IJPGP 2.20.1 resolves DataGrip 2026.3 via the corrected DG release
    // catalog -> DB platform mapping. Keep the standalone, checksum-verified
    // DataGrip 2026.2.5 gate as independent older-host binary evidence.
    // Both paths verify the same packaged plugin artifact in the required CI job.
    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdea, "2026.2.2")
            create(IntelliJPlatformType.IntellijIdea, "2026.3")
            create(IntelliJPlatformType.DataGrip, "2026.3")
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = listOf("default")
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
