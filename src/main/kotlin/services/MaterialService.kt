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

    suspend fun create(material: NewBulkMaterial): Material = db.query {
        val id = Materials.insertAndGetId {
            it[trackingType] = MaterialTrackingType.BULK
            it.fill(material.name, material.category, material.description)
        }
        BulkStock.insert {
            it[materialId] = id
            it.fill(material)
        }
        find(id.value)
    }

    suspend fun create(material: NewIndividualMaterial): Material = db.query {
        val id = Materials.insertAndGetId {
            it[trackingType] = MaterialTrackingType.INDIVIDUAL
            it.fill(material.name, material.category, material.description)
        }
        find(id.value)
    }

    suspend fun update(id: Uuid, material: NewBulkMaterial): Material = db.query {
        updateMaterial(id, MaterialTrackingType.BULK, material.name, material.category, material.description)
        BulkStock.update({ BulkStock.materialId eq id }) { it.fill(material) }
        find(id)
    }

    suspend fun update(id: Uuid, material: NewIndividualMaterial): Material = db.query {
        updateMaterial(id, MaterialTrackingType.INDIVIDUAL, material.name, material.category, material.description)
        find(id)
    }

    suspend fun delete(id: Uuid) = db.query {
        if (Materials.deleteWhere { Materials.id eq id } == 0) notFound("Material", id)
    }

    /** Updates the shared columns, but only if the material has the expected [type]. */
    private fun updateMaterial(id: Uuid, type: MaterialTrackingType, name: String, category: String?, description: String?) {
        val updated = Materials.update({ (Materials.id eq id) and (Materials.trackingType eq type) }) {
            it.fill(name, category, description)
        }
        if (updated == 0) notFound("$type material", id)
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

    /** Availability rules for individual items. LOST and DECOMMISSIONED items count as neither. */
    private fun individualItemCounts(): Map<Uuid, Counts> {
        val isActive = IndividualItems.status eq ItemStatus.ACTIVE
        val isPoor = IndividualItems.condition eq ItemCondition.POOR
        val available = countWhere(isActive and not(isPoor))
        val damaged = countWhere((isActive and isPoor) or (IndividualItems.status eq ItemStatus.IN_REPAIR))
        return IndividualItems
            .select(IndividualItems.materialId, available, damaged)
            .groupBy(IndividualItems.materialId)
            .associate { it[IndividualItems.materialId].value to Counts(it[available] ?: 0, it[damaged] ?: 0) }
    }

    /** Pieces per material that are part of a loan that hasn't been returned yet. */
    private fun loanedCounts(): Map<Uuid, Int> {
        val openLines = LoanLines.innerJoin(Loans).leftJoin(IndividualItems)
        val materialId = Coalesce(LoanLines.bulkMaterialId, IndividualItems.materialId)
        val pieces = Coalesce(LoanLines.quantity, intLiteral(1)).sum()
        return openLines
            .select(materialId, pieces)
            .where { Loans.returnedAt.isNull() }
            .groupBy(materialId)
            .associate { it[materialId].value to (it[pieces] ?: 0) }
    }

    private fun countWhere(condition: Op<Boolean>) = Case().When(condition, intLiteral(1)).Else(intLiteral(0)).sum()

    private fun UpdateBuilder<*>.fill(name: String, category: String?, description: String?) {
        this[Materials.name] = name
        this[Materials.category] = category
        this[Materials.description] = description
    }

    private fun UpdateBuilder<*>.fill(material: NewBulkMaterial) {
        this[BulkStock.quantityAvailable] = material.availableCount
        this[BulkStock.quantityDamaged] = material.damagedCount
        this[BulkStock.storageLocationId] = material.storageLocationId
        this[BulkStock.locationDetail] = material.locationDetail
    }
}
