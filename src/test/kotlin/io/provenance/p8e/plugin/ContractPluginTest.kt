package io.provenance.p8e.plugin

import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder

class ContractPluginTest : WordSpec({

    fun project(): ProjectInternal {
        val project = ProjectBuilder.builder()
            .withName("test")
            .build()
            .also { it.pluginManager.apply(ContractPlugin::class.java) } as ProjectInternal
        ProjectBuilder.builder()
            .withName("contract")
            .withParent(project)
            .build()
            .also {
                it.pluginManager.apply("java")
                it.tasks.create("shadowJar")
            }
        ProjectBuilder.builder()
            .withName("proto")
            .withParent(project)
            .build()
            .also { it.pluginManager.apply("java") }

        project.evaluate()

        return project
    }

    "Using the plugin ID" should {
        "Apply the plugin" {
            val project = ProjectBuilder.builder().build()
            project.pluginManager.apply("io.provenance.p8e.p8e-publish")

            project.plugins.getPlugin(ContractPlugin::class.java) shouldNotBe null
        }
    }

    "Applying the plugin" should {
        "Register the 'contract' extension" {
            val project = ProjectBuilder.builder().build()
            project.pluginManager.apply(ContractPlugin::class.java)

            project.p8eConfiguration() shouldNotBe null
        }
    }

    "Clean task" should {
        "Exist" {
            val project = project()

            project.tasks.withType(CleanTask::class.java).size shouldBe 1
        }
    }

    "Bootstrap task" should {
        "Exist" {
            val project = project()

            project.tasks.withType(BootstrapTask::class.java).size shouldBe 1
        }

        "Depend on p8e clean" {
            val project = project()
            val cleanTask = project.tasks.getByName("p8eClean")

            project.tasks.getByName("p8eBootstrap").dependsOn.contains(cleanTask) shouldBe true
        }
    }

    "Jar task" should {
        "Exist" {
            val project = project()

            project.tasks.getByName("p8eJar").enabled shouldBe true
        }
    }

    "Check task" should {
        "Exist" {
            val project = project()

            project.tasks.getByName("p8eCheck").enabled shouldBe true
        }
    }

    "Maven plugin" should {
        "Exist" {
            val project = project()

            project.plugins.hasPlugin("maven-publish") shouldBe true
        }

        "Depend on p8e jar" {
            val project = project()
            val jarTask = project.tasks.getByName("p8eJar")

            project.tasks.getByName("publish").dependsOn.contains(jarTask) shouldBe true
            project.tasks.getByName("publishToMavenLocal").dependsOn.contains(jarTask) shouldBe true
        }
    }
})
