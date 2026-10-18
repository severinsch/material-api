package configure

import api.ErrorResponse
import auth.Permission
import auth.TenantAccess
import auth.UserPrincipal
import database.TenantId
import io.ktor.http.*
import io.ktor.openapi.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.routing.openapi.*
import io.ktor.util.*
import io.ktor.utils.io.*
import services.AuthService
import services.ForbiddenException
import services.NotFoundException
import services.TenantService
import kotlin.uuid.Uuid

/** Name of the authentication provider for session tokens; routes inside `authenticate(SESSION)` require one. */
const val SESSION = "session"

/** Sessions are opaque tokens issued by `POST /auth/login`, sent as `Authorization: Bearer <token>`. */
fun Application.configureSecurity(authService: AuthService) {
    install(Authentication) {
        bearer(SESSION, description = "Session token from POST /auth/login") {
            authenticate { authService.authenticate(it.token) }
        }
    }
}

/** The user of a call inside `authenticate(SESSION)`. */
val ApplicationCall.user: UserPrincipal get() = checkNotNull(principal<UserPrincipal>()) { "Route not authenticated" }

// ========= Tenants =========

private val TenantAccessKey = AttributeKey<TenantAccess>("TenantAccess")

/** The access of the user to the tenant of a call inside [tenantRoutes]. */
val ApplicationCall.tenantAccess: TenantAccess get() = attributes[TenantAccessKey]

val ApplicationCall.tenant: TenantId get() = tenantAccess.tenant

/** The answer for unknown and deleted tenants, and for tenants the user has no access to. */
internal const val TENANT_NOT_FOUND = "Diesen Bereich gibt es nicht, oder du hast keinen Zugriff darauf."

private class TenantScopeConfig {
    lateinit var tenantService: TenantService
}

/** Resolves the user's access to the tenant in the path. Without access, the tenant doesn't exist for them (404). */
private val TenantScope = createRouteScopedPlugin("TenantScope", ::TenantScopeConfig) {
    val tenantService = pluginConfig.tenantService
    on(AuthenticationChecked) { call ->
        val access = call.parameters["tenantId"]?.let(Uuid::parseOrNull)?.let { tenantService.access(call.user, it) }
            ?: throw NotFoundException(TENANT_NOT_FOUND)
        call.attributes.put(TenantAccessKey, access)
    }
}

/**
 * Routes of the tenant in the path, for its members and platform admins. Every route in [build] must declare the
 * permission it needs with [requires]; otherwise the application doesn't start.
 */
fun Route.tenantRoutes(tenantService: TenantService, build: Route.() -> Unit) {
    /**
     * Path: tenantId [kotlin.uuid.Uuid] The tenant id.
     * Response: 404 application/json [api.ErrorResponse] No tenant with this id, or the user has no access to it.
     */
    route("/tenants/{tenantId}") {
        install(TenantScope) { this.tenantService = tenantService }
        build()
        val undeclared = (this as RoutingNode).getAllRoutes().filter { it.requiredPermission() == null }
        check(undeclared.isEmpty()) { "Tenant routes without a permission declaration: ${undeclared.joinToString()}" }
    }
}

// ========= Permissions =========

private class PermissionRouteSelector(val permission: Permission) : RouteSelector() {
    override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int) = RouteSelectorEvaluation.Transparent
    override fun toString() = "(requires $permission)"
}

private class PermissionConfig {
    lateinit var permission: Permission
}

private val RequirePermission = createRouteScopedPlugin("RequirePermission", ::PermissionConfig) {
    val permission = pluginConfig.permission
    // Registered after TenantScope (on a parent route), so the tenant access is resolved already.
    on(AuthenticationChecked) { call ->
        if (!call.tenantAccess.permits(permission)) throw ForbiddenException("Dafür fehlt dir die Berechtigung.")
    }
}

/** Routes in [build] require [permission] in the call's tenant (403 otherwise). Only inside [tenantRoutes]. */
@OptIn(ExperimentalKtorApi::class) // describe, which the generated route documentation uses as well
fun Route.requires(permission: Permission, build: Route.() -> Unit): Route =
    createChild(PermissionRouteSelector(permission)).apply {
        install(RequirePermission) { this.permission = permission }
        describe {
            responses {
                HttpStatusCode.Forbidden {
                    description = "Requires the permission $permission in the tenant."
                    schema = jsonSchema<ErrorResponse>()
                }
            }
        }
        build()
    }

/** The permission an endpoint declared with [requires], if any. */
fun RoutingNode.requiredPermission(): Permission? =
    generateSequence(this) { it.parent }.firstNotNullOfOrNull { (it.selector as? PermissionRouteSelector)?.permission }
