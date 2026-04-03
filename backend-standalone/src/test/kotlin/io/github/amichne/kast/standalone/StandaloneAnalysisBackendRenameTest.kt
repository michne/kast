package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.FilePosition
import io.github.amichne.kast.api.FileHashing
import io.github.amichne.kast.api.MutationCapability
import io.github.amichne.kast.api.ApplyEditsQuery
import io.github.amichne.kast.api.RenameQuery
import io.github.amichne.kast.api.ServerLimits
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

class StandaloneAnalysisBackendRenameTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `rename plans declaration and cross-file reference edits`(): TestResult = runTest {
        val declarationFile = writeFile(
            relativePath = "src/main/kotlin/sample/Greeter.kt",
            content = $$"""
                package sample

                fun greet(name: String): String = "hi $name"
            """.trimIndent() + "\n",
        )
        val usageFile = writeFile(
            relativePath = "src/main/kotlin/sample/Use.kt",
            content = """
                package sample

                fun use(): String = greet("kast")
            """.trimIndent() + "\n",
        )
        val secondaryUsageFile = writeFile(
            relativePath = "src/main/kotlin/sample/SecondaryUse.kt",
            content = """
                package sample

                fun useAgain(): String = greet("again")
            """.trimIndent() + "\n",
        )
        val queryOffset = Files.readString(usageFile).indexOf("greet")
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
        )
        session.use { session ->
            val backend = StandaloneAnalysisBackend(
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(
                    maxResults = 100,
                    requestTimeoutMillis = 30_000,
                    maxConcurrentRequests = 4,
                ),
                session = session,
            )

            val result = backend.rename(
                RenameQuery(
                    position = FilePosition(
                        filePath = usageFile.toString(),
                        offset = queryOffset,
                    ),
                    newName = "welcome",
                ),
            )

            assertEquals(
                listOf(
                    normalizePath(declarationFile),
                    normalizePath(secondaryUsageFile),
                    normalizePath(usageFile),
                ),
                result.edits.map { edit -> edit.filePath },
            )
            assertEquals(listOf("welcome", "welcome", "welcome"), result.edits.map { edit -> edit.newText })
            assertEquals(
                listOf(normalizePath(declarationFile), normalizePath(secondaryUsageFile), normalizePath(usageFile)),
                result.affectedFiles,
            )
            assertEquals(
                listOf(
                    normalizePath(declarationFile) to FileHashing.sha256(declarationFile.readText()),
                    normalizePath(secondaryUsageFile) to FileHashing.sha256(secondaryUsageFile.readText()),
                    normalizePath(usageFile) to FileHashing.sha256(usageFile.readText()),
                ),
                result.fileHashes.map { hash -> hash.filePath to hash.hash },
            )
        }
    }

    @Test
    fun `capabilities advertise rename after implementation`(): TestResult = runTest {
        writeFile(
            relativePath = "src/main/kotlin/sample/Greeter.kt",
            content = $$"""
                package sample

                fun greet(name: String): String = "hi $name"
            """.trimIndent() + "\n",
        )
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
        )
        session.use { session ->
            val backend = StandaloneAnalysisBackend(
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(
                    maxResults = 100,
                    requestTimeoutMillis = 30_000,
                    maxConcurrentRequests = 4,
                ),
                session = session,
            )

            val capabilities = backend.capabilities()

            assertTrue(MutationCapability.RENAME in capabilities.mutationCapabilities)
        }
    }

    @Test
    fun `rename plans edits without initializing full Kotlin file map`(): TestResult = runTest {
        writeFile(
            relativePath = "src/main/kotlin/sample/Greeter.kt",
            content = $$"""
                package sample

                fun greet(name: String): String = "hi $name"
            """.trimIndent() + "\n",
        )
        val usageFile = writeFile(
            relativePath = "src/main/kotlin/sample/Use.kt",
            content = """
                package sample

                fun use(): String = greet("kast")
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "src/main/kotlin/sample/SecondaryUse.kt",
            content = """
                package sample

                fun useAgain(): String = greet("again")
            """.trimIndent() + "\n",
        )
        repeat(20) { index ->
            writeFile(
                relativePath = "src/main/kotlin/sample/unrelated/Unrelated$index.kt",
                content = """
                    package sample.unrelated

                    fun unrelated$index(): String = "value$index"
                """.trimIndent() + "\n",
            )
        }
        val queryOffset = Files.readString(usageFile).indexOf("greet")
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
        )
        session.use { session ->
            val backend = StandaloneAnalysisBackend(
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(
                    maxResults = 100,
                    requestTimeoutMillis = 30_000,
                    maxConcurrentRequests = 4,
                ),
                session = session,
            )

            assertFalse(session.ktFilesByPathDelegate().isInitialized())

            backend.rename(
                RenameQuery(
                    position = FilePosition(
                        filePath = usageFile.toString(),
                        offset = queryOffset,
                    ),
                    newName = "welcome",
                ),
            )

            assertFalse(session.ktFilesByPathDelegate().isInitialized())
        }
    }

    @Test
    fun `rename applies both same-file reference edits without rereading between edits`(): TestResult = runTest {
        val declarationFile = writeFile(
            relativePath = "src/main/kotlin/sample/Greeter.kt",
            content = $$"""
                package sample

                fun greet(name: String): String = "hi $name"
            """.trimIndent() + "\n",
        )
        val usageFile = writeFile(
            relativePath = "src/main/kotlin/sample/Use.kt",
            content = """
                package sample

                fun useTwice(): String = greet("kast") + greet("again")
            """.trimIndent() + "\n",
        )
        val queryOffset = Files.readString(usageFile).indexOf("greet")
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
        )
        session.use { session ->
            val backend = StandaloneAnalysisBackend(
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(
                    maxResults = 100,
                    requestTimeoutMillis = 30_000,
                    maxConcurrentRequests = 4,
                ),
                session = session,
            )

            val renameResult = backend.rename(
                RenameQuery(
                    position = FilePosition(
                        filePath = usageFile.toString(),
                        offset = queryOffset,
                    ),
                    newName = "welcome",
                ),
            )

            assertEquals(
                listOf(
                    normalizePath(declarationFile),
                    normalizePath(usageFile),
                    normalizePath(usageFile),
                ),
                renameResult.edits.map { edit -> edit.filePath },
            )
            assertEquals(
                2,
                renameResult.edits.count { edit -> edit.filePath == normalizePath(usageFile) },
            )

            val applyResult = backend.applyEdits(
                ApplyEditsQuery(
                    edits = renameResult.edits,
                    fileHashes = renameResult.fileHashes,
                ),
            )

            assertEquals(
                listOf(normalizePath(declarationFile), normalizePath(usageFile)),
                applyResult.affectedFiles,
            )
            assertEquals(
                """
                    package sample

                    fun useTwice(): String = welcome("kast") + welcome("again")
                """.trimIndent() + "\n",
                usageFile.readText(),
            )
        }
    }

    private fun writeFile(
        relativePath: String,
        content: String,
    ): Path {
        val path = workspaceRoot.resolve(relativePath)
        Files.createDirectories(path.parent)
        path.writeText(content)
        return path
    }

    private fun normalizePath(path: Path): String {
        val absolutePath = path.toAbsolutePath().normalize()
        return runCatching { absolutePath.toRealPath().normalize().toString() }.getOrDefault(absolutePath.toString())
    }
}

@Suppress("UNCHECKED_CAST")
private fun StandaloneAnalysisSession.ktFilesByPathDelegate(): Lazy<Map<String, *>> {
    val field = StandaloneAnalysisSession::class.java.getDeclaredField($$"ktFilesByPath$delegate")
    field.isAccessible = true
    return field.get(this) as Lazy<Map<String, *>>
}
