plugins {
    kotlin("jvm") version "1.9.21"
    kotlin("plugin.serialization") version "1.9.23"
    id("com.diffplug.spotless") version "6.25.0"
}

group = "com.wolt"

version = "1.0-SNAPSHOT"

repositories { mavenCentral() }

dependencies {
    val ktorVersion = "2.3.9"
    api("io.ktor:ktor-server-core-jvm:${ktorVersion}")
    implementation("io.ktor:ktor-server-netty-jvm:${ktorVersion}")
    implementation("io.ktor:ktor-server-content-negotiation:${ktorVersion}")
    implementation("io.ktor:ktor-serialization-kotlinx-json:${ktorVersion}")

    implementation("io.github.oshai:kotlin-logging-jvm:5.1.0")

    testImplementation("io.ktor:ktor-server-status-pages:2.3.9")
    testImplementation("io.ktor:ktor-server-test-host:${ktorVersion}")

    testImplementation("io.mockk:mockk:1.13.11")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
}

tasks.test { useJUnitPlatform() }

kotlin { jvmToolchain(21) }

spotless {
    kotlin {
        ktfmt("0.47")
        ktlint()
    }
    kotlinGradle { ktfmt("0.47").kotlinlangStyle() }
}
