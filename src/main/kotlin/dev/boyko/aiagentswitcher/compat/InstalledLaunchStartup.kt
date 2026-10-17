package dev.boyko.aiagentswitcher.compat

import com.intellij.ml.llm.agents.acp.api.AcpAgentsCatalog
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.boyko.aiagentswitcher.GenericAutoRegistration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Follow native catalog changes without replacing its cache or touching live sessions. */
class InstalledLaunchStartup : ProjectActivity {
    override suspend fun execute(project: Project) = withContext(Dispatchers.IO) {
        AcpAgentsCatalog.getInstance().agentsFlow.collect {
            if (!project.isDisposed) try {
                GenericAutoRegistration.syncRoutesNow(project)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Logger.getInstance(javaClass).warn("Could not refresh installed AI Multi launches", error)
            }
        }
    }
}
