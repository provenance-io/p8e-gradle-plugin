package io.provenance.p8e.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

open class BootstrapTask: DefaultTask() {

    init {
        group = "P8e"
        description = "Bootstraps all scanned classes subclassing io.p8e.spec.P8eContract and com.google.protobuf.Message to one or more p8e locations."
    }

    @TaskAction
    fun doAction() {
        Bootstrapper(project, project.p8eConfiguration()).execute()
    }
}

open class CleanTask : DefaultTask() {

    init {
        group = "P8e"
        description = "Removes all generated hash files and java service provider files."
    }

    @TaskAction
    fun doAction() {
        Cleaner(project, project.p8eConfiguration()).execute()
    }
}
open class CheckTask : DefaultTask() {

    init {
        group = "P8e"
        description = "Checks contracts subclassing P8eContract against ruleset defined in the p8e-sdk."
    }

    @TaskAction
    fun doAction() {
        Checker(project, project.p8eConfiguration()).execute()
    }
}
