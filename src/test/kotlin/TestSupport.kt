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
import io.ktor.server.config.*
import io.ktor.server.testing.*
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
    )

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

/** What an [apiTest] can use besides its default client. */
class ApiTestContext(private val builder: ApplicationTestBuilder, val tenant: Uuid) {

    /** A client for the API of [tenant]. */
    fun client(tenant: Uuid = this.tenant): HttpClient = builder.createClient {
        install(ContentNegotiation) { json(ApiJson) }
        defaultRequest {
            url("http://localhost/tenants/$tenant/")
            contentType(ContentType.Application.Json)
        }
    }
}

/**
 * Runs [block] against the full application on an empty database with one tenant. Requests go to the tenant's API
 * by default, so paths are relative (`get("materials")`); a leading slash leaves the tenant.
 */
fun apiTest(block: suspend HttpClient.(ApiTestContext) -> Unit) = testApplication {
    TestDatabase.truncateAll()
    environment { config = TestDatabase.config }
    application { module() }
    val context = ApiTestContext(this, TestDatabase.createTenant())
    context.client().block(context)
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

suspend fun HttpClient.createBulk(
    name: String = "Hering",
    available: Int = 10,
    location: StorageLocation? = null
): BulkMaterial =
    post("materials") {
        setBody<NewMaterial>(NewBulkMaterial(name, availableCount = available, storageLocationId = location?.id))
    }.expect<Material>(HttpStatusCode.Created) as BulkMaterial

suspend fun HttpClient.createIndividual(name: String = "Kohtenblatt"): IndividualMaterial =
    post("materials") { setBody<NewMaterial>(NewIndividualMaterial(name)) }
        .expect<Material>(HttpStatusCode.Created) as IndividualMaterial

suspend fun HttpClient.createItem(
    material: IndividualMaterial,
    identifier: String,
    condition: ItemCondition = ItemCondition.GOOD,
    status: ItemStatus = ItemStatus.ACTIVE
): IndividualItem =
    post("items") { setBody(NewIndividualItem(material.id, identifier, condition, status)) }
        .expect(HttpStatusCode.Created)

suspend fun HttpClient.getMaterial(id: Uuid): Material = get("materials/$id").expect(HttpStatusCode.OK)
