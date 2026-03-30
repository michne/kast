plugins {
    id("kas.standalone-app")
}

val ideaHome = fileTree(gradle.gradleUserHomeDir.resolve("caches/9.0.0/transforms")) {
    include("**/transformed/idea-*/plugins/Kotlin/lib/kotlin-plugin.jar")
}.files.firstOrNull()?.parentFile?.parentFile?.parentFile?.parentFile
    ?: error(
        "Unable to locate the IntelliJ IDEA distribution in the Gradle cache. " +
            "Build :backend-intellij once to populate it.",
    )

val ideaLibs = fileTree(ideaHome.resolve("lib")) {
    include("**/*.jar")
}
val kotlinPluginLibs = fileTree(ideaHome.resolve("plugins/Kotlin/lib")) {
    include("**/*.jar")
}
val javaPluginLibs = fileTree(ideaHome.resolve("plugins/java/lib")) {
    include("**/*.jar")
}

application {
    mainClass = "io.github.amichne.kast.standalone.StandaloneMainKt"
}

dependencies {
    implementation(project(":analysis-api"))
    implementation(project(":analysis-server"))
    implementation(libs.analysis.api.standalone) {
        isTransitive = false
    }
    implementation(ideaLibs)
    implementation(kotlinPluginLibs)
    implementation(javaPluginLibs)
    implementation(libs.coroutines.core)
    implementation(libs.logback.classic)
    testImplementation(project(":shared-testing"))
}
