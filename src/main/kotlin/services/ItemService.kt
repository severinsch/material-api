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

    /** All items, or those of all variants of [materialId]. */
    suspend fun getAll(tenant: TenantId, materialId: Uuid? = null): List<IndividualItem> = db.tenantQuery(tenant) {
        findWhere { materialId?.let { MaterialVariants.materialId eq it } ?: Op.TRUE }
    }

    suspend fun get(tenant: TenantId, id: Uuid): IndividualItem = db.tenantQuery(tenant) { find(id) }

    suspend fun create(tenant: TenantId, item: NewIndividualItem): IndividualItem =
        createAll(tenant, listOf(item)).single()

    /** Creates several items at once; either all of them or none. */
    suspend fun createAll(tenant: TenantId, items: List<NewIndividualItem>): List<IndividualItem> =
        db.tenantQuery(tenant) {
            if (items.isEmpty()) invalid("Es wurden keine Gegenstände angegeben.")
            if (items.size > MAX_BATCH) invalid("Es können höchstens $MAX_BATCH Gegenstände auf einmal angelegt werden.")
            items.map { it.variantId }.distinct().forEach { requireVariant(it, MaterialTrackingType.INDIVIDUAL) }
            items.mapNotNull { it.storageLocationId }.distinct().forEach(::requireLocation)
            items.groupBy({ it.variantId }, { it.identifier }).forEach { (variantId, identifiers) ->
                requireFreeIdentifiers(variantId, identifiers)
            }
            val ids = IndividualItems.batchInsert(items) { fill(it) }.map { it[IndividualItems.id].value }
            findWhere { IndividualItems.id inList ids }
        }

    /**
     * Replaces an item. It can move to another variant of its material (e.g. to fix a wrong entry); like a status
     * change, that isn't checked against quantity loans of the old variant.
     */
    suspend fun update(tenant: TenantId, id: Uuid, item: NewIndividualItem): IndividualItem = db.tenantQuery(tenant) {
        val existing = find(id)
        if (item.variantId != existing.variantId) {
            val materialId = requireVariant(item.variantId, MaterialTrackingType.INDIVIDUAL)
            requireUnchanged("Das Material eines Gegenstands", existing.materialId, materialId)
        }
        requireLocation(item.storageLocationId)
        requireFreeIdentifiers(item.variantId, listOf(item.identifier), exceptItem = id)
        IndividualItems.update({ IndividualItems.id eq id }) { it.fill(item) }
        find(id)
    }

    suspend fun delete(tenant: TenantId, id: Uuid) = db.tenantQuery(tenant) {
        if (IndividualItems.deleteWhere { IndividualItems.id eq id } == 0) notFound("Gegenstand", id)
    }

    /** Rejects identifiers that are used twice or already taken by another item of the variant. */
    private fun requireFreeIdentifiers(variantId: Uuid, identifiers: List<String>, exceptItem: Uuid? = null) {
        val taken = IndividualItems
            .select(IndividualItems.identifier)
            .where {
                (IndividualItems.variantId eq variantId) and (IndividualItems.identifier inList identifiers) and
                    (exceptItem?.let { IndividualItems.id neq it } ?: Op.TRUE)
            }
            .map { it[IndividualItems.identifier] }
        val duplicates = identifiers.groupBy { it }.filterValues { it.size > 1 }.keys
        val clashes = (taken + duplicates).distinct().sorted()
        if (clashes.isEmpty()) return
        conflict("${variantName(variantId)} ${clashes.joinToString()} gibt es schon.")
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
        val variantColumns = listOf(MaterialVariants.materialId, MaterialVariants.name)
        // By material, variant (see variantNameOrder) and identifier; the sort is stable and keeps the query's order.
        return IndividualItems
            .innerJoin(MaterialVariants)
            .leftJoin(StorageLocations)
            .select(IndividualItems.columns + StorageLocations.columns + variantColumns + onLoan + hasHistory)
            .where(predicate)
            .orderBy(MaterialVariants.materialId to SortOrder.ASC, IndividualItems.identifier to SortOrder.ASC)
            .sortedWith(
                compareBy<ResultRow> { it[MaterialVariants.materialId].value }
                    .thenBy(variantNameOrder) { it[MaterialVariants.name] }
            )
            .map { row ->
                IndividualItem(
                    id = row[IndividualItems.id].value,
                    materialId = row[MaterialVariants.materialId].value,
                    variantId = row[IndividualItems.variantId].value,
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
        this[IndividualItems.variantId] = item.variantId
        this[IndividualItems.identifier] = item.identifier
        this[IndividualItems.condition] = item.condition
        this[IndividualItems.status] = item.status
        this[IndividualItems.storageLocationId] = item.storageLocationId
        this[IndividualItems.locationDetail] = item.locationDetail
        this[IndividualItems.purchasePrice] = item.purchasePrice?.toBigDecimal()
        this[IndividualItems.purchaseDate] = item.purchaseDate
    }
}
