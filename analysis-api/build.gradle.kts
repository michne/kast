plugins {
    id("kas.kotlin-library")
}

dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
}
