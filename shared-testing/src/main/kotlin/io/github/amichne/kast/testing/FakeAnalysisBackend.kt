package io.github.amichne.kast.testing

import io.github.amichne.kast.api.AnalysisBackend
import io.github.amichne.kast.api.ApplyEditsQuery
import io.github.amichne.kast.api.ApplyEditsResult
import io.github.amichne.kast.api.BackendCapabilities
import io.github.amichne.kast.api.CallDirection
import io.github.amichne.kast.api.CallHierarchyQuery
import io.github.amichne.kast.api.CallHierarchyResult
import io.github.amichne.kast.api.CallNode
import io.github.amichne.kast.api.Diagnostic
import io.github.amichne.kast.api.DiagnosticSeverity
import io.github.amichne.kast.api.DiagnosticsQuery
import io.github.amichne.kast.api.DiagnosticsResult
import io.github.amichne.kast.api.FileHash
import io.github.amichne.kast.api.FileHashing
import io.github.amichne.kast.api.FilePosition
import io.github.amichne.kast.api.HealthResponse
import io.github.amichne.kast.api.LocalDiskEditApplier
import io.github.amichne.kast.api.Location
import io.github.amichne.kast.api.MutationCapability
import io.github.amichne.kast.api.NotFoundException
import io.github.amichne.kast.api.ReadCapability
import io.github.amichne.kast.api.ReferencesQuery
import io.github.amichne.kast.api.ReferencesResult
import io.github.amichne.kast.api.RenameQuery
import io.github.amichne.kast.api.RenameResult
import io.github.amichne.kast.api.ServerLimits
import io.github.amichne.kast.api.Symbol
import io.github.amichne.kast.api.SymbolKind
import io.github.amichne.kast.api.SymbolQuery
import io.github.amichne.kast.api.SymbolResult
import io.github.amichne.kast.api.TextEdit
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

