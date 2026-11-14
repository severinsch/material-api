package services

import api.CompletedTask
import api.ItemStatus
import api.NewTask
import api.Task
import api.TaskCompletion
import auth.Permission
import auth.TenantAccess
import database.*
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.*
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Tasks and their completion. Users with [Permission.EDIT] manage all tasks; with only [Permission.WORK_ON_TASKS]
 * (viewers) they can change, complete and reopen the tasks assigned to them.
 */
class TaskService(private val db: Db) {

    /** All tasks, or only open / only completed ones, or only those about a material or one of its items. */
    suspend fun getAll(tenant: TenantId, open: Boolean? = null, materialId: Uuid? = null): List<Task> =
        db.tenantQuery(tenant) {
            val state = when (open) {
                null -> Op.TRUE
                true -> Tasks.completedAt.isNull()
                false -> Tasks.completedAt.isNotNull()
            }
            findTasks { state and (materialId?.let(::aboutMaterial) ?: Op.TRUE) }
        }

    suspend fun get(tenant: TenantId, id: Uuid): Task = db.tenantQuery(tenant) { findTask(id) }

    suspend fun create(access: TenantAccess, task: NewTask): Task = db.tenantQuery(access.tenant) {
        requireAssignee(access.tenant, task.assigneeId)
        requireLinks(task.materialIds, task.itemIds)
        task.damageReportId?.let(::requireDamageReport)
        val id = Tasks.insertAndGetId {
            it.fill(task)
            it[damageReportId] = task.damageReportId
        }
        insertLinks(id.value, task.materialIds, task.itemIds)
        findTask(id.value)
    }

    /**
     * Replaces the task's values and links; its completion and origin stay. Without [Permission.EDIT] only for the
     * user's own task, which stays theirs.
     */
    suspend fun update(access: TenantAccess, id: Uuid, task: NewTask): Task = db.tenantQuery(access.tenant) {
        val existing = findTask(id, forUpdate = true)
        requireOwnUnlessEditor(access, existing, "bearbeiten")
        if (!access.permits(Permission.EDIT) && task.assigneeId != existing.assigneeId) {
            forbidden("Du kannst die Aufgabe nicht an jemand anderen abgeben.")
        }
        if (task.damageReportId != existing.damageReportId) {
            invalid("Die Schadensmeldung einer Aufgabe kann nicht geändert werden.")
        }
        requireAssignee(access.tenant, task.assigneeId, unchangedFrom = existing.assigneeId)
        requireLinks(task.materialIds, task.itemIds)
        Tasks.update({ Tasks.id eq id }) { it.fill(task) }
        TaskLinks.deleteWhere { TaskLinks.taskId eq id }
        insertLinks(id, task.materialIds, task.itemIds)
        findTask(id)
    }

    suspend fun delete(tenant: TenantId, id: Uuid) = db.tenantQuery(tenant) {
        if (Tasks.deleteWhere { Tasks.id eq id } == 0) notFound("Diese Aufgabe")
    }

    /**
     * Completes the task. A recurring one gets its next task, due [TaskCompletion.nextDueDate] or [Task.repeatMonths]
     * after today; a rule's task takes the rule's current values, and gets none once its item is retired.
     */
    suspend fun complete(access: TenantAccess, id: Uuid, completion: TaskCompletion): CompletedTask =
        db.tenantQuery(access.tenant) {
            val task = findTask(id, forUpdate = true)
            requireOwnUnlessEditor(access, task, "erledigen")
            if (task.completedAt != null) conflict("Diese Aufgabe ist schon erledigt. $RELOAD")
            Tasks.update({ Tasks.id eq id }) {
                it[completedAt] = Clock.System.now()
                it[completedBy] = access.user.displayName
                it[completionNote] = completion.note?.takeIf(String::isNotBlank)
            }
            val nextId = task.repeatMonths?.let { createNext(access.tenant, task, completion.nextDueDate) }
            CompletedTask(findTask(id), nextId?.let(::findTask))
        }

