package io.github.amichne.kast.standalone

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import io.github.amichne.kast.api.AnalysisBackend
import io.github.amichne.kast.api.ApplyEditsQuery
import io.github.amichne.kast.api.ApplyEditsResult
import io.github.amichne.kast.api.BackendCapabilities
import io.github.amichne.kast.api.CallHierarchyQuery
import io.github.amichne.kast.api.CallHierarchyResult
import io.github.amichne.kast.api.DiagnosticsQuery
import io.github.amichne.kast.api.DiagnosticsResult
import io.github.amichne.kast.api.FileHash
import io.github.amichne.kast.api.HealthResponse
import io.github.amichne.kast.api.LocalDiskEditApplier
import io.github.amichne.kast.api.MutationCapability
import io.github.amichne.kast.api.ReadCapability
import io.github.amichne.kast.api.ReferencesQuery
import io.github.amichne.kast.api.ReferencesResult
import io.github.amichne.kast.api.RenameQuery
import io.github.amichne.kast.api.RenameResult
import io.github.amichne.kast.api.RuntimeState
import io.github.amichne.kast.api.RuntimeStatusResponse
import io.github.amichne.kast.api.ServerLimits
import io.github.amichne.kast.api.SymbolQuery
import io.github.amichne.kast.api.SymbolResult
import io.github.amichne.kast.api.TextEdit
import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.components.collectDiagnostics
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.lexer.KtTokens

