package services

import api.IndividualItem
import api.MaterialTrackingType
import api.NewIndividualItem
import database.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.*
import kotlin.uuid.Uuid

class ItemService(private val db: Db) {

    companion object {
        /** Most items created by one batch request. */
        const val MAX_BATCH = 100
    }

    suspend fun getAll(tenant: TenantId, materialId: Uuid? = null): List<IndividualItem> = db.tenantQuery(tenant) {
        findWhere { materialId?.let { IndividualItems.materialId eq it } ?: Op.TRUE }
    }

    suspend fun get(tenant: TenantId, id: Uuid): IndividualItem = db.tenantQuery(tenant) { find(id) }

    suspend fun create(tenant: TenantId, item: NewIndividualItem): IndividualItem =
        createAll(tenant, listOf(item)).single()

    /** Creates several items at once; either all of them or none. */
    suspend fun createAll(tenant: TenantId, items: List<NewIndividualItem>): List<IndividualItem> =
        db.tenantQuery(tenant) {
            if (items.isEmpty()) invalid("Es wurden keine Gegenstände angegeben.")
            if (items.size > MAX_BATCH) invalid("Es können höchstens $MAX_BATCH Gegenstände auf einmal angelegt werden.")
            items.map { it.materialId }.distinct().forEach { requireMaterial(it, MaterialTrackingType.INDIVIDUAL) }
            items.mapNotNull { it.storageLocationId }.distinct().forEach(::requireLocation)
            items.groupBy({ it.materialId }, { it.identifier }).forEach { (materialId, identifiers) ->
                requireFreeIdentifiers(materialId, identifiers)
            }
            val ids = IndividualItems.batchInsert(items) { fill(it) }.map { it[IndividualItems.id].value }
            findWhere { IndividualItems.id inList ids }
        }

    suspend fun update(tenant: TenantId, id: Uuid, item: NewIndividualItem): IndividualItem = db.tenantQuery(tenant) {
        requireUnchanged("Das Material eines Gegenstands", find(id).materialId, item.materialId)
        requireLocation(item.storageLocationId)
        requireFreeIdentifiers(item.materialId, listOf(item.identifier), exceptItem = id)
        IndividualItems.update({ IndividualItems.id eq id }) { it.fill(item) }
        find(id)
    }

    suspend fun delete(tenant: TenantId, id: Uuid) = db.tenantQuery(tenant) {
        if (IndividualItems.deleteWhere { IndividualItems.id eq id } == 0) notFound("Gegenstand", id)
    }

    /** Rejects identifiers that are used twice or already taken by another item of the material. */
    private fun requireFreeIdentifiers(materialId: Uuid, identifiers: List<String>, exceptItem: Uuid? = null) {
        val taken = IndividualItems
            .select(IndividualItems.identifier)
            .where {
                (IndividualItems.materialId eq materialId) and (IndividualItems.identifier inList identifiers) and
                    (exceptItem?.let { IndividualItems.id neq it } ?: Op.TRUE)
            }
            .map { it[IndividualItems.identifier] }
        val duplicates = identifiers.groupBy { it }.filterValues { it.size > 1 }.keys
        val clashes = (taken + duplicates).distinct().sorted()
        if (clashes.isEmpty()) return
        val material = Materials.select(Materials.name).where { Materials.id eq materialId }.single()[Materials.name]
        conflict("$material ${clashes.joinToString()} gibt es schon.")
    }

    private fun find(id: Uuid): IndividualItem =
        findWhere { IndividualItems.id eq id }.singleOrNull() ?: notFound("Gegenstand", id)

    private fun findWhere(predicate: () -> Op<Boolean>): List<IndividualItem> {
        val onLoan = exists(
            LoanLines.innerJoin(Loans).select(LoanLines.id).where {
                (LoanLines.individualItemId eq IndividualItems.id) and Loans.returnedAt.isNull()
            }
        )
        // Rows that reference the item and keep it from being deleted.
        val hasHistory = exists(
            DamageReports.select(DamageReports.id).where { DamageReports.individualItemId eq IndividualItems.id }
        ) or exists(LoanLines.select(LoanLines.id).where { LoanLines.individualItemId eq IndividualItems.id })
        return IndividualItems
            .leftJoin(StorageLocations)
            .select(IndividualItems.columns + StorageLocations.columns + onLoan + hasHistory)
            .where(predicate)
            .orderBy(IndividualItems.materialId to SortOrder.ASC, IndividualItems.identifier to SortOrder.ASC)
            .map { row ->
                IndividualItem(
                    id = row[IndividualItems.id].value,
                    materialId = row[IndividualItems.materialId].value,
                    identifier = row[IndividualItems.identifier],
                    condition = row[IndividualItems.condition],
                    status = row[IndividualItems.status],
                    storageLocation = LocationService.toStorageLocationOrNull(row),
                    locationDetail = row[IndividualItems.locationDetail],
                    purchasePrice = row[IndividualItems.purchasePrice]?.toDouble(),
                    purchaseDate = row[IndividualItems.purchaseDate],
                    onLoan = row[onLoan],
                    hasHistory = row[hasHistory]
                )
            }
    }

    private fun UpdateBuilder<*>.fill(item: NewIndividualItem) {
        this[IndividualItems.materialId] = item.materialId
        this[IndividualItems.identifier] = item.identifier
        this[IndividualItems.condition] = item.condition
        this[IndividualItems.status] = item.status
        this[IndividualItems.storageLocationId] = item.storageLocationId
        this[IndividualItems.locationDetail] = item.locationDetail
        this[IndividualItems.purchasePrice] = item.purchasePrice?.toBigDecimal()
        this[IndividualItems.purchaseDate] = item.purchaseDate
    }
}
