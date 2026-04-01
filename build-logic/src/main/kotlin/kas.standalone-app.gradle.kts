import org.gradle.api.file.DuplicatesStrategy
import org.gradle.jvm.tasks.Jar

plugins {
    application
    id("kas.kotlin-library")
}

tasks.named<Jar>("jar") {
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
    isZip64 = true
}

val fatJar by tasks.registering(Jar::class) {
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
    isZip64 = true

    from(sourceSets.main.get().output)

    dependsOn(configurations.runtimeClasspath)
    from(
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) },
    )
}

val writeWrapperScript by tasks.registering {
    dependsOn(fatJar)

    val output = layout.buildDirectory.file("scripts/${project.name}")
    outputs.file(output)

    doLast {
        val script = output.get().asFile
        script.parentFile.mkdirs()
        val libsDir = layout.buildDirectory.asFile.get().toPath().resolve("libs").toAbsolutePath()
        script.writeText(
            $$"""
            |#!/usr/bin/env bash
            |set -euo pipefail
            |
            |exec java ${JAVA_OPTS:-} -jar "$${libsDir.resolve("analysis-cli-all.jar").toAbsolutePath()}" "$@"
            """.trimMargin(),
        )
        script.setExecutable(true)
    }
}

writeWrapperScript.configure {
    mustRunAfter(tasks.named("startScripts"))
}

tasks.matching {
    it.name in setOf("distZip", "distTar", "installDist")
}.configureEach {
    dependsOn(writeWrapperScript)
}
