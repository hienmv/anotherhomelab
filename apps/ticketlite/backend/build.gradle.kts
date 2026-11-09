plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
    application
}

group = "ticketlite"
version = "1.0.0"

repositories {
    // Google's mirror of Maven Central first: on GitHub's runners repo.maven.apache.org sometimes
    // redirects large jars (the Kotlin compiler) to github.com, which returned 504s (2026-11-09).
    maven("https://maven-central.storage-download.googleapis.com/maven2/")
    mavenCentral()
}

val ktor = "3.1.3"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-cio:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    implementation("io.ktor:ktor-server-sessions:$ktor")
    implementation("io.ktor:ktor-server-status-pages:$ktor")
    implementation("io.ktor:ktor-server-forwarded-header:$ktor")
    implementation("io.ktor:ktor-server-call-logging:$ktor")
    implementation("io.ktor:ktor-server-metrics-micrometer:$ktor")
    implementation("io.micrometer:micrometer-registry-prometheus:1.14.5")
    implementation("ch.qos.logback:logback-classic:1.5.18")

    implementation("com.zaxxer:HikariCP:6.3.0")
    implementation("org.postgresql:postgresql:42.7.5")
    implementation("org.flywaydb:flyway-core:10.22.0")
    implementation("org.flywaydb:flyway-database-postgresql:10.22.0")
    implementation("redis.clients:jedis:5.2.0")
    implementation("at.favre.lib:bcrypt:0.10.2")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("ticketlite.MainKt")
    applicationName = "ticketlite"
}

tasks.test {
    useJUnitPlatform()
}

/**
 * Downloads every jar the build needs (libraries, test deps and the Kotlin compiler itself) without
 * compiling anything. The Dockerfile runs it in its own layer, which stays cached until this file
 * changes, so code-only changes download nothing. (`gradle dependencies` only fetches .pom metadata.)
 */
tasks.register("resolveDependencies") {
    doLast {
        configurations.filter { it.isCanBeResolved }.forEach { conf ->
            runCatching { conf.resolve() }.onFailure { logger.info("skip ${conf.name}: ${it.message}") }
        }
    }
}
