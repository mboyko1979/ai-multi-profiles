package dev.boyko.aiagentswitcher

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicReference

/** Open the affected profile only when discovery or applying its configuration fails. */
internal object ProfileSettingsPrompt {
    data class Issue(val modelId: String = "", val configId: String = "", val value: String = "", val reason: String = "")
    data class Request(val profileId: String, val message: String, val host: String = "", val modelId: String = "",
        val issues: List<Issue> = emptyList(), val liveOptions: List<ProfileConfiguration.Option> = emptyList())
    private val pending = AtomicReference<Request?>(null)
    private val showing = java.util.concurrent.atomic.AtomicBoolean()
    var onShown: ((Request) -> Unit)? = null
    private val waiting = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val failures = java.util.concurrent.ConcurrentHashMap<String, Request>()
    private fun key(profile: String, host: String) = "$profile:$host"
    fun failure(profile: String, host: String) = failures[key(profile, host)]
    fun isInvalid(profile: String, host: String, model: String, option: String, value: String? = null) =
        failure(profile, host)?.issues?.any { it.modelId == model && it.configId == option && (value == null || it.value == value) } == true
    fun clearIssues(profile: String, host: String, model: String? = null, option: String? = null) {
        val key = key(profile, host); val request = failures[key] ?: return
        val retained = request.issues.filterNot { (model == null || it.modelId == model) && (option == null || it.configId == option) }
        failures[key] = request.copy(issues = retained, liveOptions = if (model == null && option == null) emptyList() else request.liveOptions)
    }
    fun waiting(profile: String, host: String = "") = if (host.isBlank()) waiting.any { it.startsWith("$profile:") } else key(profile, host) in waiting
    fun resolved(profile: String, host: String) {
        failures.remove(key(profile, host)); waiting.remove(key(profile, host))
        pending.get()?.takeIf { it.profileId == profile && it.host == host }?.let { pending.compareAndSet(it, null) }
    }

    fun needsRefresh(profile: AgentProfiles.Entry) =
        profile.agentId.isNotBlank() && profile.defaultsAgentId != profile.agentId

    fun explains(error: Throwable) = generateSequence(error as Throwable?) { it.cause }
        .any { it.message?.contains("Refresh agent defaults") == true }

    fun message(profile: AgentProfiles.Entry) =
        "No agent defaults have been saved for ${profile.name}'s selected agent. Click Refresh agents defaults, then Apply."

    fun open(project: Project, profile: AgentProfiles.Entry, reason: String = message(profile), host: String = "",
             modelId: String = "", issues: List<Issue> = emptyList(), liveOptions: List<ProfileConfiguration.Option> = emptyList()) {
        val request = Request(profile.id, reason, host, modelId, issues, liveOptions)
        pending.set(request)
        if (host.isNotBlank()) { failures[key(profile.id, host)] = request; waiting.add(key(profile.id, host)) }
        if (!showing.compareAndSet(false, true)) {
            ApplicationManager.getApplication().invokeLater { pending.get()?.let { onShown?.invoke(it) } }
            return
        }
        ApplicationManager.getApplication().invokeLater {
            try {
                if (project.isDisposed) return@invokeLater
                pending.get()?.let { onShown?.invoke(it) }
                ShowSettingsUtil.getInstance().showSettingsDialog(project, SwitcherConfigurable::class.java)
            } finally { showing.set(false) }
        }
    }

    fun peek(): Request? = pending.get()

    fun consume(profileId: String): String? {
        val request = pending.get() ?: return null
        if (request.profileId != profileId) return null
        return if (pending.compareAndSet(request, null)) request.message else null
    }

    fun clear() { pending.set(null) }
}
