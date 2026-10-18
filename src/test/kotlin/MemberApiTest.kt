import api.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.test.*
import kotlin.uuid.Uuid

class MemberApiTest {

    @Test
    fun `admins create accounts that are set up with a link`() = apiTest { context ->
        val admin = context.clientFor(TenantRole.ADMIN)
        val created: CreatedMember = admin.post("members") { setBody(NewMember("Max Mustermann", "max", TenantRole.VIEWER)) }
            .expect(HttpStatusCode.Created)
        assertTrue(created.member.setupPending)

        val anonymous = context.client(null)
        val session: SessionToken = anonymous.post("/auth/setup") { setBody(PasswordSetup(created.setupLink.token, PASSWORD)) }
            .expect(HttpStatusCode.OK)
        val me: CurrentUser = context.client(session.token).get("/auth/me").expect(HttpStatusCode.OK)
        assertEquals(listOf(context.tenant to TenantRole.VIEWER), me.tenants.map { it.id to it.role })

        val members: List<Member> = get("members").expect(HttpStatusCode.OK)
        assertFalse(members.single { it.username == "max" }.setupPending)
    }

    @Test
    fun `usernames are validated and unique`() = apiTest { context ->
        val admin = context.clientFor(TenantRole.ADMIN)
        admin.post("members") { setBody("""{"displayName": "Max", "username": "Max", "role": "VIEWER"}""") }
            .expectError(HttpStatusCode.BadRequest)
        admin.post("members") { setBody(NewMember("Max", "max", TenantRole.VIEWER)) }.expect<CreatedMember>(HttpStatusCode.Created)
        val taken = admin.post("members") { setBody(NewMember("Max 2", "max", TenantRole.VIEWER)) }
            .expectError(HttpStatusCode.Conflict)
        assertEquals("Den Benutzernamen max gibt es schon.", taken)

        val other = context.createUser(TenantRole.VIEWER)
        admin.put("members/${other.id}") { setBody(MemberUpdate(TenantRole.VIEWER, username = "max")) }
            .expectError(HttpStatusCode.Conflict)
        val renamed: Member = admin.put("members/${other.id}") { setBody(MemberUpdate(TenantRole.EDITOR, "Moritz", "moritz")) }
            .expect(HttpStatusCode.OK)
        assertEquals(Triple(TenantRole.EDITOR, "Moritz", "moritz"), Triple(renamed.role, renamed.displayName, renamed.username))
    }

    @Test
    fun `tenant admins can't take over accounts with access elsewhere`() = apiTest { context ->
        val admin = context.clientFor(TenantRole.ADMIN)
        val otherTenant = TestDatabase.createTenant("Stamm West")
        val shared = context.createUser(TenantRole.VIEWER)
        addMember(otherTenant, shared.id, TenantRole.ADMIN)
        val platformAdmin = context.createUser(TenantRole.VIEWER, isPlatformAdmin = true)
        val outsider = context.createUser(TenantRole.VIEWER, tenant = otherTenant)

        // The role in this tenant is theirs to change, the account isn't.
        admin.put("members/${shared.id}") { setBody(MemberUpdate(TenantRole.EDITOR)) }.expect<Member>(HttpStatusCode.OK)
        // Sending the unchanged name is no rename.
        admin.put("members/${shared.id}") { setBody(MemberUpdate(TenantRole.EDITOR, displayName = shared.displayName)) }
            .expect<Member>(HttpStatusCode.OK)
        admin.put("members/${shared.id}") { setBody(MemberUpdate(TenantRole.EDITOR, displayName = "Mallory")) }
            .expectError(HttpStatusCode.Forbidden)
        admin.put("members/${shared.id}") { setBody(MemberUpdate(TenantRole.EDITOR, username = "mallory")) }
            .expectError(HttpStatusCode.Forbidden)
        admin.post("members/${shared.id}/setup-link").expectError(HttpStatusCode.Forbidden)

        // Platform admins are never affected by tenant admins.
        admin.put("members/${platformAdmin.id}") { setBody(MemberUpdate(TenantRole.EDITOR)) }.expectError(HttpStatusCode.Forbidden)
        admin.delete("members/${platformAdmin.id}").expectError(HttpStatusCode.Forbidden)
        admin.post("members/${platformAdmin.id}/setup-link").expectError(HttpStatusCode.Forbidden)

        // Only platform admins add users of other tenants.
        admin.put("members/${outsider.id}") { setBody(MemberUpdate(TenantRole.VIEWER)) }.expectError(HttpStatusCode.NotFound)
        context.client(platformAdmin.token).put("members/${outsider.id}") { setBody(MemberUpdate(TenantRole.VIEWER)) }
            .expect<Member>(HttpStatusCode.OK)

        // Accounts of this tenant alone are fine.
        val own = context.createUser(TenantRole.VIEWER)
        admin.put("members/${own.id}") { setBody(MemberUpdate(TenantRole.VIEWER, "Max", "max")) }.expect<Member>(HttpStatusCode.OK)
        admin.post("members/${own.id}/setup-link").expect<SetupLink>(HttpStatusCode.OK)
    }

