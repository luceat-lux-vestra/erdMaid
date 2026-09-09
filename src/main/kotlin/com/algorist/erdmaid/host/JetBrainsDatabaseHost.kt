package com.algorist.erdmaid.host

import com.algorist.erdmaid.core.ColumnId
import com.algorist.erdmaid.core.ColumnSnapshot
import com.algorist.erdmaid.core.CoreDiagnostic
import com.algorist.erdmaid.core.CoreDiagnostics
import com.algorist.erdmaid.core.Evidence
import com.algorist.erdmaid.core.ExportOutcome
import com.algorist.erdmaid.core.ForeignKeyColumnMapping
import com.algorist.erdmaid.core.ForeignKeyFact
import com.algorist.erdmaid.core.FrozenList
import com.algorist.erdmaid.core.OptionalValue
import com.algorist.erdmaid.core.OriginId
import com.algorist.erdmaid.core.PrimaryKeyFact
import com.algorist.erdmaid.core.RawTypeMetadata
import com.algorist.erdmaid.core.RelationProvenance
import com.algorist.erdmaid.core.SchemaSnapshot
import com.algorist.erdmaid.core.TableId
import com.algorist.erdmaid.core.TableReferenceEvidence
import com.algorist.erdmaid.core.TableSnapshot
import com.algorist.erdmaid.core.UniqueKeyFact
import com.intellij.database.model.DasColumn
import com.intellij.database.model.DasForeignKey
import com.intellij.database.model.DasIndex
import com.intellij.database.model.DasTable
import com.intellij.database.model.DasTableKey
import com.intellij.database.model.DataType
import com.intellij.database.model.ModelRelationManager
import com.intellij.database.psi.DbDataSource
import com.intellij.database.psi.DbElement
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.psi.DbTable
import com.intellij.database.util.DasUtil
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.coroutines.coroutineContext

/**
 * The only production compatibility boundary for the maintained closed-source DatabaseTools API.
 * Internal/reflected symbols never escape this object and every access failure is typed.
 */
@Suppress("UnstableApiUsage", "DEPRECATION")
internal object DatabaseToolsCompatibility {
    private const val CONTEXT_FUN = "com.intellij.database.view.DatabaseContextFun"
    private const val EXPAND_METHOD = "getSelectedDbElementsExpandingGroups"
    private const val EXTRA_RELATION =
        "com.intellij.database.model.ModelRelationManager\$ExtraRelation"

