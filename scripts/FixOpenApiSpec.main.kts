import java.io.File

val openApiFile = File(args[0])
val openApiJson = openApiFile.readText()

val newJson = openApiJson.replace(
    $$"\"$ref\": \"#/components/schemas/MaterialResponse\"",
    $$"""
        "anyOf": [ 
                                                    { "$ref": "#/components/schemas/IndividualMaterialSummaryResponse" }, 
                                                    { "$ref": "#/components/schemas/BulkMaterialResponse" }
                                                ]""".trimIndent()
)

openApiFile.writeText(newJson)
