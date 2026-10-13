import configure.ApiJson
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.JsonElement
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The API contract for the frontend. Fails when the generated OpenAPI document changes; after an intended change,
 * update the snapshot with `UPDATE_OPENAPI=1 ./gradlew test --rerun` and commit openapi.json.
 */
class OpenApiTest {

    private val snapshot = File("openapi.json")

    @Test
    fun `OpenAPI document matches the committed snapshot`() = apiTest {
        val document = ApiJson.parseToJsonElement(get("/swagger/documentation.yaml").bodyAsText())
        val actual = ApiJson.encodeToString(JsonElement.serializer(), document) + "\n"

        if (System.getenv("UPDATE_OPENAPI") != null || !snapshot.exists()) snapshot.writeText(actual)
        assertEquals(snapshot.readText(), actual, "OpenAPI document changed, see the class documentation")
    }
}