    internal fun expandedSelection(
        dataContext: DataContext,
        invoker: ((DataContext) -> Any?)? = null,
    ): ExportOutcome<List<DbElement>> {
        val value = try {
            if (invoker != null) invoker(dataContext) else invokeExpandedSelection(dataContext)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (symbol: SelectionSymbolException) {
            return failure(symbol.diagnostic.code, symbol.diagnostic.detail)
        } catch (failure: InvocationTargetException) {
            return failure("selection-api-invocation-failed", failure.targetException.javaClass.name)
        } catch (failure: ReflectiveOperationException) {
            return failure("selection-api-invocation-failed", failure.javaClass.name)
        } catch (failure: RuntimeException) {
            return failure("selection-api-invocation-failed", failure.javaClass.name)
        } catch (failure: LinkageError) {
            return failure("selection-api-linkage-failed", failure.javaClass.name)
        }

        val iterable = value as? Iterable<*>
            ?: return failure("selection-api-invalid-result")
        val result = ArrayList<DbElement>()
        for (item in iterable) {
            val element = item as? DbElement
                ?: return failure("selection-api-invalid-element")
            result += element
        }
        return ExportOutcome.Complete(Collections.unmodifiableList(result))
    }

    private fun invokeExpandedSelection(dataContext: DataContext): Any? {
        val method = when (val access = selectionMethod()) {
            is ExportOutcome.Complete -> access.value
            ExportOutcome.NoExport -> throw SelectionSymbolException(
                CoreDiagnostic("selection-api-no-export-invariant")
            )
            is ExportOutcome.Failure -> throw SelectionSymbolException(access.diagnostics.values.first())
            is ExportOutcome.Degraded -> throw SelectionSymbolException(access.diagnostics.values.first())
            is ExportOutcome.Unsupported -> throw SelectionSymbolException(access.diagnostics.values.first())
            ExportOutcome.Cancelled -> throw CancellationException("selection API lookup cancelled")
        }
        return method.invoke(null, dataContext)
    }

    fun isVirtualRelation(relation: DasForeignKey): ExportOutcome<Boolean> {
        val type = try {
            Class.forName(EXTRA_RELATION, false, relation.javaClass.classLoader)
        } catch (failure: ClassNotFoundException) {
            return failure("virtual-relation-api-missing", failure.javaClass.name)
        } catch (failure: LinkageError) {
            return failure("virtual-relation-api-linkage-failed", failure.javaClass.name)
        }
        return ExportOutcome.Complete(type.isInstance(relation))
    }

    private fun selectionMethod(): ExportOutcome<Method> = try {
        val type = Class.forName(CONTEXT_FUN)
        ExportOutcome.Complete(type.getMethod(EXPAND_METHOD, DataContext::class.java))
    } catch (failure: ReflectiveOperationException) {
        failure("selection-api-symbol-missing", failure.javaClass.name)
    } catch (failure: LinkageError) {
        failure("selection-api-linkage-failed", failure.javaClass.name)
    }

    private class SelectionSymbolException(
        val diagnostic: CoreDiagnostic,
    ) : ReflectiveOperationException(diagnostic.code)

    private fun failure(code: String, detail: String? = null): ExportOutcome.Failure =
        ExportOutcome.Failure(CoreDiagnostics.of(CoreDiagnostic(code, detail)))
}

/** DatabaseTools -> immutable core snapshot adapter. No SQL or connection API is used here. */
@Suppress("UnstableApiUsage", "DEPRECATION")
internal object JetBrainsDatabaseHost {

    suspend fun capture(
        project: Project,
        dataContext: DataContext,
    ): ExportOutcome<CapturedHostExport> {
        if (project.isDisposed) return ExportOutcome.Cancelled

        val selection = when (val outcome = readAction { captureSelection(dataContext) }) {
            is ExportOutcome.Complete -> outcome.value
            ExportOutcome.NoExport -> return ExportOutcome.NoExport
            is ExportOutcome.Degraded -> return ExportOutcome.Degraded(outcome.diagnostics)
            is ExportOutcome.Unsupported -> return ExportOutcome.Unsupported(outcome.diagnostics)
            is ExportOutcome.Failure -> return ExportOutcome.Failure(outcome.diagnostics)
            ExportOutcome.Cancelled -> return ExportOutcome.Cancelled
        }

        val liveTables = ArrayList<LiveTable>(selection.tables.size)
        for (table in selection.tables) {
            coroutineContext.ensureActive()
            if (project.isDisposed) return ExportOutcome.Cancelled
            val base = when (val outcome = readAction { captureTableBase(selection.origin, table) }) {
                is ExportOutcome.Complete -> outcome.value
                ExportOutcome.NoExport -> return ExportOutcome.NoExport
                is ExportOutcome.Degraded -> return ExportOutcome.Degraded(outcome.diagnostics)
                is ExportOutcome.Unsupported -> return ExportOutcome.Unsupported(outcome.diagnostics)
                is ExportOutcome.Failure -> return ExportOutcome.Failure(outcome.diagnostics)
                ExportOutcome.Cancelled -> return ExportOutcome.Cancelled
            }
            liveTables += base
        }

        if (liveTables.groupBy { it.snapshot.id }.any { (_, owners) -> owners.size > 1 }) {
            return unsupported("selection-canonical-identity-ambiguous")
        }

        val completedTables = ArrayList<TableSnapshot>(liveTables.size)
        for (live in liveTables) {
            coroutineContext.ensureActive()
            if (project.isDisposed) return ExportOutcome.Cancelled
            val foreignKeys = when (
                val outcome = readAction { captureForeignKeys(project, live) }
            ) {
                is ExportOutcome.Complete -> outcome.value
                ExportOutcome.NoExport -> return ExportOutcome.NoExport
                is ExportOutcome.Degraded -> return ExportOutcome.Degraded(outcome.diagnostics)
                is ExportOutcome.Unsupported -> return ExportOutcome.Unsupported(outcome.diagnostics)
                is ExportOutcome.Failure -> return ExportOutcome.Failure(outcome.diagnostics)
                ExportOutcome.Cancelled -> return ExportOutcome.Cancelled
            }
            completedTables += live.snapshot.copy(foreignKeys = foreignKeys)
        }

        coroutineContext.ensureActive()
        if (project.isDisposed) return ExportOutcome.Cancelled
        when (val freshness = readAction { validateFreshness(project, selection.freshness) }) {
            is ExportOutcome.Complete -> if (!freshness.value) {
                return degraded("snapshot-invalidated-during-capture")
            }
            ExportOutcome.NoExport -> return ExportOutcome.NoExport
            is ExportOutcome.Degraded -> return ExportOutcome.Degraded(freshness.diagnostics)
            is ExportOutcome.Unsupported -> return ExportOutcome.Unsupported(freshness.diagnostics)
            is ExportOutcome.Failure -> return ExportOutcome.Failure(freshness.diagnostics)
            ExportOutcome.Cancelled -> return ExportOutcome.Cancelled
        }

        return ExportOutcome.Complete(
            CapturedHostExport(
                snapshot = SchemaSnapshot(
                    origin = selection.origin,
                    tables = FrozenList.copyOf(completedTables),
                ),
                freshness = selection.freshness,
            )
        )
    }

