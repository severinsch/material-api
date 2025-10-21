import java.io.File

val openApiFile = File(args[0])
val openApiJson = openApiFile.readText()

fun String.fixMaterialResponse() = this.replace(
    Regex($$"""
        (?<padding>\h*)"\$ref": "#/components/schemas/MaterialResponse"
        """.trimIndent()
    ),
    $$"""
    ${padding}"anyOf": [ 
        ${padding}{ "\$ref": "#/components/schemas/IndividualMaterialSummaryResponse" }, 
        ${padding}{ "\$ref": "#/components/schemas/BulkMaterialResponse" }
    ${padding}]""".trimIndent()
)

val uuidFields = listOf("id", "materialId", "storageLocationId", "inventoryItemId")

fun String.addUuidFormat(): String = uuidFields.fold(
    this
) { acc, field ->
    acc.replace(
        Regex("""
            (?<padding>\h*)"$field": \{\s*"type": "string"\s*\}
            """.trimIndent()
        ),
        $$"""
        ${padding}"$$field": {
            ${padding}"type": "string",
            ${padding}"format": "uuid"
        ${padding}}""".trimIndent()
    )
}

val newJson = openApiJson
    .fixMaterialResponse()
    .addUuidFormat()

openApiFile.writeText(newJson)
