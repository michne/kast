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

tasks.register("writeWrapperScript") {
    dependsOn(fatJar)

    val output = layout.buildDirectory.file("scripts/${project.name}")
    outputs.file(output)

    doLast {
        val script = output.get().asFile
        script.parentFile.mkdirs()
        script.writeText(
            $$"""
            |#!/usr/bin/env bash
            |set -euo pipefail
            |
            |SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
            |JAR="$SCRIPT_DIR/../libs/$${project.name}-$${project.version}-all.jar"
            |
            |exec java ${JAVA_OPTS:-} -jar "$JAR" "$@"
            """.trimMargin(),
        )
        script.setExecutable(true)
    }
}
