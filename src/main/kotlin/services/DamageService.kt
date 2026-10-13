package services

import database.DamageReports
import database.dbTransaction
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import parseUUID
import routes.DamageReport
import routes.NewDamageReport
import java.time.Instant
import java.util.*

class DamageService {

    fun getAll(): List<DamageReport> = dbTransaction {
        DamageReports.selectAll().map(::toDamageReport)
    }

    fun create(newDamageReport: NewDamageReport): DamageReport {
        return dbTransaction {
            DamageReports.insertReturning {
                it[individualItemId] = parseUUID(newDamageReport.inventoryItemId)
                it[description] = newDamageReport.description
                it[reportedBy] = newDamageReport.reportedBy
                it[resolvedAt] = if (newDamageReport.isResolved) Instant.now() else null
                it[resolutionNotes] = newDamageReport.resolutionNotes
            }.single().let { toDamageReport(it) }
        }
    }

    fun getByItemId(itemId: UUID): List<DamageReport> {
        return dbTransaction {
            DamageReports
                .selectAll()
                .where { DamageReports.individualItemId eq itemId }
                .map(::toDamageReport)
        }
    }

    fun getById(id: UUID): DamageReport? = dbTransaction {
        DamageReports.selectAll().where { DamageReports.id eq id }.withDistinct().map(::toDamageReport).singleOrNull()
    }

    fun update(id: UUID, newDamageReport: NewDamageReport): DamageReport? = dbTransaction {
        val existing = getById(id) ?: return@dbTransaction null
        DamageReports.update({ DamageReports.id eq id }) {
            it[description] = newDamageReport.description
            it[reportedBy] = newDamageReport.reportedBy
            it[resolutionNotes] = newDamageReport.resolutionNotes
            // Keep the original resolution time when an already resolved report is edited.
            if (newDamageReport.isResolved != existing.isResolved) {
                it[resolvedAt] = if (newDamageReport.isResolved) Instant.now() else null
            }
        }

        return@dbTransaction getById(id)
    }

    fun delete(id: UUID): Boolean = dbTransaction {
        val noDeleted = DamageReports.deleteWhere { DamageReports.id eq id }
        return@dbTransaction noDeleted > 0
    }

    private fun toDamageReport(row: ResultRow): DamageReport = DamageReport(
        id = row[DamageReports.id].value.toString(),
        inventoryItemId = row[DamageReports.individualItemId].toString(),
        description = row[DamageReports.description],
        isResolved = row[DamageReports.resolvedAt] != null,
        reportedBy = row[DamageReports.reportedBy],
        reportedAt = row[DamageReports.reportedAt].toString(),
        resolvedAt = row[DamageReports.resolvedAt]?.toString(),
        resolutionNotes = row[DamageReports.resolutionNotes]
    )
}
