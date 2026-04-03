package io.github.amichne.kast.standalone

import com.intellij.openapi.Disposable
import com.intellij.openapi.fileTypes.BinaryFileTypeDecompilers
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.compiled.ClassFileDecompilers
import com.intellij.psi.PsiManager
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.lang.java.JavaLanguage
import com.intellij.lang.java.JavaParserDefinition
import com.intellij.lang.java.syntax.JavaElementTypeConverterExtension
import com.intellij.platform.syntax.psi.CommonElementTypeConverterFactory
import com.intellij.platform.syntax.psi.ElementTypeConverters
import io.github.amichne.kast.api.NotFoundException
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSdkModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.io.path.extension

class StandaloneAnalysisSession(
    workspaceRoot: Path,
    sourceRoots: List<Path>,
    classpathRoots: List<Path>,
    moduleName: String,
    private val initialSourceIndexBuilder: (() -> Map<String, List<String>>)? = null,
) : AutoCloseable {
    private val disposable: Disposable = Disposer.newDisposable("kast-standalone")
    private val ktFilesByPath: Map<String, KtFile> by lazy(::loadKtFilesByPath)
    private val targetedKtFilesByPath = ConcurrentHashMap<String, KtFile>()
    private val targetedCandidatePathsByLookupKey = ConcurrentHashMap<CandidateLookupKey, List<String>>()
    private val initialSourceIndex = CompletableFuture<SourceIdentifierIndex>()
    @Volatile
    private var fullKtFileMapLoaded = false

    private val session: StandaloneAnalysisAPISession
    private val sourceModuleSpecs: List<StandaloneSourceModuleSpec>
    private val dependentModuleNamesBySourceModuleName: Map<String, Set<String>>
    val sourceModules: List<KaSourceModule>
    val resolvedSourceRoots: List<Path>
    private val resolvedClasspathRoots: List<Path>

    init {
        val workspaceLayout = discoverStandaloneWorkspaceLayout(
            workspaceRoot = workspaceRoot,
            sourceRoots = sourceRoots,
            classpathRoots = classpathRoots,
            moduleName = moduleName,
        )
        require(workspaceLayout.sourceModules.isNotEmpty()) {
            "No source roots were found under ${normalizeStandalonePath(workspaceRoot)}"
        }
        sourceModuleSpecs = workspaceLayout.sourceModules
        dependentModuleNamesBySourceModuleName = buildDependentModuleNamesBySourceModuleName(sourceModuleSpecs)

        resolvedSourceRoots = workspaceLayout.sourceModules
            .flatMap { module -> module.sourceRoots }
            .distinct()
            .sorted()
        val defaultClasspathRoots = defaultClasspathRoots()
        resolvedClasspathRoots = (
            defaultClasspathRoots +
                workspaceLayout.sourceModules.flatMap { module -> module.binaryRoots }
        ).distinct().sorted()

        val jdkHome = normalizePath(Path.of(System.getProperty("java.home")))
        val createdSourceModules = mutableListOf<KaSourceModule>()
        val createdSourceModulesByName = linkedMapOf<String, KaSourceModule>()
        val createdSession = buildStandaloneAnalysisAPISession(
            projectDisposable = disposable,
            unitTestMode = false,
        ) {
            initializeJavaSourceSupport()
            buildKtModuleProvider {
                val platform = JvmPlatforms.defaultJvmPlatform
                val sdkModule = buildKtSdkModule {
                    this.platform = platform
                    addBinaryRootsFromJdkHome(jdkHome, isJre = false)
                    libraryName = "JDK for ${workspaceLayout.sourceModules.first().name}"
                }

                for (moduleSpec in topologicallySortSourceModules(workspaceLayout.sourceModules)) {
                    val libraryModule = buildLibraryModule(
                        moduleName = moduleSpec.name,
                        platform = platform,
                        binaryRoots = (defaultClasspathRoots + moduleSpec.binaryRoots).distinct().sorted(),
                    )
                    val builtSourceModule = buildKtSourceModule {
                        this.platform = platform
                        this.moduleName = moduleSpec.name
                        moduleSpec.sourceRoots.forEach(::addSourceRoot)
                        addRegularDependency(sdkModule)
                        libraryModule?.let(::addRegularDependency)
                        moduleSpec.dependencyModuleNames.forEach { dependencyName ->
                            addRegularDependency(
                                checkNotNull(createdSourceModulesByName[dependencyName]) {
                                    "The standalone session could not resolve source module dependency $dependencyName for ${moduleSpec.name}"
                                },
                            )
                        }
                    }
                    addModule(builtSourceModule)
                    createdSourceModulesByName[moduleSpec.name] = builtSourceModule
                    createdSourceModules += builtSourceModule
                }
                this.platform = platform
            }
        }

        session = createdSession
        initializeJvmDecompilerServices()
        sourceModules = createdSourceModules
        check(sourceModules.isNotEmpty()) {
            "The standalone Analysis API session did not create any source modules"
        }
        startInitialSourceIndex()
    }

    fun allKtFiles(): List<KtFile> = ktFilesByPath.values.sortedBy(::normalizeFileLookupPath)

    fun findKtFile(filePath: String): KtFile {
        val normalizedPath = normalizePath(Path.of(filePath)).toString()
        if (fullKtFileMapLoaded) {
            return ktFilesByPath[normalizedPath]
                ?: throw NotFoundException(
                    message = "The requested file is not part of the standalone analysis session",
                    details = mapOf("filePath" to normalizedPath),
                )
        }

        return targetedKtFilesByPath[normalizedPath]
            ?: loadKtFileByPath(normalizedPath)
                ?.also { file -> targetedKtFilesByPath[normalizedPath] = file }
            ?: throw NotFoundException(
                message = "The requested file is not part of the standalone analysis session",
                details = mapOf("filePath" to normalizedPath),
            )
    }

    internal fun awaitInitialSourceIndex() {
        initialSourceIndex.join()
    }

    internal fun candidateKotlinFilePaths(identifier: String): List<String> {
        return candidateKotlinFilePaths(identifier = identifier, anchorFilePath = null)
    }

    internal fun candidateKotlinFilePaths(
        identifier: String,
        anchorFilePath: String?,
    ): List<String> {
        if (!identifier.isIndexableIdentifier()) {
            return emptyList()
        }

        val anchorSourceModuleName = anchorFilePath?.let { filePath ->
            sourceModuleNameForFile(normalizePath(Path.of(filePath)).toString())
        }
        val lookupKey = CandidateLookupKey(
            identifier = identifier,
            anchorSourceModuleName = anchorSourceModuleName,
        )

        val readyIndex = initialSourceIndex.getNow(null)
        if (readyIndex != null) {
            return filterCandidatePathsByAnchorScope(
                candidatePaths = readyIndex.candidatePathsByIdentifier[identifier].orEmpty(),
                anchorSourceModuleName = anchorSourceModuleName,
            )
        }

        return targetedCandidatePathsByLookupKey.computeIfAbsent(lookupKey) { key ->
            buildTargetedCandidatePaths(
                identifier = key.identifier,
                anchorSourceModuleName = key.anchorSourceModuleName,
            )
        }
    }

    internal fun isInitialSourceIndexReady(): Boolean = initialSourceIndex.isDone

    override fun close() {
        Disposer.dispose(disposable)
    }

    /**
     * `ClassFileDecompilers` notifies `BinaryFileTypeDecompilers` on extension changes.
     * If the binary decompiler service is still lazy when the application starts disposing,
     * IntelliJ tries to instantiate it under an already-disposed parent and fails loudly.
     */
    private fun initializeJvmDecompilerServices() {
        ClassFileDecompilers.getInstance()
        BinaryFileTypeDecompilers.getInstance()
    }

    private fun initializeJavaSourceSupport() {
        val parserDefinitions = LanguageParserDefinitions.INSTANCE
        if (parserDefinitions.forLanguage(JavaLanguage.INSTANCE) == null) {
            val parserDefinition = JavaParserDefinition()
            parserDefinitions.addExplicitExtension(JavaLanguage.INSTANCE, parserDefinition)
            Disposer.register(disposable) {
                parserDefinitions.removeExplicitExtension(JavaLanguage.INSTANCE, parserDefinition)
            }
        }

        val converterFactories = ElementTypeConverters.instance
        var addedConverterFactory = false
        if (converterFactories.allForLanguage(JavaLanguage.INSTANCE).none { factory ->
                factory is CommonElementTypeConverterFactory
            }
        ) {
            val commonConverterFactory = CommonElementTypeConverterFactory()
            converterFactories.addExplicitExtension(JavaLanguage.INSTANCE, commonConverterFactory)
            addedConverterFactory = true
            Disposer.register(disposable) {
                converterFactories.removeExplicitExtension(JavaLanguage.INSTANCE, commonConverterFactory)
            }
        }
        if (converterFactories.allForLanguage(JavaLanguage.INSTANCE).none { factory ->
                factory is JavaElementTypeConverterExtension
            }
        ) {
            val converterFactory = JavaElementTypeConverterExtension()
            converterFactories.addExplicitExtension(JavaLanguage.INSTANCE, converterFactory)
            addedConverterFactory = true
            Disposer.register(disposable) {
                converterFactories.removeExplicitExtension(JavaLanguage.INSTANCE, converterFactory)
            }
        }
        if (addedConverterFactory) {
            converterFactories.clearCache()
        }
    }

    private fun normalizeFileLookupPath(file: KtFile): String {
        val virtualPath = file.virtualFile?.path
            ?: throw NotFoundException("The standalone analysis session produced a KtFile without a virtual path")
        return normalizePath(Path.of(virtualPath)).toString()
    }

    private fun loadKtFilesByPath(): Map<String, KtFile> = sourceModules
        .asSequence()
        .flatMap { sourceModule -> session.modulesWithFiles[sourceModule].orEmpty().asSequence() }
        .filterIsInstance<KtFile>()
        .associateBy(::normalizeFileLookupPath)
        .also { loadedFiles ->
            fullKtFileMapLoaded = true
            targetedKtFilesByPath.putAll(loadedFiles)
        }

    private fun loadKtFileByPath(normalizedPath: String): KtFile? {
        val filePath = Path.of(normalizedPath)
        if (filePath.extension != "kt" || resolvedSourceRoots.none(filePath::startsWith)) {
            return null
        }

        val virtualFile = VirtualFileManager.getInstance().findFileByNioPath(filePath)
            ?: VirtualFileManager.getInstance().refreshAndFindFileByNioPath(filePath)
            ?: return null

        return PsiManager.getInstance(session.project)
            .findFile(virtualFile) as? KtFile
    }

    private fun startInitialSourceIndex() {
        thread(
            start = true,
            isDaemon = true,
            name = "kast-initial-source-index",
        ) {
            runCatching {
                initialSourceIndexBuilder
                    ?.invoke()
                    ?.let(::SourceIdentifierIndex)
                    ?: buildSourceIdentifierIndex()
            }
                .onSuccess(initialSourceIndex::complete)
                .onFailure(initialSourceIndex::completeExceptionally)
        }
    }

    private fun sourceIdentifierIndex(): SourceIdentifierIndex = initialSourceIndex.getNow(null) ?: initialSourceIndex.join()

    private fun buildSourceIdentifierIndex(): SourceIdentifierIndex {
        val candidatePathsByIdentifier = linkedMapOf<String, MutableSet<String>>()

        resolvedSourceRoots.forEach { sourceRoot ->
            if (!Files.isDirectory(sourceRoot)) {
                return@forEach
            }

            Files.walk(sourceRoot).use { paths ->
                paths
                    .filter { path -> Files.isRegularFile(path) && path.extension == "kt" }
                    .forEach { file ->
                        val normalizedFilePath = normalizePath(file).toString()
                        val identifiers = identifierRegex.findAll(Files.readString(file)).map { match -> match.value }.toSet()
                        identifiers.forEach { identifier ->
                            candidatePathsByIdentifier
                                .getOrPut(identifier) { linkedSetOf() }
                                .add(normalizedFilePath)
                        }
                    }
            }
        }

        return SourceIdentifierIndex(
            candidatePathsByIdentifier = candidatePathsByIdentifier
                .mapValues { (_, filePaths) -> filePaths.toList().sorted() },
        )
    }

    private fun buildTargetedCandidatePaths(identifier: String): List<String> = buildList {
        resolvedSourceRoots.forEach { sourceRoot ->
            if (!Files.isDirectory(sourceRoot)) {
                return@forEach
            }

            Files.walk(sourceRoot).use { paths ->
                paths
                    .filter { path -> Files.isRegularFile(path) && path.extension == "kt" }
                    .forEach { file ->
                        val content = Files.readString(file)
                        if (content.identifierOccurrenceOffsets(identifier).any()) {
                            add(normalizePath(file).toString())
                        }
                    }
            }
        }
    }.distinct().sorted()

    private fun buildTargetedCandidatePaths(
        identifier: String,
        anchorSourceModuleName: String?,
    ): List<String> = buildList {
        val allowedSourceModuleNames = anchorSourceModuleName
            ?.let(dependentModuleNamesBySourceModuleName::get)

        sourceModuleSpecs
            .asSequence()
            .filter { moduleSpec -> allowedSourceModuleNames == null || moduleSpec.name in allowedSourceModuleNames }
            .flatMap { moduleSpec -> moduleSpec.sourceRoots.asSequence() }
            .distinct()
            .sorted()
            .forEach { sourceRoot ->
                if (!Files.isDirectory(sourceRoot)) {
                    return@forEach
                }

                Files.walk(sourceRoot).use { paths ->
                    paths
                        .filter { path -> Files.isRegularFile(path) && path.extension == "kt" }
                        .forEach { file ->
                            val content = Files.readString(file)
                            if (content.identifierOccurrenceOffsets(identifier).any()) {
                                add(normalizePath(file).toString())
                            }
                        }
                }
            }
    }.distinct().sorted()

    private fun filterCandidatePathsByAnchorScope(
        candidatePaths: List<String>,
        anchorSourceModuleName: String?,
    ): List<String> {
        if (anchorSourceModuleName == null) {
            return candidatePaths
        }

        val allowedSourceModuleNames = dependentModuleNamesBySourceModuleName[anchorSourceModuleName].orEmpty()
        return candidatePaths.filter { candidatePath ->
            sourceModuleNameForFile(candidatePath) in allowedSourceModuleNames
        }
    }

    private fun sourceModuleNameForFile(normalizedPath: String): String? {
        val filePath = Path.of(normalizedPath)
        return sourceModuleSpecs.firstOrNull { moduleSpec ->
            moduleSpec.sourceRoots.any(filePath::startsWith)
        }?.name
    }

    private fun org.jetbrains.kotlin.analysis.project.structure.builder.KtModuleProviderBuilder.buildLibraryModule(
        moduleName: String,
        platform: org.jetbrains.kotlin.platform.TargetPlatform,
        binaryRoots: List<Path>,
    ) = binaryRoots
        .takeIf(List<Path>::isNotEmpty)
        ?.let { roots ->
            buildKtLibraryModule {
                this.platform = platform
                addBinaryRoots(roots)
                libraryName = "Library for $moduleName"
            }
        }

    private fun defaultClasspathRoots(): List<Path> = buildList {
        classpathRootOf(Unit::class.java)?.let(::add)
    }.distinct()

    private fun classpathRootOf(type: Class<*>): Path? {
        val location = type.protectionDomain?.codeSource?.location ?: return null
        return runCatching { normalizePath(Path.of(location.toURI())) }.getOrNull()
    }

    private fun normalizePath(path: Path): Path {
        return normalizeStandalonePath(path)
    }
}

