package com.algorist.erdmaid.integration

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.arePluginsInitialized
import com.intellij.driver.sdk.isPluginLoaded
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
import com.intellij.ide.starter.runner.startIdeWithoutProject
import com.intellij.ide.starter.report.ErrorReporterToCI
import com.intellij.ide.starter.runner.CurrentTestMethod
import com.intellij.ide.starter.runner.Starter
import org.h2.Driver as H2Driver
import org.h2.tools.Server
import org.sqlite.JDBC as SQLiteDriver
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

@Remote(
    "com.intellij.platform.trialPromotion.common.TrialStateService",
    plugin = "com.intellij/intellij.platform.trialPromotion.common",
)
private interface TrialStateServiceRemote {
    fun getTrialAvailable(): TrialAvailabilityRemote
}

@Remote("kotlinx.coroutines.flow.StateFlow")
private interface TrialAvailabilityRemote {
    fun getValue(): Boolean?
}

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
    fun `IDEA 2026_3 exports real H2 and SQLite Database selections through the popup`(
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

        H2Fixture().use { h2 ->
            h2.createSchema()
            val sqlite = SQLiteFixture(projectDirectory.resolve("erdmaid-e2e.sqlite"))
            sqlite.createSchema()

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
            ensureUltimateActivation(context, projectDirectory)
            writeDatabaseToolsDriverFixture(
                configDir = context.paths.configDir,
                h2SourceJar = h2.driverJar,
                sqliteSourceJar = sqlite.driverJar,
            )

            val result = context
                .runIdeWithDriver()
                .useDriverAndCloseIde {
                    waitForIndicators(5.minutes)

                    createDataSourceThroughDatabaseTools(
                        dataSourceType = "H2",
                        driverName = H2_DRIVER_NAME,
                        jdbcUrlPrefix = "jdbc:h2:",
                        jdbcUrl = h2.jdbcUrl,
                        user = "sa",
                    )
                    val h2First = exportUnrelatedUniqueScenario(
                        dataSourceName = H2_DRIVER_NAME,
                        ordersTable = H2_ORDERS_TABLE,
                        usersTable = H2_USERS_TABLE,
                        expectedFkName = H2_FK_NAME,
                        sentinel = "ERDMAID_E2E_H2_FIRST",
                    )
                    val h2Second = exportUnrelatedUniqueScenario(
                        dataSourceName = H2_DRIVER_NAME,
                        ordersTable = H2_ORDERS_TABLE,
                        usersTable = H2_USERS_TABLE,
                        expectedFkName = H2_FK_NAME,
                        sentinel = "ERDMAID_E2E_H2_REPEAT",
                    )
                    assertEquals(
                        h2First,
                        h2Second,
                        "unchanged H2 Database Tools selection must export byte-identical Mermaid text",
                    )

                    createDataSourceThroughDatabaseTools(
                        dataSourceType = "SQLite",
                        driverName = SQLITE_DRIVER_NAME,
                        jdbcUrlPrefix = "jdbc:sqlite:",
                        jdbcUrl = sqlite.jdbcUrl,
                    )
                    val sqliteFirst = exportUnrelatedUniqueScenario(
                        dataSourceName = SQLITE_DRIVER_NAME,
                        ordersTable = SQLITE_ORDERS_TABLE,
                        usersTable = SQLITE_USERS_TABLE,
                        expectedFkName = null,
                        sentinel = "ERDMAID_E2E_SQLITE_FIRST",
                    )
                    val sqliteSecond = exportUnrelatedUniqueScenario(
                        dataSourceName = SQLITE_DRIVER_NAME,
                        ordersTable = SQLITE_ORDERS_TABLE,
                        usersTable = SQLITE_USERS_TABLE,
                        expectedFkName = null,
                        sentinel = "ERDMAID_E2E_SQLITE_REPEAT",
                    )
                    assertEquals(
                        sqliteFirst,
                        sqliteSecond,
                        "unchanged SQLite Database Tools selection must export byte-identical Mermaid text",
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

    private fun ensureUltimateActivation(
        context: com.intellij.ide.starter.ide.IDETestContext,
        projectDirectory: Path,
    ) {
        if (!System.getenv("LICENSE_KEY").isNullOrBlank()) {
            return
        }

        val seedContext = Starter.newContext(
            CurrentTestMethod.hyphenateWithClass() + "-activation",
            TestCase(
                IdeInfo.IdeaUltimate,
                LocalProjectInfo(projectDirectory, isReusable = false),
            ).withVersion(IDE_RELEASE),
        )
        seedContext
            .runIdeWithDriver(commandLine = ::startIdeWithoutProject, launchName = "activation")
            .useDriverAndCloseIde {
                waitFor("application plugins initialized", timeout = 2.minutes) {
                    arePluginsInitialized()
                }
                if (!isPluginLoaded("com.intellij.modules.ultimate")) {
                    waitFor("IDEA Ultimate trial is available", timeout = 2.minutes) {
                        service(TrialStateServiceRemote::class).getTrialAvailable().getValue() == true
                    }
                    invokeAction("StartTrial", now = false)
                }
                waitFor("IDEA Ultimate activation completed", timeout = 2.minutes) {
                    isPluginLoaded("com.intellij.modules.ultimate") && arePluginsInitialized()
                }
            }

        val seedKey = seedContext.paths.configDir.resolve("idea.key")
        check(Files.isRegularFile(seedKey)) {
            "IDEA Ultimate activation must persist idea.key before Database Tools E2E"
        }
        Files.createDirectories(context.paths.configDir)
        Files.copy(
            seedKey,
            context.paths.configDir.resolve("idea.key"),
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private fun Driver.createDataSourceThroughDatabaseTools(
        dataSourceType: String,
        driverName: String,
        jdbcUrlPrefix: String,
        jdbcUrl: String,
        user: String? = null,
    ) {
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
                    typeText(dataSourceType)
                    enter()
                }
            }

            dataSourcesAndDriversDialog {
                button {
                    and(
                        byClass("ComboBoxButton"),
                        contains(byAccessibleName(dataSourceType)),
                    )
                }.click()
                waitFor("pinned erdMaid $dataSourceType driver popup", timeout = 30.seconds) {
                    ui.popups().list().lastOrNull()?.hasText(driverName) == true
                }
                ui.popups().list().last().waitOneText(
                    driverName,
                    message = "pinned $dataSourceType E2E driver must be selectable",
                    timeout = 30.seconds,
                ).click()
                textField("//div[contains(@visible_text, '$jdbcUrlPrefix')]").text = jdbcUrl
                if (user != null) {
                    textField(
                        "//div[@accessiblename='User:' and @class='JBTextField']",
                    ).text = user
                }
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

    private fun Driver.exportUnrelatedUniqueScenario(
        dataSourceName: String,
        ordersTable: String,
        usersTable: String,
        expectedFkName: String?,
        sentinel: String,
    ): String {
        copyThroughIde(sentinel)
        invokeExportThroughDatabasePopup(dataSourceName, ordersTable, usersTable)
        return waitForClipboardDocument(sentinel).also { document ->
            assertReadableUnrelatedUniqueExport(
                document = document,
                ordersTable = ordersTable,
                usersTable = usersTable,
                expectedFkName = expectedFkName,
                dataSourceName = dataSourceName,
            )
        }
    }

    private fun Driver.invokeExportThroughDatabasePopup(
        dataSourceName: String,
        ordersTable: String,
        usersTable: String,
    ) {
        lateinit var databaseTree: JTreeUiComponent
        ideFrame {
            invokeAction("ActivateDatabaseToolWindow", now = false)
            databaseToolWindow {
                databaseTree = tree()
            }
        }

        waitFor("$dataSourceName datasource appears in Database tree", timeout = 2.minutes) {
            databaseTree.collectExpandedPaths()
                .any { path -> path.path.last().contains(dataSourceName, ignoreCase = true) }
        }

        databaseTree.expandAll(2.minutes)
        waitFor(
            message = "$ordersTable and $usersTable are visible in the synchronized Database tree",
            timeout = 2.minutes,
        ) {
            val visible = databaseTree.collectExpandedPaths().map { it.path.last().uppercase() }.toSet()
            ordersTable in visible && usersTable in visible
        }

        databaseTree.selectRows { visibleRows ->
            visibleRows.filter { row ->
                row.equals(ordersTable, ignoreCase = true) ||
                    row.equals(usersTable, ignoreCase = true)
            }
        }
        val selected = databaseTree.collectSelectedPaths()
        assertEquals(2, selected.size, "real Database tree must select exactly two table nodes")
        assertEquals(
            setOf(ordersTable, usersTable),
            selected.map { it.path.last().uppercase() }.toSet(),
            "real Database tree must own exactly the intended table pair",
        )

        // Use the shipping popup registration, not direct action invocation. Right-clicking one
        // already-selected row must preserve the multi-selection used to build AnActionEvent.
        databaseTree.rightClickRow { it.equals(ordersTable, ignoreCase = true) }
        assertEquals(
            setOf(ordersTable, usersTable),
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

    private fun assertReadableUnrelatedUniqueExport(
        document: String,
        ordersTable: String,
        usersTable: String,
        expectedFkName: String?,
        dataSourceName: String,
    ) {
        assertTrue(document.startsWith("erDiagram\n"))
        assertEquals(
            1,
            document.lineSequence().count { "[\"$ordersTable\"]" in it },
            "$ordersTable must be rendered exactly once",
        )
        assertEquals(
            1,
            document.lineSequence().count { "[\"$usersTable\"]" in it },
            "$usersTable must be rendered exactly once",
        )
        assertTrue("USER_ID" in document, "readable FK column name must survive $dataSourceName capture")
        assertTrue("NOTE" in document, "unrelated UNIQUE(NOTE) column must remain readable")
        if (expectedFkName != null) {
            assertTrue(
                expectedFkName in document,
                "named physical FK must survive $dataSourceName capture and Mermaid serialization",
            )
        }
        assertTrue(
            "%% FK: physical" in document,
            "real physical relation annotation must be present for $dataSourceName",
        )
        assertTrue(
            " ||..o{ " in document,
            "unrelated UNIQUE(NOTE) must not collapse the USER_ID FK to one-to-one for $dataSourceName",
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

    private fun writeDatabaseToolsDriverFixture(
        configDir: Path,
        h2SourceJar: Path,
        sqliteSourceJar: Path,
    ) {
        val driverDir = Files.createDirectories(configDir.resolve("jdbc-drivers"))
        val h2DriverJar = driverDir.resolve(h2SourceJar.fileName)
        val sqliteDriverJar = driverDir.resolve(sqliteSourceJar.fileName)
        Files.copy(h2SourceJar, h2DriverJar, StandardCopyOption.REPLACE_EXISTING)
        Files.copy(sqliteSourceJar, sqliteDriverJar, StandardCopyOption.REPLACE_EXISTING)

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
                    <url>${h2DriverJar.toUri().toASCIIString()}</url>
                  </library>
                </driver>
                <driver
                    id="erdmaid-sqlite-3.54.1.0"
                    name="$SQLITE_DRIVER_NAME"
                    dialect="SQLite"
                    driver-class="org.sqlite.JDBC">
                  <artifact use="false" />
                  <option name="auto-sync" value="true" />
                  <library>
                    <url>${sqliteDriverJar.toUri().toASCIIString()}</url>
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
                    CREATE TABLE $H2_USERS_TABLE (
                      ID BIGINT PRIMARY KEY,
                      EMAIL VARCHAR(255) NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE $H2_ORDERS_TABLE (
                      ID BIGINT PRIMARY KEY,
                      USER_ID BIGINT NOT NULL,
                      NOTE VARCHAR(255),
                      CONSTRAINT $H2_FK_NAME
                        FOREIGN KEY (USER_ID) REFERENCES $H2_USERS_TABLE(ID)
                    )
                    """.trimIndent(),
                )
                statement.execute("COMMENT ON COLUMN $H2_ORDERS_TABLE.NOTE IS 'human readable'")
                statement.execute(
                    "CREATE UNIQUE INDEX UX_ERDMAID_H2_ORDERS_NOTE ON $H2_ORDERS_TABLE(NOTE)"
                )
            }
        }

        override fun close() {
            runCatching { keeper.close() }
            runCatching { server.stop() }
        }
    }

    private class SQLiteFixture(private val databasePath: Path) {
        val jdbcUrl: String = "jdbc:sqlite:${databasePath.toAbsolutePath()}"
        val driverJar: Path =
            Path.of(SQLiteDriver::class.java.protectionDomain.codeSource.location.toURI())

        fun createSchema() {
            Class.forName("org.sqlite.JDBC")
            DriverManager.getConnection(jdbcUrl).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("PRAGMA foreign_keys = ON")
                    statement.execute(
                        """
                        CREATE TABLE $SQLITE_USERS_TABLE (
                          ID INTEGER PRIMARY KEY,
                          EMAIL TEXT NOT NULL
                        )
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        CREATE TABLE $SQLITE_ORDERS_TABLE (
                          ID INTEGER PRIMARY KEY,
                          USER_ID INTEGER NOT NULL,
                          NOTE TEXT,
                          CONSTRAINT FK_ERDMAID_SQLITE_ORDERS_USERS
                            FOREIGN KEY (USER_ID) REFERENCES $SQLITE_USERS_TABLE(ID)
                        )
                        """.trimIndent(),
                    )
                    // Exact #238 reproduction shape: unrelated unique index on NOTE.
                    statement.execute(
                        "CREATE UNIQUE INDEX UX_ERDMAID_SQLITE_ORDERS_NOTE ON $SQLITE_ORDERS_TABLE(NOTE)"
                    )
                }
            }
        }
    }

    private companion object {
        const val IDE_RELEASE = "2026.3"
        const val H2_DRIVER_NAME = "erdMaid H2 2.5.252"
        const val SQLITE_DRIVER_NAME = "erdMaid SQLite 3.54.1.0"
        const val EXPORT_ACTION_TEXT = "Export as Mermaid ERD (erdMaid)"

        const val H2_USERS_TABLE = "ERDMAID_H2_USERS"
        const val H2_ORDERS_TABLE = "ERDMAID_H2_ORDERS"
        const val H2_FK_NAME = "FK_ERDMAID_H2_ORDERS_USERS"

        const val SQLITE_USERS_TABLE = "ERDMAID_SQLITE_USERS"
        const val SQLITE_ORDERS_TABLE = "ERDMAID_SQLITE_ORDERS"
    }
}
