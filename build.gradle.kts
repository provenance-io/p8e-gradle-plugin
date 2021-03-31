import org.gradle.api.tasks.testing.logging.TestLogEvent.*
import org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

group = "io.provenance.p8e.p8e-publish"
version = (project.property("version") as String?)
    ?.takeUnless { it.isBlank() || it == "unspecified" }
    ?: "1.0-SNAPSHOT"

plugins {
    id("com.bmuschko.nexus") version "2.3.1"
    id("com.gradle.plugin-publish") version "0.13.0"
    `jacoco`
    `java-gradle-plugin`
    kotlin("jvm") version "1.4.32"
    `maven-publish`
}

repositories {
    mavenLocal()
    mavenCentral()
    maven { url = uri("https://javadoc.jitpack.io") }
    maven { url = uri("https://plugins.gradle.org/m2/") }
    maven {
        name = "GitHubPackages"
        url = uri("https://maven.pkg.github.com/provenance-io/p8e")
        credentials {
            username = System.getenv("GITHUB_ACTOR")
            password = System.getenv("GITHUB_TOKEN")
        }
    }
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
    implementation(kotlin("stdlib", "1.4.32"))
    implementation(kotlin("reflect", "1.4.32"))

    implementation("org.reflections:reflections:0.9.10")

    implementation("io.provenance.p8e:p8e-sdk:0.6.0-scopespec-beta.1")
    // implementation("io.provenance.p8e:p8e-sdk:1.0-SNAPSHOT")

    implementation("commons-io:commons-io:2.8.0")
    implementation("com.google.protobuf:protobuf-java:3.12.0")
    implementation("org.bouncycastle:bcprov-jdk15on:1.68")

    // third party plugins that this plugin will apply
    implementation("com.github.jengelman.gradle.plugins:shadow:6.1.0")

    testImplementation("io.kotest:kotest-runner-junit5:4.4.+")
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
    toolVersion = "0.8.6"
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
