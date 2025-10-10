package services
import database.DamageReports
import database.DatabaseFactory.dbQuery
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import routes.DamageReport
import routes.NewDamageReport
import java.time.Instant
import java.util.UUID

class DamageService {

    suspend fun getAll(): List<DamageReport> = dbQuery {
        DamageReports.selectAll().map(::toDamageReport)
    }

    suspend fun create(newDamageReport: NewDamageReport): DamageReport {
        val id = dbQuery {
            DamageReports.insert {
                it[inventoryItemId] = UUID.fromString(newDamageReport.inventoryItemId)
                it[description] = newDamageReport.description
                it[isResolved] = newDamageReport.isResolved
                it[reportedAt] = Instant.now()
            } get DamageReports.id
        }
        return getById(id)!!
    }

    private suspend fun getById(id: EntityID<UUID>): DamageReport? = dbQuery {
        DamageReports.selectAll().where { DamageReports.id eq id }.withDistinct().map(::toDamageReport).singleOrNull()
    }

    private fun toDamageReport(row: ResultRow): DamageReport = DamageReport(
        id = row[DamageReports.id].toString(),
        inventoryItemId = row[DamageReports.inventoryItemId].toString(),
        description = row[DamageReports.description],
        isResolved = row[DamageReports.isResolved],
        reportedAt = row[DamageReports.reportedAt].toString()
    )
}
