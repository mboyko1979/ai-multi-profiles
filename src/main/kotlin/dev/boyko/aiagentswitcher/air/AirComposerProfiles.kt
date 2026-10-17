@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package dev.boyko.aiagentswitcher.air

import com.intellij.air.acp.AcpSessionRuntimeAdapter
import com.intellij.air.acp.api.*
import com.intellij.air.backend.session.api.AgentSessionRuntimeAdapters
import com.intellij.air.backend.session.view.gui.BackendThreadGuiPreparedSessionApi
import com.intellij.air.frontend.session.preferences.PreparedSessionLease
import com.intellij.air.frontend.session.preferences.ThreadGuiPreparedSessionLeaseService
import com.intellij.air.frontend.session.preferences.ThreadGuiPreparedSessionLeaseServiceImpl
import com.intellij.air.shared.core.thread.acpAgentLaunchTargetId
import com.intellij.air.shared.core.thread.acpCatalogAgentId
import com.intellij.air.shared.prompt.AgentPromptGenerationPreferences
import com.intellij.air.shared.prompt.AgentPromptLaunchProfile
import com.intellij.air.shared.prompt.AirSessionPreferencesSeedKind
import com.intellij.air.shared.session.FOLDED_ACP_AGENTS
import com.intellij.air.shared.session.view.gui.ThreadGuiApi
import com.intellij.air.shared.session.view.gui.ThreadGuiPreparedSessionRequest
import com.intellij.air.shared.session.view.gui.ThreadGuiRef
import com.intellij.air.shared.session.view.gui.ThreadGuiSendPromptRequest
import com.intellij.air.shared.session.view.gui.ThreadGuiSendPromptResult
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.boyko.aiagentswitcher.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.WeakHashMap

/** Profile choice is composer-local state; it never requires a protocol session. */
internal object AirComposerProfiles {
    private const val PROFILE_ORIGIN = "ai-multi-profile:"
    private val native get() = NativeRecoveryContinuation
    private val reportedUnsupportedApi = java.util.concurrent.atomic.AtomicBoolean()
    private class Selection(val project: Project, @Volatile var profile: String)
    private val selections = WeakHashMap<Any, Selection>()
    data class Composer internal constructor(val draft: Any, val children: Any, val preferences: Any, val scope: CoroutineScope)

    fun composer(context: DataContext): Composer? = runCatching {
        val tabType = native.type("com.intellij.air.frontend.session.view.api.AgentSessionViewTabContextKt", "com.intellij.air") ?: return null
        @Suppress("UNCHECKED_CAST") val tabKey = tabType.getField("AGENT_SESSION_VIEW_TAB_CONTEXT").get(null) as DataKey<Any>
        val tab = tabKey.getData(context) ?: return null
        if (!(tab.javaClass.getField("selectedThreadId").get(tab) as? String).isNullOrBlank()) return null
        val keys = native.type("com.intellij.air.frontend.prompt.composer.actions.toolbar.AirPromptSelectorKeys", "com.intellij.air") ?: return null
        @Suppress("UNCHECKED_CAST") val key = native.call(keys.getField("INSTANCE").get(null), "getSELECTORS") as DataKey<Map<*, *>>
        val selector = key.getData(context)?.entries?.singleOrNull { it.key.toString() == "Agent" }?.value ?: return null
        val state = native.call(selector, "getState") ?: return null
        val nodes = native.call(native.call(state, "getActions")!!, "getNodes") as Map<*, *>
        val chosen = nodes["Air.Prompt.Select.Agent/acp.dynamic.6163702e61692d6d756c7469"]
            ?: nodes["Air.Prompt.Select.Agent/acp.ai-multi"] ?: return null
        val commands = native.field(state, "commandService")!!
        val draft = native.field(native.field(commands, "selection")!!, "draft")!!
        if (native.call(chosen, "getSelected") != true) {
            val snapshot = (native.call(draft, "getState") as StateFlow<*>).value ?: return null
            // Bound-agent seed uses that agent's native cache/icon; origin still marks this AI Multi composer.
            if ((native.call(snapshot, "getOrigin-") as? String)?.startsWith(PROFILE_ORIGIN) != true) return null
        }
        val preferences = native.field(commands, "preferences")!!
        val host = native.call(preferences, "getPreferences")!!
        Composer(draft, native.field(host, "childFactory")!!, preferences, native.field(commands, "scope") as CoroutineScope)
    }.onFailure {
        val cause = (it as? java.lang.reflect.InvocationTargetException)?.targetException ?: it
        if (cause is com.intellij.openapi.diagnostic.ControlFlowException || cause is java.util.concurrent.CancellationException) throw cause
        if (reportedUnsupportedApi.compareAndSet(false, true))
            com.intellij.openapi.diagnostic.Logger.getInstance(AirComposerProfiles::class.java)
                .warn("Air composer profile binding is unavailable", cause)
    }.getOrNull()

