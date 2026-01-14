package services.material

import database.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import parseUUID
import routes.*
import java.time.LocalDate
import java.util.*

data class ItemCounts(val total: Int, val damaged: Int)

class MaterialService {

    private fun getWithCondition(predicate: () -> Op<Boolean>): List<Material> = transaction {
        val isDamagedCondition = (IndividualItems.condition eq ItemCondition.POOR) or
                (IndividualItems.condition eq ItemCondition.DECOMMISSIONED)

        val damagedCountExpr = Case()
            .When(isDamagedCondition, intLiteral(1))
            .Else(intLiteral(0)).sum()
            .alias("damaged_count")

        val individualItemCounts: Map<UUID, ItemCounts> = IndividualItems
            .select(
                IndividualItems.materialId,
                IndividualItems.id.count(), // total count
                damagedCountExpr
            )
            .groupBy(IndividualItems.materialId)
            .associate {
                it[IndividualItems.materialId].value to ItemCounts(
                    total = it[IndividualItems.id.count()].toInt(),
                    damaged = it[damagedCountExpr] ?: 0

                )
            }

        val allMaterials = Materials
            .leftJoin(BulkStock)
            .selectAll()
            .where(predicate)
            .map { row ->
                val (available, damaged) = if (row[Materials.trackingType] == MaterialTrackingType.INDIVIDUAL) {
                    val counts = individualItemCounts[row[Materials.id].value]
                    counts?.let { Pair(it.total - it.damaged, it.damaged) } ?: Pair(0, 0)
                } else {
                    Pair(row[BulkStock.quantityAvailable], row[BulkStock.quantityDamaged])
                }
                toMaterialResponse(row, available, damaged)
            }

        return@transaction allMaterials
    }

    fun getAll(): List<Material> = getWithCondition { Op.TRUE }

    fun getByType(type: MaterialTrackingType): List<Material> =
        getWithCondition { Materials.trackingType eq type }

    fun getById(id: UUID): Material? = getWithCondition { Materials.id eq id }.singleOrNull()

    fun create(newBulkMaterial: NewBulkMaterial): BulkMaterial = transaction {
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
            it[purchasePrice] = newBulkMaterial.purchasePrice?.toBigDecimal()
            it[purchaseDate] = newBulkMaterial.purchaseDate?.let { d -> LocalDate.parse(d) }
            it[storageLocationId] = newBulkMaterial.storageLocationId?.let { id -> parseUUID(id) }
        }

        return@transaction (getById(newId.value) ?: throw Exception(
            "Failed to create material with name '${newBulkMaterial.name}'"
        )) as BulkMaterial
    }

    fun create(newIndividualMaterial: NewIndividualMaterial): IndividualMaterialSummary = transaction {
        val newId = Materials.insertAndGetId {
            it[name] = newIndividualMaterial.name
            it[trackingType] = MaterialTrackingType.INDIVIDUAL
            it[category] = newIndividualMaterial.category
            it[description] = newIndividualMaterial.description
        }

        return@transaction (getById(newId.value) ?: throw Exception(
            "Failed to create material with name '${newIndividualMaterial.name}'"
        )) as IndividualMaterialSummary
    }

    fun update(id: UUID, newIndividualMaterial: NewIndividualMaterial): IndividualMaterialSummary? =
        transaction {
            Materials.update({ Materials.id eq id }) {
                it[name] = newIndividualMaterial.name
                it[category] = newIndividualMaterial.category
                it[description] = newIndividualMaterial.description
            }

            return@transaction getById(id) as IndividualMaterialSummary?
        }

    fun update(id: UUID, newBulkMaterial: NewBulkMaterial): BulkMaterial? = transaction {
        Materials.update({ Materials.id eq id }) {
            it[name] = newBulkMaterial.name
            it[category] = newBulkMaterial.category
            it[description] = newBulkMaterial.description
        }

        BulkStock.update({ BulkStock.materialId eq id }) {
            it[quantityAvailable] = newBulkMaterial.availableCount
            it[quantityDamaged] = newBulkMaterial.damagedCount
            it[purchasePrice] = newBulkMaterial.purchasePrice?.toBigDecimal()
            it[purchaseDate] = newBulkMaterial.purchaseDate?.let { d -> LocalDate.parse(d) }
            it[storageLocationId] = newBulkMaterial.storageLocationId?.let { id -> parseUUID(id) }
        }

        return@transaction getById(id) as BulkMaterial?
    }

    fun delete(id: UUID): Boolean = transaction {
        val noDeleted = Materials.deleteWhere { Materials.id eq id }
        return@transaction noDeleted > 0
    }

    private fun toMaterialResponse(row: ResultRow, available: Int, damaged: Int): Material {

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
                location = locationResponse,
                description = row[Materials.description],
                category = row[Materials.category],
                availableCount = available,
                damagedCount = damaged
            )
        } else {
            return BulkMaterial(
                id = row[Materials.id].toString(),
                name = row[Materials.name],
                trackingType = row[Materials.trackingType],
                location = locationResponse,
                description = row[Materials.description],
                category = row[Materials.category],
                availableCount = available,
                damagedCount = damaged,
                purchaseDate = row[BulkStock.purchaseDate]?.toString(),
                purchasePrice = row[BulkStock.purchasePrice]?.toDouble(),
            )
        }


    }
}
