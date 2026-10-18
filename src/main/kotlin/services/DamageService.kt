package services

import api.DamageReport
import api.NewDamageReport
import database.DamageReports
import database.Db
import database.TenantId
import database.IndividualItems
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import kotlin.time.Clock
import kotlin.uuid.Uuid

class DamageService(private val db: Db) {

    suspend fun getAll(tenant: TenantId, itemId: Uuid? = null): List<DamageReport> = db.tenantQuery(tenant) {
        findWhere { itemId?.let { DamageReports.individualItemId eq it } ?: Op.TRUE }
    }

    suspend fun get(tenant: TenantId, id: Uuid): DamageReport = db.tenantQuery(tenant) { find(id) }

    suspend fun create(tenant: TenantId, report: NewDamageReport): DamageReport = db.tenantQuery(tenant) {
        requireItem(report.itemId)
        val id = DamageReports.insertAndGetId {
            it[individualItemId] = report.itemId
            it[description] = report.description
            it[reportedBy] = report.reportedBy
            it[resolvedAt] = if (report.isResolved) Clock.System.now() else null
            it[resolutionNotes] = report.resolutionNotes
        }
        updateItemStatus(report)
        find(id.value)
    }

    suspend fun update(tenant: TenantId, id: Uuid, report: NewDamageReport): DamageReport = db.tenantQuery(tenant) {
        val existing = find(id)
        requireUnchanged("Der Gegenstand einer Schadensmeldung", existing.itemId, report.itemId)
        DamageReports.update({ DamageReports.id eq id }) {
            it[description] = report.description
            it[reportedBy] = report.reportedBy
            // Keep the original resolution time when an already resolved report is edited.
            it[resolvedAt] = if (report.isResolved) existing.resolvedAt ?: Clock.System.now() else null
            it[resolutionNotes] = report.resolutionNotes
        }
        updateItemStatus(report)
        find(id)
    }

    suspend fun delete(tenant: TenantId, id: Uuid) = db.tenantQuery(tenant) {
        if (DamageReports.deleteWhere { DamageReports.id eq id } == 0) notFound("Schadensmeldung", id)
    }

    private fun updateItemStatus(report: NewDamageReport) {
        val status = report.itemStatus ?: return
        IndividualItems.update({ IndividualItems.id eq report.itemId }) { it[IndividualItems.status] = status }
    }

    private fun find(id: Uuid): DamageReport =
        findWhere { DamageReports.id eq id }.singleOrNull() ?: notFound("Schadensmeldung", id)

    private fun findWhere(predicate: () -> Op<Boolean>): List<DamageReport> = DamageReports
        .selectAll()
        .where(predicate)
        .orderBy(DamageReports.reportedAt, SortOrder.DESC)
        .map {
            DamageReport(
                id = it[DamageReports.id].value,
                itemId = it[DamageReports.individualItemId].value,
                description = it[DamageReports.description],
                reportedBy = it[DamageReports.reportedBy],
                reportedAt = it[DamageReports.reportedAt],
                resolvedAt = it[DamageReports.resolvedAt],
                resolutionNotes = it[DamageReports.resolutionNotes]
            )
        }
}
