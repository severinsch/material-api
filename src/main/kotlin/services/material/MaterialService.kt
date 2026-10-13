package services.material

import database.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import parseUUID
import routes.*
import java.util.*

data class ItemCounts(val available: Int, val damaged: Int)

class MaterialService {

    private fun getWithCondition(predicate: () -> Op<Boolean>): List<Material> = dbTransaction {
        fun countWhere(condition: Op<Boolean>, alias: String) =
            Case().When(condition, intLiteral(1)).Else(intLiteral(0)).sum().alias(alias)

        val isActive = IndividualItems.status eq ItemStatus.ACTIVE
        // Availability rules for individual items. LOST and DECOMMISSIONED items count as neither.
        val availableCountExpr = countWhere(isActive and (IndividualItems.condition neq ItemCondition.POOR), "available")
        val damagedCountExpr = countWhere(
            (isActive and (IndividualItems.condition eq ItemCondition.POOR)) or
                    (IndividualItems.status eq ItemStatus.IN_REPAIR),
            "damaged"
        )

        val individualItemCounts: Map<UUID, ItemCounts> = IndividualItems
            .select(IndividualItems.materialId, availableCountExpr, damagedCountExpr)
            .groupBy(IndividualItems.materialId)
            .associate {
                it[IndividualItems.materialId].value to ItemCounts(
                    available = it[availableCountExpr] ?: 0,
                    damaged = it[damagedCountExpr] ?: 0
                )
            }

        val allMaterials = Materials
            .leftJoin(BulkStock)
            .leftJoin(StorageLocations, { BulkStock.storageLocationId }, { StorageLocations.id })
            .selectAll()
            .where(predicate)
            .map { row ->
                val counts = if (row[Materials.trackingType] == MaterialTrackingType.INDIVIDUAL) {
                    individualItemCounts[row[Materials.id].value] ?: ItemCounts(0, 0)
                } else {
                    ItemCounts(row[BulkStock.quantityAvailable], row[BulkStock.quantityDamaged])
                }
                toMaterialResponse(row, counts)
            }

        return@dbTransaction allMaterials
    }

    fun getAll(): List<Material> = getWithCondition { Op.TRUE }

    fun getByType(type: MaterialTrackingType): List<Material> =
        getWithCondition { Materials.trackingType eq type }

    fun getById(id: UUID): Material? = getWithCondition { Materials.id eq id }.singleOrNull()

    fun create(newBulkMaterial: NewBulkMaterial): BulkMaterial = dbTransaction {
        val newId = Materials.insertAndGetId {
            it[name] = newBulkMaterial.name
            it[trackingType] = MaterialTrackingType.BULK
            it[category] = newBulkMaterial.category
            it[description] = newBulkMaterial.description
        }

        BulkStock.insert {
            it[materialId] = newId.value
            it[quantityAvailable] = newBulkMaterial.availableCount
            it[quantityDamaged] = newBulkMaterial.damagedCount
            it[storageLocationId] = newBulkMaterial.storageLocationId?.let { id -> parseUUID(id) }
            it[locationDetail] = newBulkMaterial.locationDetail
        }

        return@dbTransaction (getById(newId.value) ?: throw Exception(
            "Failed to create material with name '${newBulkMaterial.name}'"
        )) as BulkMaterial
    }

    fun create(newIndividualMaterial: NewIndividualMaterial): IndividualMaterialSummary = dbTransaction {
        val newId = Materials.insertAndGetId {
            it[name] = newIndividualMaterial.name
            it[trackingType] = MaterialTrackingType.INDIVIDUAL
            it[category] = newIndividualMaterial.category
            it[description] = newIndividualMaterial.description
        }

        return@dbTransaction (getById(newId.value) ?: throw Exception(
            "Failed to create material with name '${newIndividualMaterial.name}'"
        )) as IndividualMaterialSummary
    }

    fun update(id: UUID, newIndividualMaterial: NewIndividualMaterial): IndividualMaterialSummary? =
        dbTransaction {
            Materials.update({ Materials.id eq id }) {
                it[name] = newIndividualMaterial.name
                it[category] = newIndividualMaterial.category
                it[description] = newIndividualMaterial.description
            }

            return@dbTransaction getById(id) as IndividualMaterialSummary?
        }

    fun update(id: UUID, newBulkMaterial: NewBulkMaterial): BulkMaterial? = dbTransaction {
        Materials.update({ Materials.id eq id }) {
            it[name] = newBulkMaterial.name
            it[category] = newBulkMaterial.category
            it[description] = newBulkMaterial.description
        }

        BulkStock.update({ BulkStock.materialId eq id }) {
            it[quantityAvailable] = newBulkMaterial.availableCount
            it[quantityDamaged] = newBulkMaterial.damagedCount
            it[storageLocationId] = newBulkMaterial.storageLocationId?.let { id -> parseUUID(id) }
            it[locationDetail] = newBulkMaterial.locationDetail
        }

        return@dbTransaction getById(id) as BulkMaterial?
    }

    fun delete(id: UUID): Boolean = dbTransaction {
        val noDeleted = Materials.deleteWhere { Materials.id eq id }
        return@dbTransaction noDeleted > 0
    }

    private fun toMaterialResponse(row: ResultRow, counts: ItemCounts): Material {

        val locationResponse = row.getOrNull(StorageLocations.id)?.let { locationId ->
            StorageLocation(
                id = locationId.value.toString(),
                name = row[StorageLocations.name],
                description = row[StorageLocations.description]
            )
        }

        if (row[Materials.trackingType] == MaterialTrackingType.INDIVIDUAL) {
            return IndividualMaterialSummary(
                id = row[Materials.id].toString(),
                name = row[Materials.name],
                trackingType = row[Materials.trackingType],
                description = row[Materials.description],
                category = row[Materials.category],
                availableCount = counts.available,
                damagedCount = counts.damaged
            )
        } else {
            return BulkMaterial(
                id = row[Materials.id].toString(),
                name = row[Materials.name],
                trackingType = row[Materials.trackingType],
                location = locationResponse,
                description = row[Materials.description],
                category = row[Materials.category],
                availableCount = counts.available,
                damagedCount = counts.damaged,
                locationDetail = row[BulkStock.locationDetail],
            )
        }


    }
}
