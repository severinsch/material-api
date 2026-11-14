package services

import api.MaterialTrackingType
import api.NewTaskRule
import api.TaskRule
import database.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.*
import kotlin.uuid.Uuid

/** Rules: one recurring task per item of an individually tracked material. See [api.TaskRule]. */
class TaskRuleService(private val db: Db) {

    suspend fun getAll(tenant: TenantId): List<TaskRule> = db.tenantQuery(tenant) { findWhere { Op.TRUE } }

    suspend fun get(tenant: TenantId, id: Uuid): TaskRule = db.tenantQuery(tenant) { find(id) }

    /** Creates the rule and the tasks of the material's items. */
    suspend fun create(tenant: TenantId, rule: NewTaskRule): TaskRule = db.tenantQuery(tenant) {
        requireIndividualMaterial(rule.materialId)
        requireAssignee(tenant, rule.assigneeId)
        val id = TaskRules.insertAndGetId {
            it[materialId] = rule.materialId
            it.fill(rule)
        }
        val items = IndividualItems.innerJoin(MaterialVariants)
            .select(IndividualItems.id)
            .where { MaterialVariants.materialId eq rule.materialId }
            .map { it[IndividualItems.id].value }
        createMissingRuleTasks(tenant, items, ruleId = id.value)
        find(id.value)
    }

    /** Changes the rule; existing tasks stay as they are. */
    suspend fun update(tenant: TenantId, id: Uuid, rule: NewTaskRule): TaskRule = db.tenantQuery(tenant) {
        val existing = find(id)
        requireUnchanged("Das Material einer Regel", existing.materialId, rule.materialId)
        requireAssignee(tenant, rule.assigneeId, unchangedFrom = existing.assigneeId)
        TaskRules.update({ TaskRules.id eq id }) { it.fill(rule) }
        find(id)
    }

    /** Deletes the rule and its open tasks; completed ones stay as history. */
    suspend fun delete(tenant: TenantId, id: Uuid) = db.tenantQuery(tenant) {
        Tasks.deleteWhere { (Tasks.ruleId eq id) and Tasks.completedAt.isNull() }
        if (TaskRules.deleteWhere { TaskRules.id eq id } == 0) notFound("Diese Regel")
    }

    private fun requireIndividualMaterial(materialId: Uuid) {
        val type = Materials.select(Materials.trackingType).where { Materials.id eq materialId }.singleOrNull()
            ?.get(Materials.trackingType)
            ?: invalid("Dieses Material gibt es nicht mehr. $RELOAD")
        if (type != MaterialTrackingType.INDIVIDUAL) {
            invalid("Regeln gibt es nur für einzeln erfasste Materialien; für anderes eignet sich eine wiederkehrende Aufgabe.")
        }
    }

    private fun UpdateBuilder<*>.fill(rule: NewTaskRule) {
        this[TaskRules.title] = rule.title
        this[TaskRules.repeatMonths] = rule.repeatMonths
        this[TaskRules.fromPurchase] = rule.fromPurchase
        this[TaskRules.assigneeId] = rule.assigneeId
        this[TaskRules.label] = rule.label
        this[TaskRules.note] = rule.note?.takeIf(String::isNotBlank)
    }

    private fun find(id: Uuid): TaskRule = findWhere { TaskRules.id eq id }.singleOrNull() ?: notFound("Diese Regel")

    private fun findWhere(predicate: () -> Op<Boolean>): List<TaskRule> =
        TaskRules.join(Users, JoinType.LEFT, TaskRules.assigneeId, Users.id)
            .select(TaskRules.columns + Users.displayName)
            .where(predicate)
            .orderBy(TaskRules.title)
            .map {
                TaskRule(
                    id = it[TaskRules.id].value,
                    title = it[TaskRules.title],
                    materialId = it[TaskRules.materialId].value,
                    repeatMonths = it[TaskRules.repeatMonths],
                    fromPurchase = it[TaskRules.fromPurchase],
                    assigneeId = it[TaskRules.assigneeId]?.value,
                    assigneeName = it[TaskRules.assigneeId]?.let { _ -> it[Users.displayName] },
                    label = it[TaskRules.label],
                    note = it[TaskRules.note]
                )
            }
}
