package services
import database.Materials
import database.DatabaseFactory.dbQuery
import database.MaterialTrackingType
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import routes.Material
import routes.NewMaterial
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class MaterialService {

    suspend fun getAll(): List<Material> = dbQuery {
        Materials.selectAll().map(::toMaterial)
    }

    suspend fun create(newMaterial: NewMaterial): Material {
        val id = dbQuery {
            Materials.insert {
                it[name] = newMaterial.name
                it[trackingType] = MaterialTrackingType.valueOf(newMaterial.trackingType)
                newMaterial.storageLocationId?.let { slId -> it[storageLocationId] = UUID.fromString(slId) }
                it[quantityAvailable] = newMaterial.quantityAvailable
                it[quantityDamaged] = newMaterial.quantityDamaged
                it[description] = newMaterial.description
                it[category] = newMaterial.category
                newMaterial.purchasePrice?.let { pp -> it[purchasePrice] = BigDecimal.valueOf(pp) }
                newMaterial.purchaseDate?.let { pd -> it[purchaseDate] = LocalDate.parse(pd) }
            } get Materials.id
        }
        return getById(id)!!
    }

    private suspend fun getById(id: EntityID<UUID>): Material? = dbQuery {
        Materials.selectAll().where { Materials.id eq id }.withDistinct().map(::toMaterial).singleOrNull()
    }

    private fun toMaterial(row: ResultRow): Material = Material(
        id = row[Materials.id].toString(),
        name = row[Materials.name],
        trackingType = row[Materials.trackingType].name,
        storageLocationId = row[Materials.storageLocationId]?.toString(),
        quantityAvailable = row[Materials.quantityAvailable],
        quantityDamaged = row[Materials.quantityDamaged],
        description = row[Materials.description],
        category = row[Materials.category],
        purchasePrice = row[Materials.purchasePrice]?.toDouble(),
        purchaseDate = row[Materials.purchaseDate]?.toString()
    )
}
