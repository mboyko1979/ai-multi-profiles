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
        val applySafely = {
            if (!project.isDisposed) {
                try {
                    apply(project, composer)
                } catch (error: Exception) {
                    val cause = (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
                    if (cause is com.intellij.openapi.diagnostic.ControlFlowException || cause is java.util.concurrent.CancellationException) throw cause
                    val unsupported = cause is NoSuchElementException || cause is ClassCastException || cause is ReflectiveOperationException ||
                        ((cause is IllegalStateException || cause is IllegalArgumentException) && cause.message?.startsWith("Unsupported ") == true)
                    if (!unsupported) throw error
                    if (reportedUnsupportedApi.compareAndSet(false, true))
                        com.intellij.openapi.diagnostic.Logger.getInstance(AirComposerProfiles::class.java)
                            .warn("Air composer profile binding is unavailable", cause)
                }
            }
        }
        if (app.isDispatchThread) applySafely()
        else app.invokeLater(applySafely)
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
        // Trailing null is Air 497's optional Long. Air 521 removed that parameter.
        native.call(composer.draft, "pick-", seed(base, launch.profile), PROFILE_ORIGIN + id,
            AirSessionPreferencesSeedKind.Preset, false, null)
        native.call(composer.preferences, "requestChildStart")
    }

    /** Air keys its metadata cache by agent/route. Use the bound agent; do not add a plugin cache. */
    private fun catalogAgentId(agent: String) = FOLDED_ACP_AGENTS[agent] ?: acpCatalogAgentId(agent)

    private fun seed(base: AgentPromptLaunchProfile, profile: AgentProfiles.Entry): AgentPromptLaunchProfile {
        val agent = profile.agentId
        val changes = mutableMapOf<String, Any?>(
            "generationPreferences" to AgentPromptGenerationPreferences(),
            "sessionModeId" to null, "planMode" to false,
        )
        if (agent.isNotBlank()) {
            changes["name"] = GenericAutoRegistration.AGENT
            changes["agentId"] = catalogAgentId(agent) ?: error("The profile's native ACP agent ID is invalid")
            changes["launchTargetId"] = acpAgentLaunchTargetId(agent)
        }
        // Kotlin data-class copy has a different binary signature when Air adds a
        // property. Bind to the runtime copy and carry all its untouched fields forward.
        val fields = base.javaClass.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.isSynthetic }
        val copy = base.javaClass.declaredMethods.single { method ->
            method.name.startsWith("copy-") && !method.name.endsWith("\$default") &&
                method.parameterTypes.toList() == fields.map { it.type }
        }
        require(changes.keys.all { key -> fields.any { it.name == key } }) { "Unsupported Air launch profile fields" }
        val args = fields.map { field ->
            field.isAccessible = true
            val value = if (changes.containsKey(field.name)) changes[field.name] else field.get(base)
            if (value != null && field.type == String::class.java && value !is String)
                value.javaClass.getDeclaredMethod("unbox-impl").invoke(value)
            else value
        }.toTypedArray()
        return try { copy.invoke(base, *args) as AgentPromptLaunchProfile }
        catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
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
            val key = launch.profileId + ":" + ProfileConfiguration.digest(com.google.gson.Gson().toJson(launch.profile)) + ":" + request
            synchronized(prepared) {
                prepared[key]?.takeIf { it.scope.isActive && it.lease.value !is PreparedSessionLease.Failed }?.let { return it.lease }
                prepared.remove(key)?.scope?.cancel()
            }
            val retained = Retained(CoroutineScope(owner.coroutineContext + SupervisorJob(owner.coroutineContext[Job])), scope.coroutineContext[Job])
            val ordinary = AcpSessionRuntimeAdapter::class.java.getConstructor().newInstance()
            @Suppress("UNCHECKED_CAST")
            val resolve = native.field(ordinary, "resolveLaunchableEntry") as suspend (AcpAgentsCatalog, AcpAgentId) -> AcpCatalogEntry?
            val resolveProfile: suspend (AcpAgentsCatalog, AcpAgentId) -> AcpCatalogEntry? = resolver@ { catalog, requestedAgent ->
                val entry = resolve(catalog, AcpAgentId.parse("acp.ai-multi"))
                    ?: resolve(catalog, AcpAgentId.parse("acp.dynamic.6163702e61692d6d756c7469"))
                    ?: return@resolver null
                // Native admission/consent and generation metadata belong to
                // the bound agent. AI Multi replaces only its process launch.
                val bound = if (launch.profile.advancedOverrides) entry else
                    resolve(catalog, AcpAgentId.parse(launch.profile.agentId)) ?: return@resolver null
                object : AcpCatalogEntry by bound {
                    // Native launch admission validates entry identity against
                    // the bound agent's route. Only the process launch is Multi.
                    override val id = requestedAgent
                    override suspend fun resolveStartConfig(project: Project): AcpAgentStartConfig {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            AirProfileAgentUpdates.beforeLaunch(launch.profile)
                            val current = ProfileLaunch.resolve(launch.profileId).profile
                            check(current.agentId == launch.profile.agentId) { "Profile agent changed while preparing the launch" }
                            GenericAutoRegistration.syncRoutesNow(project)
                        }
                        val config = entry.resolveStartConfig(project)
                        return withInitialProfile(config, launch.profileId)
                    }
                }
            }
            val routed = withNativeDefaults(ordinary, mapOf("resolveLaunchableEntry" to resolveProfile))
            val resolveAdapter: (com.intellij.air.shared.core.thread.AgentLaunchTargetId) -> com.intellij.air.backend.session.api.AgentSessionRuntimeAdapter? = { target ->
                if (target == request.route.targetId) routed else AgentSessionRuntimeAdapters.resolvedOrNull(target)
            }
            val backend = withNativeDefaults(BackendThreadGuiPreparedSessionApi::class.java.getConstructor().newInstance(),
                mapOf("resolveAdapter" to resolveAdapter))
            retained.lease = ThreadGuiPreparedSessionLeaseServiceImpl(
                preparedSessionApi = { backend }, threadGuiApi = {
                    val api = ThreadGuiApi.getInstance()
                    object : ThreadGuiApi by api {
                        override suspend fun sendPrompt(ref: ThreadGuiRef, request: ThreadGuiSendPromptRequest): ThreadGuiSendPromptResult {
                            val result = api.sendPrompt(ref, request)
                            if (result.accepted) {
                                // The native composer has now handed over this
                                // preparation. Persist its actual transport owner
                                // without changing the bound agent's catalog lane.
                                withContext(Dispatchers.IO) {
                                    try {
                                        AirChatRoutingRepair.commit(ref.projectDirectory.replace('\\', '/'), ref.threadId)
                                    } catch (error: Exception) {
                                        if (error is CancellationException) throw error
                                        com.intellij.openapi.diagnostic.Logger.getInstance(AirComposerProfiles::class.java)
                                            .warn("Could not persist AI Multi chat transport ownership", error)
                                        com.intellij.notification.NotificationGroupManager.getInstance()
                                            .getNotificationGroup("AI Agent Project Switcher")
                                            .createNotification("AI Multi chat routing was not saved",
                                                "The prompt was accepted. Chat restoration may need Reconnect chat. See the IDE log for details.",
                                                com.intellij.notification.NotificationType.ERROR).notify(selection.project)
                                    }
                                }
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

    /** Pinned Air constructor binding. Keep every host default, including newly
     * added services; Kotlin's compiled default-constructor descriptor is not stable. */
    private fun <T : Any> withNativeDefaults(defaults: T, changes: Map<String, Any>): T {
        val fields = defaults.javaClass.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.isSynthetic }
        val constructor = defaults.javaClass.constructors.single { ctor ->
            !ctor.isSynthetic && ctor.parameterCount > 0 && ctor.parameterCount <= fields.size &&
                ctor.parameterTypes.toList() == fields.take(ctor.parameterCount).map { it.type }
        }
        val parameters = fields.take(constructor.parameterCount)
        require(changes.keys.all { key -> parameters.any { it.name == key } }) { "Unsupported Air constructor fields" }
        val args = parameters.map { field ->
            field.isAccessible = true
            changes[field.name] ?: field.get(defaults)
        }.toTypedArray()
        @Suppress("UNCHECKED_CAST")
        return try { constructor.newInstance(*args) as T }
        catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
    }

    // Air .512 removed withAcpArgs. Delegate all native configuration and only
    // append our routing arguments, including after native environment overlays.
    private fun withInitialProfile(config: AcpAgentStartConfig, profileId: String): AcpAgentStartConfig =
        java.lang.reflect.Proxy.newProxyInstance(AcpAgentStartConfig::class.java.classLoader,
            arrayOf(AcpAgentStartConfig::class.java)) { _, method, arguments ->
            try {
                when (method.name) {
                    "getAcpArgs" -> config.acpArgs + listOf("--initial-profile", profileId)
                    "getArgs" -> config.args + listOf("--initial-profile", profileId)
                    "withEnv" -> withInitialProfile(method.invoke(config, *(arguments ?: emptyArray())) as AcpAgentStartConfig, profileId)
                    else -> method.invoke(config, *(arguments ?: emptyArray()))
                }
            } catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
        } as AcpAgentStartConfig
}
