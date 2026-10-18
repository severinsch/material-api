package routes

import api.NewTenant
import api.UserUpdate
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import services.TenantService
import services.UserService

// Platform administration. The routes are only reachable for platform admins (see configure.platformAdminRoutes).

fun Route.tenantAdministrationRoutes(tenantService: TenantService) {
    route("/tenants") {
        /**
         * Get all tenants, including deleted ones.
         */
        get {
            call.respond(tenantService.getAll())
        }

        /**
         * Create a tenant. It has no members yet; add an admin via its member routes.
         *
         * Response: 400 application/json [api.ErrorResponse] Invalid or reserved slug.
         * Response: 409 application/json [api.ErrorResponse] The name or slug is taken.
         */
        post {
            call.respond(HttpStatusCode.Created, tenantService.create(call.receive<NewTenant>()))
        }

        /**
         * Rename a tenant or change its slug, which breaks existing links.
         *
         * Path: tenantId [kotlin.uuid.Uuid] The tenant id.
         * Response: 404 application/json [api.ErrorResponse] No tenant with this id.
         * Response: 409 application/json [api.ErrorResponse] The name or slug is taken.
         */
        put("/{tenantId}") {
            val id = call.uuidPathParameter("tenantId")
            call.respond(tenantService.update(id, call.receive<NewTenant>()))
        }

        /**
         * Delete a tenant. It can be restored with all of its data.
         *
         * Path: tenantId [kotlin.uuid.Uuid] The tenant id.
         * Response: 404 application/json [api.ErrorResponse] No tenant with this id, or already deleted.
         */
        delete("/{tenantId}") {
            tenantService.delete(call.uuidPathParameter("tenantId"))
            call.respond(HttpStatusCode.NoContent)
        }

        /**
         * Restore a deleted tenant.
         *
         * Path: tenantId [kotlin.uuid.Uuid] The tenant id.
         * Response: 404 application/json [api.ErrorResponse] No tenant with this id.
         * Response: 409 application/json [api.ErrorResponse] Another tenant has the name or slug now.
         */
        post("/{tenantId}/restore") {
            call.respond(tenantService.restore(call.uuidPathParameter("tenantId")))
        }
    }
}

fun Route.userRoutes(userService: UserService) {
    route("/users") {
        /**
         * Get all accounts with their memberships.
         */
        get {
            call.respond(userService.getAll())
        }

        /**
         * Change an account. Disabling it ends its sessions.
         *
         * Path: userId [kotlin.uuid.Uuid] The user id.
         * Response: 404 application/json [api.ErrorResponse] No user with this id.
         * Response: 409 application/json [api.ErrorResponse] The last admin of a tenant or the platform, or the username is taken.
         */
        put("/{userId}") {
            val userId = call.uuidPathParameter("userId")
            call.respond(userService.update(userId, call.receive<UserUpdate>()))
        }

        /**
         * Delete an account with its memberships.
         *
         * Path: userId [kotlin.uuid.Uuid] The user id.
         * Response: 404 application/json [api.ErrorResponse] No user with this id.
         * Response: 409 application/json [api.ErrorResponse] The last admin of a tenant or the platform.
         */
        delete("/{userId}") {
            userService.delete(call.uuidPathParameter("userId"))
            call.respond(HttpStatusCode.NoContent)
        }

        /**
         * Create a new setup link for an account, e.g. after a forgotten password. Replaces an older link; the
         * current password keeps working until the link is used.
         *
         * Path: userId [kotlin.uuid.Uuid] The user id.
         * Response: 404 application/json [api.ErrorResponse] No user with this id.
         * Response: 409 application/json [api.ErrorResponse] The account is disabled.
         */
        post("/{userId}/setup-link") {
            call.respond(userService.setupLink(call.uuidPathParameter("userId")))
        }
    }
}