class FakeAnalysisBackend private constructor(
    private val workspaceRoot: Path,
    private val symbol: Symbol,
    private val declarationOffset: Int,
    private val referenceOffset: Int,
    private val limits: ServerLimits,
    private val backendName: String,
) : AnalysisBackend {
    private val filePath: String = symbol.location.filePath

    override suspend fun capabilities(): BackendCapabilities = BackendCapabilities(
        backendName = backendName,
        backendVersion = "0.1.0-test",
        workspaceRoot = workspaceRoot.toString(),
        readCapabilities = setOf(
            ReadCapability.RESOLVE_SYMBOL,
            ReadCapability.FIND_REFERENCES,
            ReadCapability.CALL_HIERARCHY,
            ReadCapability.DIAGNOSTICS,
        ),
        mutationCapabilities = setOf(
            MutationCapability.RENAME,
            MutationCapability.APPLY_EDITS,
        ),
        limits = limits,
    )

    override suspend fun health(): HealthResponse {
        val capabilities = capabilities()
        return HealthResponse(
            backendName = capabilities.backendName,
            backendVersion = capabilities.backendVersion,
            workspaceRoot = capabilities.workspaceRoot,
        )
    }

    override suspend fun resolveSymbol(query: SymbolQuery): SymbolResult {
        requireFile(query.position)
        return when (query.position.offset) {
            in declarationOffset until declarationOffset + symbol.location.endOffset - symbol.location.startOffset -> SymbolResult(symbol)
            in referenceOffset until referenceOffset + symbol.location.endOffset - symbol.location.startOffset -> SymbolResult(symbol)
            else -> throw NotFoundException(
                message = "No symbol was found at the requested offset",
                details = mapOf("offset" to query.position.offset.toString()),
            )
        }
    }

    override suspend fun findReferences(query: ReferencesQuery): ReferencesResult {
        requireFile(query.position)
        if (query.position.offset !in declarationOffset..referenceOffset + 1) {
            throw NotFoundException("No references were found for the requested symbol")
        }

        val declaration = if (query.includeDeclaration) symbol else null
        return ReferencesResult(
            declaration = declaration,
            references = listOf(referenceLocation(filePath, referenceOffset)),
        )
    }

    override suspend fun callHierarchy(query: CallHierarchyQuery): CallHierarchyResult {
        requireFile(query.position)
        val child = if (query.direction == CallDirection.OUTGOING) {
            CallNode(
                symbol = Symbol(
                    fqName = "sample.use",
                    kind = SymbolKind.FUNCTION,
                    location = referenceLocation(filePath, referenceOffset),
                ),
                children = emptyList(),
            )
        } else {
            CallNode(symbol = symbol, children = emptyList())
        }

        return CallHierarchyResult(
            root = CallNode(symbol = symbol, children = listOf(child)),
        )
    }

    override suspend fun diagnostics(query: DiagnosticsQuery): DiagnosticsResult {
        query.filePaths.forEach { requireFile(FilePosition(it, 0)) }
        return DiagnosticsResult(
            diagnostics = emptyList<Diagnostic>(),
        )
    }

    override suspend fun rename(query: RenameQuery): RenameResult {
        requireFile(query.position)
        val content = Files.readString(Path.of(filePath))
        val declarationEdit = TextEdit(
            filePath = filePath,
            startOffset = declarationOffset,
            endOffset = declarationOffset + "greet".length,
            newText = query.newName,
        )
        val referenceEdit = TextEdit(
            filePath = filePath,
            startOffset = referenceOffset,
            endOffset = referenceOffset + "greet".length,
            newText = query.newName,
        )

        return RenameResult(
            edits = listOf(declarationEdit, referenceEdit),
            fileHashes = listOf(
                FileHash(
                    filePath = filePath,
                    hash = FileHashing.sha256(content),
                ),
            ),
            affectedFiles = listOf(filePath),
        )
    }

    override suspend fun applyEdits(query: ApplyEditsQuery): ApplyEditsResult = LocalDiskEditApplier.apply(query)

    private fun requireFile(position: FilePosition) {
        if (position.filePath != filePath) {
            throw NotFoundException(
                message = "The fake backend only exposes its fixture file",
                details = mapOf("filePath" to position.filePath),
            )
        }
    }

    companion object {
        fun sample(
            workspaceRoot: Path,
            limits: ServerLimits = ServerLimits(
                maxResults = 100,
                requestTimeoutMillis = 30_000,
                maxConcurrentRequests = 4,
            ),
            backendName: String = "fake",
        ): FakeAnalysisBackend {
            val sourceDirectory = workspaceRoot.resolve("src")
            Files.createDirectories(sourceDirectory)
            val file = sourceDirectory.resolve("Sample.kt")
            val content = """
                package sample

                fun greet() = "hi"

                fun use() = greet()
            """.trimIndent() + "\n"
            file.writeText(content)

            val declarationOffset = content.indexOf("greet")
            val referenceOffset = content.lastIndexOf("greet")
            val symbolLocation = referenceLocation(file.toString(), declarationOffset)
            val symbol = Symbol(
                fqName = "sample.greet",
                kind = SymbolKind.FUNCTION,
                location = symbolLocation,
                containingDeclaration = "sample",
            )

            return FakeAnalysisBackend(
                workspaceRoot = workspaceRoot,
                symbol = symbol,
                declarationOffset = declarationOffset,
                referenceOffset = referenceOffset,
                limits = limits,
                backendName = backendName,
            )
        }

        private fun referenceLocation(
            filePath: String,
            offset: Int,
        ): Location {
            val line = if (offset < 15) 2 else 4
            val column = if (offset < 15) 5 else 13
            return Location(
                filePath = filePath,
                startOffset = offset,
                endOffset = offset + "greet".length,
                startLine = line,
                startColumn = column,
                preview = "greet",
            )
        }
    }
}
