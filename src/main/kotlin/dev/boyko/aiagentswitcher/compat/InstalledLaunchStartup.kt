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
        val loader = try {
            mlPluginClassLoader()
        } catch (error: ClassNotFoundException) {
            return@withContext
        }
        // Link the watcher only after the catalog class is present. The watcher names that class.
        try {
            Class.forName("com.intellij.ml.llm.agents.acp.api.AcpAgentsCatalog", false, loader)
        } catch (error: ClassNotFoundException) {
            return@withContext
        } catch (error: LinkageError) {
            Logger.getInstance(javaClass).info("Installed AI agent catalog is unavailable")
            return@withContext
        }
        InstalledCatalogWatcher.watch(project)
    }
}

internal object InstalledCatalogWatcher {
    suspend fun watch(project: Project) {
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
