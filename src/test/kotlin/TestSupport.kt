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
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.MountableFile
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/** A Postgres container with db/schema.sql, shared by all tests and emptied before each one. */
object TestDatabase {
    private val container = PostgreSQLContainer("postgres:18-alpine")
        .withCopyFileToContainer(
            MountableFile.forHostPath("db/schema.sql"),
            "/docker-entrypoint-initdb.d/schema.sql"
        )
        .also { it.start() }

    val config = MapApplicationConfig(
        "database.url" to container.jdbcUrl,
        "database.user" to container.username,
        "database.password" to container.password,
    )

    fun truncateAll() = DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use {
        // Cascades to all other tables.
        it.createStatement().execute("TRUNCATE materials, storage_locations, kits, loans CASCADE")
    }
}

/** Runs [block] against the full application on an empty database. */
fun apiTest(block: suspend HttpClient.() -> Unit) = testApplication {
    TestDatabase.truncateAll()
    environment { config = TestDatabase.config }
    application { module() }
    createClient {
        install(ContentNegotiation) { json(ApiJson) }
        defaultRequest { contentType(ContentType.Application.Json) }
    }.block()
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
    post("/locations") { setBody(NewStorageLocation(name)) }.expect(HttpStatusCode.Created)

suspend fun HttpClient.createBulk(
    name: String = "Hering",
    available: Int = 10,
    location: StorageLocation? = null
): BulkMaterial =
    post("/materials") {
        setBody<NewMaterial>(NewBulkMaterial(name, availableCount = available, storageLocationId = location?.id))
    }.expect<Material>(HttpStatusCode.Created) as BulkMaterial

suspend fun HttpClient.createIndividual(name: String = "Kohtenblatt"): IndividualMaterial =
    post("/materials") { setBody<NewMaterial>(NewIndividualMaterial(name)) }
        .expect<Material>(HttpStatusCode.Created) as IndividualMaterial

suspend fun HttpClient.createItem(
    material: IndividualMaterial,
    identifier: String,
    condition: ItemCondition = ItemCondition.GOOD,
    status: ItemStatus = ItemStatus.ACTIVE
): IndividualItem =
    post("/items") { setBody(NewIndividualItem(material.id, identifier, condition, status)) }
        .expect(HttpStatusCode.Created)

suspend fun HttpClient.getMaterial(id: Uuid): Material = get("/materials/$id").expect(HttpStatusCode.OK)
