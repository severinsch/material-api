package services
import database.DamageReports
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import routes.DamageReport
import routes.NewDamageReport
import java.time.Instant
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

class DamageService {

    fun getAll(): List<DamageReport> = transaction {
        DamageReports.selectAll().map(::toDamageReport)
    }

    fun create(newDamageReport: NewDamageReport): DamageReport {
        return transaction {
            DamageReports.insertReturning {
                it[inventoryItemId] = UUID.fromString(newDamageReport.inventoryItemId)
                it[description] = newDamageReport.description
                it[isResolved] = newDamageReport.isResolved
                it[reportedAt] = Instant.now()
            }.single().let { toDamageReport(it) }
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    fun getById(uuid: Uuid): DamageReport? = getById(uuid.toJavaUuid())

    // use kotlin Uuid generally, but exposed sadly uses java UUID
    private fun getById(id: UUID): DamageReport? = transaction {
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
