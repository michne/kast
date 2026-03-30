plugins {
    base
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION").get()
