package services

import api.NewTaskRoutine
import api.RoutineStart
import api.RoutineStep
import api.Task
import api.TaskRoutine
import database.*
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.plus
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import kotlin.uuid.Uuid

/** Routines: task templates around a day 0, started by hand for an occasion. See [api.TaskRoutine]. */
class TaskRoutineService(private val db: Db) {

    suspend fun getAll(tenant: TenantId): List<TaskRoutine> = db.tenantQuery(tenant) { findWhere { Op.TRUE } }

    suspend fun get(tenant: TenantId, id: Uuid): TaskRoutine = db.tenantQuery(tenant) { find(id) }

    suspend fun create(tenant: TenantId, routine: NewTaskRoutine): TaskRoutine = db.tenantQuery(tenant) {
        check(tenant, routine, existing = null)
        val id = TaskRoutines.insertAndGetId {
            it[name] = routine.name
            it[anchor] = routine.anchor
        }
        insertSteps(id, routine)
        find(id.value)
    }

    /** Replaces the routine including its steps. Tasks it created earlier stay as they are. */
    suspend fun update(tenant: TenantId, id: Uuid, routine: NewTaskRoutine): TaskRoutine = db.tenantQuery(tenant) {
        val existing = find(id)
        check(tenant, routine, existing)
        TaskRoutines.update({ TaskRoutines.id eq id }) {
            it[name] = routine.name
            it[anchor] = routine.anchor
        }
        TaskRoutineSteps.deleteWhere { TaskRoutineSteps.routineId eq id }
        insertSteps(EntityID(id, TaskRoutines), routine)
        find(id)
    }

    /** Deletes the routine; its tasks stay, with their occasion. */
    suspend fun delete(tenant: TenantId, id: Uuid) = db.tenantQuery(tenant) {
        if (TaskRoutines.deleteWhere { TaskRoutines.id eq id } == 0) notFound("Diese Routine")
    }

    /**
     * Creates one task per step, due [RoutineStart.anchorDate] plus its offset. A step's assignee who is no longer a
     * member is left out rather than failing the start.
     */
    suspend fun start(tenant: TenantId, id: Uuid, start: RoutineStart): List<Task> = db.tenantQuery(tenant) {
        val routine = find(id)
        val ids = routine.steps.map { step ->
            val taskId = Tasks.insertAndGetId {
                it[title] = step.title
                it[note] = step.note?.let(::untick)
                it[dueDate] = start.anchorDate.plus(DatePeriod(days = step.offsetDays))
                it[urgent] = step.urgent
                it[label] = step.label
                it[assigneeId] = memberOrNull(tenant, step.assigneeId)
                it[Tasks.routineId] = id
                it[occasion] = start.occasion
            }.value
            insertLinks(taskId, step.materialIds, step.itemIds)
            taskId
        }
        findTasks { Tasks.id inList ids }
    }

    /** Checks the steps' assignees and links; a taken name is reported by the database (409). */
    private fun check(tenant: TenantId, routine: NewTaskRoutine, existing: TaskRoutine?) {
        val previous = existing?.steps.orEmpty().mapNotNull { it.assigneeId }.toSet()
        routine.steps.mapNotNull { it.assigneeId }.distinct().filter { it !in previous }
            .forEach { requireAssignee(tenant, it) }
        requireLinks(routine.steps.flatMap { it.materialIds }.distinct(), routine.steps.flatMap { it.itemIds }.distinct())
    }

    private fun insertSteps(routineId: EntityID<Uuid>, routine: NewTaskRoutine) {
        routine.steps.sortedBy { it.offsetDays }.forEachIndexed { index, step ->
            val stepId = TaskRoutineSteps.insertAndGetId {
                it[this.routineId] = routineId
                it[position] = index
                it[offsetDays] = step.offsetDays
                it[title] = step.title
                it[note] = step.note?.takeIf(String::isNotBlank)
                it[assigneeId] = step.assigneeId
                it[label] = step.label
                it[urgent] = step.urgent
            }
            TaskRoutineStepLinks.batchInsert(step.materialIds) {
                this[TaskRoutineStepLinks.stepId] = stepId
                this[TaskRoutineStepLinks.materialId] = it
            }
            TaskRoutineStepLinks.batchInsert(step.itemIds) {
                this[TaskRoutineStepLinks.stepId] = stepId
                this[TaskRoutineStepLinks.itemId] = it
            }
        }
    }

    private fun find(id: Uuid): TaskRoutine = findWhere { TaskRoutines.id eq id }.singleOrNull() ?: notFound("Diese Routine")

    private fun findWhere(predicate: () -> Op<Boolean>): List<TaskRoutine> {
        val routines = TaskRoutines.selectAll().where(predicate).orderBy(TaskRoutines.name).toList()
        val steps = TaskRoutineSteps.join(Users, JoinType.LEFT, TaskRoutineSteps.assigneeId, Users.id)
            .select(TaskRoutineSteps.columns + Users.displayName)
            .where { TaskRoutineSteps.routineId inList routines.map { it[TaskRoutines.id] } }
            .orderBy(TaskRoutineSteps.position)
            .toList()
        val links = TaskRoutineStepLinks.selectAll()
            .where { TaskRoutineStepLinks.stepId inList steps.map { it[TaskRoutineSteps.id] } }
            .groupBy { it[TaskRoutineStepLinks.stepId].value }
        val stepsByRoutine = steps.groupBy({ it[TaskRoutineSteps.routineId].value }) { row ->
            val stepLinks = links[row[TaskRoutineSteps.id].value].orEmpty()
            RoutineStep(
                offsetDays = row[TaskRoutineSteps.offsetDays],
                title = row[TaskRoutineSteps.title],
                note = row[TaskRoutineSteps.note],
                assigneeId = row[TaskRoutineSteps.assigneeId]?.value,
                assigneeName = row[TaskRoutineSteps.assigneeId]?.let { row[Users.displayName] },
                label = row[TaskRoutineSteps.label],
                urgent = row[TaskRoutineSteps.urgent],
                materialIds = stepLinks.mapNotNull { it[TaskRoutineStepLinks.materialId]?.value },
                itemIds = stepLinks.mapNotNull { it[TaskRoutineStepLinks.itemId]?.value }
            )
        }
        return routines.map {
            val id = it[TaskRoutines.id].value
            TaskRoutine(id, it[TaskRoutines.name], it[TaskRoutines.anchor], stepsByRoutine[id].orEmpty())
        }
    }
}
