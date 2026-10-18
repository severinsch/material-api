package routes

import api.MemberUpdate
import api.NewMember
import auth.Permission
import configure.requires
import configure.tenant
import configure.tenantAccess
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import services.MemberService

fun Route.memberRoutes(memberService: MemberService) {
    route("/members") {
        requires(Permission.VIEW_MEMBERS) {
            /**
             * Get the members of the tenant.
             */
            get {
                call.respond(memberService.getAll(call.tenant))
            }
        }

        requires(Permission.MANAGE_MEMBERS) {
            /**
             * Create an account as member of the tenant. The response contains a setup link token, with which the user
             * sets their password.
             *
             * Response: 409 application/json [api.ErrorResponse] The username is taken.
             */
            post {
                val member = call.receive<NewMember>()
                call.respond(HttpStatusCode.Created, memberService.create(call.tenantAccess, member))
            }

            /**
             * Change the role of a member, and optionally name and username. Tenant admins can only change name and
             * username of accounts that belong to no other tenant. Platform admins can add users of other tenants.
             *
             * Path: userId [kotlin.uuid.Uuid] The user id.
             * Response: 403 application/json [api.ErrorResponse] The account belongs to another tenant or a platform admin.
             * Response: 404 application/json [api.ErrorResponse] No member with this id.
             * Response: 409 application/json [api.ErrorResponse] The last admin can't be demoted, or the username is taken.
             */
            put("/{userId}") {
                val userId = call.uuidPathParameter("userId")
                call.respond(memberService.update(call.tenantAccess, userId, call.receive<MemberUpdate>()))
            }

            /**
             * Remove a member from the tenant. The account itself stays.
             *
             * Path: userId [kotlin.uuid.Uuid] The user id.
             * Response: 403 application/json [api.ErrorResponse] The member is a platform admin.
             * Response: 404 application/json [api.ErrorResponse] No member with this id.
             * Response: 409 application/json [api.ErrorResponse] The last admin can't be removed.
             */
            delete("/{userId}") {
                memberService.remove(call.tenantAccess, call.uuidPathParameter("userId"))
                call.respond(HttpStatusCode.NoContent)
            }

            /**
             * Create a new setup link for a member, e.g. after a forgotten password. Replaces an older link; the current
             * password keeps working until the link is used.
             *
             * Path: userId [kotlin.uuid.Uuid] The user id.
             * Response: 403 application/json [api.ErrorResponse] The account belongs to another tenant or a platform admin.
             * Response: 404 application/json [api.ErrorResponse] No member with this id.
             * Response: 409 application/json [api.ErrorResponse] The account is disabled.
             */
            post("/{userId}/setup-link") {
                call.respond(memberService.setupLink(call.tenantAccess, call.uuidPathParameter("userId")))
            }
        }
    }
}