@OptIn(KaExperimentalApi::class)
class StandaloneAnalysisBackend(
    private val workspaceRoot: Path,
    private val limits: ServerLimits,
    private val session: StandaloneAnalysisSession,
) : AnalysisBackend {
    private val readDispatcher = Dispatchers.IO.limitedParallelism(limits.maxConcurrentRequests)
    private val renameProfilingEnabled = System.getenv("KAST_PROFILE_RENAME") == "1"
    private val renameProfileOutputPath = System.getenv("KAST_PROFILE_RENAME_FILE")
        ?.takeIf(String::isNotBlank)
        ?.let(Path::of)

    override suspend fun capabilities(): BackendCapabilities = BackendCapabilities(
        backendName = "standalone",
        backendVersion = "0.1.0",
        workspaceRoot = workspaceRoot.toString(),
        readCapabilities = setOf(
            ReadCapability.RESOLVE_SYMBOL,
            ReadCapability.FIND_REFERENCES,
            ReadCapability.DIAGNOSTICS,
        ),
        mutationCapabilities = setOf(
            MutationCapability.RENAME,
            MutationCapability.APPLY_EDITS,
        ),
        limits = limits,
    )

    override suspend fun runtimeStatus(): RuntimeStatusResponse {
        val capabilities = capabilities()
        return RuntimeStatusResponse(
            state = RuntimeState.READY,
            healthy = true,
            active = true,
            indexing = false,
            backendName = capabilities.backendName,
            backendVersion = capabilities.backendVersion,
            workspaceRoot = capabilities.workspaceRoot,
            message = "Standalone analysis session is initialized",
        )
    }

    override suspend fun health(): HealthResponse {
        val capabilities = capabilities()
        return HealthResponse(
            backendName = capabilities.backendName,
            backendVersion = capabilities.backendVersion,
            workspaceRoot = capabilities.workspaceRoot,
        )
    }

    override suspend fun resolveSymbol(query: SymbolQuery): SymbolResult = withContext(readDispatcher) {
        val file = session.findKtFile(query.position.filePath)
        val target = resolveTarget(file, query.position.offset)
        SymbolResult(analyze(file) { target.toSymbolModel(containingDeclaration = null) })
    }

    override suspend fun findReferences(query: ReferencesQuery): ReferencesResult = withContext(readDispatcher) {
        val file = session.findKtFile(query.position.filePath)
        val target = resolveTarget(file, query.position.offset)
        val references = candidateReferenceFiles(target)
            .flatMap { candidateFile -> candidateFile.findReferenceLocations(target) }
            .sortedWith(compareBy({ it.filePath }, { it.startOffset }))

        ReferencesResult(
            declaration = if (query.includeDeclaration) analyze(file) { target.toSymbolModel(containingDeclaration = null) } else null,
            references = references,
        )
    }

    override suspend fun callHierarchy(query: CallHierarchyQuery): CallHierarchyResult {
        throw unsupported(ReadCapability.CALL_HIERARCHY)
    }

    override suspend fun diagnostics(query: DiagnosticsQuery): DiagnosticsResult = withContext(readDispatcher) {
        val diagnostics = query.filePaths
            .sorted()
            .flatMap { filePath ->
                val file = session.findKtFile(filePath)
                analyze(file) {
                    file.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)
                }.flatMap { diagnostic -> diagnostic.toApiDiagnostics() }
            }
            .sortedWith(compareBy({ it.location.filePath }, { it.location.startOffset }, { it.code ?: "" }))

        DiagnosticsResult(diagnostics = diagnostics)
    }

    override suspend fun rename(query: RenameQuery): RenameResult = withContext(readDispatcher) {
        val file = profileRenamePhase("findKtFile") {
            session.findKtFile(query.position.filePath)
        }
        val target = profileRenamePhase("resolveTarget") {
            resolveTarget(file, query.position.offset)
        }
        val searchIdentifier = target.referenceSearchIdentifier()
        val candidateFiles = profileRenamePhase("candidateReferenceFiles") {
            candidateReferenceFiles(target)
        }.also { files ->
            if (renameProfilingEnabled) {
                logRenameProfile("rename-profile phase=candidateReferenceFiles count=${files.size} identifier=${searchIdentifier ?: "<fallback>"}")
                logRenameProfile(
                    "rename-profile phase=candidateReferenceFiles files=${files.joinToString(separator = "|") { file -> file.virtualFile?.path ?: file.name }}",
                )
            }
        }
        val edits = profileRenamePhase("collectReferenceEdits") {
            (listOf(target.declarationEdit(query.newName)) + candidateFiles
                .flatMap { candidateFile ->
                    if (renameProfilingEnabled) {
                        val occurrenceCount = searchIdentifier
                            ?.let { identifier -> candidateFile.text.identifierOccurrenceOffsets(identifier).count() }
                        logRenameProfile(
                            "rename-profile phase=referenceEdits-start file=${candidateFile.virtualFile?.path ?: candidateFile.name} occurrences=${occurrenceCount ?: -1}",
                        )
                    }
                    profileRenamePhase("referenceEdits:${candidateFile.virtualFile?.path ?: candidateFile.name}") {
                        candidateFile.referenceEdits(target, query.newName, searchIdentifier)
                    }.also { candidateEdits ->
                        if (renameProfilingEnabled) {
                            logRenameProfile(
                                "rename-profile phase=referenceEdits file=${candidateFile.virtualFile?.path ?: candidateFile.name} edits=${candidateEdits.size}",
                            )
                        }
                    }
                })
                .distinctBy { edit -> Triple(edit.filePath, edit.startOffset, edit.endOffset) }
                .sortedWith(compareBy({ it.filePath }, { it.startOffset }))
        }.also { collectedEdits ->
            if (renameProfilingEnabled) {
                println("rename-profile phase=collectReferenceEdits edits=${collectedEdits.size}")
            }
        }
        val fileHashes = profileRenamePhase("currentFileHashes") {
            currentFileHashes(edits.map(TextEdit::filePath))
        }

        RenameResult(
            edits = edits,
            fileHashes = fileHashes,
            affectedFiles = fileHashes.map(FileHash::filePath),
        )
    }

    override suspend fun applyEdits(query: ApplyEditsQuery): ApplyEditsResult = LocalDiskEditApplier.apply(query)

    private fun candidateReferenceFiles(target: PsiElement): List<KtFile> {
        val searchIdentifier = target.referenceSearchIdentifier() ?: return session.allKtFiles()
        val anchorFilePath = target.containingFile.virtualFile?.path
            ?: target.containingFile.viewProvider.virtualFile.path
        val candidatePaths = session.candidateKotlinFilePaths(
            identifier = searchIdentifier,
            anchorFilePath = anchorFilePath,
        )
        if (candidatePaths.isEmpty()) {
            return session.allKtFiles()
        }

        return candidatePaths.map(session::findKtFile)
    }

    private fun KtFile.findReferenceLocations(target: PsiElement): List<io.github.amichne.kast.api.Location> {
        val references = mutableListOf<io.github.amichne.kast.api.Location>()

        // The standalone Analysis API session does not register the ReferencesSearch extension point,
        // so resolve references directly across the loaded PSI files.
        accept(
            object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    element.references.forEach { reference ->
                        val resolved = reference.resolve()
                        if (resolved == target || resolved?.isEquivalentTo(target) == true) {
                            references += reference.element.toKastLocation(
                                com.intellij.openapi.util.TextRange(
                                    reference.element.textRange.startOffset + reference.rangeInElement.startOffset,
                                    reference.element.textRange.startOffset + reference.rangeInElement.endOffset,
                                )
                            )
                        }
                    }
                    super.visitElement(element)
                }
            },
        )

        return references
    }

    private fun KtFile.referenceEdits(
        target: PsiElement,
        newName: String,
        searchIdentifier: String?,
    ): List<TextEdit> {
        if (searchIdentifier != null) {
            return referenceEditsAtIdentifierOccurrences(target, newName, searchIdentifier)
        }

        val edits = mutableListOf<TextEdit>()

        accept(
            object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    element.references.forEach { reference ->
                        val resolved = reference.resolve()
                        if (resolved == target || resolved?.isEquivalentTo(target) == true) {
                            val elementStart = reference.element.textRange.startOffset
                            edits += TextEdit(
                                filePath = reference.element.containingFile.virtualFile?.path
                                    ?: reference.element.containingFile.viewProvider.virtualFile.path,
                                startOffset = elementStart + reference.rangeInElement.startOffset,
                                endOffset = elementStart + reference.rangeInElement.endOffset,
                                newText = newName,
                            )
                        }
                    }
                    super.visitElement(element)
                }
            },
        )

        return edits
    }

    private fun KtFile.referenceEditsAtIdentifierOccurrences(
        target: PsiElement,
        newName: String,
        searchIdentifier: String,
    ): List<TextEdit> = renameReferenceCandidateElements(searchIdentifier)
        .flatMap { element ->
            element.references.mapNotNull { reference ->
                val resolved = reference.resolve()
                if (resolved == target || resolved?.isEquivalentTo(target) == true) {
                    val elementStart = reference.element.textRange.startOffset
                    TextEdit(
                        filePath = reference.element.containingFile.virtualFile?.path
                            ?: reference.element.containingFile.viewProvider.virtualFile.path,
                        startOffset = elementStart + reference.rangeInElement.startOffset,
                        endOffset = elementStart + reference.rangeInElement.endOffset,
                        newText = newName,
                    )
                } else {
                    null
                }
            }
        }

    private fun KtFile.renameReferenceCandidateElements(searchIdentifier: String): List<PsiElement> {
        val candidates = linkedSetOf<PsiElement>()
        text.identifierOccurrenceOffsets(searchIdentifier).forEach { occurrenceOffset ->
            val leaf = findElementAt(occurrenceOffset) ?: return@forEach
            generateSequence(leaf as PsiElement?) { element -> element.parent }
                .firstOrNull { element ->
                    element.references.isNotEmpty() &&
                        element.textRange.startOffset <= occurrenceOffset &&
                        element.textRange.endOffset >= occurrenceOffset + searchIdentifier.length
                }
                ?.let(candidates::add)
        }
        return candidates.toList()
    }

    private fun currentFileHashes(filePaths: Collection<String>): List<FileHash> = LocalDiskEditApplier.currentHashes(filePaths)

    private fun unsupported(capability: ReadCapability) = io.github.amichne.kast.api.CapabilityNotSupportedException(
        capability = capability.name,
        message = "The standalone backend does not support $capability",
    )

    private inline fun <T> profileRenamePhase(
        phaseName: String,
        action: () -> T,
    ): T {
        if (!renameProfilingEnabled) {
            return action()
        }

        val startedAt = System.nanoTime()
        return action().also {
            val durationMillis = (System.nanoTime() - startedAt) / 1_000_000
            logRenameProfile("rename-profile phase=$phaseName durationMs=$durationMillis")
        }
    }

    private fun logRenameProfile(message: String) {
        println(message)
        val outputPath = renameProfileOutputPath ?: return
        runCatching {
            outputPath.parent?.createDirectories()
            outputPath.appendText(message + System.lineSeparator())
        }
    }
}

private fun PsiElement.referenceSearchIdentifier(): String? = when (this) {
    is KtNamedFunction -> name.takeUnless { hasModifier(KtTokens.OPERATOR_KEYWORD) }
    is KtNamedDeclaration -> name
    else -> (this as? com.intellij.psi.PsiNamedElement)?.name
}
    ?.takeIf { identifier -> identifier.isNotBlank() }

private fun String.identifierOccurrenceOffsets(identifier: String): Sequence<Int> = sequence {
    var searchFrom = 0
    while (true) {
        val occurrenceOffset = indexOf(identifier, startIndex = searchFrom)
        if (occurrenceOffset == -1) {
            break
        }

        val before = getOrNull(occurrenceOffset - 1)
        val after = getOrNull(occurrenceOffset + identifier.length)
        val startsIdentifier = before?.isKastIdentifierPart() != true
        val endsIdentifier = after?.isKastIdentifierPart() != true
        if (startsIdentifier && endsIdentifier) {
            yield(occurrenceOffset)
        }

        searchFrom = occurrenceOffset + identifier.length
    }
}

private fun Char.isKastIdentifierPart(): Boolean = this == '_' || isLetterOrDigit()
