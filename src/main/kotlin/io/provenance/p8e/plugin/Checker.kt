package io.provenance.p8e.plugin

import io.p8e.annotations.Function
import io.p8e.proto.Common.ProvenanceReference
import io.p8e.spec.ContractSpecMapper
import org.gradle.api.Project
import java.net.URLClassLoader
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.functions

class ContractDefinitionException(msg: String) : Exception(msg)

// TODO add tests for contract validation - will need to check cases hit in dehydrate spec as well
internal class Checker(
    private val project: Project,
    val extension: P8eExtension
) {

    @Synchronized
    fun execute() {
        project.logger.info("Checking P8eContracts against ruleset")

        val contractProject = getProject(project, extension.contractProject)
        val contractJar = getJar(contractProject, "shadowJar")
        val contractClassLoader = URLClassLoader(arrayOf(contractJar.toURI().toURL()), javaClass.classLoader)

        findContracts(contractClassLoader).forEach { clazz ->
            project.logger.info("Checking ${clazz.name}")

            val spec = ContractSpecMapper.dehydrateSpec(
                clazz = clazz.kotlin,
                contractRef = ProvenanceReference.getDefaultInstance(),
                protoRef = ProvenanceReference.getDefaultInstance()
            )
            val participants =  spec.partiesInvolvedList.toSet()

            if (participants.isEmpty()) {
                throw ContractDefinitionException("${clazz.name} must have at least one participant.")
            }

            clazz.kotlin.functions
                .map { Pair(it, it.findAnnotation<Function>()) }
                .filter { it.second != null }
                .forEach { (func, annotation) ->
                    if (!participants.contains(annotation!!.invokedBy)) {
                        throw ContractDefinitionException("Function invoker for $func is not a participant.")
                    }
                }
        }
    }
}
