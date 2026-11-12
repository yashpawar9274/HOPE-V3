plugins {
    kotlin("jvm") version "2.2.21"
    application
}

group = "com.jarvis.hope"
version = "1.0.0"

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.jarvis.hope.agent.MainKt")
}

dependencies {
    implementation("com.google.adk:google-adk-kotlin-core:1.5.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}
