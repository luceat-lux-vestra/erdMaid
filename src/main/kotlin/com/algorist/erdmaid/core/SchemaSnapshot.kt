package com.algorist.erdmaid.core

/**
 * Invocation-safe identity for the datasource/origin that supplied an export snapshot.
 *
 * The JetBrains host adapter decides which stable platform fact becomes this value. The core
 * treats it as opaque identity and never derives it from a display label.
 */
@JvmInline
value class OriginId(val value: String) {
    init {
        require(value.isNotEmpty()) { "Origin identity must not be empty" }
    }
}

/** Plain diagnostic data only. Never retain platform exceptions or objects in the core. */
data class CoreDiagnostic(
    val code: String,
    val detail: String? = null,
) {
    init {
        require(code.isNotBlank()) { "Diagnostic code must not be blank" }
    }
}

/**
 * Metadata that may be authoritatively absent.
 *
 * [Absent] means the value is known not to exist. [Unavailable] means the adapter could not
 * establish whether a value exists. Those states are intentionally not interchangeable.
 */
sealed interface OptionalValue<out T> {
    data class Present<T>(val value: T) : OptionalValue<T>

    data object Absent : OptionalValue<Nothing>

    data class Unavailable(val diagnostic: CoreDiagnostic) : OptionalValue<Nothing>
}

/** Metadata that semantically exists but may be unavailable to the adapter. */
sealed interface Evidence<out T> {
    data class Known<T>(val value: T) : Evidence<T>

    data class Unavailable(val diagnostic: CoreDiagnostic) : Evidence<Nothing>
}

data class TableId(
    val origin: OriginId,
    val catalog: String?,
    val schema: String?,
    val name: String,
) {
    init {
        require(name.isNotEmpty()) { "Table name must not be empty" }
    }
}

data class ColumnId(
    val table: TableId,
    val name: String,
) {
    init {
        require(name.isNotEmpty()) { "Column name must not be empty" }
    }
}

data class RawTypeMetadata(
    val name: String,
    val length: OptionalValue<Int> = unavailableTypeDetail("length"),
    val precision: OptionalValue<Int> = unavailableTypeDetail("precision"),
    val scale: OptionalValue<Int> = unavailableTypeDetail("scale"),
) {
    init {
        require(name.isNotEmpty()) { "Raw type name must not be empty" }
    }
}

data class ColumnSnapshot(
    val id: ColumnId,
    /** Zero-based order captured from the authoritative metadata iteration. */
    val sourcePosition: Int,
    val rawType: Evidence<RawTypeMetadata>,
    val nullable: Evidence<Boolean>,
    val comment: OptionalValue<String>,
) {
    init {
        require(sourcePosition >= 0) { "Column source position must be non-negative" }
    }
}

data class PrimaryKeyFact(
    val name: OptionalValue<String>,
    val columns: List<ColumnId>,
) {
    init {
        require(columns.isNotEmpty()) { "Primary key must contain at least one column" }
        require(columns.distinct().size == columns.size) {
            "Primary key columns must not contain duplicates"
        }
        requireOptionalName(name, "Primary key")
    }
}

data class UniqueKeyFact(
    val name: OptionalValue<String>,
    val columns: List<ColumnId>,
) {
    init {
        require(columns.isNotEmpty()) { "Unique key must contain at least one column" }
        require(columns.distinct().size == columns.size) {
            "Unique key columns must not contain duplicates"
        }
        requireOptionalName(name, "Unique key")
    }
}

enum class RelationProvenance {
    PHYSICAL,
    VIRTUAL,
}

/**
 * Evidence identifying an FK target without pretending incomplete qualification is a TableId.
 *
 * In particular, an unavailable schema/catalog/origin remains unavailable. Later semantic code
 * must not turn that into a wildcard and bind the reference merely because one selected table
 * happens to have the same name.
 */
