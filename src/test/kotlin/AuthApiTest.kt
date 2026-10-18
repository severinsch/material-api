import api.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import services.NEW_ACCOUNT_LINK_VALIDITY
import services.issueSetupLink
import kotlin.test.*
import kotlin.uuid.Uuid

class AuthApiTest {

    @Test
    fun `logging in starts a session that shows the user and their tenants`() = apiTest { context ->
        val user = context.createUser(TenantRole.VIEWER, username = "max")
        val anonymous = context.client(null)

        val session: SessionToken = anonymous.login(" Max ", PASSWORD).expect(HttpStatusCode.OK)
        val me: CurrentUser = context.client(session.token).get("/auth/me").expect(HttpStatusCode.OK)
        assertEquals(user.id, me.id)
        assertEquals("max", me.username)
        assertEquals(listOf(TenantRole.VIEWER to true), me.tenants.map { it.role to it.isMember })
        assertEquals(context.tenant, me.tenants.single().id)
    }

    @Test
    fun `failed logins get the same answer whatever the reason`() = apiTest { context ->
        context.createUser(username = "max")
        context.createUser(username = "pending", password = null)
        context.createUser(username = "disabled").also { disable(it.id) }
        val anonymous = context.client(null)

        val messages = listOf("max" to "wrong password!", "unknown" to PASSWORD, "pending" to PASSWORD, "disabled" to PASSWORD)
            .map { (username, password) -> anonymous.login(username, password).expectError(HttpStatusCode.Unauthorized) }
        assertEquals(listOf("Benutzername oder Passwort ist falsch."), messages.distinct())
    }

    @Test
    fun `requests without a valid session are rejected`() = apiTest { context ->
        val user = context.createUser()
        val client = context.client(user.token)
        client.get("materials").expect<List<Material>>(HttpStatusCode.OK)

        val message = context.client(null).get("materials").expectError(HttpStatusCode.Unauthorized)
        assertEquals("Du bist nicht angemeldet, oder deine Sitzung ist abgelaufen.", message)
        context.client("not-a-token").get("/auth/me").expectError(HttpStatusCode.Unauthorized)

        // Idle for too long.
        TestDatabase.execute("UPDATE sessions SET last_used_at = now() - interval '31 days' WHERE user_id = '${user.id}'")
        client.get("materials").expectError(HttpStatusCode.Unauthorized)

        // Past the absolute limit, although used recently.
        val other = context.createUser()
        TestDatabase.execute("UPDATE sessions SET expires_at = now() - interval '1 second' WHERE user_id = '${other.id}'")
        context.client(other.token).get("materials").expectError(HttpStatusCode.Unauthorized)

        // Disabled users lose their sessions at once.
        val disabled = context.createUser()
        disable(disabled.id)
        context.client(disabled.token).get("materials").expectError(HttpStatusCode.Unauthorized)
    }

    @Test
    fun `logging out ends the session`() = apiTest { context ->
        val user = context.createUser()
        val client = context.client(user.token)
        assertEquals(HttpStatusCode.NoContent, client.post("/auth/logout").status)
        client.get("/auth/me").expectError(HttpStatusCode.Unauthorized)
    }

    @Test
    fun `the profile and the password can be changed`() = apiTest { context ->
        val user = context.createUser(username = "max")
        val client = context.client(user.token)
        val otherSession = context.client(null).login("max", PASSWORD).expect<SessionToken>(HttpStatusCode.OK)

        val me: CurrentUser = client.put("/auth/me") { setBody(NewProfile("Max Mustermann")) }.expect(HttpStatusCode.OK)
        assertEquals("Max Mustermann", me.displayName)

        val wrong = client.put("/auth/me/password") { setBody(PasswordChange("wrong password!", "a new password")) }
            .expectError(HttpStatusCode.BadRequest)
        assertEquals("Das aktuelle Passwort ist falsch.", wrong)
        val tooShort = client.put("/auth/me/password") {
            setBody("""{"currentPassword": "$PASSWORD", "newPassword": "too short"}""")
        }.expectError(HttpStatusCode.BadRequest)
        assertEquals("Das Passwort muss mindestens 12 Zeichen lang sein.", tooShort)
        assertEquals(HttpStatusCode.NoContent, client.put("/auth/me/password") { setBody(PasswordChange(PASSWORD, "a new password")) }.status)

        // Ends the other sessions, but not the current one.
        context.client(otherSession.token).get("/auth/me").expectError(HttpStatusCode.Unauthorized)
        client.get("/auth/me").expect<CurrentUser>(HttpStatusCode.OK)
        context.client(null).login("max", PASSWORD).expectError(HttpStatusCode.Unauthorized)
        context.client(null).login("max", "a new password").expect<SessionToken>(HttpStatusCode.OK)
    }

