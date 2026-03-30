package io.github.amichne.kast.intellij

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase5
import io.github.amichne.kast.api.ServerLimits
import io.github.amichne.kast.testing.AnalysisBackendContractAssertions
import io.github.amichne.kast.testing.AnalysisBackendContractFixture
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.nio.file.Path

class IntelliJAnalysisBackendContractTest : LightJavaCodeInsightFixtureTestCase5() {
    @Test
    fun `intellij backend satisfies the shared contract fixture`() = runTest {
        val fixtureProject = createContractFixture()
        val backend = createBackend()

        AnalysisBackendContractAssertions.assertCommonContract(
            backend = backend,
            fixture = fixtureProject,
        )
    }

    @Test
    fun `intellij diagnostics report fixture syntax errors`() = runTest {
        val fixtureProject = createContractFixture()
        val backend = createBackend()

        AnalysisBackendContractAssertions.assertDiagnostics(
            backend = backend,
            fixture = fixtureProject,
        )
    }

    private fun createContractFixture(): AnalysisBackendContractFixture {
        return AnalysisBackendContractFixture.create(
            workspaceRoot = Path.of(fixture.tempDirPath),
        ) { relativePath, content ->
            Path.of(fixture.addFileToProject(relativePath, content).virtualFile.path)
        }
    }

    private fun createBackend(): IntelliJAnalysisBackend = IntelliJAnalysisBackend(
        project = project,
        limits = ServerLimits(
            maxResults = 100,
            requestTimeoutMillis = 30_000,
            maxConcurrentRequests = 4,
        ),
    )
}
