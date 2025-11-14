package services.kits


import database.KitMaterials
import database.KitMaterials.kitId
import database.KitMaterials.quantityRequired
import database.Kits
import database.Materials
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import parseUUID
import routes.Kit
import routes.KitMaterialItem
import routes.NewKit
import java.util.*


class KitService {

    private fun getWithCondition(predicate: () -> Op<Boolean>): List<Kit> = transaction {
        val kits = Kits
            .selectAll()
            .where(predicate)
            .map {
                Kit(
                    id = it[Kits.id].toString(),
                    name = it[Kits.name],
                    description = it[Kits.description],
                    materials = emptyList()
                )
            }

        return@transaction kits.map {
            val requiredMaterials = KitMaterials
                .leftJoin(Materials)
                .select(KitMaterials.materialId, quantityRequired, Materials.name)
                .where(kitId eq parseUUID(it.id))
                .map { mat ->
                    KitMaterialItem(
                        materialId = mat[KitMaterials.materialId].toString(),
                        materialName = mat[Materials.name],
                        quantityRequired = mat[quantityRequired]
                    )
                }
            it.copy(materials = requiredMaterials)
        }
    }

    fun getById(id: UUID): Kit? {
        return getWithCondition { Kits.id eq id }.singleOrNull()
    }

    fun getAll(): List<Kit> {
        return getWithCondition { Op.TRUE }
    }

    fun create(newKit: NewKit): Kit = transaction {
        val newId = Kits.insertAndGetId {
            it[name] = newKit.name
            it[description] = newKit.description
        }

        newKit.materials.forEach { matItem ->
            KitMaterials.insert {
                it[kitId] = newId.value
                it[KitMaterials.materialId] = parseUUID(matItem.materialId)
                it[quantityRequired] = matItem.quantityRequired
            }
        }

        return@transaction getById(newId.value) ?: throw Exception(
            "Failed to create kit with name '${newKit.name}'"
        )
    }

    fun update(id: UUID, newKit: NewKit): Kit? = transaction {
        Kits.update({ Kits.id eq id }) {
            it[name] = newKit.name
            it[description] = newKit.description
        }

        KitMaterials.deleteWhere { KitMaterials.kitId eq id }

        KitMaterials.batchInsert(newKit.materials) { matItem ->
            this[kitId] = id
            this[KitMaterials.materialId] = parseUUID(matItem.materialId)
            this[quantityRequired] = matItem.quantityRequired
        }

        return@transaction getById(id)
    }
}