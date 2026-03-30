package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.ServerLimits
import io.github.amichne.kast.server.AnalysisServer
import io.github.amichne.kast.server.AnalysisServerConfig
import java.nio.file.Path
import kotlin.io.path.Path

fun main(args: Array<String>) {
    val cli = StandaloneCliArguments.parse(args)
    val backend = StandaloneAnalysisBackend(
        workspaceRoot = cli.workspaceRoot,
        limits = ServerLimits(
            maxResults = cli.maxResults,
            requestTimeoutMillis = cli.requestTimeoutMillis,
            maxConcurrentRequests = cli.maxConcurrentRequests,
        ),
    )
    val server = AnalysisServer(
        backend = backend,
        config = AnalysisServerConfig(
            host = cli.host,
            port = cli.port,
            token = cli.token,
            requestTimeoutMillis = cli.requestTimeoutMillis,
            maxResults = cli.maxResults,
            maxConcurrentRequests = cli.maxConcurrentRequests,
        ),
    ).start()

    Runtime.getRuntime().addShutdownHook(
        Thread {
            server.close()
        },
    )

    println("kast standalone listening on ${server.descriptor.host}:${server.descriptor.port}")
    println("descriptor: ${server.descriptor}")
    Thread.currentThread().join()
}

private data class StandaloneCliArguments(
    val workspaceRoot: Path,
    val host: String,
    val port: Int,
    val token: String?,
    val requestTimeoutMillis: Long,
    val maxResults: Int,
    val maxConcurrentRequests: Int,
) {
    companion object {
        fun parse(args: Array<String>): StandaloneCliArguments {
            val values = args.associate { argument ->
                val parts = argument.removePrefix("--").split("=", limit = 2)
                if (parts.size != 2) {
                    error("Arguments must use --key=value syntax: $argument")
                }
                parts[0] to parts[1]
            }

            return StandaloneCliArguments(
                workspaceRoot = Path(
                    values["workspace-root"]
                        ?: System.getenv("KAST_WORKSPACE_ROOT")
                        ?: System.getProperty("user.dir"),
                ).toAbsolutePath().normalize(),
                host = values["host"] ?: "127.0.0.1",
                port = values["port"]?.toInt() ?: 0,
                token = values["token"] ?: System.getenv("KAST_TOKEN"),
                requestTimeoutMillis = values["request-timeout-ms"]?.toLong() ?: 30_000L,
                maxResults = values["max-results"]?.toInt() ?: 500,
                maxConcurrentRequests = values["max-concurrent-requests"]?.toInt() ?: 4,
            )
        }
    }
}