internal data class SourceIdentifierIndex(
    val candidatePathsByIdentifier: Map<String, List<String>>,
)

private data class CandidateLookupKey(
    val identifier: String,
    val anchorSourceModuleName: String?,
)

private val identifierRegex = Regex("""\b[A-Za-z_][A-Za-z0-9_]*\b""")

private fun String.isIndexableIdentifier(): Boolean = identifierRegex.matches(this)

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

private fun buildDependentModuleNamesBySourceModuleName(
    sourceModules: List<StandaloneSourceModuleSpec>,
): Map<String, Set<String>> {
    val reverseDependencies = linkedMapOf<String, MutableSet<String>>()
    sourceModules.forEach { sourceModule ->
        sourceModule.dependencyModuleNames.forEach { dependencyModuleName ->
            reverseDependencies.getOrPut(dependencyModuleName) { linkedSetOf() }.add(sourceModule.name)
        }
    }

    return sourceModules.associate { sourceModule ->
        val visitedModuleNames = linkedSetOf(sourceModule.name)
        val pendingModuleNames = ArrayDeque(listOf(sourceModule.name))
        while (pendingModuleNames.isNotEmpty()) {
            val currentModuleName = pendingModuleNames.removeFirst()
            reverseDependencies[currentModuleName].orEmpty().forEach { dependentModuleName ->
                if (visitedModuleNames.add(dependentModuleName)) {
                    pendingModuleNames += dependentModuleName
                }
            }
        }
        sourceModule.name to visitedModuleNames.toSet()
    }
}

