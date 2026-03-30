plugins {
    id("kas.standalone-app")
}

application {
    mainClass = "io.github.amichne.kast.standalone.StandaloneMainKt"
}

dependencies {
    implementation(project(":analysis-api"))
    implementation(project(":analysis-server"))
    implementation(libs.logback.classic)
    testImplementation(project(":shared-testing"))
}
