package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.AnalysisBackend
import io.github.amichne.kast.api.ApplyEditsQuery
import io.github.amichne.kast.api.ApplyEditsResult
import io.github.amichne.kast.api.BackendCapabilities
import io.github.amichne.kast.api.CallHierarchyQuery
import io.github.amichne.kast.api.CallHierarchyResult
import io.github.amichne.kast.api.CapabilityNotSupportedException
import io.github.amichne.kast.api.DiagnosticsQuery
import io.github.amichne.kast.api.DiagnosticsResult
import io.github.amichne.kast.api.HealthResponse
import io.github.amichne.kast.api.LocalDiskEditApplier
import io.github.amichne.kast.api.MutationCapability
import io.github.amichne.kast.api.NotFoundException
import io.github.amichne.kast.api.ReadCapability
import io.github.amichne.kast.api.ReferencesQuery
import io.github.amichne.kast.api.ReferencesResult
import io.github.amichne.kast.api.RenameQuery
import io.github.amichne.kast.api.RenameResult
import io.github.amichne.kast.api.ServerLimits
import io.github.amichne.kast.api.SymbolQuery
import io.github.amichne.kast.api.SymbolResult
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.psi.KtFile

@OptIn(KaExperimentalApi::class)
class StandaloneAnalysisBackend(
    private val workspaceRoot: Path,
    private val limits: ServerLimits,
    private val session: StandaloneAnalysisSession,
) : AnalysisBackend {
    private val readDispatcher = Dispatchers.IO.limitedParallelism(limits.maxConcurrentRequests)

    override suspend fun capabilities(): BackendCapabilities = BackendCapabilities(
        backendName = "standalone",
        backendVersion = "0.1.0",
        workspaceRoot = workspaceRoot.toString(),
        readCapabilities = setOf(ReadCapability.RESOLVE_SYMBOL),
        mutationCapabilities = setOf(MutationCapability.APPLY_EDITS),
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

    override suspend fun resolveSymbol(query: SymbolQuery): SymbolResult = withContext(readDispatcher) {
        val file = session.findKtFile(query.position.filePath)
        val target = resolveTarget(file, query.position.offset)
        SymbolResult(analyze(file) { target.toSymbol() })
    }

    override suspend fun findReferences(query: ReferencesQuery): ReferencesResult {
        throw unsupported(ReadCapability.FIND_REFERENCES)
    }

    override suspend fun callHierarchy(query: CallHierarchyQuery): CallHierarchyResult {
        throw unsupported(ReadCapability.CALL_HIERARCHY)
    }

    override suspend fun diagnostics(query: DiagnosticsQuery): DiagnosticsResult {
        throw unsupported(ReadCapability.DIAGNOSTICS)
    }

    override suspend fun rename(query: RenameQuery): RenameResult {
        throw unsupported(MutationCapability.RENAME)
    }

    override suspend fun applyEdits(query: ApplyEditsQuery): ApplyEditsResult = LocalDiskEditApplier.apply(query)

    private fun resolveTarget(
        file: KtFile,
        offset: Int,
    ): com.intellij.psi.PsiElement {
        val leaf = file.findElementAt(offset)
            ?: throw NotFoundException(
                message = "No PSI element was found at the requested offset",
                details = mapOf("offset" to offset.toString()),
            )

        generateSequence(leaf as com.intellij.psi.PsiElement?) { element -> element.parent }.forEach { element ->
            element.references.firstNotNullOfOrNull { reference -> reference.resolve() }?.let { resolved ->
                return resolved
            }

            if (element is com.intellij.psi.PsiNamedElement && !element.name.isNullOrBlank()) {
                return element
            }
        }

        throw NotFoundException("No resolvable symbol was found at the requested offset")
    }

    private fun unsupported(capability: ReadCapability): CapabilityNotSupportedException {
        return CapabilityNotSupportedException(
            capability = capability.name,
            message = "The standalone backend is scaffolded, but $capability is not implemented yet",
        )
    }

    private fun unsupported(capability: MutationCapability): CapabilityNotSupportedException {
        return CapabilityNotSupportedException(
            capability = capability.name,
            message = "The standalone backend is scaffolded, but $capability is not implemented yet",
        )
    }
}
