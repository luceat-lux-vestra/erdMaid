package com.algorist.erdmaid.integration

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.client.service
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.expireNotifications
import com.intellij.driver.sdk.getNotifications
import com.intellij.driver.sdk.getPlugin
import com.intellij.driver.sdk.arePluginsInitialized
import com.intellij.driver.sdk.isPluginLoaded
import com.intellij.driver.sdk.ui.StringSelectionRef
import com.intellij.driver.sdk.ui.components.elements.JTreeUiComponent
import com.intellij.driver.sdk.ui.components.elements.actionButton
import com.intellij.driver.sdk.ui.components.elements.button
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
import com.intellij.tools.ide.starter.product.idea.ultimate.IdeaUltimate
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

@Remote("javax.swing.JTree")
private interface DatabaseTreeSelectionRef {
    fun setSelectionRows(rows: IntArray)
}

@Remote("com.intellij.openapi.application.ApplicationInfo")
private interface ApplicationInfoRef {
    fun getFullVersion(): String
    fun getBuild(): BuildNumberRef
}

@Remote("com.intellij.openapi.util.BuildNumber")
private interface BuildNumberRef {
    fun asString(): String
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
    private val expectedPluginVersion = System.getProperty("erdmaid.e2e.expectedPluginVersion")

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
                    assertTrue(
                        isPluginLoaded("com.algorist.erdmaid"),
                        "the exact packaged erdMaid ZIP must be loaded in the IDEA process",
                    )
                    val installedPlugin = checkNotNull(getPlugin("com.algorist.erdmaid")) {
                        "erdMaid plugin descriptor must be visible in the live IDEA process"
                    }
                    assertEquals(
                        expectedPluginVersion,
                        installedPlugin.getVersion(),
                        "live IDEA must load the exact packaged plugin version",
                    )
                    println(
                        "ERDMAID_E2E_PLUGIN id=com.algorist.erdmaid version=${installedPlugin.getVersion()} " +
                            "fixture_revision=$FIXTURE_REVISION",
                    )
                    val applicationInfo = service<ApplicationInfoRef>()
                    assertEquals(
                        IDE_RELEASE,
                        applicationInfo.getFullVersion(),
                        "Starter must launch the exact maintained IDEA release",
                    )
                    println(
                        "ERDMAID_E2E_HOST product=IDEA_ULTIMATE " +
                            "version=${applicationInfo.getFullVersion()} " +
                            "build=${applicationInfo.getBuild().asString()}",
                    )

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
                    assertSelectionOrderInvariantH2Scenario()

                    val richH2 = exportRichH2Scenario()
                    assertRichH2Scenario(richH2)
                    assertSelfReferenceH2Scenario()
                    assertIdentifyingOneToZeroOrOneH2Scenario()
                    assertSelectedSubgraphH2Scenario()
                    assertTablesGroupH2Scenario()
                    assertDuplicateNamesAcrossSchemasH2Scenario()
                    assertQuotedCaseSensitiveH2Scenario()
                    assertViewOnlyActionSuppressed()

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

