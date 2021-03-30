package io.provenance.p8e.plugin

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

fun findContracts(classLoader: ClassLoader): Set<Class<out io.p8e.spec.P8eContract>> =
    findClasses(io.p8e.spec.P8eContract::class.java, classLoader)

fun findProtos(classLoader: ClassLoader): Set<Class<out com.google.protobuf.Message>> =
    findClasses(com.google.protobuf.Message::class.java, classLoader)

fun<T> findClasses(clazz: Class<T>, classLoader: ClassLoader): Set<Class<out T>> =
    Reflections("io", "com", SubTypesScanner(false), classLoader)
        .getSubTypesOf(clazz)