    /**
     * Reopens a completed task. The task its completion created is deleted, so this undoes the completion; if that one
     * is completed too, it has to be reopened first.
     */
    suspend fun reopen(access: TenantAccess, id: Uuid): Task = db.tenantQuery(access.tenant) {
        val task = findTask(id, forUpdate = true)
        requireOwnUnlessEditor(access, task, "wieder öffnen")
        if (task.completedAt == null) conflict("Diese Aufgabe ist nicht erledigt. $RELOAD")
        // Locked, so it can't be completed meanwhile (which would leave two next tasks after completing this again).
        val nextDone = Tasks.select(Tasks.completedAt).where { Tasks.previousId eq id }.forUpdate()
            .any { it[Tasks.completedAt] != null }
        if (nextDone) conflict("Die nächste Aufgabe dieser Reihe ist schon erledigt. Öffne zuerst sie wieder.")
        Tasks.deleteWhere { Tasks.previousId eq id }
        Tasks.update({ Tasks.id eq id }) {
            it[completedAt] = null
            it[completedBy] = null
            it[completionNote] = null
        }
        findTask(id)
    }

    /** Without [Permission.EDIT], users can only [action] tasks assigned to them. */
    private fun requireOwnUnlessEditor(access: TenantAccess, task: Task, action: String) {
        if (!access.permits(Permission.EDIT) && task.assigneeId != access.user.userId) {
            forbidden("Du kannst nur Aufgaben $action, die dir zugewiesen sind.")
        }
    }

    /**
     * Creates the next task of the recurring [task] and returns its id; none for a rule's task whose item is retired
     * (or gone), like for retired items when the rule is created.
     */
    private fun createNext(tenant: TenantId, task: Task, dueDate: LocalDate?): Uuid? {
        val rule = task.ruleId?.let { ruleId -> TaskRules.selectAll().where { TaskRules.id eq ruleId }.singleOrNull() }
        if (rule != null && !hasCurrentItem(task.itemIds)) return null
        val months = rule?.get(TaskRules.repeatMonths) ?: task.repeatMonths!!
        val id = Tasks.insertAndGetId {
            it[title] = rule?.get(TaskRules.title) ?: task.title
            it[note] = (if (rule != null) rule[TaskRules.note] else task.note)?.let(::untick)
            it[this.dueDate] = dueDate ?: today().plus(DatePeriod(months = months))
            it[urgent] = if (rule != null) false else task.urgent
            it[label] = rule?.get(TaskRules.label) ?: task.label
            it[assigneeId] = memberOrNull(tenant, if (rule != null) rule[TaskRules.assigneeId]?.value else task.assigneeId)
            it[repeatMonths] = months
            it[previousId] = task.id
            it[Tasks.ruleId] = task.ruleId
            it[routineId] = task.routineId
            it[occasion] = task.occasion
        }
        insertLinks(id.value, task.materialIds, task.itemIds)
        return id.value
    }

    private fun hasCurrentItem(itemIds: List<Uuid>): Boolean = IndividualItems.select(IndividualItems.id)
        .where { (IndividualItems.id inList itemIds) and (IndividualItems.status inList CURRENT_ITEM_STATUSES) }
        .any()

    private fun aboutMaterial(materialId: Uuid): Op<Boolean> {
        val items = IndividualItems.innerJoin(MaterialVariants)
            .select(IndividualItems.id)
            .where { MaterialVariants.materialId eq materialId }
        val linked = TaskLinks.select(TaskLinks.taskId)
            .where { (TaskLinks.materialId eq materialId) or (TaskLinks.itemId inSubQuery items) }
        return Tasks.id inSubQuery linked
    }

