package io.github.amichne.kast.server

import io.github.amichne.kast.api.ServerInstanceDescriptor
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText

class DescriptorStore(
    private val directory: Path,
) {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun write(descriptor: ServerInstanceDescriptor): Path {
        Files.createDirectories(directory)
        val path = pathFor(descriptor)
        path.writeText(json.encodeToString(ServerInstanceDescriptor.serializer(), descriptor))
        return path
    }

    fun delete(descriptor: ServerInstanceDescriptor) {
        pathFor(descriptor).deleteIfExists()
    }

    fun pathFor(descriptor: ServerInstanceDescriptor): Path {
        val identity = FileNameHasher.hash("${descriptor.backendName}:${descriptor.workspaceRoot}")
        return directory.resolve("$identity.json")
    }
}

private object FileNameHasher {
    fun hash(input: String): String = java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { "%02x".format(it) }
}
