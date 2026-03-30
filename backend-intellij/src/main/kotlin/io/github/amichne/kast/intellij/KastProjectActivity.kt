package io.github.amichne.kast.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class KastProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.getService(KastProjectService::class.java).start()
    }
}
