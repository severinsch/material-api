import api.*
import auth.Permission
import auth.permissions
import configure.TENANT_NOT_FOUND
import configure.isPlatformAdminRoute
import configure.requirePermissionDeclarations
import configure.requiredPermission
import configure.requires
import configure.tenantRoutes
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import services.TenantService
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class PermissionTest {

    /** [permission] is null for platform routes. */
    private data class Endpoint(val method: HttpMethod, val path: String, val permission: Permission? = null)

    /** Who calls, and the permissions they have in the tenant (null: no access at all). */
    private data class Caller(val name: String, val client: HttpClient, val permissions: Set<Permission>?)

    /** Generated from the declarations in the routes, so new routes are covered automatically. */
    @Test
    fun `every tenant route requires its declared permission`() = apiTest { context ->
        val endpoints = context.routing.getAllRoutes().mapNotNull { route ->
            route.requiredPermission()?.let { Endpoint(route.method(), route.path(), it) }
        }
        assertTrue(Endpoint(HttpMethod.Post, "/tenants/{tenantId}/damages", Permission.REPORT_DAMAGE) in endpoints)

        val callers = TenantRole.entries.map { Caller(it.name, context.clientFor(it), it.permissions) } + listOf(
            Caller("platform admin", context.clientFor(null, isPlatformAdmin = true), TenantRole.ADMIN.permissions),
            Caller("non-member", context.clientFor(null), null),
        )
        val wrong = endpoints.flatMap { endpoint ->
            callers.mapNotNull { caller ->
                val response = caller.client.call(endpoint, context.tenant)
                val ok = when {
                    caller.permissions == null -> response.isTenantNotFound()
                    endpoint.permission in caller.permissions ->
                        response.status !in setOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden) && !response.isTenantNotFound()
                    else -> response.status == HttpStatusCode.Forbidden
                }
                if (ok) null else "${caller.name}: ${endpoint.method.value} ${endpoint.path} -> ${response.status}"
            }
        }
        assertEquals(emptyList(), wrong)

        // Nobody gets into a deleted tenant.
        TestDatabase.execute("UPDATE tenants SET deleted_at = now()")
        val stillReachable = endpoints.flatMap { endpoint ->
            callers.filterNot { caller -> caller.client.call(endpoint, context.tenant).isTenantNotFound() }
                .map { "${it.name}: ${endpoint.method.value} ${endpoint.path}" }
        }
        assertEquals(emptyList(), stillReachable)
    }

    @Test
    fun `platform routes are only for platform admins`() = apiTest { context ->
        val endpoints = context.routing.getAllRoutes().filter { it.isPlatformAdminRoute() }.map { route ->
            Endpoint(route.method(), route.path())
        }
        assertTrue(endpoints.any { it.path == "/users/{userId}" })

        val platformAdmin = context.clientFor(null, isPlatformAdmin = true)
        val tenantAdmin = context.clientFor(TenantRole.ADMIN)
        val wrong = endpoints.mapNotNull { endpoint ->
            val asTenantAdmin = tenantAdmin.call(endpoint, context.tenant).status
            val asPlatformAdmin = platformAdmin.call(endpoint, context.tenant).status
            val ok = asTenantAdmin == HttpStatusCode.Forbidden &&
                asPlatformAdmin !in setOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)
            if (ok) null else "${endpoint.method.value} ${endpoint.path}: $asTenantAdmin / $asPlatformAdmin"
        }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun `viewers can report damage but neither resolve it nor retire items`() = apiTest { context ->
        val item = createItem(createIndividual(), "1")
        val viewer = context.clientFor(TenantRole.VIEWER)
        suspend fun report(report: NewDamageReport) = viewer.post("damages") { setBody(report) }

        report(NewDamageReport(item.id, "Riss", reportedBy = "Max")).expect<DamageReport>(HttpStatusCode.Created)
        report(NewDamageReport(item.id, "Riss", isResolved = true)).expectError(HttpStatusCode.Forbidden)
        report(NewDamageReport(item.id, "Riss", resolutionNotes = "genäht")).expectError(HttpStatusCode.Forbidden)
        report(NewDamageReport(item.id, "Riss", itemStatus = ItemStatus.DECOMMISSIONED)).expectError(HttpStatusCode.Forbidden)

        report(NewDamageReport(item.id, "Riss", itemStatus = ItemStatus.IN_REPAIR)).expect<DamageReport>(HttpStatusCode.Created)
        // Unchanged status is fine, but not back to active.
        report(NewDamageReport(item.id, "Riss", itemStatus = ItemStatus.IN_REPAIR)).expect<DamageReport>(HttpStatusCode.Created)
        report(NewDamageReport(item.id, "Riss", itemStatus = ItemStatus.ACTIVE)).expectError(HttpStatusCode.Forbidden)
        assertEquals(ItemStatus.IN_REPAIR, get("items/${item.id}").expect<IndividualItem>(HttpStatusCode.OK).status)
    }

    @Test
    fun `tenant routes without a permission declaration keep the application from starting`() {
        val e = assertFailsWith<IllegalStateException> {
            testApplication {
                application {
                    routing {
                        tenantRoutes(TenantService(TestDatabase.db)) {
                            requires(Permission.READ) { get("/declared") {} }
                        }
                        // Ends up below the tenant route, too.
                        route("/tenants/{tenantId}/undeclared") { get {} }
                    }.requirePermissionDeclarations()
                }
                startApplication()
            }
        }
        assertContains(e.message.orEmpty(), "/tenants/{tenantId}/undeclared")
        assertFalse(e.message.orEmpty().contains("{tenantId}/declared"))
    }

    private fun RoutingNode.method() =
        generateSequence(this) { it.parent }.firstNotNullOf { (it.selector as? HttpMethodRouteSelector)?.method }

    private suspend fun HttpClient.call(endpoint: Endpoint, tenant: Uuid): HttpResponse =
        request(endpoint.path.replace("{tenantId}", tenant.toString()).replace(Regex("""\{\w+}"""), Uuid.random().toString())) {
            method = endpoint.method
            if (method == HttpMethod.Post || method == HttpMethod.Put) setBody("{}")
        }

    private suspend fun HttpResponse.isTenantNotFound() =
        status == HttpStatusCode.NotFound && body<ErrorResponse>().message == TENANT_NOT_FOUND
}