    /**
     * Re-resolves by opaque origin id. This method is intentionally non-suspending so the final
     * EDT publication gate can validate and mutate the clipboard without an intervening suspension.
     */
    fun validateFreshness(
        project: Project,
        expected: HostFreshnessToken,
    ): ExportOutcome<Boolean> {
        if (project.isDisposed) return ExportOutcome.Cancelled
        return try {
            val source = DbPsiFacade.getInstance(project).findDataSource(expected.origin.value)
                ?: return ExportOutcome.Complete(false)
            if (source.uniqueId != expected.origin.value) {
                ExportOutcome.Complete(false)
            } else {
                ExportOutcome.Complete(
                    source.modificationTracker.modificationCount == expected.modificationCount
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RuntimeException) {
            failure("freshness-read-failed", failure.javaClass.name)
        } catch (failure: LinkageError) {
            failure("freshness-read-failed", failure.javaClass.name)
        }
    }

    private fun captureSelection(dataContext: DataContext): ExportOutcome<LiveSelection> {
        val elements = when (val outcome = DatabaseToolsCompatibility.expandedSelection(dataContext)) {
            is ExportOutcome.Complete -> outcome.value
            ExportOutcome.NoExport -> return ExportOutcome.NoExport
            is ExportOutcome.Degraded -> return ExportOutcome.Degraded(outcome.diagnostics)
            is ExportOutcome.Unsupported -> return ExportOutcome.Unsupported(outcome.diagnostics)
            is ExportOutcome.Failure -> return ExportOutcome.Failure(outcome.diagnostics)
            ExportOutcome.Cancelled -> return ExportOutcome.Cancelled
        }
        return classifyExpandedSelection(elements)
    }

    internal fun classifyExpandedSelection(elements: List<DbElement>): ExportOutcome<LiveSelection> {
        if (elements.isEmpty()) return ExportOutcome.NoExport
        if (elements.any { it !is DbTable }) return unsupported("selection-mixed-or-unsupported")

        val unique = Collections.newSetFromMap(IdentityHashMap<DbTable, Boolean>())
        val tables = ArrayList<DbTable>(elements.size)
        for (element in elements) {
            val table = element as DbTable
            if (!table.isValid) return unsupported("selection-object-invalid")
            if (unique.add(table)) tables += table
        }
        if (tables.isEmpty()) return ExportOutcome.NoExport

        return try {
            val firstSource = tables.first().dataSource
            val originValue = firstSource.uniqueId
            if (originValue.isEmpty()) return failure("selection-origin-empty")
            val origin = OriginId(originValue)
            val modificationCount = firstSource.modificationTracker.modificationCount

            for (table in tables.drop(1)) {
                val source = table.dataSource
                if (source.uniqueId != originValue) {
                    return unsupported("selection-cross-origin")
                }
                if (source.modificationTracker.modificationCount != modificationCount) {
                    return degraded("selection-origin-modified-during-capture")
                }
            }

            ExportOutcome.Complete(
                LiveSelection(
                    origin = origin,
                    tables = Collections.unmodifiableList(tables),
                    freshness = HostFreshnessToken(origin, modificationCount),
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RuntimeException) {
            failure("selection-origin-read-failed", failure.javaClass.name)
        } catch (failure: LinkageError) {
            failure("selection-origin-read-failed", failure.javaClass.name)
        }
    }

    private fun captureTableBase(origin: OriginId, dbTable: DbTable): ExportOutcome<LiveTable> {
        if (!dbTable.isValid) return unsupported("selection-object-invalid")
        return try {
            val table = dbTable.dasObject
            val tableId = TableId(
                origin = origin,
                catalog = DasUtil.getCatalog(table),
                schema = DasUtil.getSchema(table),
                name = table.name,
            )
            val columns = DasUtil.getColumns(table).toList()
            val snapshots = ArrayList<ColumnSnapshot>(columns.size)
            val byName = LinkedHashMap<String, ColumnId>(columns.size)
            val columnObjectsByName = LinkedHashMap<String, DasColumn>(columns.size)
            for ((index, column) in columns.withIndex()) {
                val columnId = ColumnId(tableId, column.name)
                if (byName.putIfAbsent(column.name, columnId) != null ||
                    columnObjectsByName.putIfAbsent(column.name, column) != null
                ) {
                    return degraded("metadata-duplicate-column-name")
                }
                val dataType = column.dasType.toDataType()
                val rawType = dataType.typeName
                val typeEvidence = if (rawType.isEmpty()) {
                    Evidence.Unavailable(CoreDiagnostic("column-type-name-empty"))
                } else {
                    Evidence.Known(
                        RawTypeMetadata(
                            name = rawType,
                            length = typeDetail(dataType.length, DataType.NO_SIZE, "length"),
                            precision = typeDetail(dataType.precision, DataType.NO_SIZE, "precision"),
                            scale = typeDetail(dataType.scale, DataType.NO_SCALE, "scale"),
                        )
                    )
                }
                snapshots += ColumnSnapshot(
                    id = columnId,
                    sourcePosition = index,
                    rawType = typeEvidence,
                    nullable = Evidence.Known(!column.isNotNull),
                    comment = optionalText(column.comment),
                )
            }

            val tableKeys = DasUtil.getTableKeys(table).toList()
            val primaryKeys = tableKeys.filter(DasTableKey::isPrimary)
            if (primaryKeys.size > 1) return degraded("metadata-multiple-primary-keys")

            val primaryKey = primaryKeys.singleOrNull()?.let { key ->
                val ids = resolveKeyColumns(key, byName)
                    ?: return degraded("metadata-primary-key-column-unresolved")
                OptionalValue.Present(
                    PrimaryKeyFact(
                        name = optionalName(key.name),
                        columns = FrozenList.copyOf(ids),
                    )
                )
            } ?: OptionalValue.Absent

            val uniqueKeys = captureUniqueKeys(
                table = table,
                columns = columns,
                columnIds = byName,
                columnObjects = columnObjectsByName,
                tableKeys = tableKeys,
                primaryKeys = primaryKeys,
                indices = DasUtil.getIndices(table).toList(),
            )

            ExportOutcome.Complete(
                LiveTable(
                    dbTable = dbTable,
                    dasTable = table,
                    snapshot = TableSnapshot(
                        id = tableId,
                        comment = optionalText(table.comment),
                        columns = FrozenList.copyOf(snapshots),
                        primaryKey = primaryKey,
                        uniqueKeys = uniqueKeys,
                        foreignKeys = Evidence.Known(FrozenList.copyOf(emptyList<ForeignKeyFact>())),
                    ),
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RuntimeException) {
            degraded("metadata-table-read-failed", failure.javaClass.name)
        } catch (failure: LinkageError) {
            degraded("metadata-table-read-failed", failure.javaClass.name)
        }
    }

    /**
     * DatabaseTools exposes grouped table keys, per-column CANDIDATE_KEY attributes, and indices.
     * We claim alternate-key uniqueness only when the first two sources agree. A unique index is
     * never promoted to a UniqueKeyFact by itself because the maintained API does not expose enough
     * semantics to rule out filtered/expression-specific uniqueness. Instead it is an omission
     * detector: a unique index must match a key already proved by stronger evidence, otherwise the
     * entire unique-key collection remains unavailable rather than allowing a false NON_UNIQUE claim.
     */
    private fun captureUniqueKeys(
        table: DasTable,
        columns: List<DasColumn>,
        columnIds: Map<String, ColumnId>,
        columnObjects: Map<String, DasColumn>,
        tableKeys: List<DasTableKey>,
        primaryKeys: List<DasTableKey>,
        indices: List<DasIndex>,
    ): Evidence<FrozenList<UniqueKeyFact>> {
        val primaryNames = primaryKeys.singleOrNull()
            ?.columnsRef
            ?.names()
            ?.toList()
            .orEmpty()
        val candidateNames = columns
            .filter { column -> DasColumn.Attribute.CANDIDATE_KEY in table.getColumnAttrs(column) }
            .mapTo(linkedSetOf()) { it.name }
            .apply { removeAll(primaryNames.toSet()) }
        val alternateKeys = tableKeys.filterNot(DasTableKey::isPrimary)
        val groupedCandidateNames = linkedSetOf<String>()
        val provenGroups = linkedSetOf<Set<String>>()
        if (primaryNames.isNotEmpty() && primaryNames.all(columnIds::containsKey)) {
            provenGroups += primaryNames.toSet()
        }

        val facts = ArrayList<UniqueKeyFact>(alternateKeys.size)
        for (key in alternateKeys) {
            val names = key.columnsRef.names().toList()
            val ids = resolveKeyColumns(key, columnIds)
                ?: return uniqueUnavailable("non-primary-table-key-column-unresolved")
            if (names.any { name ->
                    val column = columnObjects[name] ?: return@any true
                    DasColumn.Attribute.CANDIDATE_KEY !in table.getColumnAttrs(column)
                }
            ) {
                return uniqueUnavailable("non-primary-table-key-not-candidate-marked")
            }
            groupedCandidateNames += names
            provenGroups += names.toSet()
            facts += UniqueKeyFact(
                name = optionalName(key.name),
                columns = FrozenList.copyOf(ids),
            )
        }

        if (candidateNames.any { it !in groupedCandidateNames }) {
            return uniqueUnavailable("candidate-key-column-without-table-key")
        }

        for (index in indices) {
            if (!index.isUnique) continue
            val names = index.columnsRef.names().toList()
            if (names.isEmpty() || names.any { it !in columnIds } || names.toSet().size != names.size) {
                return uniqueUnavailable("unique-index-column-unresolved")
            }
            if (names.toSet() in provenGroups) continue
            if (index.isFunctionBased) {
                return uniqueUnavailable("function-based-unique-index-unreconciled")
            }
            return uniqueUnavailable("unreconciled-unique-index")
        }

        return Evidence.Known(FrozenList.copyOf(facts))
    }

    private fun uniqueUnavailable(detail: String): Evidence.Unavailable =
        Evidence.Unavailable(
            CoreDiagnostic(
                "metadata-unique-key-authority-unavailable",
                detail,
            )
        )

    private fun captureForeignKeys(
        project: Project,
        child: LiveTable,
    ): ExportOutcome<Evidence<FrozenList<ForeignKeyFact>>> {
        if (!child.dbTable.isValid) return ExportOutcome.Complete(
            Evidence.Unavailable(CoreDiagnostic("foreign-key-child-invalid"))
        )
        return try {
            val facts = ArrayList<ForeignKeyFact>()
            for (foreignKey in DasUtil.getForeignKeys(child.dasTable)) {
                when (val fact = foreignKeyFact(
                    child = child,
                    foreignKey = foreignKey,
                    provenance = RelationProvenance.PHYSICAL,
                )) {
                    is ExportOutcome.Complete -> facts += fact.value
                    ExportOutcome.NoExport -> return ExportOutcome.NoExport
                    is ExportOutcome.Degraded -> return fact
                    is ExportOutcome.Unsupported -> return fact
                    is ExportOutcome.Failure -> return fact
                    ExportOutcome.Cancelled -> return ExportOutcome.Cancelled
                }
            }

            for (foreignKey in ModelRelationManager.getForeignKeys(project, child.dasTable)) {
                when (val virtual = DatabaseToolsCompatibility.isVirtualRelation(foreignKey)) {
                    is ExportOutcome.Complete -> if (!virtual.value) continue
                    ExportOutcome.NoExport -> return ExportOutcome.NoExport
                    is ExportOutcome.Degraded -> return ExportOutcome.Degraded(virtual.diagnostics)
                    is ExportOutcome.Unsupported -> return ExportOutcome.Unsupported(virtual.diagnostics)
                    is ExportOutcome.Failure -> return virtual
                    ExportOutcome.Cancelled -> return ExportOutcome.Cancelled
                }
                when (val fact = foreignKeyFact(
                    child = child,
                    foreignKey = foreignKey,
                    provenance = RelationProvenance.VIRTUAL,
                )) {
                    is ExportOutcome.Complete -> facts += fact.value
                    ExportOutcome.NoExport -> return ExportOutcome.NoExport
                    is ExportOutcome.Degraded -> return fact
                    is ExportOutcome.Unsupported -> return fact
                    is ExportOutcome.Failure -> return fact
                    ExportOutcome.Cancelled -> return ExportOutcome.Cancelled
                }
            }

            ExportOutcome.Complete(Evidence.Known(FrozenList.copyOf(facts)))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RuntimeException) {
            ExportOutcome.Complete(
                Evidence.Unavailable(
                    CoreDiagnostic("foreign-keys-read-failed", failure.javaClass.name)
                )
            )
        } catch (failure: LinkageError) {
            ExportOutcome.Complete(
                Evidence.Unavailable(
                    CoreDiagnostic("foreign-keys-read-failed", failure.javaClass.name)
                )
            )
        }
    }

    private fun foreignKeyFact(
        child: LiveTable,
        foreignKey: DasForeignKey,
        provenance: RelationProvenance,
    ): ExportOutcome<ForeignKeyFact> {
        val refTable = foreignKey.refTable
        val referenced = if (refTable == null) {
            TableReferenceEvidence(
                origin = Evidence.Unavailable(
                    CoreDiagnostic("foreign-key-parent-origin-unavailable", "ref-table-null")
                ),
                catalog = optionalText(foreignKey.refTableCatalog),
                schema = optionalText(foreignKey.refTableSchema),
                name = evidenceText(
                    foreignKey.refTableName,
                    "foreign-key-parent-name-unavailable",
                ),
            )
        } else {
            TableReferenceEvidence(
                origin = referencedOrigin(child.dbTable.dataSource, refTable),
                catalog = optionalText(DasUtil.getCatalog(refTable)),
                schema = optionalText(DasUtil.getSchema(refTable)),
                name = evidenceText(refTable.name, "foreign-key-parent-name-unavailable"),
            )
        }

        val childNames = foreignKey.columnsRef.names().toList()
        val parentNames = foreignKey.refColumns.names().toList()
        if (childNames.isEmpty() || childNames.size != parentNames.size) {
            return degraded("foreign-key-mapping-shape-invalid")
        }
        val childColumns = child.snapshot.columns.associateBy { it.id.name }
        val mappings = ArrayList<ForeignKeyColumnMapping>(childNames.size)
        for (index in childNames.indices) {
            val childColumn = childColumns[childNames[index]]?.id
                ?: return degraded("foreign-key-child-column-unresolved")
            val parentName = parentNames[index]
            if (parentName.isEmpty()) return degraded("foreign-key-parent-column-empty")
            mappings += ForeignKeyColumnMapping(childColumn, parentName)
        }

        return ExportOutcome.Complete(
            ForeignKeyFact(
                childTable = child.snapshot.id,
                referencedTable = referenced,
                name = optionalName(foreignKey.name),
                provenance = Evidence.Known(provenance),
                mappings = Evidence.Known(FrozenList.copyOf(mappings)),
            )
        )
    }

    /** Establishes endpoint origin from the referenced object itself, never selected-set names. */
    private fun referencedOrigin(source: DbDataSource, refTable: DasTable): Evidence<OriginId> {
        val element = source.findElement(refTable)
            ?: return Evidence.Unavailable(
                CoreDiagnostic(
                    "foreign-key-parent-origin-unavailable",
                    "datasource-element-not-found",
                )
            )
        val sourceId = source.uniqueId
        val elementId = element.dataSource.uniqueId
        return when {
            sourceId.isEmpty() || elementId.isEmpty() -> Evidence.Unavailable(
                CoreDiagnostic("foreign-key-parent-origin-unavailable", "origin-id-empty")
            )
            elementId != sourceId -> Evidence.Unavailable(
                CoreDiagnostic(
                    "foreign-key-parent-origin-unavailable",
                    "datasource-origin-mismatch",
                )
            )
            else -> Evidence.Known(OriginId(elementId))
        }
    }

    private fun resolveKeyColumns(
        key: DasTableKey,
        columns: Map<String, ColumnId>,
    ): List<ColumnId>? {
        val names = key.columnsRef.names().toList()
        if (names.isEmpty()) return null
        val result = ArrayList<ColumnId>(names.size)
        for (name in names) {
            val id = columns[name] ?: return null
            result += id
        }
        return result
    }

    private fun optionalName(value: String?): OptionalValue<String> =
        if (value.isNullOrEmpty()) OptionalValue.Absent else OptionalValue.Present(value)

    private fun optionalText(value: String?): OptionalValue<String> =
        if (value == null) OptionalValue.Absent else OptionalValue.Present(value)

    private fun evidenceText(value: String?, code: String): Evidence<String> =
        if (value.isNullOrEmpty()) Evidence.Unavailable(CoreDiagnostic(code)) else Evidence.Known(value)

    private fun typeDetail(
        value: Int,
        absentSentinel: Int,
        field: String,
    ): OptionalValue<Int> = when {
        value == absentSentinel -> OptionalValue.Absent
        value >= 0 -> OptionalValue.Present(value)
        else -> OptionalValue.Unavailable(
            CoreDiagnostic("type-$field-symbolic-or-unavailable", value.toString())
        )
    }

    internal data class LiveSelection(
        val origin: OriginId,
        val tables: List<DbTable>,
        val freshness: HostFreshnessToken,
    )

    private data class LiveTable(
        val dbTable: DbTable,
        val dasTable: DasTable,
        val snapshot: TableSnapshot,
    )

    private fun degraded(code: String, detail: String? = null): ExportOutcome.Degraded =
        ExportOutcome.Degraded(CoreDiagnostics.of(CoreDiagnostic(code, detail)))

    private fun unsupported(code: String, detail: String? = null): ExportOutcome.Unsupported =
        ExportOutcome.Unsupported(CoreDiagnostics.of(CoreDiagnostic(code, detail)))

    private fun failure(code: String, detail: String? = null): ExportOutcome.Failure =
        ExportOutcome.Failure(CoreDiagnostics.of(CoreDiagnostic(code, detail)))
}
