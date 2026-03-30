plugins {
    id("kas.ktor-service")
}

dependencies {
    api(project(":analysis-api"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    testImplementation(project(":shared-testing"))
}
