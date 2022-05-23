import org.gradle.api.tasks.testing.logging.TestLogEvent.*
import org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

group = "io.provenance.p8e.p8e-publish"
version = (project.property("version") as String?)
    ?.takeUnless { it.isBlank() || it == "unspecified" }
    ?: "1.0-SNAPSHOT"

plugins {
    id("com.gradle.plugin-publish") version "0.13.0"
    `jacoco`
    `java-gradle-plugin`
    kotlin("jvm") version "1.5.21"
    `maven-publish`
}

repositories {
    mavenLocal()
    mavenCentral()
    maven { url = uri("https://javadoc.jitpack.io") }
    maven { url = uri("https://plugins.gradle.org/m2/") }
}

val integrationTest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets["main"].output + configurations.testRuntimeClasspath
    runtimeClasspath += output + compileClasspath
}

configurations {
    "integrationTestImplementation" { extendsFrom(configurations["testImplementation"]) }
    "integrationTestRuntimeOnly" { extendsFrom(configurations["testRuntimeOnly"]) }
}

dependencies {
    implementation(kotlin("stdlib", "1.5.21"))
    implementation(kotlin("reflect", "1.5.21"))

    implementation("org.reflections:reflections:0.9.10")

    implementation("io.provenance.scope:sdk:0.6.0-rc2")
    implementation("io.provenance.scope:util:0.6.0-rc2")
    implementation("io.provenance.protobuf:pb-proto-java:1.5.0")

    implementation("io.grpc:grpc-protobuf:1.39.0")
    implementation("io.grpc", "grpc-stub", "1.39.0")

    implementation("commons-io:commons-io:2.8.0")
    implementation("com.google.protobuf:protobuf-java:3.12.0")
    implementation("org.bouncycastle:bcprov-jdk15on:1.69")

    runtimeOnly("io.grpc", "grpc-netty-shaded", "1.39.0")

    // third party plugins that this plugin will apply
    implementation("com.github.jengelman.gradle.plugins:shadow:6.1.0")

    // added for copied StdSignature functionality
    implementation("com.github.komputing.kethereum:crypto:0.83.4")
    implementation("com.github.komputing.kethereum:crypto_api:0.83.4")
    implementation("com.github.komputing.kethereum:crypto_impl_bouncycastle:0.83.4")
    implementation("com.fasterxml.jackson.core:jackson-core:2.12.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.12.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.12.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.12.2")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.12.2")

    testImplementation("io.kotest:kotest-runner-junit5:5.2.+")
    testImplementation("io.kotest:kotest-assertions-core:5.2.+")
    testImplementation("io.kotest:kotest-property:5.2.+")
    "integrationTestImplementation"("io.kotest:kotest-runner-junit5:4.4.+")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

tasks.withType<Test> {
    useJUnitPlatform()

    testLogging.showStandardStreams = true

    testLogging {
        events = setOf(PASSED, FAILED, SKIPPED, STANDARD_ERROR)
        exceptionFormat = FULL
    }
}

tasks.register<Test>("integrationTest") {
    description = "Run integration tests."
    group = "verification"

    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath

    useJUnitPlatform()

    testLogging.showStandardStreams = true

    testLogging {
        events = setOf(PASSED, FAILED, SKIPPED, STANDARD_ERROR)
        exceptionFormat = FULL
    }

    tasks.withType<Test>()
}

jacoco {
    toolVersion = "0.8.7"
}

tasks.jacocoTestReport {
    reports {
        xml.isEnabled = true
        csv.isEnabled = false
    }
}

pluginBundle {
    website = "https://github.com/provenance-io/p8e-gradle-plugin"
    vcsUrl = "https://github.com/provenance-io/p8e-gradle-plugin.git"
    tags = listOf("provenance", "provenance.io", "p8e", "bootstrap", "publish")
}

gradlePlugin {
    testSourceSets(integrationTest)

    plugins {
        create("p8ePlugin") {
            id = "io.provenance.p8e.p8e-publish"
            displayName = "p8e gradle plugin"
            description = "Publishes P8eContract classes to Provenance P8e execution environments"
            implementationClass = "io.provenance.p8e.plugin.ContractPlugin"
        }
    }
}