    @Test
    fun `a tenant keeps an enabled admin`() = apiTest { context ->
        val first = context.createUser(TenantRole.ADMIN)
        val admin = context.client(first.token)
        admin.put("members/${first.id}") { setBody(MemberUpdate(TenantRole.EDITOR)) }.expectError(HttpStatusCode.Conflict)
        admin.delete("members/${first.id}").expectError(HttpStatusCode.Conflict)

        // A disabled admin doesn't count.
        val disabled = context.createUser(TenantRole.ADMIN)
        TestDatabase.execute("UPDATE users SET disabled_at = now() WHERE id = '${disabled.id}'")
        admin.delete("members/${first.id}").expectError(HttpStatusCode.Conflict)

        val second = context.createUser(TenantRole.ADMIN)
        assertEquals(HttpStatusCode.NoContent, admin.delete("members/${first.id}").status)
        // Removed, but the account stays and sees no tenant.
        val me: CurrentUser = admin.get("/auth/me").expect(HttpStatusCode.OK)
        assertEquals(emptyList(), me.tenants)
        admin.get("members").expectError(HttpStatusCode.NotFound)
        assertEquals(1, adminCount(context.tenant), "only ${second.username} is left")
    }

    @Test
    fun `two admins demoting each other at the same time leave one admin`() = apiTest { context ->
        val (a, b) = List(2) { context.createUser(TenantRole.ADMIN) }
        val statuses = coroutineScope {
            listOf(a to b, b to a).map { (actor, target) ->
                async { context.client(actor.token).put("members/${target.id}") { setBody(MemberUpdate(TenantRole.EDITOR)) }.status }
            }.awaitAll()
        }
        assertEquals(1, statuses.count { it == HttpStatusCode.OK }, statuses.toString())
        assertEquals(1, adminCount(context.tenant))
    }

    @Test
    fun `setup links are revoked when the user's access changes`() = apiTest { context ->
        val admin = context.clientFor(TenantRole.ADMIN)
        val platformAdmin = context.clientFor(null, isPlatformAdmin = true)
        val otherTenant = TestDatabase.createTenant("Stamm West")
        val user = context.createUser(TenantRole.VIEWER)
        suspend fun newLink() = platformAdmin.post("/users/${user.id}/setup-link").expect<SetupLink>(HttpStatusCode.OK).token
        // Checked in the database: the setup page would hide the link of a disabled user anyway.
        fun hasLink() = TestDatabase.query("SELECT * FROM setup_tokens WHERE user_id = '${user.id}'").isNotEmpty()

        newLink()
        admin.put("members/${user.id}") { setBody(MemberUpdate(TenantRole.VIEWER, displayName = "Max")) }
            .expect<Member>(HttpStatusCode.OK)
        assertTrue(hasLink(), "renaming doesn't change access")

        val changes = listOf<Pair<String, suspend () -> HttpStatusCode>>(
            "role" to { admin.put("members/${user.id}") { setBody(MemberUpdate(TenantRole.EDITOR)) }.status },
            "new membership" to {
                platformAdmin.put("/tenants/$otherTenant/members/${user.id}") { setBody(MemberUpdate(TenantRole.VIEWER)) }.status
            },
            "removed membership" to { platformAdmin.delete("/tenants/$otherTenant/members/${user.id}").status },
            "platform admin" to {
                platformAdmin.put("/users/${user.id}") { setBody(UserUpdate("Max", isPlatformAdmin = true, isDisabled = false)) }.status
            },
            "disabled" to {
                platformAdmin.put("/users/${user.id}") { setBody(UserUpdate("Max", isPlatformAdmin = true, isDisabled = true)) }.status
            },
        )
        for ((change, perform) in changes) {
            newLink()
            assertTrue(perform().isSuccess(), change)
            assertFalse(hasLink(), change)
        }
    }

    private fun addMember(tenant: Uuid, userId: Uuid, role: TenantRole) = TestDatabase.execute(
        "INSERT INTO tenant_members (tenant_id, user_id, role) VALUES ('$tenant', '$userId', '$role')"
    )

    private fun adminCount(tenant: Uuid) = TestDatabase.query(
        "SELECT count(*) FROM tenant_members JOIN users ON id = user_id " +
            "WHERE tenant_id = '$tenant' AND role = 'ADMIN' AND disabled_at IS NULL"
    ).single().single().toString().toInt()
}