internal data class StandaloneWorkspaceLayout(
    val sourceModules: List<StandaloneSourceModuleSpec>,
)

internal data class StandaloneSourceModuleSpec(
    val name: String,
    val sourceRoots: List<Path>,
    val binaryRoots: List<Path>,
    val dependencyModuleNames: List<String>,
)

internal fun discoverStandaloneWorkspaceLayout(
    workspaceRoot: Path,
    sourceRoots: List<Path>,
    classpathRoots: List<Path>,
    moduleName: String,
): StandaloneWorkspaceLayout {
    val normalizedWorkspaceRoot = normalizeStandalonePath(workspaceRoot)
    if (sourceRoots.isNotEmpty()) {
        return StandaloneWorkspaceLayout(
            sourceModules = listOf(
                StandaloneSourceModuleSpec(
                    name = moduleName,
                    sourceRoots = normalizeStandaloneSourceRoots(sourceRoots),
                    binaryRoots = normalizeStandalonePaths(classpathRoots),
                    dependencyModuleNames = emptyList(),
                ),
            ),
        )
    }
    if (looksLikeGradleWorkspace(normalizedWorkspaceRoot)) {
        return GradleWorkspaceDiscovery.discover(
            workspaceRoot = normalizedWorkspaceRoot,
            extraClasspathRoots = normalizeStandalonePaths(classpathRoots),
        )
    }

    return StandaloneWorkspaceLayout(
        sourceModules = listOf(
            StandaloneSourceModuleSpec(
                name = moduleName,
                sourceRoots = discoverSourceRoots(normalizedWorkspaceRoot),
                binaryRoots = normalizeStandalonePaths(classpathRoots),
                dependencyModuleNames = emptyList(),
            ),
        ),
    )
}

