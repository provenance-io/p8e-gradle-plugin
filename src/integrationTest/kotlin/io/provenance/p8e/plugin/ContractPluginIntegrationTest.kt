package io.provenance.p8e.plugin

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.WordSpec
import io.kotest.core.test.TestCaseOrder
import io.kotest.matchers.Matcher
import io.kotest.matchers.MatcherResult
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.file.shouldHaveFileSize
import io.kotest.matchers.file.shouldNotHaveFileSize
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNot
import io.kotest.matchers.string.shouldContain
import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.WildcardFileFilter
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.UnexpectedBuildFailure
import java.io.File
import org.gradle.testkit.runner.BuildTask
import org.gradle.testkit.runner.TaskOutcome

class ContractPluginIntegrationTest : WordSpec() {

    override fun testCaseOrder() = TestCaseOrder.Sequential
    override fun isolationMode() = IsolationMode.SingleInstance

    private fun haveOutcome(outcome: TaskOutcome) = object: Matcher<BuildTask?> {
        override fun test(value: BuildTask?) = MatcherResult(
            value != null && value.outcome.equals(outcome),
            { "build had outcome ${value?.outcome} but expected outcome: $outcome" },
            { "build should not have outcome: $outcome" },
        )
    }

    private fun run(projectDir: File, task: String) = try {
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(task, "--info", "--stacktrace", "--no-build-cache")
            .build()
    } catch (e: UnexpectedBuildFailure) {
        e.buildResult
    }

    fun p8eClean(projectDir: File) = run(projectDir, "p8eClean")
    fun p8eJar(projectDir: File) = run(projectDir, "p8eJar")
    fun p8eBootstrap(projectDir: File) = run(projectDir, "p8eBootstrap")

    val contractUberJar = "example-kotlin/contracts/build/libs/contracts-1.0-SNAPSHOT-all.jar"
    val contractJar = "example-kotlin/contracts/build/libs/contracts-1.0-SNAPSHOT.jar"
    val contractPath = "example-kotlin/contracts/src/main/kotlin/io/p8e/contracts/examplekotlin/"
    val contractServiceFile = "example-kotlin/contracts/src/main/resources/META-INF/services/io.provenance.scope.contract.contracts.ContractHash"

    val protoJar = "example-kotlin/protos/build/libs/protos-1.0-SNAPSHOT.jar"
    val protoPath = "example-kotlin/protos/src/main/kotlin/io/p8e/proto/examplekotlin/"
    val protoServiceFile = "example-kotlin/protos/src/main/resources/META-INF/services/io.provenance.scope.contract.proto.ProtoHash"

    init {
        "A configured DSL buildscript" should {

            "Start with a clean base" {
                val projectDir = File("example-kotlin")

                val cleanResult = run(projectDir, "clean")
                val p8eCleanResult = p8eClean(projectDir)

                cleanResult.task(":protos:clean") shouldNot haveOutcome(TaskOutcome.FAILED) // UP-TO-DATE or SUCCESS are valid outcomes
                cleanResult.task(":contracts:clean") shouldNot haveOutcome(TaskOutcome.FAILED)
                p8eCleanResult.task(":p8eClean") should haveOutcome(TaskOutcome.SUCCESS)

                FileUtils.listFiles(File(contractPath), WildcardFileFilter.builder().setWildcards("ContractHash*.kt").get(), WildcardFileFilter.builder().setWildcards(".").get())
                    .shouldBeEmpty()
                FileUtils.listFiles(File(protoPath), WildcardFileFilter.builder().setWildcards("ProtoHash*.kt").get(), WildcardFileFilter.builder().setWildcards(".").get())
                    .shouldBeEmpty()

                File(contractServiceFile).exists().shouldBeFalse()
                File(protoServiceFile).exists().shouldBeFalse()

                File(contractUberJar).exists().shouldBeFalse()

                File(contractJar).exists().shouldBeFalse()
                File(protoJar).exists().shouldBeFalse()
            }

            "Lead to a successful bootstrap with saved specs" {
                val projectDir = File("example-kotlin")
                val result = p8eBootstrap(projectDir)

                result.also {
                    println("hi $it")
                }.task(":p8eBootstrap") should haveOutcome(TaskOutcome.SUCCESS)

                FileUtils.listFiles(File(contractPath), WildcardFileFilter.builder().setWildcards("ContractHash*.kt").get(), WildcardFileFilter.builder().setWildcards(".").get())
                    .shouldHaveSize(1)
                FileUtils.listFiles(File(protoPath), WildcardFileFilter.builder().setWildcards("ProtoHash*.kt").get(), WildcardFileFilter.builder().setWildcards(".").get())
                    .shouldHaveSize(1)

                File(contractServiceFile).exists().shouldBeTrue()
                File(protoServiceFile).exists().shouldBeTrue()

                File(contractUberJar).exists().shouldBeTrue()

                result.output.shouldContain("Saved jar")
                result.output.shouldContain("Saved contract specification io.p8e.contracts.examplekotlin.HelloWorldContract")
            }

            "Lead to a successful publish" {
                val projectDir = File("example-kotlin")

                File(contractUberJar).exists().shouldBeTrue()
                val uberJarSize = File(contractUberJar).length()

                File(contractJar).exists().shouldBeFalse()

                File(protoJar).exists().shouldBeTrue()
                val protoJarSize = File(protoJar).length()

                val result = p8eJar(projectDir)

                result.task(":p8eJar") should haveOutcome(TaskOutcome.SUCCESS)

                File(contractUberJar).shouldHaveFileSize(uberJarSize)

                File(contractJar).exists().shouldBeTrue()
                File(protoJar).shouldNotHaveFileSize(protoJarSize)
            }

            "Lead to a successful clean" {
                val projectDir = File("example-kotlin")

                FileUtils.listFiles(File(contractPath), WildcardFileFilter.builder().setWildcards("ContractHash*.kt").get(), WildcardFileFilter.builder().setWildcards(".").get())
                    .shouldHaveSize(1)
                FileUtils.listFiles(File(protoPath), WildcardFileFilter.builder().setWildcards("ProtoHash*.kt").get(), WildcardFileFilter.builder().setWildcards(".").get())
                    .shouldHaveSize(1)

                File(contractServiceFile).exists().shouldBeTrue()
                File(protoServiceFile).exists().shouldBeTrue()

                val result = p8eClean(projectDir)

                result.task(":p8eClean") should haveOutcome(TaskOutcome.SUCCESS)

                FileUtils.listFiles(File(contractPath), WildcardFileFilter.builder().setWildcards("ContractHash*.kt").get(), WildcardFileFilter.builder().setWildcards(".").get())
                    .shouldBeEmpty()
                FileUtils.listFiles(File(protoPath), WildcardFileFilter.builder().setWildcards("ProtoHash*.kt").get(), WildcardFileFilter.builder().setWildcards(".").get())
                    .shouldBeEmpty()

                File(contractServiceFile).exists().shouldBeFalse()
                File(protoServiceFile).exists().shouldBeFalse()
            }
        }
    }
}
