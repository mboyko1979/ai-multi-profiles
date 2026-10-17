package dev.boyko.aiagentswitcher

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/** First-use capture uses the same isolated discovery as the Settings Refresh action. */
internal object ProfileDefaultsBootstrap {
    private val locks = ConcurrentHashMap<String, Any>()

    fun ensure(project: Project, profileId: String, agentId: String) = synchronized(locks.computeIfAbsent(profileId) { Any() }) {
        repeat(3) {
            val original = service<AgentProfiles>().find(profileId) ?: error("Profile was removed")
            check(original.agentId == agentId) { "The profile agent changed during defaults discovery. Try again with the selected agent." }
            if (!ProfileSettingsPrompt.needsRefresh(original)) return@synchronized
            val prepared = ProfileDefaultsProbe.prepare(project, original)
            ProfileDefaultsProbe.discoverClients(project, prepared)
            if (GenericAutoRegistration.captureDefaults(project, original, prepared)) {
                ApplicationManager.getApplication().saveSettings()
                return@synchronized
            }
        }
        error("Profile settings changed during defaults discovery. Try again after finishing the profile edit.")
    }
}