                    assertRelevantUniqueSQLiteNeverClaimsMany()
                    assertMixedViewSelectionFailsClosed()
                    assertCrossDataSourceSelectionFailsClosed()
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
        // Do not wait for all IDE background indicators here. Database Tools may keep
        // unrelated long-lived background work active in headless CI. The following export
        // helpers wait on the authoritative observable boundary instead: the datasource and
        // exact fixture tables becoming visible in the real Database tree.
    }

    private fun Driver.assertSelectionOrderInvariantH2Scenario() {
        lateinit var databaseTree: JTreeUiComponent
        ideFrame {
            invokeAction("ActivateDatabaseToolWindow", now = false)
            databaseToolWindow {
                databaseTree = tree()
            }
        }
        databaseTree.expandAll(2.minutes)
        val expanded = databaseTree.collectExpandedPaths()
        val orders = expanded.single { path ->
            path.path.last().equals(H2_ORDERS_TABLE, ignoreCase = true) &&
                path.path.any { it.contains(H2_DRIVER_NAME, ignoreCase = true) }
        }
        val users = expanded.single { path ->
            path.path.last().equals(H2_USERS_TABLE, ignoreCase = true) &&
                path.path.any { it.contains(H2_DRIVER_NAME, ignoreCase = true) }
        }

        fun exportWithOrder(rows: IntArray, sentinel: String): String {
            expireNotifications()
            copyThroughIde(sentinel)
            withContext(OnDispatcher.EDT) {
                cast(databaseTree.component, DatabaseTreeSelectionRef::class).setSelectionRows(rows)
            }
            assertEquals(2, databaseTree.collectSelectedPaths().size)
            databaseTree.rightClickRow(orders.row)
            ui.popup().list().waitOneText(
                EXPORT_ACTION_TEXT,
                message = "erdMaid action must remain available for reordered table selection",
                timeout = 30.seconds,
            ).click()
            waitForErdMaidNotification("Mermaid ERD copied to clipboard")
            return waitForClipboardDocument(sentinel)
        }

        val forward = exportWithOrder(
            intArrayOf(users.row, orders.row),
            "ERDMAID_E2E_SELECTION_ORDER_FORWARD",
        )
        val reverse = exportWithOrder(
            intArrayOf(orders.row, users.row),
            "ERDMAID_E2E_SELECTION_ORDER_REVERSE",
        )
        assertEquals(
            forward,
            reverse,
            "canonical Mermaid output must not depend on actual JTree selection order",
        )
    }

    private fun Driver.exportRichH2Scenario(): String =
        exportSelectedTables(
            dataSourceName = H2_DRIVER_NAME,
            selectedTables = setOf(H2_ACCOUNT_TABLE, H2_PURCHASE_ORDER_TABLE),
            rightClickName = H2_PURCHASE_ORDER_TABLE,
            sentinel = "ERDMAID_E2E_H2_RICH",
        )

    private fun assertRichH2Scenario(document: String) {
        assertEquals(
            1,
            document.lineSequence().count { "[\"$H2_ACCOUNT_TABLE\"]" in it },
            "rich H2 parent table must be rendered exactly once",
        )
        assertEquals(
            1,
            document.lineSequence().count { "[\"$H2_PURCHASE_ORDER_TABLE\"]" in it },
            "rich H2 child table must be rendered exactly once",
        )

        assertColumnOrder(
            entityBlock(document, H2_ACCOUNT_TABLE),
            listOf("TENANT_ID", "ACCOUNT_ID", "DISPLAY_NAME", "EXTERNAL_REF", "CREATED_AT"),
        )
        assertColumnOrder(
            entityBlock(document, H2_PURCHASE_ORDER_TABLE),
            listOf(
                "TENANT_ID",
                "ORDER_ID",
                "ACCOUNT_ID",
                "APPROVER_ACCOUNT_ID",
                "AMOUNT",
                "MEMO",
                "CREATED_AT",
            ),
        )
        assertTrue(
            document.contains("Customer account fixture") &&
                document.contains("Purchase order fixture") &&
                document.contains("Order amount") &&
                document.contains("Optional memo"),
            "table and column comments must survive real H2 metadata capture",
        )
        assertTrue(
            Regex("""\S+\s+AMOUNT\b.*Order amount""").containsMatchIn(document),
            "representative DECIMAL type metadata must be rendered with AMOUNT",
        )

        val compositeAnnotation = document.lineSequence().single { line ->
            line.contains("%% FK: physical") &&
                line.contains(".TENANT_ID ->") &&
                line.contains(".ACCOUNT_ID ->")
        }
        assertTrue(
            compositeAnnotation.indexOf(".TENANT_ID ->") < compositeAnnotation.indexOf(".ACCOUNT_ID ->"),
            "composite FK mapping order must remain TENANT_ID then ACCOUNT_ID",
        )
        assertEquals(1, document.lineSequence().count { it.contains("physical:$H2_ACCOUNT_FK") })
        assertEquals(1, document.lineSequence().count { it.contains("physical:$H2_APPROVER_FK") })

        val requiredEdge = document.lineSequence().single { it.contains("physical:$H2_ACCOUNT_FK") }
        val nullableEdge = document.lineSequence().single { it.contains("physical:$H2_APPROVER_FK") }
        assertTrue(
            " ||..o{ " in requiredEdge,
            "non-null composite FK must require exactly one parent and allow many children",
        )
        assertTrue(
            " o|..o{ " in nullableEdge,
            "nullable composite FK must allow zero-or-one parent without inventing uniqueness",
        )
    }

    private fun Driver.assertSelfReferenceH2Scenario() {
        val document = exportSelectedTables(
            dataSourceName = H2_DRIVER_NAME,
            selectedTables = setOf(H2_EMPLOYEE_TABLE),
            sentinel = "ERDMAID_E2E_H2_SELF",
        )
        assertEquals(1, document.lineSequence().count { "[\"$H2_EMPLOYEE_TABLE\"]" in it })
        assertTrue(
            document.lineSequence().any {
                it.contains("%% FK: physical") &&
                    it.contains(".MANAGER_ID ->") &&
                    it.contains(".ID")
            },
            "self-FK mapping must be present",
        )
        val edge = document.lineSequence().single { it.contains("physical:$H2_SELF_FK") }
        assertTrue(
            " o|..o{ " in edge,
            "nullable self-FK must preserve optional parent multiplicity",
        )
    }

    private fun Driver.assertIdentifyingOneToZeroOrOneH2Scenario() {
        val document = exportSelectedTables(
            dataSourceName = H2_DRIVER_NAME,
            selectedTables = setOf(H2_USERS_TABLE, H2_PROFILE_TABLE),
            rightClickName = H2_PROFILE_TABLE,
            sentinel = "ERDMAID_E2E_H2_IDENTIFYING_PROFILE",
        )
        assertEquals(1, document.lineSequence().count { "[\"$H2_PROFILE_TABLE\"]" in it })
        val edge = document.lineSequence().single { it.contains("physical:$H2_PROFILE_FK") }
        assertTrue(
            " ||--o| " in edge,
            "PK-as-FK profile relation must remain identifying one-to-zero-or-one",
        )
        assertTrue(
            document.lineSequence().any {
                it.contains("%% FK: physical") &&
                    it.contains(".USER_ID ->") &&
                    it.contains(".ID")
            },
            "identifying profile FK mapping must survive real Database Tools capture",
        )
    }

    private fun Driver.assertSelectedSubgraphH2Scenario() {
        val document = exportSelectedTables(
            dataSourceName = H2_DRIVER_NAME,
            selectedTables = setOf(H2_PURCHASE_ORDER_TABLE),
            sentinel = "ERDMAID_E2E_H2_SUBGRAPH",
        )
        assertEquals(1, document.lineSequence().count { "[\"$H2_PURCHASE_ORDER_TABLE\"]" in it })
        assertFalse(
            document.lineSequence().any { "[\"$H2_ACCOUNT_TABLE\"]" in it },
            "unselected FK parent must not be auto-included",
        )
        assertFalse(
            document.contains("physical:$H2_ACCOUNT_FK") || document.contains("physical:$H2_APPROVER_FK"),
            "relations whose parent endpoint is outside the selected snapshot must be omitted",
        )
    }

    private fun Driver.assertTablesGroupH2Scenario() {
        expireNotifications()
        val sentinel = "ERDMAID_E2E_H2_TABLES_GROUP"
        copyThroughIde(sentinel)

        lateinit var databaseTree: JTreeUiComponent
        ideFrame {
            invokeAction("ActivateDatabaseToolWindow", now = false)
            databaseToolWindow {
                databaseTree = tree()
            }
        }
        databaseTree.expandAll(2.minutes)

        val groupCandidates = databaseTree.collectExpandedPaths().filter { path ->
            path.path.last().equals("Tables", ignoreCase = true) &&
                path.path.any { it.contains(H2_DRIVER_NAME, ignoreCase = true) } &&
                path.path.any { it.equals("PUBLIC", ignoreCase = true) }
        }
        assertEquals(
            1,
            groupCandidates.size,
            "the H2 datasource must expose one unambiguous non-system Tables group",
        )
        val tablesRow = groupCandidates.single().row
        databaseTree.clickRow(tablesRow)
        databaseTree.rightClickRow(tablesRow)
        ui.popup().list().waitOneText(
            EXPORT_ACTION_TEXT,
            message = "erdMaid action must be enabled for the schema Tables node",
            timeout = 30.seconds,
        ).click()

        waitForErdMaidNotification("Mermaid ERD copied to clipboard")
        val document = waitForClipboardDocument(sentinel)
        for (table in listOf(
            H2_USERS_TABLE,
            H2_ORDERS_TABLE,
            H2_ACCOUNT_TABLE,
            H2_PURCHASE_ORDER_TABLE,
            H2_EMPLOYEE_TABLE,
            H2_PROFILE_TABLE,
            H2_QUOTED_UPPER_TABLE,
            H2_QUOTED_LOWER_TABLE,
        )) {
            assertEquals(
                1,
                document.lineSequence().count { "[\"$table\"]" in it },
                "Tables-group expansion must include physical table $table exactly once",
            )
        }
        assertFalse(
            document.lineSequence().any { "[\"$H2_VIEW_NAME\"]" in it },
            "Tables-group expansion must not silently treat a view as a physical table",
        )
    }

    private fun Driver.assertDuplicateNamesAcrossSchemasH2Scenario() {
        expireNotifications()
        val sentinel = "ERDMAID_E2E_H2_DUPLICATE_SCHEMA"
        copyThroughIde(sentinel)

        lateinit var databaseTree: JTreeUiComponent
        ideFrame {
            invokeAction("ActivateDatabaseToolWindow", now = false)
            databaseToolWindow {
                databaseTree = tree()
            }
        }
        databaseTree.expandAll(2.minutes)
        val expanded = databaseTree.collectExpandedPaths()
        fun exactRow(schema: String, table: String) =
            expanded.single { path ->
                path.path.last().equals(table, ignoreCase = true) &&
                    path.path.any { it.equals(schema, ignoreCase = true) } &&
                    path.path.any { it.contains(H2_DRIVER_NAME, ignoreCase = true) }
            }

        val salesOrders = exactRow(H2_SALES_SCHEMA, "ORDERS")
        val archiveOrders = exactRow(H2_ARCHIVE_SCHEMA, "ORDERS")
        val salesUsers = exactRow(H2_SALES_SCHEMA, "USERS")
        val rows = intArrayOf(salesOrders.row, archiveOrders.row, salesUsers.row)
        withContext(OnDispatcher.EDT) {
            cast(databaseTree.component, DatabaseTreeSelectionRef::class).setSelectionRows(rows)
        }

        val selected = databaseTree.collectSelectedPaths()
        assertEquals(3, selected.size, "multi-schema scenario must select exactly three physical tables")
        assertTrue(
            selected.any { path ->
                path.path.last().equals("ORDERS", ignoreCase = true) &&
                    path.path.any { it.equals(H2_SALES_SCHEMA, ignoreCase = true) }
            },
            "sales.orders must be selected by authoritative tree path",
        )
        assertTrue(
            selected.any { path ->
                path.path.last().equals("ORDERS", ignoreCase = true) &&
                    path.path.any { it.equals(H2_ARCHIVE_SCHEMA, ignoreCase = true) }
            },
            "archive.orders must remain a distinct selected object",
        )

        databaseTree.rightClickRow(salesOrders.row)
        ui.popup().list().waitOneText(
            EXPORT_ACTION_TEXT,
            message = "erdMaid action must support multiple schemas in one datasource",
            timeout = 30.seconds,
        ).click()

        waitForErdMaidNotification("Mermaid ERD copied to clipboard")
        val document = waitForClipboardDocument(sentinel)
        val salesOrdersId = entityIdForQualifiedLabel(document, H2_SALES_SCHEMA, "ORDERS")
        val archiveOrdersId = entityIdForQualifiedLabel(document, H2_ARCHIVE_SCHEMA, "ORDERS")
        val salesUsersId = entityIdForLabel(document, "USERS")
        assertTrue(salesOrdersId != archiveOrdersId, "same-named tables across schemas must not collapse")

        val relation = document.lineSequence().single { it.contains("physical:$H2_SALES_FK") }
        assertTrue(
            relation.contains(salesOrdersId) && relation.contains(salesUsersId),
            "physical FK must bind sales.orders to sales.users",
        )
        assertFalse(
            relation.contains(archiveOrdersId),
            "archive.orders must not be cross-wired into the sales FK",
        )
    }

    private fun Driver.assertQuotedCaseSensitiveH2Scenario() {
        expireNotifications()
        val sentinel = "ERDMAID_E2E_H2_QUOTED_CASE"
        copyThroughIde(sentinel)

        lateinit var databaseTree: JTreeUiComponent
        ideFrame {
            invokeAction("ActivateDatabaseToolWindow", now = false)
            databaseToolWindow {
                databaseTree = tree()
            }
        }
        databaseTree.expandAll(2.minutes)
        val expanded = databaseTree.collectExpandedPaths()
        val upper = expanded.single { path ->
            path.path.last() == H2_QUOTED_UPPER_TABLE &&
                path.path.any { it.contains(H2_DRIVER_NAME, ignoreCase = true) }
        }
        val lower = expanded.single { path ->
            path.path.last() == H2_QUOTED_LOWER_TABLE &&
                path.path.any { it.contains(H2_DRIVER_NAME, ignoreCase = true) }
        }
        withContext(OnDispatcher.EDT) {
            cast(databaseTree.component, DatabaseTreeSelectionRef::class)
                .setSelectionRows(intArrayOf(upper.row, lower.row))
        }
        assertEquals(2, databaseTree.collectSelectedPaths().size)
        databaseTree.rightClickRow(upper.row)
        ui.popup().list().waitOneText(
            EXPORT_ACTION_TEXT,
            message = "erdMaid action must preserve quoted case-sensitive table identities",
            timeout = 30.seconds,
        ).click()

        waitForErdMaidNotification("Mermaid ERD copied to clipboard")
        val document = waitForClipboardDocument(sentinel)
        assertEquals(
            1,
            document.lineSequence().count { "[\"$H2_QUOTED_UPPER_TABLE\"]" in it },
            "quoted upper-case table identity must survive exactly once",
        )
        assertEquals(
            1,
            document.lineSequence().count { "[\"$H2_QUOTED_LOWER_TABLE\"]" in it },
            "quoted lower-case table identity must remain distinct",
        )
        val block = entityBlock(document, H2_QUOTED_UPPER_TABLE)
        assertColumnOrder(block, listOf("ItemID", "itemid"))
    }

    private fun Driver.assertViewOnlyActionSuppressed() {
        val sentinel = "ERDMAID_E2E_VIEW_ONLY"
        copyThroughIde(sentinel)
        lateinit var databaseTree: JTreeUiComponent
        ideFrame {
            invokeAction("ActivateDatabaseToolWindow", now = false)
            databaseToolWindow {
                databaseTree = tree()
            }
        }
        databaseTree.expandAll(2.minutes)
        val view = databaseTree.collectExpandedPaths().single { path ->
            path.path.last().equals(H2_VIEW_NAME, ignoreCase = true) &&
                path.path.any { it.contains(H2_DRIVER_NAME, ignoreCase = true) }
        }
        databaseTree.clickRow(view.row)
        databaseTree.rightClickRow(view.row)
        val popup = ui.popup().list()
        assertFalse(
            popup.hasText(EXPORT_ACTION_TEXT),
            "view-only selection must not expose an enabled erdMaid export action",
        )
        popup.keyboard { escape() }
        assertEquals(sentinel, clipboardText(), "view-only menu inspection must not mutate clipboard")
    }

    private fun entityIdForQualifiedLabel(document: String, schema: String, table: String): String {
        val line = document.lineSequence().single { candidate ->
            candidate.contains("[\"") &&
                candidate.contains(schema) &&
                candidate.contains(table) &&
                candidate.contains("\"] {")
        }
        return line.trimStart().substringBefore("[")
    }

    private fun entityIdForLabel(document: String, table: String): String {
        val line = document.lineSequence().single { candidate ->
            candidate.contains("[\"") &&
                candidate.contains(table) &&
                candidate.contains("\"] {")
        }
        return line.trimStart().substringBefore("[")
    }

    private fun Driver.assertRelevantUniqueSQLiteNeverClaimsMany() {
        expireNotifications()
        val sentinel = "ERDMAID_E2E_SQLITE_RELEVANT_UNIQUE"
        copyThroughIde(sentinel)
        invokeExportThroughDatabasePopup(
            dataSourceName = SQLITE_DRIVER_NAME,
            selectedObjects = setOf(SQLITE_RELEVANT_ORDERS_TABLE, SQLITE_RELEVANT_USERS_TABLE),
            rightClickName = SQLITE_RELEVANT_ORDERS_TABLE,
        )

        waitFor(
            message = "relevant SQLite UNIQUE reaches a bounded terminal erdMaid outcome",
            timeout = 2.minutes,
        ) {
            getNotifications().any { notification ->
                notification.getTitle() == "erdMaid" &&
                    (
                        notification.getContent().contains("Mermaid ERD copied to clipboard") ||
                            notification.getContent().contains("mermaid-cardinality-unavailable")
                    )
            }
        }
        val terminal = getNotifications().single { notification ->
            notification.getTitle() == "erdMaid" &&
                (
                    notification.getContent().contains("Mermaid ERD copied to clipboard") ||
                        notification.getContent().contains("mermaid-cardinality-unavailable")
                )
        }

        if (terminal.getContent().contains("mermaid-cardinality-unavailable")) {
            assertEquals(
                sentinel,
                clipboardText(),
                "unverified relevant UNIQUE evidence must fail closed without publishing",
            )
            return
        }

        val document = waitForClipboardDocument(sentinel)
        println("ERDMAID_E2E_DOCUMENT_BEGIN datasource=$SQLITE_DRIVER_NAME sentinel=$sentinel")
        print(document)
        if (!document.endsWith("\n")) println()
        println("ERDMAID_E2E_DOCUMENT_END datasource=$SQLITE_DRIVER_NAME sentinel=$sentinel")
        val edge = document.lineSequence().single { line ->
            line.contains("physical:") && line.contains("||..o|")
        }
        assertTrue(
            " ||..o| " in edge,
            "proven relevant UNIQUE must cap children at one",
        )
        assertFalse(
            document.contains(" ||..o{ ") || document.contains(" o|..o{ "),
            "relevant UNIQUE must never be generalized to a MANY child multiplicity",
        )
    }

    private fun Driver.assertMixedViewSelectionFailsClosed() {
        expireNotifications()
        val sentinel = "ERDMAID_E2E_H2_MIXED_VIEW"
        copyThroughIde(sentinel)
        val invoked = invokeExportThroughDatabasePopup(
            dataSourceName = H2_DRIVER_NAME,
            selectedObjects = setOf(H2_ORDERS_TABLE, H2_VIEW_NAME),
            rightClickName = H2_ORDERS_TABLE,
            allowSuppressedAction = true,
        )
        if (invoked) {
            waitForErdMaidNotification("selection-mixed-or-unsupported")
        }
        assertEquals(
            sentinel,
            clipboardText(),
            "mixed table/view selection must never publish Mermaid output",
        )
    }

    private fun Driver.assertCrossDataSourceSelectionFailsClosed() {
        assertFailClosedSelection(
            dataSourceName = H2_DRIVER_NAME,
            selectedObjects = setOf(H2_ORDERS_TABLE, SQLITE_USERS_TABLE),
            rightClickName = H2_ORDERS_TABLE,
            sentinel = "ERDMAID_E2E_CROSS_DATASOURCE",
            expectedDiagnostic = "selection-cross-origin",
        )
    }

    private fun Driver.assertFailClosedSelection(
        dataSourceName: String,
        selectedObjects: Set<String>,
        rightClickName: String,
        sentinel: String,
        expectedDiagnostic: String,
    ) {
        expireNotifications()
        copyThroughIde(sentinel)
        invokeExportThroughDatabasePopup(
            dataSourceName = dataSourceName,
            selectedObjects = selectedObjects,
            rightClickName = rightClickName,
        )
        waitForErdMaidNotification(expectedDiagnostic)
        assertEquals(
            sentinel,
            clipboardText(),
            "fail-closed scenario $expectedDiagnostic must preserve the existing clipboard",
        )
    }

    private fun entityBlock(document: String, tableName: String): String {
        val marker = "[\"$tableName\"] {"
        val start = document.indexOf(marker)
        require(start >= 0) { "missing Mermaid entity block for $tableName" }
        val end = document.indexOf("\n    }", start)
        require(end > start) { "unterminated Mermaid entity block for $tableName" }
        return document.substring(start, end)
    }

    private fun assertColumnOrder(block: String, expectedColumns: List<String>) {
        var previous = -1
        for (column in expectedColumns) {
            val position = block.indexOf(column)
            assertTrue(position > previous, "column $column must preserve authoritative source order")
            previous = position
        }
    }

    private fun Driver.exportUnrelatedUniqueScenario(
        dataSourceName: String,
        ordersTable: String,
        usersTable: String,
        expectedFkName: String?,
        sentinel: String,
    ): String {
        expireNotifications()
        copyThroughIde(sentinel)
        invokeExportThroughDatabasePopup(dataSourceName, ordersTable, usersTable)
        waitForErdMaidNotification("Mermaid ERD copied to clipboard")
        return waitForClipboardDocument(sentinel).also { document ->
            println("ERDMAID_E2E_DOCUMENT_BEGIN datasource=$dataSourceName sentinel=$sentinel")
            print(document)
            if (!document.endsWith("\n")) println()
            println("ERDMAID_E2E_DOCUMENT_END datasource=$dataSourceName sentinel=$sentinel")
            assertReadableUnrelatedUniqueExport(
                document = document,
                ordersTable = ordersTable,
                usersTable = usersTable,
                expectedFkName = expectedFkName,
                dataSourceName = dataSourceName,
            )
        }
    }

    private fun Driver.exportSelectedTables(
        dataSourceName: String,
        selectedTables: Set<String>,
        sentinel: String,
        rightClickName: String = selectedTables.first(),
    ): String {
        expireNotifications()
        copyThroughIde(sentinel)
        invokeExportThroughDatabasePopup(
            dataSourceName = dataSourceName,
            selectedObjects = selectedTables,
            rightClickName = rightClickName,
        )
        waitForErdMaidNotification("Mermaid ERD copied to clipboard")
        return waitForClipboardDocument(sentinel).also { document ->
            println("ERDMAID_E2E_DOCUMENT_BEGIN datasource=$dataSourceName sentinel=$sentinel")
            print(document)
            if (!document.endsWith("\n")) println()
            println("ERDMAID_E2E_DOCUMENT_END datasource=$dataSourceName sentinel=$sentinel")
        }
    }

    private fun Driver.invokeExportThroughDatabasePopup(
        dataSourceName: String,
        ordersTable: String,
        usersTable: String,
    ) = invokeExportThroughDatabasePopup(
        dataSourceName = dataSourceName,
        selectedObjects = setOf(ordersTable, usersTable),
        rightClickName = ordersTable,
    )

    private fun Driver.invokeExportThroughDatabasePopup(
        dataSourceName: String,
        selectedObjects: Set<String>,
        rightClickName: String,
        allowSuppressedAction: Boolean = false,
    ): Boolean {
        val expectedNames = selectedObjects.map { it.uppercase() }.toSet()
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
            message = "$selectedObjects are visible in the synchronized Database tree",
            timeout = 2.minutes,
        ) {
            val visible = databaseTree.collectExpandedPaths().map { it.path.last().uppercase() }.toSet()
            expectedNames.all { it in visible }
        }

        databaseTree.selectRows { visibleRows ->
            visibleRows.filter { row -> row.uppercase() in expectedNames }
        }
        val selected = databaseTree.collectSelectedPaths()
        assertEquals(
            selectedObjects.size,
            selected.size,
            "real Database tree must select exactly the intended database objects",
        )
        assertEquals(
            expectedNames,
            selected.map { it.path.last().uppercase() }.toSet(),
            "real Database tree must own exactly the intended selection",
        )

        // Use the shipping popup registration, not direct action invocation. Right-clicking one
        // already-selected row must preserve the multi-selection used to build AnActionEvent.
        databaseTree.rightClickRow { it.equals(rightClickName, ignoreCase = true) }
        assertEquals(
            expectedNames,
            databaseTree.collectSelectedPaths().map { it.path.last().uppercase() }.toSet(),
            "opening the Database popup must preserve the intended selection",
        )
        val popup = ui.popup().list()
        if (!popup.hasText(EXPORT_ACTION_TEXT)) {
            assertTrue(
                allowSuppressedAction,
                "erdMaid action must be visible and enabled for the selected database objects",
            )
            popup.keyboard { escape() }
            return false
        }
        popup.waitOneText(
            EXPORT_ACTION_TEXT,
            message = "erdMaid action must be visible and enabled for the selected database objects",
            timeout = 30.seconds,
        ).click()
        return true
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

    private fun Driver.waitForErdMaidNotification(contentFragment: String) {
        waitFor(
            message = "erdMaid terminal notification contains '$contentFragment'",
            timeout = 2.minutes,
        ) {
            getNotifications().any { notification ->
                notification.getTitle() == "erdMaid" &&
                    notification.getContent().contains(contentFragment)
            }
        }
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
        if (dataSourceName == SQLITE_DRIVER_NAME) {
            assertFalse(
                Regex("""\bTEXT\s*\(\s*0\s*\)""", RegexOption.IGNORE_CASE).containsMatchIn(document),
                "authoritatively absent SQLite TEXT length must not be fabricated as zero",
            )
        }
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
                statement.execute(
                    """
                    CREATE TABLE $H2_ACCOUNT_TABLE (
                      TENANT_ID BIGINT NOT NULL,
                      ACCOUNT_ID BIGINT NOT NULL,
                      DISPLAY_NAME VARCHAR(120) NOT NULL,
                      EXTERNAL_REF VARCHAR(64),
                      CREATED_AT TIMESTAMP NOT NULL,
                      CONSTRAINT PK_ERDMAID_ACCOUNT PRIMARY KEY (TENANT_ID, ACCOUNT_ID),
                      CONSTRAINT UK_ERDMAID_ACCOUNT_EXTERNAL UNIQUE (TENANT_ID, EXTERNAL_REF)
                    )
                    """.trimIndent(),
                )
                statement.execute("COMMENT ON TABLE $H2_ACCOUNT_TABLE IS 'Customer account fixture'")
                statement.execute(
                    "COMMENT ON COLUMN $H2_ACCOUNT_TABLE.DISPLAY_NAME IS 'Display name shown to users'"
                )
                statement.execute(
                    "COMMENT ON COLUMN $H2_ACCOUNT_TABLE.EXTERNAL_REF IS 'Optional external reference'"
                )
                statement.execute(
                    """
                    CREATE TABLE $H2_PURCHASE_ORDER_TABLE (
                      TENANT_ID BIGINT NOT NULL,
                      ORDER_ID BIGINT NOT NULL,
                      ACCOUNT_ID BIGINT NOT NULL,
                      APPROVER_ACCOUNT_ID BIGINT,
                      AMOUNT DECIMAL(18, 2) NOT NULL,
                      MEMO VARCHAR(255),
                      CREATED_AT TIMESTAMP NOT NULL,
                      CONSTRAINT PK_ERDMAID_PURCHASE_ORDER PRIMARY KEY (TENANT_ID, ORDER_ID),
                      CONSTRAINT $H2_ACCOUNT_FK
                        FOREIGN KEY (TENANT_ID, ACCOUNT_ID)
                        REFERENCES $H2_ACCOUNT_TABLE (TENANT_ID, ACCOUNT_ID),
                      CONSTRAINT $H2_APPROVER_FK
                        FOREIGN KEY (TENANT_ID, APPROVER_ACCOUNT_ID)
                        REFERENCES $H2_ACCOUNT_TABLE (TENANT_ID, ACCOUNT_ID)
                    )
                    """.trimIndent(),
                )
                statement.execute("COMMENT ON TABLE $H2_PURCHASE_ORDER_TABLE IS 'Purchase order fixture'")
                statement.execute(
                    "COMMENT ON COLUMN $H2_PURCHASE_ORDER_TABLE.AMOUNT IS 'Order amount'"
                )
                statement.execute(
                    "COMMENT ON COLUMN $H2_PURCHASE_ORDER_TABLE.MEMO IS 'Optional memo'"
                )
                statement.execute(
                    """
                    CREATE TABLE $H2_EMPLOYEE_TABLE (
                      ID BIGINT PRIMARY KEY,
                      MANAGER_ID BIGINT,
                      NAME VARCHAR(120) NOT NULL,
                      CONSTRAINT $H2_SELF_FK
                        FOREIGN KEY (MANAGER_ID) REFERENCES $H2_EMPLOYEE_TABLE(ID)
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE $H2_PROFILE_TABLE (
                      USER_ID BIGINT PRIMARY KEY,
                      BIO VARCHAR(255),
                      CONSTRAINT $H2_PROFILE_FK
                        FOREIGN KEY (USER_ID) REFERENCES $H2_USERS_TABLE(ID)
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE "$H2_QUOTED_UPPER_TABLE" (
                      "ItemID" BIGINT PRIMARY KEY,
                      "itemid" BIGINT
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE "$H2_QUOTED_LOWER_TABLE" (
                      "ID" BIGINT PRIMARY KEY
                    )
                    """.trimIndent(),
                )
                statement.execute("CREATE SCHEMA $H2_SALES_SCHEMA")
                statement.execute("CREATE SCHEMA $H2_ARCHIVE_SCHEMA")
                statement.execute(
                    """
                    CREATE TABLE $H2_SALES_SCHEMA.USERS (
                      ID BIGINT PRIMARY KEY
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE $H2_SALES_SCHEMA.ORDERS (
                      ID BIGINT PRIMARY KEY,
                      USER_ID BIGINT NOT NULL,
                      CONSTRAINT $H2_SALES_FK
                        FOREIGN KEY (USER_ID) REFERENCES $H2_SALES_SCHEMA.USERS(ID)
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE $H2_ARCHIVE_SCHEMA.ORDERS (
                      ID BIGINT PRIMARY KEY,
                      NOTE VARCHAR(255)
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    "CREATE VIEW $H2_VIEW_NAME AS SELECT ID, USER_ID, NOTE FROM $H2_ORDERS_TABLE"
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
                    statement.execute(
                        """
                        CREATE TABLE $SQLITE_RELEVANT_USERS_TABLE (
                          ID INTEGER PRIMARY KEY
                        )
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        CREATE TABLE $SQLITE_RELEVANT_ORDERS_TABLE (
                          ID INTEGER PRIMARY KEY,
                          USER_ID INTEGER NOT NULL,
                          CONSTRAINT FK_ERDMAID_SQLITE_RELEVANT_USERS
                            FOREIGN KEY (USER_ID) REFERENCES $SQLITE_RELEVANT_USERS_TABLE(ID)
                        )
                        """.trimIndent(),
                    )
                    // Negative counterpart to #238: index coverage overlaps the FK tuple and
                    // therefore must not be treated as proven non-unique.
                    statement.execute(
                        "CREATE UNIQUE INDEX UX_ERDMAID_SQLITE_RELEVANT_USER_ID " +
                            "ON $SQLITE_RELEVANT_ORDERS_TABLE(USER_ID)"
                    )
                }
            }
        }
    }

    private companion object {
        const val IDE_RELEASE = "2026.3"
        const val FIXTURE_REVISION = "idea-263-h2-sqlite-v4"
        const val H2_DRIVER_NAME = "erdMaid H2 2.5.252"
        const val SQLITE_DRIVER_NAME = "erdMaid SQLite 3.54.1.0"
        const val EXPORT_ACTION_TEXT = "Export as Mermaid ERD (erdMaid)"

        const val H2_USERS_TABLE = "ERDMAID_H2_USERS"
        const val H2_ORDERS_TABLE = "ERDMAID_H2_ORDERS"
        const val H2_FK_NAME = "FK_ERDMAID_H2_ORDERS_USERS"
        const val H2_ACCOUNT_TABLE = "ERDMAID_ACCOUNT"
        const val H2_PURCHASE_ORDER_TABLE = "ERDMAID_PURCHASE_ORDER"
        const val H2_ACCOUNT_FK = "FK_ERDMAID_PURCHASE_ORDER_ACCOUNT"
        const val H2_APPROVER_FK = "FK_ERDMAID_PURCHASE_ORDER_APPROVER"
        const val H2_EMPLOYEE_TABLE = "ERDMAID_EMPLOYEE"
        const val H2_SELF_FK = "FK_ERDMAID_EMPLOYEE_MANAGER"
        const val H2_PROFILE_TABLE = "ERDMAID_PROFILE"
        const val H2_PROFILE_FK = "FK_ERDMAID_PROFILE_USER"
        const val H2_QUOTED_UPPER_TABLE = "ERDMAID Order Items"
        const val H2_QUOTED_LOWER_TABLE = "erdmaid order items"
        const val H2_VIEW_NAME = "ERDMAID_H2_ORDER_VIEW"
        const val H2_SALES_SCHEMA = "ERDMAID_SALES"
        const val H2_ARCHIVE_SCHEMA = "ERDMAID_ARCHIVE"
        const val H2_SALES_FK = "FK_ERDMAID_SALES_ORDERS_USERS"

        const val SQLITE_USERS_TABLE = "ERDMAID_SQLITE_USERS"
        const val SQLITE_ORDERS_TABLE = "ERDMAID_SQLITE_ORDERS"
        const val SQLITE_RELEVANT_USERS_TABLE = "ERDMAID_SQLITE_RELEVANT_USERS"
        const val SQLITE_RELEVANT_ORDERS_TABLE = "ERDMAID_SQLITE_RELEVANT_ORDERS"
    }
}
