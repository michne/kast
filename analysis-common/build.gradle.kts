import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.ConfigurableFileTree
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// analysis-common provides shared PSI and K2 Analysis API utilities used by both
// backend-intellij and backend-standalone. PSI and K2 Analysis API types are
// provided at runtime by each backend; this module compiles against them as compileOnly.
plugins {
    id("kas.kotlin-library")
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
private val intellijIdeaVersion = catalog.findVersion("intellij-idea").get().requiredVersion

// analysis-common compiles against the IntelliJ + Kotlin plugin classes that the
// backend-intellij module resolves. CI runs with a cold Gradle home, so wire the
// compile path through backend-intellij's extraction task instead of assuming a
// pre-warmed transform cache.
private fun locateIdeaHome(): File? = fileTree(gradle.gradleUserHomeDir.resolve("caches")) {
    include("**/transformed/idea-$intellijIdeaVersion-*/plugins/Kotlin/lib/kotlin-plugin.jar")
}.files.firstOrNull()?.parentFile?.parentFile?.parentFile?.parentFile

private fun requireIdeaHome(): File = locateIdeaHome()
    ?: error(
        "IntelliJ IDEA $intellijIdeaVersion distribution not found in the Gradle cache. " +
            "Run './gradlew :backend-intellij:initializeIntellijPlatformPlugin' once to populate it.",
    )

private fun ideaJarFiles(
    relativePath: String,
    configure: ConfigurableFileTree.() -> Unit,
) = files(
    providers.provider {
        fileTree(requireIdeaHome().resolve(relativePath), configure).files
    },
)

tasks.withType<KotlinCompile>().configureEach {
    dependsOn(":backend-intellij:initializeIntellijPlatformPlugin")
}

dependencies {
    api(project(":analysis-api"))

    // Full IJ lib directory: provides PsiElement, PsiFile, TextRange, etc.
    compileOnly(ideaJarFiles("lib") {
        include("**/*.jar")
        exclude("testFramework.jar")
        exclude("testFramework-k1.jar")
    })
    // Kotlin plugin: provides KtFile, KtNamedDeclaration, K2 Analysis API types, etc.
    compileOnly(ideaJarFiles("plugins/Kotlin/lib") {
        include("**/*.jar")
        exclude("jps/**")
    })
    // Java plugin: provides PsiMethod, PsiField, PsiClass (for Java-aware symbol mapping).
    compileOnly(ideaJarFiles("plugins/java/lib") { include("**/*.jar") })
}
