rootProject.name = "build-logic"

dependencyResolutionManagement {
    repositories {
        maven("https://artifactory.aexp.com/iq-maven-central-proxy/")
        exclusiveContent {
            forRepository { maven("https://artifactory.aexp.com/iq-gradle/") }
            filter {
                includeGroupByRegex("(org|com)\\.gradle\\..*")
                includeGroup("com.gradle")
                includeModule("org.sonarsource.scanner.gradle", "sonarqube-gradle-plugin")
            }
        }

        maven("https://repo.gradle.org/gradle/libs-releases")
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
        maven("https://www.jetbrains.com/intellij-repository/releases")

    }

    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
