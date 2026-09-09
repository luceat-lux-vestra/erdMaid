package com.algorist.erdmaid.semantic

import com.algorist.erdmaid.core.ExportOutcome
import com.algorist.erdmaid.core.FrozenList
import com.algorist.erdmaid.core.OriginId
import com.algorist.erdmaid.core.SchemaSnapshot
import com.algorist.erdmaid.core.TableId
import com.algorist.erdmaid.core.TableSnapshot
import com.algorist.erdmaid.core.WorkCheckpoint

enum class TableQualificationIntent {
    UNQUALIFIED,
    SCHEMA,
    CATALOG_SCHEMA,
}

/**
 * Canonical table facts paired only with the qualification depth needed by downstream rendering.
 *
 * [snapshot] remains the authority for table/column/key metadata. Qualification is presentation
 * intent derived from canonical identity; it never rewrites or replaces that identity.
 */
data class ErdGraphTable(
    val snapshot: TableSnapshot,
    val qualification: TableQualificationIntent,
)

/** Minimal renderer-neutral semantic aggregate produced from one immutable schema snapshot. */
data class ErdGraph(
    val origin: OriginId,
    val tables: FrozenList<ErdGraphTable>,
    val relations: FrozenList<ErdGraphRelation>,
) {
    init {
        require(tables.all { it.snapshot.id.origin == origin }) {
            "Every ER graph table must belong to the graph origin"
        }
        require(tables.map { it.snapshot.id }.distinct().size == tables.size) {
            "ER graph table identities must be unique"
        }
        require(tables.zipWithNext().all { (left, right) ->
            compareTableId(left.snapshot.id, right.snapshot.id) < 0
        }) {
            "ER graph tables must be in canonical identity order"
        }

        val tableIds = tables.mapTo(linkedSetOf()) { it.snapshot.id }
        require(relations.all { relation ->
            relation.relation.relation.childTable in tableIds &&
                relation.relation.relation.parentTable in tableIds
        }) {
            "ER graph relation endpoints must belong to graph tables"
        }
    }
}

/**
 * Composes the already-canonical relation pipeline with canonical table order and qualification
 * intent. Relation identification is derived upstream by [RelationIdentificationCompiler]; this
 * boundary never re-derives relation identity, constraints, multiplicity or identification.
 */
object ErdGraphCompiler {

    fun compile(snapshot: SchemaSnapshot): ExportOutcome<ErdGraph> =
        compile(snapshot, WorkCheckpoint.NONE)

    internal fun compile(
        snapshot: SchemaSnapshot,
        checkpoint: WorkCheckpoint,
    ): ExportOutcome<ErdGraph> {
        checkpoint.check()
        val relations = RelationIdentificationCompiler.compile(snapshot, checkpoint)
        return when (relations) {
            is ExportOutcome.Complete -> ExportOutcome.Complete(
                ErdGraph(
                    origin = snapshot.origin,
                    tables = graphTables(snapshot.tables, checkpoint),
                    relations = relations.value,
                )
            )
            ExportOutcome.NoExport -> ExportOutcome.NoExport
            is ExportOutcome.Degraded -> ExportOutcome.Degraded(relations.diagnostics)
            is ExportOutcome.Unsupported -> ExportOutcome.Unsupported(relations.diagnostics)
            is ExportOutcome.Failure -> ExportOutcome.Failure(relations.diagnostics)
            ExportOutcome.Cancelled -> ExportOutcome.Cancelled
        }
    }

    private fun graphTables(
        tables: List<TableSnapshot>,
        checkpoint: WorkCheckpoint,
    ): FrozenList<ErdGraphTable> {
        checkpoint.check()
        val ordered = tables.sortedWith(tableSnapshotComparator)
        val intents = qualificationIntents(ordered, checkpoint)
        val graphTables = ArrayList<ErdGraphTable>(ordered.size)
        for (table in ordered) {
            checkpoint.check()
            graphTables += ErdGraphTable(
                snapshot = table,
                qualification = intents.getValue(table.id),
            )
        }
        return FrozenList.copyOf(graphTables)
    }

    private fun qualificationIntents(
        tables: List<TableSnapshot>,
        checkpoint: WorkCheckpoint,
    ): Map<TableId, TableQualificationIntent> {
        val result = LinkedHashMap<TableId, TableQualificationIntent>(tables.size)

        for (sameName in tables.groupBy { it.id.name }.values) {
            checkpoint.check()
            val intent = when {
                sameName.size == 1 -> TableQualificationIntent.UNQUALIFIED
                sameName.map { it.id.schema }.distinct().size == sameName.size ->
                    TableQualificationIntent.SCHEMA
                else -> TableQualificationIntent.CATALOG_SCHEMA
            }
            for (table in sameName) {
                checkpoint.check()
                result[table.id] = intent
            }
        }

        return result
    }
}

private val tableSnapshotComparator = Comparator<TableSnapshot> { left, right ->
    compareTableId(left.id, right.id)
}

private fun compareTableId(left: TableId, right: TableId): Int {
    var comparison = left.origin.value.compareTo(right.origin.value)
    if (comparison != 0) return comparison

    comparison = compareNullableString(left.catalog, right.catalog)
    if (comparison != 0) return comparison

    comparison = compareNullableString(left.schema, right.schema)
    if (comparison != 0) return comparison

    return left.name.compareTo(right.name)
}

private fun compareNullableString(left: String?, right: String?): Int = when {
    left == right -> 0
    left == null -> -1
    right == null -> 1
    else -> left.compareTo(right)
}
