package io.provenance.p8e.plugin

import org.gradle.api.Project

internal class Cleaner(
    private val project: Project,
    val extension: P8eExtension
) {

    @Synchronized
    fun execute() {
        project.logger.info("Cleaning generated hash files")

        val contractProject = getProject(project, extension.contractProject)
        val protoProject = getProject(project, extension.protoProject)

        ServiceProvider.cleanContracts(contractProject, extension)
        ServiceProvider.cleanProtos(protoProject, extension)
    }
}
