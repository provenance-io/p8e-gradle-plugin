import org.gradle.api.tasks.testing.logging.TestLogEvent.*
import org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

group = "io.provenance.p8e.p8e-publish"
version = (project.property("version") as String?)
    ?.takeUnless { it.isBlank() || it == "unspecified" }
    ?: "1.0-SNAPSHOT"

plugins {
    id("com.gradle.plugin-publish") version "1.2.1"
    jacoco
    kotlin("jvm") version "1.9.10"
}

repositories {
    mavenLocal()
    maven {
        url = uri("https://s01.oss.sonatype.org/content/groups/staging/")
    }
    mavenCentral()
    maven { url = uri("https://javadoc.jitpack.io") }
    maven { url = uri("https://plugins.gradle.org/m2/") }
}

val integrationTest: SourceSet by sourceSets.creating {
    compileClasspath += files(sourceSets["main"].output, configurations.testRuntimeClasspath)
    runtimeClasspath += output + compileClasspath
}

configurations {
    configurations["integrationTestImplementation"].also { intTestImplementation ->
        intTestImplementation.extendsFrom(configurations["testImplementation"])
    }
    configurations["integrationTestRuntimeOnly"].also { intTestRuntimeOnly ->
        intTestRuntimeOnly.extendsFrom(configurations["testRuntimeOnly"])
    }
}

dependencies {
    listOf(
        libs.bundles.kotlinLibs,
        libs.bundles.provenance,
        libs.bundles.grpc,

        libs.reflections,
        libs.commons,
        libs.protobuf,
        libs.bouncycastle,

        // third party plugins that this plugin will apply
        libs.shadow,

        // added for copied StdSignature functionality
        libs.bundles.kethereum,
        libs.bundles.jackson,
    ).forEach(::implementation)

    listOf(
        libs.bundles.kotest
    ).forEach(::testImplementation)

    configurations["integrationTestImplementation"](libs.kotest.runner4)
}

kotlin {
    jvmToolchain(17)
}


tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget = "17"
    }
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
    toolVersion = "0.8.11"
}

tasks.jacocoTestReport {
    reports {
        xml.required.set(true)
        csv.required.set(false)
    }
}

gradlePlugin {
    testSourceSets(integrationTest)
    website = "https://github.com/provenance-io/p8e-gradle-plugin"
    vcsUrl = "https://github.com/provenance-io/p8e-gradle-plugin.git"

    plugins {
        create("p8ePlugin") {
            id = "io.provenance.p8e.p8e-publish"
            displayName = "p8e gradle plugin"
            description = "Publishes P8eContract classes to Provenance P8e execution environments"
            implementationClass = "io.provenance.p8e.plugin.ContractPlugin"
            tags = listOf("provenance", "provenance.io", "p8e", "bootstrap", "publish")
        }
    }
}
