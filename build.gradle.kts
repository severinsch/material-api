val exposed_version = property("exposed_version")
val logback_version = property("logback_version")
val postgres_version = property("postgres_version")
val hikari_version = property("hikari_version")
val testcontainers_version = property("testcontainers_version")

plugins {
    kotlin("jvm") version "2.4.21"
    id("io.ktor.plugin") version "3.6.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21"
}

group = "de.dpsg"
version = "0.0.1"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

kotlin {
    jvmToolchain(21)
}

ktor {
    // Generates OpenAPI metadata for the routes at compile time; the document itself is assembled at runtime.
    openApi {
        enabled = true
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-config-yaml")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("io.ktor:ktor-server-status-pages")
    implementation("io.ktor:ktor-server-cors")
    implementation("io.ktor:ktor-server-routing-openapi")
    implementation("io.ktor:ktor-server-openapi")
    implementation("io.ktor:ktor-server-swagger")
    implementation("org.jetbrains.exposed:exposed-core:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:$exposed_version")
    implementation("org.postgresql:postgresql:$postgres_version")
    implementation("com.zaxxer:HikariCP:$hikari_version")
    implementation("ch.qos.logback:logback-classic:$logback_version")
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation("io.ktor:ktor-client-content-negotiation")
    testImplementation("org.testcontainers:testcontainers-postgresql:$testcontainers_version")
}

tasks.test {
    useJUnitPlatform()
}