    private fun UpdateBuilder<*>.fill(task: NewTask) {
        this[Tasks.title] = task.title
        this[Tasks.note] = task.note?.takeIf(String::isNotBlank)
        this[Tasks.dueDate] = task.dueDate
        this[Tasks.urgent] = task.urgent
        this[Tasks.label] = task.label
        this[Tasks.assigneeId] = task.assigneeId
        this[Tasks.repeatMonths] = task.repeatMonths
    }

    private fun findTask(id: Uuid, forUpdate: Boolean = false): Task {
        if (forUpdate) Tasks.select(Tasks.id).where { Tasks.id eq id }.forUpdate().toList()
        return findTasks { Tasks.id eq id }.singleOrNull() ?: notFound("Diese Aufgabe")
    }
}

// ========= Shared by the task services (inside a transaction) =========

/** "Today" for due dates: the Stämme are in Germany. */
internal fun today(): LocalDate = Clock.System.todayIn(TimeZone.of("Europe/Berlin"))

/** Unticks all checkboxes of a note, for the next task of a recurring one. */
internal fun untick(note: String): String = note.lines().joinToString("\n") {
    if (it.startsWith("[x] ") || it == "[x]") "[ ]" + it.removePrefix("[x]") else it
}

/** Tasks matching [predicate] with their assignee's name and links, by due date (undated last), then title. */
internal fun findTasks(predicate: () -> Op<Boolean>): List<Task> {
    val rows = Tasks.join(Users, JoinType.LEFT, Tasks.assigneeId, Users.id)
        .select(Tasks.columns + Users.displayName)
        .where(predicate)
        .orderBy(Tasks.dueDate to SortOrder.ASC_NULLS_LAST, Tasks.title to SortOrder.ASC)
        .toList()
    val links = TaskLinks.selectAll()
        .where { TaskLinks.taskId inList rows.map { it[Tasks.id].value } }
        .groupBy { it[TaskLinks.taskId].value }
    return rows.map { row ->
        val id = row[Tasks.id].value
        val taskLinks = links[id].orEmpty()
        Task(
            id = id,
            title = row[Tasks.title],
            note = row[Tasks.note],
            dueDate = row[Tasks.dueDate],
            urgent = row[Tasks.urgent],
            label = row[Tasks.label],
            assigneeId = row[Tasks.assigneeId]?.value,
            assigneeName = row[Tasks.assigneeId]?.let { row[Users.displayName] },
            repeatMonths = row[Tasks.repeatMonths],
            materialIds = taskLinks.mapNotNull { it[TaskLinks.materialId]?.value },
            itemIds = taskLinks.mapNotNull { it[TaskLinks.itemId]?.value },
            completedAt = row[Tasks.completedAt],
            completedBy = row[Tasks.completedBy],
            completionNote = row[Tasks.completionNote],
            previousId = row[Tasks.previousId]?.value,
            ruleId = row[Tasks.ruleId]?.value,
            routineId = row[Tasks.routineId]?.value,
            occasion = row[Tasks.occasion],
            damageReportId = row[Tasks.damageReportId]?.value
        )
    }
}

internal fun insertLinks(taskId: Uuid, materialIds: List<Uuid>, itemIds: List<Uuid>) {
    val task = EntityID(taskId, Tasks)
    TaskLinks.batchInsert(materialIds) { this[TaskLinks.taskId] = task; this[TaskLinks.materialId] = it }
    TaskLinks.batchInsert(itemIds) { this[TaskLinks.taskId] = task; this[TaskLinks.itemId] = it }
}

internal fun requireLinks(materialIds: Collection<Uuid>, itemIds: Collection<Uuid>) {
    requireMaterials(materialIds)
    if (itemIds.isEmpty()) return
    val existing = IndividualItems.select(IndividualItems.id).where { IndividualItems.id inList itemIds }.count()
    if (existing < itemIds.size) invalid("Einen der ausgewählten Gegenstände gibt es nicht mehr. $RELOAD")
}

