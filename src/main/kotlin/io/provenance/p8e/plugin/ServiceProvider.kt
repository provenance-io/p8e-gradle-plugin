package io.provenance.p8e.plugin

import io.p8e.spec.P8eContract
import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.WildcardFileFilter
import org.gradle.api.Project
import java.io.File

data class ProjectPaths(
    val sourcePath: String,
    val serviceProviderImplPath: String
)

// TODO classes should go at src/main/java instead so we don't need to depend on kotlin
object ServiceProvider {
    val interfaceContractPackage = "io.p8e.contracts"
    val interfaceProtoPackage = "io.p8e.proto"

    fun projectPaths(project: Project, _package: String): ProjectPaths {
        val sourcePath = (listOf(project.projectDir.path, "src", "main", "kotlin") + _package.split("."))
            .joinToString(separator = File.separator)
        val resourcePath = listOf(project.projectDir.path, "src", "main", "resources").joinToString(separator = File.separator)
        val serviceProviderPath = listOf(resourcePath, "META-INF").joinToString(separator = File.separator)
        val serviceProviderImplPath = listOf(serviceProviderPath, "services").joinToString(separator = File.separator)

        return ProjectPaths(
            sourcePath = sourcePath,
            serviceProviderImplPath = serviceProviderImplPath
        )
    }

    fun writeContractHash(
        project: Project,
        extension: P8eExtension,
        uid: String,
        contracts: Set<Class<out P8eContract>>,
        contractHash: String
    ) {
        val contractHashContent =
            """
package ${extension.contractHashPackage}

import io.p8e.contracts.ContractHash

class ContractHash$uid : ContractHash {

    private val classes = ${
        contracts.map { it.name.replace("\$", "\\$") }
            .map { "\"$it\" to true" }
            .joinToString(separator = ", ", prefix = "mapOf(", postfix = ")")
    }
    
    override fun getClasses(): Map<String, Boolean> {
        return classes
    }
    
    override fun getUuid(): String {
        return "$uid"
    }

    override fun getHash(): String {
        return "$contractHash"
    }
}
            """
        val contractHashServiceContent = "${extension.contractHashPackage}.ContractHash$uid"
        val projectPaths = projectPaths(project, extension.contractHashPackage)

        File(projectPaths.sourcePath).mkdirs()
        File(projectPaths.serviceProviderImplPath).mkdirs()

        listOf(projectPaths.sourcePath, "ContractHash$uid.kt")
            .joinToString(separator = File.separator)
            .let { File(it) }
            .also { it.delete() }
            .also { it.createNewFile() }
            .writeText(contractHashContent)

        listOf(projectPaths.serviceProviderImplPath, "$interfaceContractPackage.ContractHash")
            .joinToString(separator = File.separator)
            .let { File(it) }
            .also { it.delete() }
            .also { it.createNewFile() }
            .writeText(contractHashServiceContent)
    }

    fun writeProtoHash(
        project: Project,
        extension: P8eExtension,
        uid: String,
        protos: Set<Class<out com.google.protobuf.Message>>,
        protoHash: String
    ) {
        val protoHashContent =
            """
package ${extension.protoHashPackage}

import io.p8e.proto.ProtoHash

class ProtoHash$uid : ProtoHash {

    private val classes = ${
        protos.map { it.name.replace("\$", "\\$") }
            .map { "\"$it\" to true" }
            .joinToString(separator = ", ", prefix = "mapOf(", postfix = ")")
    }

    override fun getClasses(): Map<String, Boolean> {
        return classes
    }
    
    override fun getUuid(): String {
        return "$uid"
    }

    override fun getHash(): String {
        return "$protoHash"
    }
}
            """
        val protoHashServiceContent = "${extension.protoHashPackage}.ProtoHash$uid"
        val projectPaths = projectPaths(project, extension.protoHashPackage)

        File(projectPaths.sourcePath).mkdirs()
        File(projectPaths.serviceProviderImplPath).mkdirs()

        listOf(projectPaths.sourcePath, "ProtoHash$uid.kt")
            .joinToString(separator = File.separator)
            .let { File(it) }
            .also { it.delete() }
            .also { it.createNewFile() }
            .writeText(protoHashContent)

        listOf(projectPaths.serviceProviderImplPath, "$interfaceProtoPackage.ProtoHash")
            .joinToString(separator = File.separator)
            .let { File(it) }
            .also { it.delete() }
            .also { it.createNewFile() }
            .writeText(protoHashServiceContent)
    }

    fun cleanContracts(project: Project, extension: P8eExtension) {
        val projectPaths = projectPaths(project, extension.contractHashPackage)

        File(projectPaths.sourcePath).mkdirs()
        File(projectPaths.serviceProviderImplPath).mkdirs()

        FileUtils.listFiles(File(projectPaths.sourcePath), WildcardFileFilter("ContractHash*.kt"), WildcardFileFilter("."))
            .forEach { it.delete() }

        listOf(projectPaths.serviceProviderImplPath, "$interfaceContractPackage.ContractHash")
            .joinToString(separator = File.separator)
            .let { File(it) }
            .also { it.delete() }
    }

    fun cleanProtos(project: Project, extension: P8eExtension) {
        val projectPaths = projectPaths(project, extension.protoHashPackage)

        File(projectPaths.sourcePath).mkdirs()
        File(projectPaths.serviceProviderImplPath).mkdirs()

        FileUtils.listFiles(File(projectPaths.sourcePath), WildcardFileFilter("ProtoHash*.kt"), WildcardFileFilter("."))
            .forEach { it.delete() }

        listOf(projectPaths.serviceProviderImplPath, "$interfaceProtoPackage.ProtoHash")
            .joinToString(separator = File.separator)
            .let { File(it) }
            .also { it.delete() }
    }
}
