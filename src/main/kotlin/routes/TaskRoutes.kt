package routes

import api.NewTask
import api.NewTaskRoutine
import api.NewTaskRule
import api.RoutineStart
import api.TaskCompletion
import auth.Permission
import configure.requires
import configure.tenant
import configure.tenantAccess
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import services.TaskRoutineService
import services.TaskRuleService
import services.TaskService

fun Route.taskRoutes(taskService: TaskService, ruleService: TaskRuleService, routineService: TaskRoutineService) {
    route("/tasks") {
        requires(Permission.READ) {
            /**
             * Get all tasks, open and completed.
             *
             * Query: open [kotlin.Boolean] If set, only open (true) or only completed (false) tasks.
             * Query: materialId [kotlin.uuid.Uuid] Only tasks about this material or one of its items.
             */
            get {
                val open = call.booleanQueryParameter("open")
                call.respond(taskService.getAll(call.tenant, open, call.uuidQueryParameter("materialId")))
            }

            /**
             * Get a task.
             *
             * Path: id [kotlin.uuid.Uuid] The task id.
             * Response: 404 application/json [api.ErrorResponse] No task with this id.
             */
            get("/{id}") {
                call.respond(taskService.get(call.tenant, call.uuidPathParameter()))
            }
        }

        requires(Permission.WORK_ON_TASKS) {
            /**
             * Replace a task's values and links; its completion and origin stay. Without the permission EDIT only
             * tasks assigned to the user, whose assignee can't be changed.
             *
             * Path: id [kotlin.uuid.Uuid] The task id.
             * Response: 400 application/json [api.ErrorResponse] Invalid task, unknown material or item, changed damage report, or the assignee isn't a member.
             * Response: 403 application/json [api.ErrorResponse] Without EDIT: not the user's task, or a changed assignee.
             * Response: 404 application/json [api.ErrorResponse] No task with this id.
             */
            put("/{id}") {
                val id = call.uuidPathParameter()
                call.respond(taskService.update(call.tenantAccess, id, call.receive<NewTask>()))
            }

            /**
             * Complete a task. A recurring one gets its next task, which is returned as `next`. Without the
             * permission EDIT only tasks assigned to the user.
             *
             * Path: id [kotlin.uuid.Uuid] The task id.
             * Response: 403 application/json [api.ErrorResponse] Without EDIT: not the user's task.
             * Response: 404 application/json [api.ErrorResponse] No task with this id.
             * Response: 409 application/json [api.ErrorResponse] The task is completed already.
             */
            post("/{id}/complete") {
                val id = call.uuidPathParameter()
                call.respond(taskService.complete(call.tenantAccess, id, call.receive<TaskCompletion>()))
            }

            /**
             * Reopen a completed task. The task its completion created is deleted if it is still open. Without the
             * permission EDIT only tasks assigned to the user.
             *
             * Path: id [kotlin.uuid.Uuid] The task id.
             * Response: 403 application/json [api.ErrorResponse] Without EDIT: not the user's task.
             * Response: 404 application/json [api.ErrorResponse] No task with this id.
             * Response: 409 application/json [api.ErrorResponse] The task isn't completed, or its next task is completed too.
             */
            post("/{id}/reopen") {
                call.respond(taskService.reopen(call.tenantAccess, call.uuidPathParameter()))
            }
        }

        requires(Permission.EDIT) {
            /**
             * Create a task.
             *
             * Response: 400 application/json [api.ErrorResponse] Invalid task, unknown material, item or damage report, or the assignee isn't a member.
             */
            post {
                call.respond(HttpStatusCode.Created, taskService.create(call.tenantAccess, call.receive<NewTask>()))
            }

            /**
             * Delete a task.
             *
             * Path: id [kotlin.uuid.Uuid] The task id.
             * Response: 404 application/json [api.ErrorResponse] No task with this id.
             */
            delete("/{id}") {
                taskService.delete(call.tenant, call.uuidPathParameter())
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }

    route("/task-rules") {
        requires(Permission.READ) {
            /** Get all rules. */
            get {
                call.respond(ruleService.getAll(call.tenant))
            }

            /**
             * Get a rule.
             *
             * Path: id [kotlin.uuid.Uuid] The rule id.
             * Response: 404 application/json [api.ErrorResponse] No rule with this id.
             */
            get("/{id}") {
                call.respond(ruleService.get(call.tenant, call.uuidPathParameter()))
            }
        }

        requires(Permission.EDIT) {
            /**
             * Create a rule, and the tasks of the material's items.
             *
             * Response: 400 application/json [api.ErrorResponse] Invalid rule, unknown or not individually tracked material, or the assignee isn't a member.
             */
            post {
                call.respond(HttpStatusCode.Created, ruleService.create(call.tenant, call.receive<NewTaskRule>()))
            }

            /**
             * Change a rule. Its `materialId` can't be changed; existing tasks stay as they are.
             *
             * Path: id [kotlin.uuid.Uuid] The rule id.
             * Response: 400 application/json [api.ErrorResponse] Invalid rule, changed material, or the assignee isn't a member.
             * Response: 404 application/json [api.ErrorResponse] No rule with this id.
             */
            put("/{id}") {
                val id = call.uuidPathParameter()
                call.respond(ruleService.update(call.tenant, id, call.receive<NewTaskRule>()))
            }

            /**
             * Delete a rule and its open tasks; completed ones stay.
             *
             * Path: id [kotlin.uuid.Uuid] The rule id.
             * Response: 404 application/json [api.ErrorResponse] No rule with this id.
             */
            delete("/{id}") {
                ruleService.delete(call.tenant, call.uuidPathParameter())
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }

    route("/task-routines") {
        requires(Permission.READ) {
            /** Get all routines. */
            get {
                call.respond(routineService.getAll(call.tenant))
            }

            /**
             * Get a routine.
             *
             * Path: id [kotlin.uuid.Uuid] The routine id.
             * Response: 404 application/json [api.ErrorResponse] No routine with this id.
             */
            get("/{id}") {
                call.respond(routineService.get(call.tenant, call.uuidPathParameter()))
            }
        }

        requires(Permission.EDIT) {
            /**
             * Create a routine with its steps.
             *
             * Response: 400 application/json [api.ErrorResponse] Invalid routine, unknown material or item, or an assignee isn't a member.
             * Response: 409 application/json [api.ErrorResponse] The name is taken.
             */
            post {
                call.respond(HttpStatusCode.Created, routineService.create(call.tenant, call.receive<NewTaskRoutine>()))
            }

            /**
             * Replace a routine including its steps. Tasks it created earlier stay as they are.
             *
             * Path: id [kotlin.uuid.Uuid] The routine id.
             * Response: 400 application/json [api.ErrorResponse] Invalid routine, unknown material or item, or an assignee isn't a member.
             * Response: 404 application/json [api.ErrorResponse] No routine with this id.
             * Response: 409 application/json [api.ErrorResponse] The name is taken.
             */
            put("/{id}") {
                val id = call.uuidPathParameter()
                call.respond(routineService.update(call.tenant, id, call.receive<NewTaskRoutine>()))
            }

            /**
             * Delete a routine; the tasks it created stay.
             *
             * Path: id [kotlin.uuid.Uuid] The routine id.
             * Response: 404 application/json [api.ErrorResponse] No routine with this id.
             */
            delete("/{id}") {
                routineService.delete(call.tenant, call.uuidPathParameter())
                call.respond(HttpStatusCode.NoContent)
            }

            /**
             * Start a routine for an occasion. Creates one task per step, due the given date plus the step's offset,
             * and returns them.
             *
             * Path: id [kotlin.uuid.Uuid] The routine id.
             * Response: 400 application/json [api.ErrorResponse] Invalid occasion or date.
             * Response: 404 application/json [api.ErrorResponse] No routine with this id.
             */
            post("/{id}/start") {
                val id = call.uuidPathParameter()
                call.respond(HttpStatusCode.Created, routineService.start(call.tenant, id, call.receive<RoutineStart>()))
            }
        }
    }
}
