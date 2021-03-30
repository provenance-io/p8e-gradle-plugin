package io.provenance.p8e.plugin

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.WordSpec
import io.kotest.core.test.TestCaseOrder
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.file.shouldHaveFileSize
import io.kotest.matchers.file.shouldNotHaveFileSize
import io.kotest.matchers.should
import io.kotest.matchers.string.shouldContain
import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.WildcardFileFilter
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.testkit.runner.UnexpectedBuildFailure
import java.io.File

class ContractPluginIntegrationTest : WordSpec() {

    override fun testCaseOrder() = TestCaseOrder.Sequential
    override fun isolationMode() = IsolationMode.SingleInstance

    fun run(projectDir: File, task: String) = try {
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(task, "--info", "--stacktrace")
            .build()
    } catch (e: UnexpectedBuildFailure) {
        e.buildResult
    }

    fun p8eClean(projectDir: File) = run(projectDir, "p8eClean")
    fun p8eJar(projectDir: File) = run(projectDir, "p8eJar")
    fun p8eBootstrap(projectDir: File) = run(projectDir, "p8eBootstrap")

    val contractUberJar = "example/contracts/build/libs/contracts-1.0-SNAPSHOT-all.jar"
    val contractJar = "example/contracts/build/libs/contracts-1.0-SNAPSHOT.jar"
    val contractPath = "example/contracts/src/main/kotlin/io/p8e/contracts/example/"
    val contractServiceFile = "example/contracts/src/main/resources/META-INF/services/io.p8e.contracts.ContractHash"

    val protoJar = "example/protos/build/libs/protos-1.0-SNAPSHOT.jar"
    val protoPath = "example/protos/src/main/kotlin/io/p8e/proto/example/"
    val protoServiceFile = "example/protos/src/main/resources/META-INF/services/io.p8e.proto.ProtoHash"

    init {
        "A configured DSL buildscript" should {

            "Start with a clean base" {
                val projectDir = File("example")
                val cleanResult = run(projectDir, "clean")
                val p8eCleanResult = p8eClean(projectDir)

                cleanResult.task("clean") should {
                    it != null && it.outcome == TaskOutcome.SUCCESS
                }
                p8eCleanResult.task("p8eClean") should {
                    it != null && it.outcome == TaskOutcome.SUCCESS
                }

                FileUtils.listFiles(File(contractPath), WildcardFileFilter("ContractHash*.kt"), WildcardFileFilter("."))
                    .shouldBeEmpty()
                FileUtils.listFiles(File(protoPath), WildcardFileFilter("ProtoHash*.kt"), WildcardFileFilter("."))
                    .shouldBeEmpty()

                File(contractServiceFile).exists().shouldBeFalse()
                File(protoServiceFile).exists().shouldBeFalse()

                File(contractUberJar).exists().shouldBeFalse()

                File(contractJar).exists().shouldBeFalse()
                File(protoJar).exists().shouldBeFalse()
            }

            "Lead to a successful bootstrap with saved specs" {
                val projectDir = File("example")
                val result = p8eBootstrap(projectDir)

                result.task("p8eBootstrap") should {
                    it != null && it.outcome == TaskOutcome.SUCCESS
                }

                FileUtils.listFiles(File(contractPath), WildcardFileFilter("ContractHash*.kt"), WildcardFileFilter("."))
                    .shouldHaveSize(1)
                FileUtils.listFiles(File(protoPath), WildcardFileFilter("ProtoHash*.kt"), WildcardFileFilter("."))
                    .shouldHaveSize(1)

                File(contractServiceFile).exists().shouldBeTrue()
                File(protoServiceFile).exists().shouldBeTrue()

                File(contractUberJar).exists().shouldBeTrue()

                result.output.shouldContain("Saved jar")
                result.output.shouldContain("Saving 2 contract specifications")
            }

            "Lead to a successful publish" {
                val projectDir = File("example")

                File(contractUberJar).exists().shouldBeTrue()
                val uberJarSize = File(contractUberJar).length()

                File(contractJar).exists().shouldBeFalse()

                File(protoJar).exists().shouldBeTrue()
                val protoJarSize = File(protoJar).length()

                val result = p8eJar(projectDir)

                result.task("p8eJar") should {
                    it != null && it.outcome == TaskOutcome.SUCCESS
                }

                File(contractUberJar).shouldHaveFileSize(uberJarSize)

                File(contractJar).exists().shouldBeTrue()
                File(protoJar).shouldNotHaveFileSize(protoJarSize)
            }

            "Lead to a successful clean" {
                val projectDir = File("example")

                FileUtils.listFiles(File(contractPath), WildcardFileFilter("ContractHash*.kt"), WildcardFileFilter("."))
                    .shouldHaveSize(1)
                FileUtils.listFiles(File(protoPath), WildcardFileFilter("ProtoHash*.kt"), WildcardFileFilter("."))
                    .shouldHaveSize(1)

                File(contractServiceFile).exists().shouldBeTrue()
                File(protoServiceFile).exists().shouldBeTrue()

                val result = p8eClean(projectDir)

                result.task("p8eClean") should {
                    it != null && it.outcome == TaskOutcome.SUCCESS
                }

                FileUtils.listFiles(File(contractPath), WildcardFileFilter("ContractHash*.kt"), WildcardFileFilter("."))
                    .shouldBeEmpty()
                FileUtils.listFiles(File(protoPath), WildcardFileFilter("ProtoHash*.kt"), WildcardFileFilter("."))
                    .shouldBeEmpty()

                File(contractServiceFile).exists().shouldBeFalse()
                File(protoServiceFile).exists().shouldBeFalse()
            }
        }
    }
}
