import java.io.File

val openApiFile = File(args[0])
val openApiJson = openApiFile.readText()

fun String.fixMaterialResponse() = this.replace(
    $$"\"$ref\": \"#/components/schemas/MaterialResponse\"",
    $$"""
        "anyOf": [ 
                                                    { "$ref": "#/components/schemas/IndividualMaterialSummaryResponse" }, 
                                                    { "$ref": "#/components/schemas/BulkMaterialResponse" }
                                                ]""".trimIndent()
)

val uuidFields = listOf("id", "materialId", "storageLocationId", "inventoryItemId")

fun String.addUuidFormat(): String = uuidFields.fold(
    this
) { acc, field ->
    acc.replace(
        Regex("(?<padding>\\h*)\"$field\": \\{\\s*\"type\": \"string\"\\s*\\}"),
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
