package services

import api.Kit
import api.KitGroup
import api.KitGroupAmount
import api.KitGroupOption
import api.NewKit
import database.Db
import database.TenantId
import database.KitGroupAmounts
import database.KitGroupOptions
import database.KitGroups
import database.Kits
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import kotlin.uuid.Uuid

class KitService(private val db: Db) {

    suspend fun getAll(tenant: TenantId): List<Kit> = db.tenantQuery(tenant) { findWhere { Op.TRUE } }

    suspend fun get(tenant: TenantId, id: Uuid): Kit = db.tenantQuery(tenant) { find(id) }

    suspend fun create(tenant: TenantId, kit: NewKit): Kit = db.tenantQuery(tenant) {
        requireOptionMaterials(kit)
        val id = Kits.insertAndGetId {
            it[name] = kit.name
            it[description] = kit.description
        }
        insertGroups(id, kit)
        find(id.value)
    }

    /** Replaces the kit including all of its groups, which are recreated. */
    suspend fun update(tenant: TenantId, id: Uuid, kit: NewKit): Kit = db.tenantQuery(tenant) {
        requireOptionMaterials(kit)
        val updated = Kits.update({ Kits.id eq id }) {
            it[name] = kit.name
            it[description] = kit.description
        }
        if (updated == 0) notFound("Set", id)
        KitGroups.deleteWhere { kitId eq id }
        insertGroups(EntityID(id, Kits), kit)
        find(id)
    }

    suspend fun delete(tenant: TenantId, id: Uuid) = db.tenantQuery(tenant) {
        if (Kits.deleteWhere { Kits.id eq id } == 0) notFound("Set", id)
    }

    private fun requireOptionMaterials(kit: NewKit) =
        requireMaterials(kit.groups.flatMap { group -> group.options.map { it.materialId } }.toSet())

    private fun insertGroups(kitId: EntityID<Uuid>, kit: NewKit) = kit.groups.forEachIndexed { index, group ->
        val groupId = KitGroups.insertAndGetId {
            it[KitGroups.kitId] = kitId
            it[position] = index
            it[name] = group.name
        }
        KitGroupAmounts.batchInsert(group.amounts.withIndex()) { (position, amount) ->
            this[KitGroupAmounts.groupId] = groupId
            this[KitGroupAmounts.position] = position
            this[KitGroupAmounts.label] = amount.label
            this[KitGroupAmounts.requiredUnits] = amount.requiredUnits
        }
        KitGroupOptions.batchInsert(group.options) { option ->
            this[KitGroupOptions.groupId] = groupId
            this[KitGroupOptions.materialId] = option.materialId
            this[KitGroupOptions.unitValue] = option.unitValue
            this[KitGroupOptions.priority] = option.priority
        }
    }

    private fun find(id: Uuid): Kit = findWhere { Kits.id eq id }.singleOrNull() ?: notFound("Set", id)

    private fun findWhere(predicate: () -> Op<Boolean>): List<Kit> {
        val kitRows = Kits.selectAll().where(predicate).orderBy(Kits.name).toList()
        val groupRows = KitGroups.selectAll()
            .where { KitGroups.kitId inList kitRows.map { it[Kits.id] } }
            .orderBy(KitGroups.position)
            .toList()
        val groupIds = groupRows.map { it[KitGroups.id] }
        val amountsByGroup = KitGroupAmounts
            .selectAll()
            .where { KitGroupAmounts.groupId inList groupIds }
            .orderBy(KitGroupAmounts.position)
            .groupBy({ it[KitGroupAmounts.groupId].value }) {
                KitGroupAmount(label = it[KitGroupAmounts.label], requiredUnits = it[KitGroupAmounts.requiredUnits])
            }
        val optionsByGroup = KitGroupOptions
            .selectAll()
            .where { KitGroupOptions.groupId inList groupIds }
            .orderBy(KitGroupOptions.priority to SortOrder.ASC, KitGroupOptions.materialId to SortOrder.ASC)
            .groupBy({ it[KitGroupOptions.groupId].value }) {
                KitGroupOption(
                    materialId = it[KitGroupOptions.materialId].value,
                    unitValue = it[KitGroupOptions.unitValue],
                    priority = it[KitGroupOptions.priority]
                )
            }
        val groupsByKit = groupRows.groupBy({ it[KitGroups.kitId].value }) {
            KitGroup(
                name = it[KitGroups.name],
                amounts = amountsByGroup[it[KitGroups.id].value].orEmpty(),
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
