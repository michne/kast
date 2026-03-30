package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.FilePosition
import io.github.amichne.kast.api.ServerLimits
import io.github.amichne.kast.api.SymbolQuery
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class StandaloneWorkspaceDiscoveryTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `gradle workspace discovery derives source sets, classpath roots, and module graph`() {
        createGradleWorkspace(includeLocalTestJar = true)

        val layout = discoverStandaloneWorkspaceLayout(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "ignored",
        )

        val modulesByName = layout.sourceModules.associateBy(StandaloneSourceModuleSpec::name)
        assertEquals(setOf(":app[main]", ":app[test]", ":lib[main]"), modulesByName.keys)

        assertEquals(listOf(":lib[main]"), modulesByName.getValue(":app[main]").dependencyModuleNames)
        assertEquals(
            listOf(":app[main]", ":lib[main]"),
            modulesByName.getValue(":app[test]").dependencyModuleNames,
        )
        assertEquals(emptyList<String>(), modulesByName.getValue(":lib[main]").dependencyModuleNames)

        assertFalse(
            modulesByName.getValue(":app[main]").binaryRoots.any { path -> path.fileName.toString() == "test-support.jar" },
        )
        assertTrue(
            modulesByName.getValue(":app[test]").binaryRoots.any { path -> path.fileName.toString() == "test-support.jar" },
        )
    }

    @Test
    fun `explicit source roots keep manual session construction even in a gradle workspace`() {
        createGradleWorkspace(includeLocalTestJar = false)

        val layout = discoverStandaloneWorkspaceLayout(
            workspaceRoot = workspaceRoot,
            sourceRoots = listOf(workspaceRoot.resolve("app/src/main/kotlin")),
            classpathRoots = emptyList(),
            moduleName = "manual",
        )

        assertEquals(1, layout.sourceModules.size)
        assertEquals("manual", layout.sourceModules.single().name)
        assertEquals(
            listOf(normalizeStandalonePath(workspaceRoot.resolve("app/src/main/kotlin"))),
            layout.sourceModules.single().sourceRoots,
        )
    }

    @Test
    fun `standalone session resolves symbols across discovered gradle modules`() = runTest {
        createGradleWorkspace(includeLocalTestJar = false)
        val usageFile = workspaceRoot.resolve("app/src/main/kotlin/sample/Use.kt")
        val queryOffset = Files.readString(usageFile).indexOf("greet")
        val declarationFile = workspaceRoot.resolve("lib/src/main/kotlin/sample/Greeter.kt")
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "ignored",
        )
        try {
            val backend = StandaloneAnalysisBackend(
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(
                    maxResults = 100,
                    requestTimeoutMillis = 30_000,
                    maxConcurrentRequests = 4,
                ),
                session = session,
            )

            val result = backend.resolveSymbol(
                SymbolQuery(
                    position = FilePosition(
                        filePath = usageFile.toString(),
                        offset = queryOffset,
                    ),
                ),
            )

            assertEquals(normalizePath(declarationFile), result.symbol.location.filePath)
            assertTrue(session.sourceModules.map { module -> module.name }.contains(":lib[main]"))
            assertTrue(session.sourceModules.map { module -> module.name }.contains(":app[main]"))
        } finally {
            session.close()
        }
    }

    private fun createGradleWorkspace(includeLocalTestJar: Boolean) {
        writeFile(
            relativePath = "settings.gradle.kts",
            content = """
                rootProject.name = "workspace"
                include(":app", ":lib")
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "build.gradle.kts",
            content = buildString {
                appendLine("""plugins { idea }""")
                appendLine("""subprojects {""")
                appendLine("""    apply(plugin = "java-library")""")
                appendLine("""    repositories { mavenCentral() }""")
                appendLine("""    configure<org.gradle.api.tasks.SourceSetContainer> {""")
                appendLine("""        named("main") { java.srcDir("src/main/kotlin") }""")
                appendLine("""        named("test") { java.srcDir("src/test/kotlin") }""")
                appendLine("""    }""")
                appendLine("""}""")
                appendLine("""project(":app") {""")
                appendLine("""    dependencies {""")
                appendLine("""        add("implementation", project(":lib"))""")
                if (includeLocalTestJar) {
                    appendLine("""        add("testImplementation", files(rootProject.layout.projectDirectory.file("support/test-support.jar")))""")
                }
                appendLine("""    }""")
                appendLine("""}""")
            },
        )
        writeFile(
            relativePath = "lib/src/main/kotlin/sample/Greeter.kt",
            content = """
                package sample

                fun greet(name: String): String = "hi ${'$'}name"
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "app/src/main/kotlin/sample/Use.kt",
            content = """
                package sample

                fun use(): String = greet("kast")
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "app/src/test/kotlin/sample/UseTest.kt",
            content = """
                package sample

                class UseTest
            """.trimIndent() + "\n",
        )
        if (includeLocalTestJar) {
            createJar(workspaceRoot.resolve("support/test-support.jar"))
        }
    }

    private fun createJar(path: Path) {
        path.parent.createDirectories()
        Files.newOutputStream(path).use { output ->
            JarOutputStream(output).use { jar ->
                jar.putNextEntry(JarEntry("META-INF/"))
                jar.closeEntry()
            }
        }
    }

    private fun writeFile(
        relativePath: String,
        content: String,
    ): Path {
        val path = workspaceRoot.resolve(relativePath)
        path.parent?.createDirectories()
        path.writeText(content)
        return path
    }

    private fun normalizePath(path: Path): String {
        val absolutePath = path.toAbsolutePath().normalize()
        return runCatching { absolutePath.toRealPath().normalize().toString() }.getOrDefault(absolutePath.toString())
    }
}
