package configure

import database.TenantId
import io.ktor.server.application.*
import io.ktor.util.*
import services.NotFoundException
import services.TenantService
import kotlin.uuid.Uuid

private val TenantKey = AttributeKey<TenantId>("Tenant")

/** The tenant of a call below `/tenants/{tenantId}`, resolved by [TenantScope]. */
val ApplicationCall.tenant: TenantId get() = attributes[TenantKey]

class TenantScopeConfig {
    lateinit var tenantService: TenantService
}

/** Resolves the `tenantId` path parameter. Unknown and deleted tenants are answered with 404. */
val TenantScope = createRouteScopedPlugin("TenantScope", ::TenantScopeConfig) {
    val tenantService = pluginConfig.tenantService
    onCall { call ->
        val tenant = call.parameters["tenantId"]?.let(Uuid::parseOrNull)?.let { tenantService.findActive(it) }
            ?: throw NotFoundException("Diesen Bereich gibt es nicht.")
        call.attributes.put(TenantKey, tenant)
    }
}
