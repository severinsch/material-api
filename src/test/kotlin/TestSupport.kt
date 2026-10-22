import api.*
import configure.ApiJson
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import auth.Argon2Config
import auth.Passwords
import database.*
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import services.startSession
import org.flywaydb.core.Flyway
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/** A Postgres container with the migrated schema, shared by all tests and emptied before each one. */
object TestDatabase {
    private val container = PostgreSQLContainer("postgres:18-alpine").also {
        it.start()
        // The application migrates as well, but the tables must exist before the first truncate.
        Flyway.configure().dataSource(it.jdbcUrl, it.username, it.password).load().migrate()
    }

    val config = MapApplicationConfig(
        "database.url" to container.jdbcUrl,
        "database.user" to container.username,
        "database.password" to container.password,
        "auth.bootstrap" to "false",
        "auth.argon2.memoryKib" to "64",
        "auth.argon2.iterations" to "1",
    )

    /** Cheap hashing for tests, as in [config]. */
    val passwords = Passwords(Argon2Config(memoryKib = 64, iterations = 1))

    /** For fixtures that are simpler to set up directly than through the API. */
    val db by lazy { Db(DatabaseConfig(container.jdbcUrl, container.username, container.password)) }

    /** URL of another database in the same container, e.g. created by [execute]. */
    fun url(database: String) = "jdbc:postgresql://${container.host}:${container.firstMappedPort}/$database"

    /** Applies the migrations up to [target] (all if null) to the database at [url]. */
    fun migrate(url: String, target: String? = null) {
        Flyway.configure().dataSource(url, container.username, container.password)
            .apply { target?.let(::target) }
            .load().migrate()
    }

    /**
     * Runs [block] with a connection as the owner of the tables, which isn't subject to row-level security. The
     * connection is in auto-commit mode.
     */
    fun <T> connect(url: String = container.jdbcUrl, block: (Connection) -> T): T =
        DriverManager.getConnection(url, container.username, container.password).use(block)

    fun execute(sql: String, url: String = container.jdbcUrl) = connect(url) { it.createStatement().execute(sql) }

    /** The rows returned by [sql], each as a list of column values. */
    fun query(sql: String, url: String = container.jdbcUrl): List<List<Any?>> = connect(url) {
        it.createStatement().executeQuery(sql).use { rs ->
            buildList { while (rs.next()) add((1..rs.metaData.columnCount).map(rs::getObject)) }
        }
    }

    // Cascades to all other tables.
    fun truncateAll() = execute("TRUNCATE tenants, users CASCADE")

    fun createTenant(name: String = "Stamm Nord"): Uuid = Uuid.random().also {
        execute("INSERT INTO tenants (id, name, slug) VALUES ('$it', '$name', '${name.lowercase().replace(' ', '-')}')")
    }
}

const val PASSWORD = "correct horse battery staple"

/** A user created by [ApiTestContext.createUser], with a session. */
data class TestUser(val id: Uuid, val username: String, val displayName: String, val token: String)

/** What an [apiTest] can use besides its default client. */
class ApiTestContext(
    private val builder: ApplicationTestBuilder,
    /** The tenant of the default client, which belongs to an EDITOR. */
    val tenant: Uuid,
    val routing: RoutingRoot,
) {
    private var users = 0

    /**
     * A user with [role] in [tenant] (no membership if null) and the password [PASSWORD] (pending setup if null),
     * logged in.
     */
    suspend fun createUser(
        role: TenantRole? = TenantRole.EDITOR,
        tenant: Uuid = this.tenant,
        isPlatformAdmin: Boolean = false,
        password: String? = PASSWORD,
        username: String = "user${++users}",
    ): TestUser {
        val hash = password?.let { TestDatabase.passwords.hash(it) }
        val displayName = username.replaceFirstChar(Char::uppercase)
        return TestDatabase.db.query {
            val id = Users.insertAndGetId {
                it[Users.displayName] = displayName
                it[Users.isPlatformAdmin] = isPlatformAdmin
            }
            PasswordLogins.insert {
                it[userId] = id
                it[PasswordLogins.username] = username
                it[passwordHash] = hash
            }
            role?.let { role ->
                TenantMembers.insert {
                    it[tenantId] = tenant
                    it[userId] = id
                    it[TenantMembers.role] = role
                }
            }
            TestUser(id.value, username, displayName, startSession(id.value, LoginMethod.PASSWORD).token)
        }
    }

    /** A client for the API of [tenant], logged in with [token] if given. */
    fun client(token: String?, tenant: Uuid = this.tenant): HttpClient = builder.createClient {
        install(ContentNegotiation) { json(ApiJson) }
        defaultRequest {
            url("http://localhost/tenants/$tenant/")
            contentType(ContentType.Application.Json)
            token?.let(::bearerAuth)
        }
    }

    /** A client of a new user with [role] in [tenant]. */
    suspend fun clientFor(role: TenantRole?, tenant: Uuid = this.tenant, isPlatformAdmin: Boolean = false) =
        client(createUser(role, tenant, isPlatformAdmin).token, tenant)
}

