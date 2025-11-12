package services

import database.DamageReports
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import parseUUID
import routes.DamageReport
import routes.NewDamageReport
import java.time.Instant
import java.util.*

class DamageService {

    fun getAll(): List<DamageReport> = transaction {
        DamageReports.selectAll().map(::toDamageReport)
    }

    fun create(newDamageReport: NewDamageReport): DamageReport {
        return transaction {
            DamageReports.insertReturning {
                it[inventoryItemId] = parseUUID(newDamageReport.inventoryItemId)
                it[description] = newDamageReport.description
                it[isResolved] = newDamageReport.isResolved
                it[reportedAt] = Instant.now()
            }.single().let { toDamageReport(it) }
        }
    }

    fun getByItemId(itemId: UUID): List<DamageReport> {
        return transaction {
            DamageReports
                .selectAll()
                .where { DamageReports.inventoryItemId eq itemId }
                .map(::toDamageReport)
        }
    }

    fun getById(id: UUID): DamageReport? = transaction {
        DamageReports.selectAll().where { DamageReports.id eq id }.withDistinct().map(::toDamageReport).singleOrNull()
    }

    fun update(id: UUID, newDamageReport: NewDamageReport): DamageReport? = transaction {
        DamageReports.update({ DamageReports.id eq id }) {
            it[description] = newDamageReport.description
            it[isResolved] = newDamageReport.isResolved
        }

        return@transaction getById(id)
    }

    fun delete(id: UUID): Boolean = transaction {
        val noDeleted = DamageReports.deleteWhere { DamageReports.id eq id }
        return@transaction noDeleted > 0
    }

    private fun toDamageReport(row: ResultRow): DamageReport = DamageReport(
        id = row[DamageReports.id].value.toString(),
        inventoryItemId = row[DamageReports.inventoryItemId].toString(),
        description = row[DamageReports.description],
        isResolved = row[DamageReports.isResolved],
        reportedAt = row[DamageReports.reportedAt].toString()
    )
}
