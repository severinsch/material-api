import java.io.File

val openApiFile = File(args[0])
val openApiJson = openApiFile.readText()

// copied from main code, watch for divergence
enum class MaterialTrackingType {
    BULK, INDIVIDUAL
}

enum class ItemCondition {
    NEW, GOOD, FAIR, POOR, DECOMMISSIONED
}

fun String.fixMaterialResponse() = this.replace(
    Regex(
        $$"""
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
        Regex(
            """
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

fun String.addEnums(): String {
    fun String.insertEnum(name: String, values: List<String>): String {
        return this.replace(
            Regex(
                """
                (?<padding>\h*)"components": \{\s*"schemas": \{
            """.trimIndent()
            ),
            $$"""
            ${padding}"components": {
                ${padding}"schemas": {
                    ${padding}"$$name": {
                        ${padding}"type": "string",
                        ${padding}"enum": [$${values.joinToString(", ") { "\"$it\"" }}]
                    ${padding}},
            """.trimIndent()
        )
    }

    fun String.insertRef(fieldName: String, ref: String): String {
        return this.replace(
            Regex(
                """
                (?<padding>\h*)"$fieldName": .+?\}\s*\}\s*\}""".trimIndent(),
                RegexOption.DOT_MATCHES_ALL
            ),
            $$"""
                ${padding}"$$fieldName": {  
                    ${padding}"\$ref": "$$ref"
                ${padding}}
             """.trimIndent()
        )
    }

    val result = this
        .insertEnum("Condition", ItemCondition.entries.map { it.name })
        .insertEnum("TrackingType", MaterialTrackingType.entries.map { it.name })

    val conditionRef = "#/components/schemas/Condition"
    val trackingTypeRef = "#/components/schemas/TrackingType"

    return result
        .insertRef("condition", conditionRef)
        .insertRef("trackingType", trackingTypeRef)
}

fun String.fixVersion(): String = this.replace(
    Regex("\"openapi\": \"3.[0-9]+.[0-9]+\""),
    "\"openapi\": \"3.0.3\""
)

val newJson = openApiJson
    .fixMaterialResponse()
    .addUuidFormat()
    .addEnums()
    .fixVersion()

openApiFile.writeText(newJson)
