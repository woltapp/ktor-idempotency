plugins {
    kotlin("jvm") version "2.0.0"
    kotlin("plugin.serialization") version "1.9.23"
    id("com.diffplug.spotless") version "6.25.0"
    id("maven-publish")
    id("signing")
}

group = "com.wolt"

repositories { mavenCentral() }

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    val ktorVersion = "2.3.11"
    api("io.ktor:ktor-server-core-jvm:${ktorVersion}")
    implementation("io.ktor:ktor-server-netty-jvm:${ktorVersion}")
    implementation("io.ktor:ktor-server-content-negotiation:${ktorVersion}")
    implementation("io.ktor:ktor-serialization-kotlinx-json:${ktorVersion}")

    implementation("io.github.oshai:kotlin-logging-jvm:6.0.9")

    testImplementation("io.ktor:ktor-server-status-pages:3.0.0")
    testImplementation("io.ktor:ktor-server-test-host:${ktorVersion}")

    testImplementation("io.mockk:mockk:1.13.13")
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

tasks {
    compileKotlin { kotlinOptions.jvmTarget = "11" }
    compileTestKotlin { kotlinOptions.jvmTarget = "11" }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["kotlin"])
            groupId = project.group.toString()
            artifactId = project.name
            version = project.version.toString()
            artifact(tasks.kotlinSourcesJar)
            artifact(tasks.named("javadocJar"))

            pom {
                name.set("Ktor Idempotency Plugin")
                description.set("A Ktor plugin for handling idempotency in HTTP requests")
                url.set("https://github.com/woltapp/ktor-idempotency")

                licenses {
                    license {
                        name.set("The MIT License")
                        url.set("https://github.com/woltapp/ktor-idempotency/blob/main/LICENSE")
                    }
                }
                developers {
                    developer {
                        id.set("muatik")
                        name.set("Mustafa Atik")
                        email.set("mustafa.atik@wolt.com")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/woltapp/ktor-idempotency.git")
                    developerConnection.set(
                        "scm:git:https://github.com/woltapp/ktor-idempotency.git"
                    )
                    url.set("https://github.com/woltapp/ktor-idempotency")
                }
            }
        }
    }

    repositories {
        maven {
            name = "sonatype"
            url = uri("https://oss.sonatype.org/service/local/staging/deploy/maven2/")
            credentials(PasswordCredentials::class)
        }
    }
}

val isReleaseVersion = !version.toString().endsWith("-SNAPSHOT")

signing {
    setRequired {
        // signing is required if this is a release version and the artifacts are to be published
        isReleaseVersion && gradle.taskGraph.allTasks.any { it is PublishToMavenRepository }
    }

    val signingKey: String? by project
    val signingPassword: String? by project
    useInMemoryPgpKeys(signingKey, signingPassword)
    sign(publishing.publications["mavenJava"])
}
