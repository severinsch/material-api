package services.kits


import database.KitMaterials
import database.Kits
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.leftJoin
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import parseUUID
import routes.Kit
import routes.NewKit
import services.material.MaterialService
import java.util.*


class KitService(val materialService: MaterialService) {

    private fun getWithCondition(predicate: () -> Op<Boolean>): List<Kit> = transaction {
        val kits = Kits
            .leftJoin(KitMaterials, { Kits.id }, { KitMaterials.kitId })
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
                .selectAll()
                .where(KitMaterials.kitId eq parseUUID(it.id))
                .map { mat ->
                    materialService
                        .getById(mat[KitMaterials.materialId].value)!! to mat[KitMaterials.quantityRequired]
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

        newKit.materials.forEach { (materialId, quantity) ->
            KitMaterials.insert {
                it[kitId] = newId.value
                it[KitMaterials.materialId] = parseUUID(materialId)
                it[quantityRequired] = quantity
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
        newKit.materials.forEach { (materialId, quantity) ->
            KitMaterials.insert {
                it[kitId] = id
                it[KitMaterials.materialId] = parseUUID(materialId)
                it[quantityRequired] = quantity
            }
        }

        return@transaction getById(id)
    }
}