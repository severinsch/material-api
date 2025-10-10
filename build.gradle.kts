import io.ktor.plugin.OpenApiPreview

val exposed_version: String by project

val kotlin_version: String by project
val logback_version: String by project

plugins {
    kotlin("jvm") version "2.2.20"
    id("io.ktor.plugin") version "3.3.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20"
}

group = "de.dpsg"
version = "0.0.1"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

ktor {
    @OptIn(OpenApiPreview::class)
    openApi {
        title = "OpenAPI example"
        version = "2.1"
        summary = "Material management api"
        description = "This is a longer description"
        termsOfService = "https://example.com/terms/"
        contact = "severin@dpsg-langenbach.de"
        license = "Apache/1.0"

        // Location of the generated specification (defaults to openapi/generated.json)\
        target = project.layout.projectDirectory.dir("src/main/resources/openapi").file("open-api.json")
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-hsts")
    implementation("io.ktor:ktor-server-http-redirect")
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-openapi")
    implementation("io.ktor:ktor-server-swagger")
    implementation("io.ktor:ktor-server-auth")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("io.ktor:ktor-server-sessions")
    implementation("org.jetbrains.exposed:exposed-core:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposed_version")
    implementation("org.postgresql:postgresql:42.7.3")
    implementation("io.ktor:ktor-server-netty")
    implementation("ch.qos.logback:logback-classic:$logback_version")
    implementation("io.ktor:ktor-server-config-yaml")
    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")
}
