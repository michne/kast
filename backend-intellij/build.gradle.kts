import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("kas.intellij-plugin")
}

repositories {
    mavenCentral()
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
    maven("https://www.jetbrains.com/intellij-repository/releases")
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(project(":analysis-api"))
    implementation(project(":analysis-server"))
    testImplementation(project(":shared-testing"))

    intellijPlatform {
        intellijIdea("2025.3")
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
        testFramework(TestFrameworkType.Platform)
    }
}

tasks.named("buildSearchableOptions") {
    enabled = false
}