/**
 * Runs [block] against the full application on an empty database with one tenant, as one of its editors. Requests
 * go to the tenant's API by default, so paths are relative (`get("materials")`); a leading slash leaves the tenant.
 */
fun apiTest(block: suspend HttpClient.(ApiTestContext) -> Unit) = testApplication {
    TestDatabase.truncateAll()
    environment { config = TestDatabase.config }
    lateinit var routing: RoutingRoot
    application {
        module()
        routing = plugin(RoutingRoot)
    }
    startApplication()
    val context = ApiTestContext(this, TestDatabase.createTenant(), routing)
    context.clientFor(TenantRole.EDITOR).block(context)
}

suspend inline fun <reified T> HttpResponse.expect(status: HttpStatusCode): T {
    assertEquals(status, this.status, bodyAsText())
    return body()
}

suspend fun HttpResponse.expectError(status: HttpStatusCode): String {
    assertEquals(status, this.status, bodyAsText())
    return body<ErrorResponse>().message
}

// Fixtures

suspend fun HttpClient.createLocation(name: String = "Keller"): StorageLocation =
    post("locations") { setBody(NewStorageLocation(name)) }.expect(HttpStatusCode.Created)

/** A bulk material with a single, unnamed variant. */
suspend fun HttpClient.createBulk(
    name: String = "Hering",
    available: Int = 10,
    location: StorageLocation? = null
): BulkMaterial =
    createBulk(name, NewBulkVariant(availableCount = available, storageLocationId = location?.id))

suspend fun HttpClient.createBulk(name: String, vararg variants: NewBulkVariant): BulkMaterial =
    post("materials") { setBody<NewMaterial>(NewBulkMaterial(name, variants = variants.toList())) }
        .expect<Material>(HttpStatusCode.Created) as BulkMaterial

/** An individual material with a single, unnamed variant, or with the named [variants]. */
suspend fun HttpClient.createIndividual(name: String = "Kohtenblatt", vararg variants: String): IndividualMaterial {
    val newVariants = variants.map { NewIndividualVariant(name = it) }.ifEmpty { listOf(NewIndividualVariant()) }
    return post("materials") { setBody<NewMaterial>(NewIndividualMaterial(name, variants = newVariants)) }
        .expect<Material>(HttpStatusCode.Created) as IndividualMaterial
}

suspend fun HttpClient.createItem(
    material: IndividualMaterial,
    identifier: String,
    condition: ItemCondition = ItemCondition.GOOD,
    status: ItemStatus = ItemStatus.ACTIVE,
    variant: IndividualVariant = material.variants.single()
): IndividualItem =
    post("items") { setBody(NewIndividualItem(variant.id, identifier, condition, status)) }
        .expect(HttpStatusCode.Created)

suspend fun HttpClient.getMaterial(id: Uuid): Material = get("materials/$id").expect(HttpStatusCode.OK)

/** The id of the material's single variant. */
val Material.variantId: Uuid
    get() = when (this) {
        is BulkMaterial -> variants.single().id
        is IndividualMaterial -> variants.single().id
    }

/** [material] with its single variant's stock set to [available], expecting [expected] if given. */
fun stockUpdate(material: BulkMaterial, available: Int, expected: Int? = null): NewMaterial = NewBulkMaterial(
    material.name,
    variants = listOf(NewBulkVariant(material.variantId, availableCount = available, expectedAvailableCount = expected))
)
