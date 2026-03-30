package io.github.amichne.kast.intellij

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.api.AnalysisBackend
import io.github.amichne.kast.api.ApplyEditsQuery
import io.github.amichne.kast.api.ApplyEditsResult
import io.github.amichne.kast.api.BackendCapabilities
import io.github.amichne.kast.api.CallHierarchyQuery
import io.github.amichne.kast.api.CallHierarchyResult
import io.github.amichne.kast.api.ConflictException
import io.github.amichne.kast.api.Diagnostic
import io.github.amichne.kast.api.DiagnosticSeverity
import io.github.amichne.kast.api.DiagnosticsQuery
import io.github.amichne.kast.api.DiagnosticsResult
import io.github.amichne.kast.api.EditPlanValidator
import io.github.amichne.kast.api.FileHash
import io.github.amichne.kast.api.FileHashing
import io.github.amichne.kast.api.FilePosition
import io.github.amichne.kast.api.HealthResponse
import io.github.amichne.kast.api.Location
import io.github.amichne.kast.api.MutationCapability
import io.github.amichne.kast.api.NotFoundException
import io.github.amichne.kast.api.PartialApplyException
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import java.nio.file.Path
import kotlin.io.path.readText

class IntelliJAnalysisBackend(
    private val project: Project,
    private val limits: ServerLimits,
) : AnalysisBackend {
    private val readDispatcher = Dispatchers.IO.limitedParallelism(limits.maxConcurrentRequests)
    private val writeMutex = Mutex()

    override suspend fun capabilities(): BackendCapabilities = BackendCapabilities(
        backendName = "intellij",
        backendVersion = "0.1.0",
        workspaceRoot = project.basePath.orEmpty(),
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

    override suspend fun health(): HealthResponse {
        val capabilities = capabilities()
        return HealthResponse(
            backendName = capabilities.backendName,
            backendVersion = capabilities.backendVersion,
            workspaceRoot = capabilities.workspaceRoot,
        )
    }

    override suspend fun resolveSymbol(query: SymbolQuery): SymbolResult = readInSmartMode {
        val file = requirePsiFile(query.position.filePath)
        val target = resolveTarget(file, query.position.offset)
        SymbolResult(target.toSymbol(file))
    }

    override suspend fun findReferences(query: ReferencesQuery): ReferencesResult = readInSmartMode {
        val file = requirePsiFile(query.position.filePath)
        val target = resolveTarget(file, query.position.offset)
        val references = ReferencesSearch.search(target, GlobalSearchScope.projectScope(project))
            .findAll()
            .map { reference ->
                val range = reference.element.textRange.shiftRight(reference.rangeInElement.startOffset)
                reference.element.toLocation(range)
            }
            .sortedWith(compareBy({ it.filePath }, { it.startOffset }))

        ReferencesResult(
            declaration = if (query.includeDeclaration) target.toSymbol(file) else null,
            references = references,
        )
    }

    override suspend fun callHierarchy(query: CallHierarchyQuery): CallHierarchyResult {
        return super.callHierarchy(query)
    }

    override suspend fun diagnostics(query: DiagnosticsQuery): DiagnosticsResult = readInSmartMode {
        val diagnostics = query.filePaths.sorted().flatMap { filePath ->
            val file = requirePsiFile(filePath)
            PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java)
                .map { error ->
                    Diagnostic(
                        location = error.toLocation(error.textRange),
                        severity = DiagnosticSeverity.ERROR,
                        message = error.errorDescription,
                        code = "PSI_PARSE_ERROR",
                    )
                }
        }

        DiagnosticsResult(diagnostics = diagnostics)
    }

    override suspend fun rename(query: RenameQuery): RenameResult = readInSmartMode {
        val file = requirePsiFile(query.position.filePath)
        val target = resolveTarget(file, query.position.offset)
        val declarationEdit = target.declarationEdit(query.newName)
        val referenceEdits = ReferencesSearch.search(target, GlobalSearchScope.projectScope(project))
            .findAll()
            .map { reference ->
                val elementRange = reference.element.textRange
                TextEdit(
                    filePath = reference.element.containingFile.virtualFile.path,
                    startOffset = elementRange.startOffset + reference.rangeInElement.startOffset,
                    endOffset = elementRange.startOffset + reference.rangeInElement.endOffset,
                    newText = query.newName,
                )
            }

        val edits = (listOf(declarationEdit) + referenceEdits)
            .distinctBy { Triple(it.filePath, it.startOffset, it.endOffset) }
            .sortedWith(compareBy({ it.filePath }, { it.startOffset }))
        val fileHashes = currentFileHashes(edits.map(TextEdit::filePath))
        RenameResult(
            edits = edits,
            fileHashes = fileHashes,
            affectedFiles = fileHashes.map(FileHash::filePath),
        )
    }

    override suspend fun applyEdits(query: ApplyEditsQuery): ApplyEditsResult = writeMutex.withLock {
        val validated = EditPlanValidator.validate(query.edits, query.fileHashes)
        val snapshots = readInSmartMode {
            validated.associateWith { plan ->
                currentText(plan.filePath)
            }
        }

        snapshots.forEach { (plan, content) ->
            val actualHash = FileHashing.sha256(content)
            if (actualHash != plan.expectedHash) {
                throw ConflictException(
                    message = "The file changed after the edit plan was created",
                    details = mapOf(
                        "filePath" to plan.filePath,
                        "expectedHash" to plan.expectedHash,
                        "actualHash" to actualHash,
                    ),
                )
            }
        }

        val appliedFiles = mutableListOf<String>()
        val appliedEdits = mutableListOf<TextEdit>()

        validated.forEach { plan ->
            try {
                applyFileEdits(plan.filePath, plan.edits)
                appliedFiles += plan.filePath
                appliedEdits += plan.edits.sortedBy { it.startOffset }
            } catch (exception: Exception) {
                throw PartialApplyException(
                    details = mapOf(
                        "failedFile" to plan.filePath,
                        "appliedFiles" to appliedFiles.joinToString(","),
                        "reason" to (exception.message ?: exception::class.java.simpleName),
                    ),
                )
            }
        }

        ApplyEditsResult(
            applied = appliedEdits,
            affectedFiles = appliedFiles.sorted(),
        )
    }

    private suspend fun <T> readInSmartMode(action: () -> T): T = withContext(readDispatcher) {
        DumbService.getInstance(project).runReadActionInSmartMode(
            Computable { action() },
        )
    }

    private fun requirePsiFile(filePath: String): PsiFile {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath)
            ?: throw NotFoundException(
                message = "The requested file does not exist in the IntelliJ project",
                details = mapOf("filePath" to filePath),
            )
        return PsiManager.getInstance(project).findFile(virtualFile)
            ?: throw NotFoundException(
                message = "The requested file could not be resolved to a PSI file",
                details = mapOf("filePath" to filePath),
            )
    }

    private fun resolveTarget(
        file: PsiFile,
        offset: Int,
    ): PsiElement {
        val leaf = file.findElementAt(offset)
            ?: throw NotFoundException(
                message = "No PSI element was found at the requested offset",
                details = mapOf("offset" to offset.toString()),
            )

        generateSequence(leaf as PsiElement?) { element -> element.parent }.forEach { element ->
            element.references.firstNotNullOfOrNull { it.resolve() }?.let { resolved ->
                return resolved
            }

            if (element is PsiNamedElement && !element.name.isNullOrBlank()) {
                return element
            }
        }

        throw NotFoundException("No resolvable symbol was found at the requested offset")
    }

    private fun currentFileHashes(filePaths: Collection<String>): List<FileHash> = filePaths
        .distinct()
        .sorted()
        .map { filePath ->
            FileHash(
                filePath = filePath,
                hash = FileHashing.sha256(currentText(filePath)),
            )
        }

    private fun currentText(filePath: String): String {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath)
            ?: throw NotFoundException("The requested file does not exist", mapOf("filePath" to filePath))
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)
        return document?.text ?: Path.of(filePath).readText()
    }

    private suspend fun applyFileEdits(
        filePath: String,
        edits: List<TextEdit>,
    ) {
        withContext(Dispatchers.IO.limitedParallelism(1)) {
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath)
                ?: throw NotFoundException("The requested file does not exist", mapOf("filePath" to filePath))
            val document = FileDocumentManager.getInstance().getDocument(virtualFile)
                ?: throw NotFoundException("The requested file does not have a document", mapOf("filePath" to filePath))
            WriteCommandAction.runWriteCommandAction(project) {
                edits.sortedByDescending { it.startOffset }.forEach { edit ->
                    document.replaceString(edit.startOffset, edit.endOffset, edit.newText)
                }
                PsiDocumentManager.getInstance(project).commitDocument(document)
                FileDocumentManager.getInstance().saveDocument(document)
            }
        }
    }

    private fun PsiElement.declarationEdit(newName: String): TextEdit {
        val range = when (this) {
            is KtNamedDeclaration -> nameIdentifier?.textRange ?: textRange
            is PsiNameIdentifierOwner -> nameIdentifier?.textRange ?: textRange
            else -> textRange
        }

        return TextEdit(
            filePath = containingFile.virtualFile.path,
            startOffset = range.startOffset,
            endOffset = range.endOffset,
            newText = newName,
        )
    }

    private fun PsiElement.toSymbol(file: PsiFile): Symbol {
        return Symbol(
            fqName = fqName(),
            kind = kind(),
            location = toLocation(nameRange()),
            type = typeDescription(),
            containingDeclaration = file.packageNameOrNull(),
        )
    }

    private fun PsiElement.nameRange(): TextRange = when (this) {
        is KtNamedDeclaration -> nameIdentifier?.textRange ?: textRange
        is PsiNameIdentifierOwner -> nameIdentifier?.textRange ?: textRange
        else -> textRange
    }

    private fun PsiElement.toLocation(range: TextRange): Location {
        val document = PsiDocumentManager.getInstance(project).getDocument(containingFile)
            ?: throw NotFoundException("Unable to create a document for the PSI file")
        val lineIndex = document.getLineNumber(range.startOffset)
        val previewStart = document.getLineStartOffset(lineIndex)
        val previewEnd = document.getLineEndOffset(lineIndex)

        return Location(
            filePath = containingFile.virtualFile.path,
            startOffset = range.startOffset,
            endOffset = range.endOffset,
            startLine = lineIndex + 1,
            startColumn = range.startOffset - previewStart + 1,
            preview = document.getText(TextRange(previewStart, previewEnd)).trimEnd(),
        )
    }

    private fun PsiElement.fqName(): String = when (this) {
        is KtNamedDeclaration -> fqName?.asString() ?: name ?: "<anonymous>"
        is PsiClass -> qualifiedName ?: name ?: "<anonymous>"
        is PsiMethod -> "${containingClass?.qualifiedName ?: "<local>"}#${name}"
        is PsiField -> "${containingClass?.qualifiedName ?: "<local>"}.${name}"
        is PsiNamedElement -> name ?: "<anonymous>"
        else -> text
    }

    private fun PsiElement.kind(): SymbolKind = when (this) {
        is KtClass -> SymbolKind.CLASS
        is KtObjectDeclaration -> SymbolKind.OBJECT
        is KtNamedFunction -> SymbolKind.FUNCTION
        is KtProperty -> SymbolKind.PROPERTY
        is KtParameter -> SymbolKind.PARAMETER
        is PsiClass -> if (isInterface) SymbolKind.INTERFACE else SymbolKind.CLASS
        is PsiMethod -> SymbolKind.FUNCTION
        is PsiField -> SymbolKind.PROPERTY
        else -> SymbolKind.UNKNOWN
    }

    private fun PsiElement.typeDescription(): String? = when (this) {
        is KtNamedFunction -> typeReference?.text
        is KtProperty -> typeReference?.text
        is PsiMethod -> returnType?.presentableText
        is PsiField -> type.presentableText
        else -> null
    }

    private fun PsiFile.packageNameOrNull(): String? = when (this) {
        is org.jetbrains.kotlin.psi.KtFile -> packageFqName.asString().ifBlank { null }
        else -> null
    }
}
