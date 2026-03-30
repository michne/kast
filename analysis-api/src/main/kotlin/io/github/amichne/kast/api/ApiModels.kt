package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

const val SCHEMA_VERSION: Int = 1

@Serializable
data class FilePosition(
    val filePath: String,
    val offset: Int,
)

@Serializable
data class Location(
    val filePath: String,
    val startOffset: Int,
    val endOffset: Int,
    val startLine: Int,
    val startColumn: Int,
    val preview: String,
)

@Serializable
data class Symbol(
    val fqName: String,
    val kind: SymbolKind,
    val location: Location,
    val type: String? = null,
    val containingDeclaration: String? = null,
)

@Serializable
enum class SymbolKind {
    CLASS,
    INTERFACE,
    OBJECT,
    FUNCTION,
    PROPERTY,
    CONSTRUCTOR,
    ENUM_ENTRY,
    TYPE_ALIAS,
    PACKAGE,
    PARAMETER,
    LOCAL_VARIABLE,
    UNKNOWN,
}

@Serializable
data class SymbolQuery(
    val position: FilePosition,
)

@Serializable
data class ReferencesQuery(
    val position: FilePosition,
    val includeDeclaration: Boolean = false,
)

@Serializable
enum class CallDirection {
    INCOMING,
    OUTGOING,
}

@Serializable
data class CallHierarchyQuery(
    val position: FilePosition,
    val direction: CallDirection,
    val depth: Int = 3,
)

@Serializable
data class DiagnosticsQuery(
    val filePaths: List<String>,
)

@Serializable
data class RenameQuery(
    val position: FilePosition,
    val newName: String,
    val dryRun: Boolean = true,
)

@Serializable
data class TextEdit(
    val filePath: String,
    val startOffset: Int,
    val endOffset: Int,
    val newText: String,
)

@Serializable
data class FileHash(
    val filePath: String,
    val hash: String,
)

@Serializable
data class ApplyEditsQuery(
    val edits: List<TextEdit>,
    val fileHashes: List<FileHash>,
)

@Serializable
data class PageInfo(
    val truncated: Boolean,
    val nextPageToken: String? = null,
)

@Serializable
data class SymbolResult(
    val symbol: Symbol,
    val schemaVersion: Int = SCHEMA_VERSION,
)

@Serializable
data class ReferencesResult(
    val declaration: Symbol? = null,
    val references: List<Location>,
    val page: PageInfo? = null,
    val schemaVersion: Int = SCHEMA_VERSION,
)

@Serializable
data class CallNode(
    val symbol: Symbol,
    val children: List<CallNode>,
)

@Serializable
data class CallHierarchyResult(
    val root: CallNode,
    val schemaVersion: Int = SCHEMA_VERSION,
)

@Serializable
enum class DiagnosticSeverity {
    ERROR,
    WARNING,
    INFO,
}

@Serializable
data class Diagnostic(
    val location: Location,
    val severity: DiagnosticSeverity,
    val message: String,
    val code: String? = null,
)

@Serializable
data class DiagnosticsResult(
    val diagnostics: List<Diagnostic>,
    val page: PageInfo? = null,
    val schemaVersion: Int = SCHEMA_VERSION,
)

@Serializable
data class RenameResult(
    val edits: List<TextEdit>,
    val fileHashes: List<FileHash>,
    val affectedFiles: List<String>,
    val schemaVersion: Int = SCHEMA_VERSION,
)

@Serializable
data class ApplyEditsResult(
    val applied: List<TextEdit>,
    val affectedFiles: List<String>,
    val schemaVersion: Int = SCHEMA_VERSION,
)

@Serializable
enum class ReadCapability {
    RESOLVE_SYMBOL,
    FIND_REFERENCES,
    CALL_HIERARCHY,
    DIAGNOSTICS,
}

@Serializable
enum class MutationCapability {
    RENAME,
    APPLY_EDITS,
}

@Serializable
data class ServerLimits(
    val maxResults: Int,
    val requestTimeoutMillis: Long,
    val maxConcurrentRequests: Int,
)

@Serializable
data class BackendCapabilities(
    val backendName: String,
    val backendVersion: String,
    val workspaceRoot: String,
    val readCapabilities: Set<ReadCapability>,
    val mutationCapabilities: Set<MutationCapability>,
    val limits: ServerLimits,
    val schemaVersion: Int = SCHEMA_VERSION,
)

@Serializable
data class HealthResponse(
    val status: String = "ok",
    val backendName: String,
    val backendVersion: String,
    val workspaceRoot: String,
    val schemaVersion: Int = SCHEMA_VERSION,
)

@Serializable
data class ApiErrorResponse(
    val schemaVersion: Int = SCHEMA_VERSION,
    val requestId: String,
    val code: String,
    val message: String,
    val retryable: Boolean,
    val details: Map<String, String> = emptyMap(),
)

@Serializable
data class ServerInstanceDescriptor(
    val workspaceRoot: String,
    val backendName: String,
    val backendVersion: String,
    val host: String,
    val port: Int,
    val token: String? = null,
    val pid: Long = ProcessHandle.current().pid(),
    val schemaVersion: Int = SCHEMA_VERSION,
)
