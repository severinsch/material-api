package services.material

import database.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import routes.BulkMaterialResponse
import routes.IndividualMaterialSummaryResponse
import routes.MaterialResponse
import routes.NewBulkMaterial
import routes.NewMaterial
import routes.StorageLocation
import java.time.LocalDate
import java.util.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

data class ItemCounts(val total: Int, val damaged: Int)

class MaterialService {

    private fun getWithCondition(predicate: () -> Op<Boolean>): List<MaterialResponse> = transaction {
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

    fun getAll(): List<MaterialResponse> = getWithCondition { Op.TRUE }

    fun getByType(type: MaterialTrackingType): List<MaterialResponse> =
        getWithCondition { Materials.trackingType eq type }

    @OptIn(ExperimentalUuidApi::class)
    fun getById(uuid: Uuid): MaterialResponse? = getById(uuid.toJavaUuid())

    private fun getById(id: UUID): MaterialResponse? = getWithCondition { Materials.id eq id }.singleOrNull()

    @OptIn(ExperimentalUuidApi::class)
    fun create(newBulkMaterial: NewBulkMaterial): BulkMaterialResponse = transaction {
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
            it[storageLocationId] = newBulkMaterial.storageLocationId.toJavaUuid()
        }

        return@transaction (getById(newId.value) ?: throw Exception(
            "Failed to create material with name '${newBulkMaterial.name}'"
        )) as BulkMaterialResponse
    }

    fun create(newMaterial: NewMaterial): IndividualMaterialSummaryResponse = transaction {
        val newId = Materials.insertAndGetId {
            it[name] = newMaterial.name
            it[trackingType] = newMaterial.trackingType
            it[category] = newMaterial.category
            it[description] = newMaterial.description
        }

        return@transaction (getById(newId.value) ?: throw Exception(
            "Failed to create material with name '${newMaterial.name}'"
        )) as IndividualMaterialSummaryResponse
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun toMaterialResponse(row: ResultRow, available: Int, damaged: Int): MaterialResponse {

        val locationResponse = if (row.getOrNull(StorageLocations.id) != null) {
            StorageLocation(
                id = Uuid.parse(row[StorageLocations.id].value.toString()),
                name = row[StorageLocations.name],
                description = row[StorageLocations.description]
            )
        } else {
            null
        }

        if(row[Materials.trackingType] == MaterialTrackingType.INDIVIDUAL) {
            return IndividualMaterialSummaryResponse(
                id = Uuid.parse(row[Materials.id].toString()),
                name = row[Materials.name],
                trackingType = row[Materials.trackingType],
                location = locationResponse,
                description = row[Materials.description],
                category = row[Materials.category],
                availableCount = available,
                damagedCount = damaged
            )
        } else {
            return BulkMaterialResponse(
                id = Uuid.parse(row[Materials.id].toString()),
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
