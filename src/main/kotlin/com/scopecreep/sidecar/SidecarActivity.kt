package com.scopecreep.sidecar

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class SidecarActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        SidecarManager.getInstance().startIfNeeded()
    }
}
