package io.provenance.p8e.plugin

import io.provenance.scope.contract.spec.P8eContract
import io.provenance.scope.contract.spec.P8eScopeSpecification
import org.gradle.api.Project
import org.gradle.jvm.tasks.Jar
import org.reflections.Reflections
import org.reflections.scanners.SubTypesScanner
import java.io.File
import java.util.jar.JarFile

fun getProject(project: Project, name: String): Project {
    return project.subprojects.firstOrNull { it.name == name }
        ?: throw IllegalStateException("Subproject $name could not be found")
}

fun getJar(project: Project, taskName: String = "jar"): File {
    return (project.tasks.getByName(taskName) as Jar)
        .archiveFile
        .orNull
        ?.asFile
        ?.also { JarFile(it) }
        ?: throw IllegalStateException("task :$taskName in ${project.name} could not be found")
}

fun findScopes(classLoader: ClassLoader, includePackages: Array<String>): Set<Class<out P8eScopeSpecification>> =
    findClasses(P8eScopeSpecification::class.java, classLoader, includePackages)

fun findContracts(classLoader: ClassLoader, includePackages: Array<String>): Set<Class<out P8eContract>> =
    findClasses(P8eContract::class.java, classLoader, includePackages)

fun findProtos(classLoader: ClassLoader, includePackages: Array<String>): Set<Class<out com.google.protobuf.Message>> =
    findClasses(com.google.protobuf.Message::class.java, classLoader, includePackages)

fun<T> findClasses(clazz: Class<T>, classLoader: ClassLoader, includePackages: Array<String>): Set<Class<out T>> =
    Reflections(includePackages, SubTypesScanner(false), classLoader)
        .getSubTypesOf(clazz)
