package com.algorist.erdmaid.integration

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.StringSelectionRef
import com.intellij.driver.sdk.ui.components.elements.JTreeUiComponent
import com.intellij.driver.sdk.ui.components.elements.actionButton
import com.intellij.driver.sdk.ui.components.elements.isDialogOpened
import com.intellij.driver.sdk.ui.components.elements.list
import com.intellij.driver.sdk.ui.components.elements.popup
import com.intellij.driver.sdk.ui.components.elements.popups
import com.intellij.driver.sdk.ui.components.elements.textField
import com.intellij.driver.sdk.ui.components.elements.tree
import com.intellij.driver.sdk.ui.components.common.databaseToolWindow
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.ultimate.dataSourcesAndDriversDialog
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.junit5.hyphenateWithClass
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.report.ErrorReporterToCI
import com.intellij.ide.starter.runner.CurrentTestMethod
import com.intellij.ide.starter.runner.Starter
import org.h2.Driver as H2Driver
import org.h2.tools.Server
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.datatransfer.DataFlavor
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.DriverManager
import java.util.UUID
import kotlin.io.path.Path as pathOf
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Remote("com.intellij.openapi.ide.CopyPasteManager")
private interface IdeClipboard {
    fun getInstance(): IdeClipboard
    fun getContents(flavor: DataFlavor): String?
    fun setContents(content: StringSelectionRef)
}

private fun Driver.copyThroughIde(text: String) {
    utility(IdeClipboard::class).getInstance()
        .setContents(new(StringSelectionRef::class, text))
}

private fun Driver.clipboardText(): String? =
    utility(IdeClipboard::class).getInstance().getContents(DataFlavor.stringFlavor)

/**
 * Real-process release acceptance slice.
 *
 * The fixture deliberately includes an unrelated UNIQUE index on ERDMAID_ORDERS.NOTE. A successful export
 * therefore proves the #238 fix through the actual Database Tools metadata path while the visible
 * SQL identifiers and popup gesture simultaneously cover the #234 readability and #237 eligibility
 * regressions. Unit tests still own exhaustive semantic truth tables; this test owns the real IDE
 * process, Database tool-window selection, popup action and IDE clipboard boundary.
 */
class LiveIdeSmokeTest {
    private val pluginPath = pathOf(System.getProperty("path.to.build.plugin")).toAbsolutePath()

