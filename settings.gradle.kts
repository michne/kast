import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

plugins {
    id("org.jetbrains.intellij.platform.settings") version "2.13.1"
}

rootProject.name = "kast"

includeBuild("build-logic")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.gradle.org/gradle/libs-releases")
        intellijPlatform {
            defaultRepositories()
        }
    }
}

include(
    ":analysis-api",
    ":analysis-cli",
    ":analysis-common",
    ":analysis-server",
    ":backend-intellij",
    ":backend-standalone",
    ":shared-testing",
)