internal fun normalizeStandalonePath(path: Path): Path {
    val absolutePath = path.toAbsolutePath().normalize()
    return runCatching { absolutePath.toRealPath().normalize() }.getOrDefault(absolutePath)
}

internal fun normalizeStandaloneModelPath(path: Path): Path = path.toAbsolutePath().normalize()

internal fun normalizeStandalonePaths(paths: Iterable<Path>): List<Path> = paths
    .map(::normalizeStandalonePath)
    .distinct()
    .sorted()

internal fun normalizeStandaloneSourceRoots(paths: Iterable<Path>): List<Path> = paths
    .map(::normalizeStandalonePath)
    .distinct()
    .sorted()

private fun discoverSourceRoots(workspaceRoot: Path): List<Path> {
    val conventionalRoots = listOf(
        workspaceRoot.resolve("src/main/kotlin"),
        workspaceRoot.resolve("src/main/java"),
        workspaceRoot.resolve("src/test/kotlin"),
        workspaceRoot.resolve("src/test/java"),
    ).filter(Files::isDirectory)
    if (conventionalRoots.isNotEmpty()) {
        return conventionalRoots.map(::normalizeStandalonePath).distinct().sorted()
    }

    val discoveredRoots = linkedSetOf<Path>()
    Files.walk(workspaceRoot).use { paths ->
        paths
            .filter { path ->
                Files.isRegularFile(path) && path.extension in setOf("kt", "kts", "java")
            }
            .forEach { file -> discoveredRoots.add(normalizeStandalonePath(file.parent)) }
    }
    return discoveredRoots.toList().sorted()
}

