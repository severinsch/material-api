import kotlinx.coroutines.runBlocking
import services.BootstrapService
import kotlin.test.*
import kotlin.uuid.Uuid

class BootstrapTest {

    private val bootstrap = BootstrapService(TestDatabase.db)

    @BeforeTest
    fun emptyDatabase() {
        TestDatabase.truncateAll()
    }

    @Test
    fun `the first platform admin gets a new setup link on every start until the password is set`() = runBlocking {
        val first = bootstrap.run(createAdmin = true, recoveryUsername = null)
        assertEquals(setOf("admin"), first.keys)
        assertEquals(
            listOf(listOf(true, null)),
            TestDatabase.query("SELECT is_platform_admin, password_hash FROM users JOIN password_logins ON user_id = id")
        )

        val second = bootstrap.run(createAdmin = true, recoveryUsername = null)
        assertNotEquals(first.getValue("admin").token, second.getValue("admin").token)
        assertEquals(1, TestDatabase.query("SELECT * FROM setup_tokens").size, "the old link is replaced")

        TestDatabase.execute("UPDATE password_logins SET password_hash = 'set'")
        assertEquals(emptyMap(), bootstrap.run(createAdmin = true, recoveryUsername = null))
        assertEquals(1, TestDatabase.query("SELECT * FROM users").size)
    }

    @Test
    fun `a platform admin can get a recovery link`() = runBlocking {
        bootstrap.run(createAdmin = true, recoveryUsername = null)
        TestDatabase.execute("UPDATE password_logins SET password_hash = 'set'")
        assertEquals(setOf("admin"), bootstrap.run(createAdmin = false, recoveryUsername = "admin").keys)

        insertUser("max", isPlatformAdmin = false)
        assertFailsWith<IllegalStateException> { bootstrap.run(createAdmin = false, recoveryUsername = "max") }
        assertFailsWith<IllegalStateException> { bootstrap.run(createAdmin = false, recoveryUsername = "nobody") }
    }

    @Test
    fun `startup fails if the username admin is taken by someone who isn't platform admin`() {
        insertUser("admin", isPlatformAdmin = false)
        val e = assertFailsWith<IllegalStateException> { runBlocking { bootstrap.run(createAdmin = true, recoveryUsername = null) } }
        assertContains(e.message.orEmpty(), "username admin is taken")
    }

    private fun insertUser(username: String, isPlatformAdmin: Boolean) {
        val id = Uuid.random()
        TestDatabase.execute(
            """
            INSERT INTO users (id, display_name, is_platform_admin) VALUES ('$id', '$username', $isPlatformAdmin);
            INSERT INTO password_logins (user_id, username, password_hash) VALUES ('$id', '$username', 'set');
            """
        )
    }
}
