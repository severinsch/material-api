package services

import api.*
import database.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.*
import kotlin.uuid.Uuid

class MaterialService(private val db: Db) {

    private data class Counts(val available: Int = 0, val damaged: Int = 0)

    suspend fun getAll(type: MaterialTrackingType? = null): List<Material> = db.query {
        findWhere { type?.let { Materials.trackingType eq it } ?: Op.TRUE }
    }

    suspend fun get(id: Uuid): Material = db.query { find(id) }

    suspend fun create(material: NewMaterial): Material = db.query {
        if (material is NewBulkMaterial) requireLocation(material.storageLocationId)
        val id = Materials.insertAndGetId {
            it[trackingType] = material.trackingType
            it.fillMaterial(material)
        }
        if (material is NewBulkMaterial) BulkStock.insert {
            it[materialId] = id
            it.fillStock(material)
        }
        find(id.value)
    }

    /** Replaces a material. Its type can't be changed. */
    suspend fun update(id: Uuid, material: NewMaterial): Material = db.query {
        if (find(id).trackingType != material.trackingType) conflict("Die Erfassungsart eines Materials kann nicht geändert werden.")
        if (material is NewBulkMaterial) {
            requireLocation(material.storageLocationId)
            requireStockUpdate(id, material)
        }
        Materials.update({ Materials.id eq id }) { it.fillMaterial(material) }
        if (material is NewBulkMaterial) BulkStock.update({ BulkStock.materialId eq id }) { it.fillStock(material) }
        find(id)
    }

    suspend fun delete(id: Uuid) = db.query {
        if (Materials.deleteWhere { Materials.id eq id } == 0) notFound("Material", id)
    }

    /** Rejects stock changes based on an outdated count, and stocks below what is lent out. */
    private fun requireStockUpdate(id: Uuid, material: NewBulkMaterial) {
        val current = lockStock(id)
        if (material.expectedAvailableCount != null && material.expectedAvailableCount != current) {
            conflict(
                "Der Bestand von ${material.name} wurde inzwischen auf $current geändert, z. B. durch einen Einkauf. " +
                    "Lade die Seite neu und bearbeite das Material erneut."
            )
        }
        val lent = lentQuantity(id)
        if (material.availableCount < lent) {
            conflict("Von ${material.name} sind $lent ausgeliehen, der Bestand kann nicht kleiner sein.")
        }
    }

    private fun find(id: Uuid): Material = findWhere { Materials.id eq id }.singleOrNull() ?: notFound("Material", id)

    private fun findWhere(predicate: () -> Op<Boolean>): List<Material> {
        val itemCounts = individualItemCounts()
        val loaned = loanedCounts()
        return Materials
            .leftJoin(BulkStock)
            .leftJoin(StorageLocations, { BulkStock.storageLocationId }, { StorageLocations.id })
            .selectAll()
            .where(predicate)
            .orderBy(Materials.name)
            .map { row ->
                val id = row[Materials.id].value
                when (row[Materials.trackingType]) {
                    MaterialTrackingType.BULK -> BulkMaterial(
                        id = id,
                        name = row[Materials.name],
                        category = row[Materials.category],
                        description = row[Materials.description],
                        availableCount = row[BulkStock.quantityAvailable],
                        damagedCount = row[BulkStock.quantityDamaged],
                        loanedCount = loaned[id] ?: 0,
                        location = LocationService.toStorageLocationOrNull(row),
                        locationDetail = row[BulkStock.locationDetail]
                    )

                    MaterialTrackingType.INDIVIDUAL -> {
                        val counts = itemCounts[id] ?: Counts()
                        IndividualMaterial(
                            id = id,
                            name = row[Materials.name],
                            category = row[Materials.category],
                            description = row[Materials.description],
                            availableCount = counts.available,
                            damagedCount = counts.damaged,
                            loanedCount = loaned[id] ?: 0
                        )
                    }
                }
            }
    }

    /** Individual items that count as available: active and not in POOR condition. */
    private val isAvailableItem
        get() = (IndividualItems.status eq ItemStatus.ACTIVE) and (IndividualItems.condition neq ItemCondition.POOR)

    /** Availability rules for individual items. LOST and DECOMMISSIONED items count as neither. */
    private fun individualItemCounts(): Map<Uuid, Counts> {
        val isActive = IndividualItems.status eq ItemStatus.ACTIVE
        val isPoor = IndividualItems.condition eq ItemCondition.POOR
        val available = countWhere(isAvailableItem)
        val damaged = countWhere((isActive and isPoor) or (IndividualItems.status eq ItemStatus.IN_REPAIR))
        return IndividualItems
            .select(IndividualItems.materialId, available, damaged)
            .groupBy(IndividualItems.materialId)
            .associate { it[IndividualItems.materialId].value to Counts(it[available] ?: 0, it[damaged] ?: 0) }
    }

    /**
     * Available pieces per material that are part of a loan that hasn't been returned yet. Lent items that don't count
     * as available (e.g. in POOR condition) are left out, so `availableCount - loanedCount` is what's in storage.
     */
    private fun loanedCounts(): Map<Uuid, Int> {
        val openLines = LoanLines.innerJoin(Loans).leftJoin(IndividualItems)
        val materialId = Coalesce(LoanLines.bulkMaterialId, IndividualItems.materialId)
        val pieces = Coalesce(LoanLines.quantity, intLiteral(1)).sum()
        return openLines
            .select(materialId, pieces)
            .where { Loans.returnedAt.isNull() and (LoanLines.bulkMaterialId.isNotNull() or isAvailableItem) }
            .groupBy(materialId)
            .associate { it[materialId].value to (it[pieces] ?: 0) }
    }

    private fun countWhere(condition: Op<Boolean>) = Case().When(condition, intLiteral(1)).Else(intLiteral(0)).sum()

    private val Material.trackingType
        get() = when (this) {
            is BulkMaterial -> MaterialTrackingType.BULK
            is IndividualMaterial -> MaterialTrackingType.INDIVIDUAL
        }

    private val NewMaterial.trackingType
        get() = when (this) {
            is NewBulkMaterial -> MaterialTrackingType.BULK
            is NewIndividualMaterial -> MaterialTrackingType.INDIVIDUAL
        }

    private fun UpdateBuilder<*>.fillMaterial(material: NewMaterial) {
        this[Materials.name] = material.name
        this[Materials.category] = material.category
        this[Materials.description] = material.description
    }

    private fun UpdateBuilder<*>.fillStock(material: NewBulkMaterial) {
        this[BulkStock.quantityAvailable] = material.availableCount
        this[BulkStock.quantityDamaged] = material.damagedCount
        this[BulkStock.storageLocationId] = material.storageLocationId
        this[BulkStock.locationDetail] = material.locationDetail
    }
}
