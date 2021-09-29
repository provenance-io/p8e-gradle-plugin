package io.provenance.p8e.plugin

import io.provenance.scope.contract.spec.P8eContract
import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.WildcardFileFilter
import org.gradle.api.Project
import java.io.File

data class ProjectPaths(
    val sourcePath: String,
    val serviceProviderImplPath: String,
    val language: Language,
)

enum class Language {
    JAVA,
    KOTLIN,
}

fun Language.extension() = when(this) {
    Language.KOTLIN -> "kt"
    Language.JAVA -> "java"
}

fun String.language() = when(this) {
    "kt" -> Language.KOTLIN
    "java" -> Language.JAVA
    else -> throw IllegalStateException("Could not convert $this to a language output. Accepts the following options [\"kt\", \"java\"]")
}

object ServiceProvider {
    val interfaceContractPackage = "io.provenance.scope.contract.contracts"
    val interfaceProtoPackage = "io.provenance.scope.contract.proto"

    fun projectPaths(project: Project, _package: String, language: Language): ProjectPaths {
        val kotlinSourcePath = (listOf(project.projectDir.path, "src", "main", "kotlin") + _package.split("."))
            .joinToString(separator = File.separator)
        val javaSourcePath = (listOf(project.projectDir.path, "src", "main", "java") + _package.split("."))
            .joinToString(separator = File.separator)
        val sourcePath = when (language) {
            Language.KOTLIN -> kotlinSourcePath
            Language.JAVA -> javaSourcePath
        }
        val resourcePath = listOf(project.projectDir.path, "src", "main", "resources").joinToString(separator = File.separator)
        val serviceProviderPath = listOf(resourcePath, "META-INF").joinToString(separator = File.separator)
        val serviceProviderImplPath = listOf(serviceProviderPath, "services").joinToString(separator = File.separator)

        return ProjectPaths(
            sourcePath = sourcePath,
            serviceProviderImplPath = serviceProviderImplPath,
            language = language,
        )
    }

    fun writeContractHash(
        project: Project,
        extension: P8eExtension,
        uid: String,
        contracts: Set<Class<out P8eContract>>,
        contractHash: String
    ) {
        val contractHashServiceContent = "${extension.contractHashPackage}.ContractHash$uid"
        val projectPaths = projectPaths(project, extension.contractHashPackage, extension.language.language())

        File(projectPaths.sourcePath).mkdirs()
        File(projectPaths.serviceProviderImplPath).mkdirs()

        listOf(projectPaths.sourcePath, "ContractHash$uid.${projectPaths.language.extension()}")
            .joinToString(separator = File.separator)
            .let { File(it) }
            .also { it.delete() }
            .also { it.createNewFile() }
            .writeText(getContractHashContent(projectPaths, extension, uid, contracts, contractHash))

        listOf(projectPaths.serviceProviderImplPath, "$interfaceContractPackage.ContractHash")
            .joinToString(separator = File.separator)
            .let { File(it) }
            .also { it.delete() }
            .also { it.createNewFile() }
            .writeText(contractHashServiceContent)
    }

    fun getContractHashContent(
        projectPaths: ProjectPaths,
        extension: P8eExtension,
        uid: String,
        contracts: Set<Class<out P8eContract>>,
        contractHash: String,
    ) = when (projectPaths.language) {
        Language.KOTLIN -> """
package ${extension.contractHashPackage}

import io.provenance.scope.contract.contracts.ContractHash

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
        Language.JAVA -> """
package ${extension.contractHashPackage};

import io.provenance.scope.contract.contracts.ContractHash;

import java.util.Map;
import java.util.HashMap;

public class ContractHash$uid implements ContractHash {

    private final Map<String, Boolean> classes = new HashMap<String, Boolean>() {{
        ${
            contracts.map { it.name }
                .map { "put(\"$it\", true);" }
                .joinToString(separator = "\n")
        }
    }};
    
    @Override
    public Map<String, Boolean> getClasses() {
        return classes;
    }
    
    @Override
    public String getUuid() {
        return "$uid";
    }

    @Override
    public String getHash() {
        return "$contractHash";
    }
}
        """
    }

    fun writeProtoHash(
        project: Project,
        extension: P8eExtension,
        uid: String,
        protos: Set<Class<out com.google.protobuf.Message>>,
        protoHash: String
    ) {
        val protoHashServiceContent = "${extension.protoHashPackage}.ProtoHash$uid"
        val projectPaths = projectPaths(project, extension.protoHashPackage, extension.language.language())

        File(projectPaths.sourcePath).mkdirs()
        File(projectPaths.serviceProviderImplPath).mkdirs()

        listOf(projectPaths.sourcePath, "ProtoHash$uid.${projectPaths.language.extension()}")
            .joinToString(separator = File.separator)
            .let { File(it) }
            .also { it.delete() }
            .also { it.createNewFile() }
            .writeText(getProtoHashContent(projectPaths, extension, uid, protos, protoHash))

        listOf(projectPaths.serviceProviderImplPath, "$interfaceProtoPackage.ProtoHash")
            .joinToString(separator = File.separator)
            .let { File(it) }
            .also { it.delete() }
            .also { it.createNewFile() }
            .writeText(protoHashServiceContent)
    }

    fun getProtoHashContent(
        projectPaths: ProjectPaths,
        extension: P8eExtension,
        uid: String,
        protos: Set<Class<out com.google.protobuf.Message>>,
        protoHash: String,
    ) = when (projectPaths.language) {
        Language.KOTLIN -> """
package ${extension.protoHashPackage}

import io.provenance.scope.contract.proto.ProtoHash

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
        Language.JAVA -> """
package ${extension.protoHashPackage};

import java.util.Map;
import java.util.HashMap;

import io.provenance.scope.contract.proto.ProtoHash;

public class ProtoHash$uid implements ProtoHash {

    private final Map<String, Boolean> classes = new HashMap<String, Boolean>() {{
        ${
            protos.map { it.name }
                .map { "put(\"$it\", true);" }
                .joinToString(separator = "\n")
        }
    }};

    @Override
    public Map<String, Boolean> getClasses() {
        return classes;
    }
    
    @Override
    public String getUuid() {
        return "$uid";
    }

    @Override
    public String getHash() {
        return "$protoHash";
    }
}
        """
    }

    fun cleanContracts(project: Project, extension: P8eExtension) {
        val projectPaths = projectPaths(project, extension.contractHashPackage, extension.language.language())

        File(projectPaths.sourcePath).mkdirs()
        File(projectPaths.serviceProviderImplPath).mkdirs()

        FileUtils.listFiles(File(projectPaths.sourcePath), WildcardFileFilter("ContractHash*.${projectPaths.language.extension()}"), WildcardFileFilter("."))
            .forEach { it.delete() }

        listOf(projectPaths.serviceProviderImplPath, "$interfaceContractPackage.ContractHash")
            .joinToString(separator = File.separator)
            .let { File(it) }
            .also { it.delete() }
    }

    fun cleanProtos(project: Project, extension: P8eExtension) {
        val projectPaths = projectPaths(project, extension.protoHashPackage, extension.language.language())

        File(projectPaths.sourcePath).mkdirs()
        File(projectPaths.serviceProviderImplPath).mkdirs()

        FileUtils.listFiles(File(projectPaths.sourcePath), WildcardFileFilter("ProtoHash*.${projectPaths.language.extension()}"), WildcardFileFilter("."))
            .forEach { it.delete() }

        listOf(projectPaths.serviceProviderImplPath, "$interfaceProtoPackage.ProtoHash")
            .joinToString(separator = File.separator)
            .let { File(it) }
            .also { it.delete() }
    }
}