/**
 * The assignee must be a member of the tenant, unless they already are the assignee (e.g. after leaving it), as for
 * the borrower of a loan.
 */
internal fun requireAssignee(tenant: TenantId, assigneeId: Uuid?, unchangedFrom: Uuid? = null) {
    if (assigneeId == null || assigneeId == unchangedFrom) return
    if (!isMember(tenant, assigneeId)) invalid("Die zuständige Person ist kein Mitglied dieses Stamms.")
}

/**
 * [userId] if they are a member of the tenant, else null: for tasks created from a template (rule, routine step,
 * previous task) whose assignee has left the tenant meanwhile, which then get none rather than failing.
 */
internal fun memberOrNull(tenant: TenantId, userId: Uuid?): Uuid? = userId?.takeIf { isMember(tenant, it) }

private fun requireDamageReport(id: Uuid) {
    if (DamageReports.select(DamageReports.id).where { DamageReports.id eq id }.empty()) {
        invalid("Diese Schadensmeldung gibt es nicht mehr. $RELOAD")
    }
}

/** Items that rules create tasks for; lost and decommissioned ones get none. */
internal val CURRENT_ITEM_STATUSES = listOf(ItemStatus.ACTIVE, ItemStatus.IN_REPAIR)

/**
 * Creates the missing tasks of the rules for [itemIds] (all rules of their materials, or only [ruleId]): one for each
 * current item that has no open task of the rule, e.g. a new item, one found again, or one that came with a merge.
 * The first due date counts from the item's purchase date if the rule says so and it is known, else from today.
 */
internal fun createMissingRuleTasks(tenant: TenantId, itemIds: Collection<Uuid>, ruleId: Uuid? = null) {
    if (itemIds.isEmpty()) return
    val items = IndividualItems.innerJoin(MaterialVariants)
        .select(IndividualItems.id, IndividualItems.purchaseDate, MaterialVariants.materialId)
        .where {
            (IndividualItems.id inList itemIds) and
                (IndividualItems.status inList CURRENT_ITEM_STATUSES)
        }
        .toList()
    val materialIds = items.map { it[MaterialVariants.materialId].value }.distinct()
    val rules = TaskRules.selectAll()
        .where { (TaskRules.materialId inList materialIds) and (ruleId?.let { TaskRules.id eq it } ?: Op.TRUE) }
        .groupBy { it[TaskRules.materialId].value }
    val open = Tasks.join(TaskLinks, JoinType.INNER, Tasks.id, TaskLinks.taskId)
        .select(Tasks.ruleId, TaskLinks.itemId)
        .where { Tasks.ruleId.isNotNull() and Tasks.completedAt.isNull() and (TaskLinks.itemId inList itemIds) }
        .map { it[Tasks.ruleId]!!.value to it[TaskLinks.itemId]!!.value }
        .toSet()
    val today = today()
    val assignees = rules.values.flatten()
        .associate { it[TaskRules.id].value to memberOrNull(tenant, it[TaskRules.assigneeId]?.value) }
    for (item in items) {
        for (rule in rules[item[MaterialVariants.materialId].value].orEmpty()) {
            if (rule[TaskRules.id].value to item[IndividualItems.id].value in open) continue
            val months = rule[TaskRules.repeatMonths]
            val from = item[IndividualItems.purchaseDate]?.takeIf { rule[TaskRules.fromPurchase] } ?: today
            val id = Tasks.insertAndGetId {
                it[title] = rule[TaskRules.title]
                it[note] = rule[TaskRules.note]?.let(::untick)
                it[dueDate] = from.plus(DatePeriod(months = months))
                it[urgent] = false
                it[label] = rule[TaskRules.label]
                it[assigneeId] = assignees.getValue(rule[TaskRules.id].value)
                it[repeatMonths] = months
                it[Tasks.ruleId] = rule[TaskRules.id]
            }
            insertLinks(id.value, emptyList(), listOf(item[IndividualItems.id].value))
        }
    }
}