private fun looksLikeGradleWorkspace(workspaceRoot: Path): Boolean = listOf(
    "settings.gradle.kts",
    "settings.gradle",
    "build.gradle.kts",
    "build.gradle",
).any { fileName -> Files.isRegularFile(workspaceRoot.resolve(fileName)) }

private fun topologicallySortSourceModules(sourceModules: List<StandaloneSourceModuleSpec>): List<StandaloneSourceModuleSpec> {
    val sourceModulesByName = sourceModules.associateBy(StandaloneSourceModuleSpec::name)
    val incomingEdges = sourceModules.associate { module ->
        module.name to module.dependencyModuleNames.toMutableSet()
    }.toMutableMap()
    val outgoingEdges = linkedMapOf<String, MutableSet<String>>()
    for (module in sourceModules) {
        for (dependencyName in module.dependencyModuleNames) {
            require(sourceModulesByName.containsKey(dependencyName)) {
                "The standalone workspace layout referenced an unknown source module dependency $dependencyName"
            }
            outgoingEdges.getOrPut(dependencyName) { linkedSetOf() }.add(module.name)
        }
    }

    val readyNames = ArrayDeque(
        sourceModules
            .filter { module -> incomingEdges.getValue(module.name).isEmpty() }
            .map(StandaloneSourceModuleSpec::name)
            .sorted(),
    )
    val orderedModules = mutableListOf<StandaloneSourceModuleSpec>()
    while (readyNames.isNotEmpty()) {
        val moduleName = readyNames.removeFirst()
        orderedModules += checkNotNull(sourceModulesByName[moduleName])
        for (dependentName in outgoingEdges[moduleName].orEmpty().sorted()) {
            val dependencies = incomingEdges.getValue(dependentName)
            dependencies.remove(moduleName)
            if (dependencies.isEmpty()) {
                readyNames.addLast(dependentName)
            }
        }
    }

    require(orderedModules.size == sourceModules.size) {
        val unresolvedModuleNames = incomingEdges
            .filterValues(Set<String>::isNotEmpty)
            .keys
            .sorted()
        "The standalone workspace layout contains cyclic source module dependencies: ${unresolvedModuleNames.joinToString(", ")}"
    }
    return orderedModules
}
