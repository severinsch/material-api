package services.kits


import database.KitMaterials
import database.Kits
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.leftJoin
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import parseUUID
import routes.Kit
import routes.MaterialResponse
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

    fun getMaterialsForKit(id: UUID): List<Pair<MaterialResponse, Int>> = transaction {
        return@transaction getWithCondition { Kits.id eq id }.single().materials
    }

    fun getById(id: UUID): Kit? {
        return getWithCondition { Kits.id eq id }.singleOrNull()
    }

    fun getAll(): List<Kit> {
        return getWithCondition { Op.TRUE }
    }
}