data class TableReferenceEvidence(
    val origin: Evidence<OriginId>,
    val catalog: OptionalValue<String>,
    val schema: OptionalValue<String>,
    val name: Evidence<String>,
) {
    init {
        if (name is Evidence.Known) {
            require(name.value.isNotEmpty()) { "Referenced table name must not be empty" }
        }
    }
}

data class ForeignKeyColumnMapping(
    val child: ColumnId,
    val referencedColumnName: String,
) {
    init {
        require(referencedColumnName.isNotEmpty()) {
            "Referenced column name must not be empty"
        }
    }
}

data class ForeignKeyFact(
    val childTable: TableId,
    val referencedTable: TableReferenceEvidence,
    val name: OptionalValue<String>,
    val provenance: Evidence<RelationProvenance>,
    val mappings: Evidence<List<ForeignKeyColumnMapping>>,
) {
    init {
        requireOptionalName(name, "Foreign key")
        if (mappings is Evidence.Known) {
            require(mappings.value.isNotEmpty()) {
                "Known foreign key mappings must not be empty"
            }
            require(mappings.value.all { it.child.table == childTable }) {
                "Foreign key child mappings must belong to the child table"
            }
            require(mappings.value.map { it.child }.distinct().size == mappings.value.size) {
                "Foreign key child mappings must not contain duplicate columns"
            }
        }
    }
}

data class TableSnapshot(
    val id: TableId,
    val comment: OptionalValue<String>,
    val columns: List<ColumnSnapshot>,
    /** Absent means the table is authoritatively known to have no primary key. */
    val primaryKey: OptionalValue<PrimaryKeyFact>,
    /** Known(emptyList()) means the table is authoritatively known to have no unique keys. */
    val uniqueKeys: Evidence<List<UniqueKeyFact>>,
    /** Known(emptyList()) means the table is authoritatively known to have no foreign keys. */
    val foreignKeys: Evidence<List<ForeignKeyFact>>,
) {
    init {
        require(columns.all { it.id.table == id }) {
            "Every column must belong to its table snapshot"
        }
        require(columns.map { it.id }.distinct().size == columns.size) {
            "Column identities must be unique within a table snapshot"
        }
        require(columns.zipWithNext().all { (left, right) ->
            left.sourcePosition < right.sourcePosition
        }) {
            "Columns must preserve strictly increasing source positions"
        }

        val knownColumns = columns.mapTo(linkedSetOf()) { it.id }
        if (primaryKey is OptionalValue.Present) {
            require(primaryKey.value.columns.all { it.table == id && it in knownColumns }) {
                "Primary key columns must resolve to columns in the same table snapshot"
            }
        }
        if (uniqueKeys is Evidence.Known) {
            require(uniqueKeys.value.all { key ->
                key.columns.all { it.table == id && it in knownColumns }
            }) {
                "Unique key columns must resolve to columns in the same table snapshot"
            }
        }
        if (foreignKeys is Evidence.Known) {
            require(foreignKeys.value.all { it.childTable == id }) {
                "Foreign keys must belong to the containing table snapshot"
            }
            require(foreignKeys.value.all { foreignKey ->
                when (val mappings = foreignKey.mappings) {
                    is Evidence.Known -> mappings.value.all { it.child in knownColumns }
                    is Evidence.Unavailable -> true
                }
            }) {
                "Foreign key child mappings must resolve to columns in the same table snapshot"
            }
        }
    }
}

data class SchemaSnapshot(
    val origin: OriginId,
    val tables: List<TableSnapshot>,
) {
    init {
        require(tables.all { it.id.origin == origin }) {
            "A schema snapshot must contain exactly one origin"
        }
        require(tables.map { it.id }.distinct().size == tables.size) {
            "Canonical table identities must be unique within a schema snapshot"
        }
    }
}

private fun requireOptionalName(value: OptionalValue<String>, owner: String) {
    if (value is OptionalValue.Present) {
        require(value.value.isNotEmpty()) { "$owner name must not be empty when present" }
    }
}

private fun unavailableTypeDetail(detail: String): OptionalValue<Int> =
    OptionalValue.Unavailable(CoreDiagnostic("type-$detail-not-established"))
