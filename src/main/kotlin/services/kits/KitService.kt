package services.kits


import database.*
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import parseUUID
import routes.*
import java.util.*


class KitService {

    private fun getWithCondition(predicate: () -> Op<Boolean>): List<Kit> = dbTransaction {
        val kitRows = Kits.selectAll().where(predicate).toList()
        val kitIds = kitRows.map { it[Kits.id] }

        val groupRows = KitGroups.selectAll().where { KitGroups.kitId inList kitIds }.toList()
        val optionsByGroup = KitGroupOptions
            .selectAll()
            .where { KitGroupOptions.groupId inList groupRows.map { it[KitGroups.id] } }
            .orderBy(KitGroupOptions.priority)
            .groupBy({ it[KitGroupOptions.groupId].value }) {
                KitGroupOption(
                    materialId = it[KitGroupOptions.materialId].value.toString(),
                    unitValue = it[KitGroupOptions.unitValue].toDouble(),
                    priority = it[KitGroupOptions.priority]
                )
            }
        val groupsByKit = groupRows.groupBy({ it[KitGroups.kitId].value }) {
            val groupId = it[KitGroups.id].value
            KitGroup(
                id = groupId.toString(),
                name = it[KitGroups.name],
                requiredUnits = it[KitGroups.requiredUnits].toDouble(),
                isOptional = it[KitGroups.isOptional],
                options = optionsByGroup[groupId].orEmpty()
            )
        }

        kitRows.map { row ->
            Kit(
                id = row[Kits.id].value.toString(),
                name = row[Kits.name],
                description = row[Kits.description],
                groups = groupsByKit[row[Kits.id].value].orEmpty()
            )
        }
    }

    fun getById(id: UUID): Kit? {
        return getWithCondition { Kits.id eq id }.singleOrNull()
    }

    fun getAll(): List<Kit> {
        return getWithCondition { Op.TRUE }
    }

    fun create(newKit: NewKit): Kit = dbTransaction {
        val newId = Kits.insertAndGetId {
            it[name] = newKit.name
            it[description] = newKit.description
        }

        newKit.groups.forEach { group ->
            val newGroupId = KitGroups.insertAndGetId {
                it[kitId] = newId.value
                it[name] = group.name
                it[requiredUnits] = group.requiredUnits.toBigDecimal()
                it[isOptional] = group.isOptional
            }
            KitGroupOptions.batchInsert(group.options) { option ->
                this[KitGroupOptions.groupId] = newGroupId.value
                this[KitGroupOptions.materialId] = parseUUID(option.materialId)
                this[KitGroupOptions.unitValue] = option.unitValue.toBigDecimal()
                this[KitGroupOptions.priority] = option.priority
            }
        }

        return@dbTransaction getById(newId.value) ?: throw Exception(
            "Failed to create kit with name '${newKit.name}'"
        )
    }
}