    private fun selection(project: Project, composer: Composer): Selection = synchronized(selections) {
        selections.getOrPut(composer.draft) { Selection(project, project.service<ProjectSelection>().selected) }
    }
    fun selected(project: Project, composer: Composer) = selection(project, composer).profile

    /** Runs on the native action's EDT. This modifies only this composer's launch binding. */
    fun choose(project: Project, composer: Composer, id: String) {
        val state = selection(project, composer)
        if (state.profile == id) return
        val previous = state.profile
        state.profile = id
        try {
            apply(project, composer)
        } catch (error: Exception) {
            state.profile = previous
            throw error
        }
    }

    /** Toolbar updates can run off the EDT; Air's draft pick must not. */
    fun bind(project: Project, composer: Composer) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) apply(project, composer)
        else app.invokeLater { if (!project.isDisposed) apply(project, composer) }
    }

    private fun apply(project: Project, composer: Composer) {
        val id = selected(project, composer)
        val launch = runCatching { ProfileLaunch.resolve(id) }.getOrNull() ?: return
        val state = selection(project, composer)
        val original = native.field(composer.children, "leaseService") as ThreadGuiPreparedSessionLeaseService
        if (original !is ProfileLease) {
            // Pinned Air 262 instance dependency, not an application service or extension replacement.
            composer.children.javaClass.getDeclaredField("leaseService").apply { isAccessible = true }
                .set(composer.children, ProfileLease(original, state, composer))
        }
        val snapshot = (native.call(composer.draft, "getState") as StateFlow<*>).value ?: return
        val origin = native.call(snapshot, "getOrigin-") as? String
        val base = native.call(snapshot, "getProfile") as AgentPromptLaunchProfile
        val bound = launch.profile.agentId
        if (origin == PROFILE_ORIGIN + id && (bound.isBlank() || base.agentId == catalogAgentId(bound))) return
        native.call(composer.draft, "pick-", seed(base, launch.profile), PROFILE_ORIGIN + id,
            AirSessionPreferencesSeedKind.Preset, false, null)
        native.call(composer.preferences, "requestChildStart")
    }

    /** Air keys its metadata cache by agent/route. Use the bound agent; do not add a plugin cache. */
    private fun catalogAgentId(agent: String) = FOLDED_ACP_AGENTS[agent] ?: acpCatalogAgentId(agent)

    private fun seed(base: AgentPromptLaunchProfile, profile: AgentProfiles.Entry): AgentPromptLaunchProfile {
        val prefs = AgentPromptGenerationPreferences()
        val agent = profile.agentId
        if (agent.isBlank()) return base.copy(generationPreferences = prefs, sessionModeId = null, planMode = false)
        return base.copy(
            name = GenericAutoRegistration.AGENT,
            // Air folds registry agents such as Codex into its native catalog ID.
            // A synthesized dynamic ID is absent from the launch catalog for those agents.
            agentId = catalogAgentId(agent) ?: error("The profile's native ACP agent ID is invalid"),
            launchTargetId = acpAgentLaunchTargetId(agent),
            generationPreferences = prefs,
            sessionModeId = null,
            planMode = false,
        )
    }

    /** Air's own lease/state machine, with only this request's ACP launch arguments bound. */
    private class ProfileLease(val original: ThreadGuiPreparedSessionLeaseService, val selection: Selection, composer: Composer) : ThreadGuiPreparedSessionLeaseService {
        private class Retained(val scope: CoroutineScope, val child: Job?) {
            lateinit var lease: StateFlow<PreparedSessionLease>
        }
        private val owner = composer.scope
        private val draft = composer.draft
        private val prepared = mutableMapOf<String, Retained>()

        init {
            owner.launch {
                @Suppress("UNCHECKED_CAST")
                (native.call(composer.draft, "getState") as StateFlow<Any>).collect { snapshot ->
                    if ((native.call(snapshot, "getOrigin-") as? String)?.startsWith(PROFILE_ORIGIN) != true) {
                        synchronized(prepared) { prepared.values.toList().also { prepared.clear() } }.forEach { it.scope.cancel() }
                    }
                }
            }
        }

        override fun open(scope: CoroutineScope, request: ThreadGuiPreparedSessionRequest): StateFlow<PreparedSessionLease> {
            val snapshot = (native.call(draft, "getState") as StateFlow<*>).value!!
            if ((native.call(snapshot, "getOrigin-") as? String)?.startsWith(PROFILE_ORIGIN) != true)
                return original.open(scope, request)
            val launch = ProfileLaunch.resolve(selection.profile)
            // Native owner scopes remain alive across child replacement. These
            // entries retain real leases, never metadata or substitute readiness.
            val key = launch.profileId + ":" + ProfileConfiguration.digest(com.google.gson.Gson().toJson(launch.profile)) + ":" + request.route + ":" + request.destination + ":" + request.sourceId + ":" + request.projectDirectory + ":" + request.source
            synchronized(prepared) {
                prepared[key]?.takeIf { it.scope.isActive && it.lease.value !is PreparedSessionLease.Failed }?.let { return it.lease }
                prepared.remove(key)?.scope?.cancel()
            }
            val retained = Retained(CoroutineScope(owner.coroutineContext + SupervisorJob(owner.coroutineContext[Job])), scope.coroutineContext[Job])
            val ordinary = AcpSessionRuntimeAdapter()
            @Suppress("UNCHECKED_CAST")
            val resolve = native.field(ordinary, "resolveLaunchableEntry") as suspend (AcpAgentsCatalog, AcpAgentId) -> AcpCatalogEntry?
            val routed = AcpSessionRuntimeAdapter(resolveLaunchableEntry = { catalog, requestedAgent ->
                val entry = resolve(catalog, AcpAgentId.parse("acp.ai-multi"))
                    ?: resolve(catalog, AcpAgentId.parse("acp.dynamic.6163702e61692d6d756c7469"))
                    ?: return@AcpSessionRuntimeAdapter null
                object : AcpCatalogEntry by entry {
                    // Native launch admission validates entry identity against
                    // the bound agent's route. Only the process launch is Multi.
                    override val id = requestedAgent
                    override suspend fun resolveStartConfig(project: Project): AcpAgentStartConfig {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { GenericAutoRegistration.syncRoutesNow(project) }
                        val config = entry.resolveStartConfig(project)
                        return config.withAcpArgs(config.acpArgs + listOf("--initial-profile", launch.profileId))
                    }
                }
            })
            val backend = BackendThreadGuiPreparedSessionApi(resolveAdapter = { target ->
                if (target == request.route.targetId) routed else AgentSessionRuntimeAdapters.resolvedOrNull(target)
            })
            retained.lease = ThreadGuiPreparedSessionLeaseServiceImpl(
                preparedSessionApi = { backend }, threadGuiApi = {
                    val api = ThreadGuiApi.getInstance()
                    object : ThreadGuiApi by api {
                        override suspend fun sendPrompt(ref: ThreadGuiRef, request: ThreadGuiSendPromptRequest): ThreadGuiSendPromptResult {
                            val result = api.sendPrompt(ref, request)
                            if (result.accepted) {
                                val unused = synchronized(prepared) {
                                    prepared.values.filter { it !== retained }.also { prepared.clear() }
                                }
                                unused.forEach { it.scope.cancel() }
                                // The admitted thread is now owned by Air. Release
                                // the selected preparation lease with its native child.
                                retained.child?.invokeOnCompletion { retained.scope.cancel() }
                            }
                            return result
                        }
                    }
                }
            ).open(retained.scope, request)
            synchronized(prepared) { prepared[key] = retained }
            return retained.lease
        }
    }
}