    @Test
    fun `IDEA 2026_3 exports selected H2 tables through the real Database popup`(
        @TempDir projectDirectory: Path,
    ) {
        Files.writeString(
            projectDirectory.resolve("README.md"),
            "Disposable project for erdMaid Starter Database Tools E2E.\n",
        )
        assertTrue(
            Files.isRegularFile(pluginPath) && pluginPath.fileName.toString().endsWith(".zip"),
            "Starter E2E must install the exact buildPlugin ZIP, got: $pluginPath",
        )

        H2Fixture().use { fixture ->
            fixture.createSchema()

            val context = Starter.newContext(
                CurrentTestMethod.hyphenateWithClass(),
                TestCase(
                    IdeInfo.IdeaUltimate,
                    LocalProjectInfo(projectDirectory, isReusable = false),
                ).withVersion(IDE_RELEASE),
            ).apply {
                System.getenv("LICENSE_KEY")
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::setLicense)
                PluginConfigurator(this).installPluginFromPath(pluginPath)
            }
            writeH2DatabaseToolsDriverFixture(context.paths.configDir, fixture.driverJar)

            val result = context
                .runIdeWithDriver()
                .useDriverAndCloseIde {
                    waitForIndicators(5.minutes)
                    createH2DataSourceThroughDatabaseTools(fixture.jdbcUrl)

                    val firstSentinel = "ERDMAID_E2E_SENTINEL_FIRST"
                    copyThroughIde(firstSentinel)
                    invokeExportThroughDatabasePopup()
                    val first = waitForClipboardDocument(firstSentinel)
                    assertReadableUnrelatedUniqueExport(first)

                    val secondSentinel = "ERDMAID_E2E_SENTINEL_REPEAT"
                    copyThroughIde(secondSentinel)
                    invokeExportThroughDatabasePopup()
                    val second = waitForClipboardDocument(secondSentinel)
                    assertEquals(
                        first,
                        second,
                        "unchanged Database Tools selection must export byte-identical Mermaid text",
                    )
                }

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

    private fun Driver.createH2DataSourceThroughDatabaseTools(jdbcUrl: String) {
        ideFrame {
            invokeAction("ActivateDatabaseToolWindow", now = false)
            databaseToolWindow {
                actionButton {
                    and(byClass("ActionButton"), byAccessibleName("New"))
                }.performAction()
            }

            val addList = ui.popup().list()
            addList.waitOneText(
                "Data Source",
                message = "Database Tools New menu must expose Data Source",
                timeout = 30.seconds,
            ).click()
            waitFor("Database Tools datasource type submenu", timeout = 30.seconds) {
                ui.popups().list().size >= 2
            }
            ui.popups().list().last().list().apply {
                setFocus()
                keyboard {
                    typeText("H2")
                    enter()
                }
            }

            dataSourcesAndDriversDialog {
                button {
                    and(
                        byClass("ComboBoxButton"),
                        contains(byAccessibleName("H2")),
                    )
                }.click()
                waitFor("pinned erdMaid H2 driver popup", timeout = 30.seconds) {
                    ui.popups().list().lastOrNull()?.hasText(H2_DRIVER_NAME) == true
                }
                ui.popups().list().last().waitOneText(
                    H2_DRIVER_NAME,
                    message = "pinned H2 E2E driver must be selectable",
                    timeout = 30.seconds,
                ).click()
                textField("//div[contains(@visible_text, 'jdbc:h2:')]").text = jdbcUrl
                textField(
                    "//div[@accessiblename='User:' and @class='JBTextField']",
                ).text = "sa"
                okButton.click()
            }
        }

        waitFor("Data Sources and Drivers dialog is closed", timeout = 30.seconds) {
            !ui.isDialogOpened("//div[@title='Data Sources and Drivers']")
        }

        ideFrame {
            invokeAction("ActivateDatabaseToolWindow", now = false)
            databaseToolWindow {
                waitFor("DatabaseView.Refresh is accepted", timeout = 30.seconds) {
                    runCatching {
                        driver.invokeAction(
                            "DatabaseView.Refresh",
                            now = false,
                            component = component,
                        )
                        true
                    }.getOrDefault(false)
                }
            }
        }
        waitForIndicators(5.minutes)
    }

    private fun Driver.invokeExportThroughDatabasePopup() {
        lateinit var databaseTree: JTreeUiComponent
        ideFrame {
            invokeAction("ActivateDatabaseToolWindow", now = false)
            databaseToolWindow {
                databaseTree = tree()
            }
        }

        waitFor("H2 datasource appears in Database tree", timeout = 2.minutes) {
            databaseTree.collectExpandedPaths()
                .any { path -> path.path.last().contains(H2_DRIVER_NAME, ignoreCase = true) }
        }

        databaseTree.expandAll(2.minutes)
        waitFor(
            message = "ERDMAID_ORDERS and ERDMAID_USERS are visible in the synchronized H2 Database tree",
            timeout = 2.minutes,
        ) {
            val visible = databaseTree.collectExpandedPaths().map { it.path.last().uppercase() }.toSet()
            ORDERS_TABLE in visible && USERS_TABLE in visible
        }

        databaseTree.selectRows { visibleRows ->
            visibleRows.filter { row ->
                row.equals(ORDERS_TABLE, ignoreCase = true) ||
                    row.equals(USERS_TABLE, ignoreCase = true)
            }
        }
        val selected = databaseTree.collectSelectedPaths()
        assertEquals(2, selected.size, "real Database tree must select exactly two table nodes")
        assertEquals(
            setOf(ORDERS_TABLE, USERS_TABLE),
            selected.map { it.path.last().uppercase() }.toSet(),
            "real Database tree must own exactly the two intended table selections",
        )

        // Use the shipping popup registration, not direct action invocation. Right-clicking one
        // already-selected row must preserve the multi-selection used to build AnActionEvent.
        databaseTree.rightClickRow { it.equals(ORDERS_TABLE, ignoreCase = true) }
        assertEquals(
            setOf(ORDERS_TABLE, USERS_TABLE),
            databaseTree.collectSelectedPaths().map { it.path.last().uppercase() }.toSet(),
            "opening the Database popup must preserve the selected table pair",
        )
        ui.popup().list().waitOneText(
            EXPORT_ACTION_TEXT,
            message = "erdMaid action must be visible and enabled for selected database tables",
            timeout = 30.seconds,
        ).click()
    }

    private fun Driver.waitForClipboardDocument(sentinel: String): String {
        waitFor(
            message = "erdMaid publishes a complete Mermaid document to the IDE clipboard",
            timeout = 2.minutes,
        ) {
            clipboardText()?.let { it != sentinel && it.startsWith("erDiagram\n") } == true
        }
        return checkNotNull(clipboardText())
    }

    private fun assertReadableUnrelatedUniqueExport(document: String) {
        assertTrue(document.startsWith("erDiagram\n"))
        assertEquals(
            1,
            Regex("""\["ERDMAID_ORDERS"]""").findAll(document).count(),
            "ERDMAID_ORDERS must be rendered exactly once",
        )
        assertEquals(
            1,
            Regex("""\["ERDMAID_USERS"]""").findAll(document).count(),
            "ERDMAID_USERS must be rendered exactly once",
        )
        assertTrue("USER_ID" in document, "readable FK column name must survive real Database Tools capture")
        assertTrue("NOTE" in document, "unrelated UNIQUE(NOTE) column must remain readable")
        assertTrue(
            "FK_ERDMAID_ORDERS_USERS" in document,
            "physical H2 FK must survive capture and Mermaid serialization",
        )
        assertTrue(
            "%% FK: physical" in document,
            "real physical relation annotation must be present",
        )
        assertTrue(
            " ||..o{ " in document,
            "unrelated UNIQUE(NOTE) must not collapse the USER_ID FK to one-to-one",
        )
        assertFalse(
            Regex("""\bc_[A-Za-z0-9_]""").containsMatchIn(document),
            "column identifiers must not regress to opaque c_ tokens",
        )
        assertFalse(
            Regex("""\bt_[A-Za-z0-9_]""").containsMatchIn(document),
            "type identifiers must not regress to opaque t_ tokens",
        )
    }

    private fun writeH2DatabaseToolsDriverFixture(configDir: Path, sourceJar: Path) {
        val driverDir = Files.createDirectories(configDir.resolve("jdbc-drivers"))
        val driverJar = driverDir.resolve(sourceJar.fileName)
        Files.copy(sourceJar, driverJar, StandardCopyOption.REPLACE_EXISTING)

        val optionsDir = Files.createDirectories(configDir.resolve("options"))
        Files.writeString(
            optionsDir.resolve("databaseDrivers.xml"),
            """
            <application>
              <component name="LocalDatabaseDriverManager" version="201">
                <driver
                    id="erdmaid-h2-2.5.252"
                    name="$H2_DRIVER_NAME"
                    dialect="H2"
                    driver-class="org.h2.Driver">
                  <artifact use="false" />
                  <option name="auto-sync" value="true" />
                  <option name="schema-control" value="AUTOMATIC" />
                  <library>
                    <url>${driverJar.toUri().toASCIIString()}</url>
                  </library>
                </driver>
              </component>
            </application>
            """.trimIndent(),
        )
    }

    private class H2Fixture : AutoCloseable {
        private val databaseName = "erdmaid_e2e_" + UUID.randomUUID().toString().replace("-", "")
        private val tcpPort = ServerSocket(0).use { it.localPort }
        private val server = Server.createTcpServer(
            "-tcpPort",
            tcpPort.toString(),
            "-tcpDaemon",
            "-ifNotExists",
        ).start()
        private val keeper = DriverManager.getConnection(
            "jdbc:h2:mem:$databaseName;DB_CLOSE_DELAY=-1",
            "sa",
            "",
        )

        val jdbcUrl: String =
            "jdbc:h2:tcp://127.0.0.1:$tcpPort/mem:$databaseName;DB_CLOSE_DELAY=-1"
        val driverJar: Path =
            Path.of(H2Driver::class.java.protectionDomain.codeSource.location.toURI())

        fun createSchema() {
            keeper.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE ERDMAID_USERS (
                      ID BIGINT PRIMARY KEY,
                      EMAIL VARCHAR(255) NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE ERDMAID_ORDERS (
                      ID BIGINT PRIMARY KEY,
                      USER_ID BIGINT NOT NULL,
                      NOTE VARCHAR(255),
                      CONSTRAINT FK_ERDMAID_ORDERS_USERS
                        FOREIGN KEY (USER_ID) REFERENCES ERDMAID_USERS(ID)
                    )
                    """.trimIndent(),
                )
                statement.execute("COMMENT ON COLUMN ERDMAID_ORDERS.NOTE IS 'human readable'")
                // Regression #238: this is intentionally unrelated to the FK tuple USER_ID.
                statement.execute("CREATE UNIQUE INDEX UX_ERDMAID_ORDERS_NOTE ON ERDMAID_ORDERS(NOTE)")
            }
        }

        override fun close() {
            runCatching { keeper.close() }
            runCatching { server.stop() }
        }
    }

    private companion object {
        const val IDE_RELEASE = "2026.3"
        const val H2_DRIVER_NAME = "erdMaid H2 2.5.252"
        const val EXPORT_ACTION_TEXT = "Export as Mermaid ERD (erdMaid)"
        const val USERS_TABLE = "ERDMAID_USERS"
        const val ORDERS_TABLE = "ERDMAID_ORDERS"
    }
}