    @Test
    fun `a setup link sets the password once and ends existing sessions`() = apiTest { context ->
        val user = context.createUser(username = "max")
        val link = TestDatabase.db.query { issueSetupLink(user.id, NEW_ACCOUNT_LINK_VALIDITY) }
        val anonymous = context.client(null)

        val info: SetupInfo = anonymous.post("/auth/setup/inspect") { setBody(SetupToken(link.token)) }
            .expect(HttpStatusCode.OK)
        assertEquals(SetupInfo("Max", "max"), info)
        anonymous.post("/auth/setup") { setBody("""{"token": "${link.token}", "password": "short"}""") }
            .expectError(HttpStatusCode.BadRequest)

        val session: SessionToken = anonymous.post("/auth/setup") { setBody(PasswordSetup(link.token, "a new password")) }
            .expect(HttpStatusCode.OK)
        context.client(session.token).get("/auth/me").expect<CurrentUser>(HttpStatusCode.OK)
        context.client(user.token).get("/auth/me").expectError(HttpStatusCode.Unauthorized)
        anonymous.login("max", "a new password").expect<SessionToken>(HttpStatusCode.OK)

        // Single use.
        anonymous.post("/auth/setup/inspect") { setBody(SetupToken(link.token)) }.expectError(HttpStatusCode.NotFound)
        anonymous.post("/auth/setup") { setBody(PasswordSetup(link.token, "another password")) }
            .expectError(HttpStatusCode.NotFound)
    }

    @Test
    fun `expired setup links and links of disabled users don't work`() = apiTest { context ->
        val anonymous = context.client(null)
        val expired = context.createUser(password = null)
        val expiredLink = TestDatabase.db.query { issueSetupLink(expired.id, NEW_ACCOUNT_LINK_VALIDITY) }
        TestDatabase.execute("UPDATE setup_tokens SET expires_at = now() - interval '1 second'")
        anonymous.post("/auth/setup") { setBody(PasswordSetup(expiredLink.token, "a new password")) }
            .expectError(HttpStatusCode.NotFound)

        val disabled = context.createUser(password = null)
        val disabledLink = TestDatabase.db.query { issueSetupLink(disabled.id, NEW_ACCOUNT_LINK_VALIDITY) }
        disable(disabled.id)
        anonymous.post("/auth/setup/inspect") { setBody(SetupToken(disabledLink.token)) }
            .expectError(HttpStatusCode.NotFound)
        anonymous.post("/auth/setup") { setBody(PasswordSetup(disabledLink.token, "a new password")) }
            .expectError(HttpStatusCode.NotFound)
    }

    @Test
    fun `repeated failed logins are throttled per username and client`() = apiTest { context ->
        context.createUser(username = "max")
        val anonymous = context.client(null)
        repeat(5) { anonymous.login("max", "wrong password!", ip = "192.0.2.1").expectError(HttpStatusCode.Unauthorized) }

        val throttled = anonymous.login("max", PASSWORD, ip = "192.0.2.1")
        throttled.expectError(HttpStatusCode.TooManyRequests)
        assertNotNull(throttled.headers[HttpHeaders.RetryAfter])
        // Others can still log in as max, so the account can't be locked out by guessing.
        anonymous.login("max", PASSWORD, ip = "192.0.2.2").expect<SessionToken>(HttpStatusCode.OK)
    }

    private suspend fun HttpClient.login(username: String, password: String, ip: String? = null) =
        post("/auth/login") {
            setBody(Credentials(username, password))
            ip?.let { header(HttpHeaders.XForwardedFor, it) }
        }

    private fun disable(userId: Uuid) = TestDatabase.execute("UPDATE users SET disabled_at = now() WHERE id = '$userId'")
}
