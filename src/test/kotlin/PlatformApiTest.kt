import api.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlin.test.*

class PlatformApiTest {

    @Test
    fun `tenants are created, renamed, soft deleted and restored`() = apiTest { context ->
        val platformAdmin = context.clientFor(null, isPlatformAdmin = true)
        val created: Tenant = platformAdmin.post("/tenants") { setBody(NewTenant("Stamm West", "west")) }
            .expect(HttpStatusCode.Created)
        val renamed: Tenant = platformAdmin.put("/tenants/${created.id}") { setBody(NewTenant("Stamm Westend", "westend")) }
            .expect(HttpStatusCode.OK)
        assertEquals("westend", renamed.slug)

        platformAdmin.post("/tenants") { setBody("""{"name": "Login", "slug": "login"}""") }.expectError(HttpStatusCode.BadRequest)
        platformAdmin.post("/tenants") { setBody("""{"name": "X", "slug": "Stamm West"}""") }.expectError(HttpStatusCode.BadRequest)
        val taken = platformAdmin.post("/tenants") { setBody(NewTenant("Stamm Westend", "other")) }
            .expectError(HttpStatusCode.Conflict)
        assertEquals("Es gibt schon einen Stamm mit diesem Namen.", taken)

        // Deleted tenants are gone for everyone, including members, but stay listed for platform admins.
        val member = context.createUser(TenantRole.ADMIN, tenant = created.id)
        context.client(member.token, created.id).get("materials").expect<List<Material>>(HttpStatusCode.OK)
        assertEquals(HttpStatusCode.NoContent, platformAdmin.delete("/tenants/${created.id}").status)
        context.client(member.token, created.id).get("materials").expectError(HttpStatusCode.NotFound)
        assertEquals(emptyList(), context.client(member.token).get("/auth/me").expect<CurrentUser>(HttpStatusCode.OK).tenants)
        val all: List<Tenant> = platformAdmin.get("/tenants").expect(HttpStatusCode.OK)
        assertNotNull(all.single { it.id == created.id }.deletedAt)

        // Its name and slug are free again, which blocks restoring it until resolved.
        val successor: Tenant = platformAdmin.post("/tenants") { setBody(NewTenant("Stamm Westend", "westend")) }
            .expect(HttpStatusCode.Created)
        platformAdmin.post("/tenants/${created.id}/restore").expectError(HttpStatusCode.Conflict)
        platformAdmin.put("/tenants/${successor.id}") { setBody(NewTenant("Stamm Westend neu", "westend-neu")) }
            .expect<Tenant>(HttpStatusCode.OK)
        val restored: Tenant = platformAdmin.post("/tenants/${created.id}/restore").expect(HttpStatusCode.OK)
        assertNull(restored.deletedAt)
        context.client(member.token, created.id).get("materials").expect<List<Material>>(HttpStatusCode.OK)
    }

    @Test
    fun `platform admins see all tenants as admin`() = apiTest { context ->
        val other = TestDatabase.createTenant("Stamm West")
        val platformAdmin = context.createUser(TenantRole.VIEWER, isPlatformAdmin = true)
        val me: CurrentUser = context.client(platformAdmin.token).get("/auth/me").expect(HttpStatusCode.OK)
        assertEquals(
            listOf(Triple("Stamm Nord", TenantRole.ADMIN, true), Triple("Stamm West", TenantRole.ADMIN, false)),
            me.tenants.map { Triple(it.name, it.role, it.isMember) }
        )
        context.client(platformAdmin.token, other).post("members") { setBody(NewMember("Max", "max", TenantRole.ADMIN)) }
            .expect<CreatedMember>(HttpStatusCode.Created)
    }

    @Test
    fun `users are changed, disabled and deleted`() = apiTest { context ->
        val platformAdmin = context.clientFor(null, isPlatformAdmin = true)
        val user = context.createUser(TenantRole.EDITOR, username = "max")

        val renamed: User = platformAdmin.put("/users/${user.id}") {
            setBody(UserUpdate("Max Mustermann", "max.mustermann", isPlatformAdmin = false, isDisabled = false))
        }.expect(HttpStatusCode.OK)
        assertEquals("max.mustermann", renamed.username)
        assertEquals(listOf(Membership(context.tenant, TenantRole.EDITOR)), renamed.memberships)

        val disabled: User = platformAdmin.put("/users/${user.id}") {
            setBody(UserUpdate("Max Mustermann", isPlatformAdmin = false, isDisabled = true))
        }.expect(HttpStatusCode.OK)
        assertTrue(disabled.isDisabled)
        platformAdmin.post("/users/${user.id}/setup-link").expectError(HttpStatusCode.Conflict)
        // Enabling again doesn't bring back the old sessions.
        platformAdmin.put("/users/${user.id}") { setBody(UserUpdate("Max Mustermann", isPlatformAdmin = false, isDisabled = false)) }
            .expect<User>(HttpStatusCode.OK)
        context.client(user.token).get("/auth/me").expectError(HttpStatusCode.Unauthorized)

        assertEquals(HttpStatusCode.NoContent, platformAdmin.delete("/users/${user.id}").status)
        platformAdmin.delete("/users/${user.id}").expectError(HttpStatusCode.NotFound)
        val users: List<User> = platformAdmin.get("/users").expect(HttpStatusCode.OK)
        assertTrue(users.none { it.id == user.id })
    }

    @Test
    fun `the last admins can't be removed by disabling or deleting them`() = apiTest { context ->
        val platformAdmin = context.createUser(null, isPlatformAdmin = true)
        val client = context.client(platformAdmin.token)
        fun update(isPlatformAdmin: Boolean, isDisabled: Boolean) = UserUpdate("Admin", null, isPlatformAdmin, isDisabled)

        // The only platform admin.
        client.put("/users/${platformAdmin.id}") { setBody(update(isPlatformAdmin = false, isDisabled = false)) }
            .expectError(HttpStatusCode.Conflict)
        client.put("/users/${platformAdmin.id}") { setBody(update(isPlatformAdmin = true, isDisabled = true)) }
            .expectError(HttpStatusCode.Conflict)
        client.delete("/users/${platformAdmin.id}").expectError(HttpStatusCode.Conflict)

        // The only admin of a tenant.
        val tenantAdmin = context.createUser(TenantRole.ADMIN)
        val message = client.put("/users/${tenantAdmin.id}") { setBody(update(isPlatformAdmin = false, isDisabled = true)) }
            .expectError(HttpStatusCode.Conflict)
        assertEquals("Stamm Nord braucht mindestens einen Admin. Mach zuerst jemand anderen zum Admin.", message)
        client.delete("/users/${tenantAdmin.id}").expectError(HttpStatusCode.Conflict)

        // With a second one, both work.
        val second = context.createUser(null, isPlatformAdmin = true)
        context.createUser(TenantRole.ADMIN)
        client.put("/users/${second.id}") { setBody(update(isPlatformAdmin = false, isDisabled = false)) }
            .expect<User>(HttpStatusCode.OK)
        assertEquals(HttpStatusCode.NoContent, client.delete("/users/${tenantAdmin.id}").status)
    }
}
