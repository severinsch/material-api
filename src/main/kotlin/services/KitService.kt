package services

import api.Kit
import api.KitGroup
import api.KitGroupOption
import api.NewKit
import database.Db
import database.KitGroupOptions
import database.KitGroups
import database.Kits
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import kotlin.uuid.Uuid

class KitService(private val db: Db) {

    suspend fun getAll(): List<Kit> = db.query { findWhere { Op.TRUE } }

    suspend fun get(id: Uuid): Kit = db.query { find(id) }

    suspend fun create(kit: NewKit): Kit = db.query {
        val id = Kits.insertAndGetId {
            it[name] = kit.name
            it[description] = kit.description
        }
        insertGroups(id, kit)
        find(id.value)
    }

    /** Replaces the kit including all of its groups. */
    suspend fun update(id: Uuid, kit: NewKit): Kit = db.query {
        val updated = Kits.update({ Kits.id eq id }) {
            it[name] = kit.name
            it[description] = kit.description
        }
        if (updated == 0) notFound("Kit", id)
        KitGroups.deleteWhere { kitId eq id }
        insertGroups(EntityID(id, Kits), kit)
        find(id)
    }

    suspend fun delete(id: Uuid) = db.query {
        if (Kits.deleteWhere { Kits.id eq id } == 0) notFound("Kit", id)
    }

    private fun insertGroups(kitId: EntityID<Uuid>, kit: NewKit) = kit.groups.forEach { group ->
        val groupId = KitGroups.insertAndGetId {
            it[KitGroups.kitId] = kitId
            it[name] = group.name
            it[requiredUnits] = group.requiredUnits.toBigDecimal()
            it[isOptional] = group.isOptional
        }
        KitGroupOptions.batchInsert(group.options) { option ->
            this[KitGroupOptions.groupId] = groupId
            this[KitGroupOptions.materialId] = option.materialId
            this[KitGroupOptions.unitValue] = option.unitValue.toBigDecimal()
            this[KitGroupOptions.priority] = option.priority
        }
    }

    private fun find(id: Uuid): Kit = findWhere { Kits.id eq id }.singleOrNull() ?: notFound("Kit", id)

    private fun findWhere(predicate: () -> Op<Boolean>): List<Kit> {
        val kitRows = Kits.selectAll().where(predicate).orderBy(Kits.name).toList()
        val groupRows = KitGroups.selectAll()
            .where { KitGroups.kitId inList kitRows.map { it[Kits.id] } }
            .orderBy(KitGroups.id) // insertion order
            .toList()
        val optionsByGroup = KitGroupOptions
            .selectAll()
            .where { KitGroupOptions.groupId inList groupRows.map { it[KitGroups.id] } }
            .orderBy(KitGroupOptions.priority)
            .groupBy({ it[KitGroupOptions.groupId].value }) {
                KitGroupOption(
                    materialId = it[KitGroupOptions.materialId].value,
                    unitValue = it[KitGroupOptions.unitValue].toDouble(),
                    priority = it[KitGroupOptions.priority]
                )
            }
        val groupsByKit = groupRows.groupBy({ it[KitGroups.kitId].value }) {
            KitGroup(
                id = it[KitGroups.id].value,
                name = it[KitGroups.name],
                requiredUnits = it[KitGroups.requiredUnits].toDouble(),
                isOptional = it[KitGroups.isOptional],
                options = optionsByGroup[it[KitGroups.id].value].orEmpty()
            )
        }
        return kitRows.map {
            Kit(
                id = it[Kits.id].value,
                name = it[Kits.name],
                description = it[Kits.description],
                groups = groupsByKit[it[Kits.id].value].orEmpty()
            )
        }
    }
}
