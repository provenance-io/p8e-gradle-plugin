package io.provenance.p8e.plugin

import io.provenance.scope.contract.annotations.Function
import io.provenance.scope.contract.annotations.ScopeSpecification
import io.provenance.scope.contract.annotations.ScopeSpecificationDefinition
import io.provenance.scope.contract.proto.Commons.ProvenanceReference
import io.provenance.scope.sdk.ContractSpecMapper
import org.gradle.api.Project
import java.net.URLClassLoader
import java.util.*
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.functions

class ContractDefinitionException(msg: String) : Exception(msg)
class ScopeDefinitionException(msg: String) : Exception(msg)

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

        val scopeDefinitions = findScopes(contractClassLoader).map { clazz ->
            project.logger.info("Found ${clazz.name}")

            val definitions = clazz.annotations
                .filter { it is ScopeSpecificationDefinition }
                .map { it as ScopeSpecificationDefinition }

            if (definitions.size != 1) {
                throw ScopeDefinitionException("${clazz.name} - A P8eScopeSpecification subclass must have exactly one ScopeSpecificationDefinition annotation.")
            }

            definitions.first()
        }
        val scopeDefinitionsNameSet = scopeDefinitions.map { it.name }.toSet()

        if (scopeDefinitions.map { it.name }.size != scopeDefinitionsNameSet.size) {
            throw ScopeDefinitionException("ScopeSpecificationDefinition names must be unique")
        }

        if (scopeDefinitions.map { it.uuid }.size != scopeDefinitionsNameSet.size) {
            throw ScopeDefinitionException("ScopeSpecificationDefinition UUIDs must be unique")
        }

        scopeDefinitions.forEach { scopeDefinition ->
            try {
                UUID.fromString(scopeDefinition.uuid)
            } catch (e: Exception) {
                throw ScopeDefinitionException("ScopeSpecificationDefinition ${scopeDefinition.name} does not have a valid UUID - ${scopeDefinition.uuid}")
            }
        }

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

            val scopeSpecification = clazz.annotations
                .filter { it is ScopeSpecification }
                .map { it as ScopeSpecification }
                .firstOrNull()
                ?: throw ContractDefinitionException("${clazz.name} must contain a ScopeSpecification")

            scopeSpecification.names.forEach {
                if (!scopeDefinitionsNameSet.contains(it)) {
                    throw ScopeDefinitionException("${clazz.name} - ScopeSpecification of $scopeSpecification is not defined as a ScopeSpecificationDefinition")
                }
            }

            clazz.kotlin.functions
                .map { Pair(it, it.findAnnotation<Function>()) }
                .filter { it.second != null }
                .forEach { (func, annotation) ->
                    if (!participants.contains(annotation!!.invokedBy)) {
                        throw ContractDefinitionException("${clazz.name} - Function invoker for $func is not a participant.")
                    }
                }
        }
    }
}
