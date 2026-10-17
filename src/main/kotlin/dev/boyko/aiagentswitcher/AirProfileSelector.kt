package dev.boyko.aiagentswitcher

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.ide.ActivityTracker
import com.intellij.ui.AnimatedIcon
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JComponent

/** Air 262 adapter: use its action group and current session admission, never the Swing tree. */
internal object AirProfileBridge {
    private val native get() = NativeRecoveryContinuation
    private val switches = ConcurrentHashMap<Any, String>()
    fun switching(binding: Binding): String? = switches[binding.runtime]
    data class Binding(val manager: Any, val ref: Any, val runtime: Any, val sessionId: String, val cwd: String)
    private fun preparedThread(context: DataContext): String? {
        val keys = native.type("com.intellij.air.frontend.prompt.composer.actions.toolbar.AirPromptSelectorKeys", "com.intellij.air") ?: return null
        @Suppress("UNCHECKED_CAST") val key = native.call(keys.getField("INSTANCE").get(null), "getSELECTORS") as DataKey<Map<*, *>>
        val agent = key.getData(context)?.entries?.singleOrNull { it.key.toString() == "Agent" }?.value ?: return null
        val state = native.call(agent, "getState") ?: return null
        val nodes = native.call(native.call(state, "getActions")!!, "getNodes") as Map<*, *>
        val selected = nodes["Air.Prompt.Select.Agent/acp.dynamic.6163702e61692d6d756c7469"]
            ?: nodes["Air.Prompt.Select.Agent/acp.ai-multi"] ?: return null
        if (native.call(selected, "getSelected") != true && dev.boyko.aiagentswitcher.air.AirComposerProfiles.composer(context) == null) return null
        val commands = native.field(state, "commandService") ?: return null
        val lane = native.field(commands, "preferences") ?: return null
        val host = native.call(lane, "getPreferences") ?: return null
        val preferences = (native.call(host, "getPreferencesState") as StateFlow<*>).value ?: return null
        if (!preferences.javaClass.name.endsWith("AirSessionPreferencesState\$Ready")) return null
        val model = native.call(preferences, "getViewModel") ?: return null
        val source = native.field(model, "source") ?: return null
        if (!source.javaClass.name.endsWith("LiveThreadPreferencesSource")) return null
        val ref = native.field(source, "ref") ?: return null
        return native.call(ref, "getThreadId") as? String
    }
    fun binding(context: DataContext): Binding? = runCatching {
        val keys = native.type("com.intellij.air.frontend.session.view.api.AgentSessionViewTabContextKt", "com.intellij.air") ?: return null
        @Suppress("UNCHECKED_CAST") val key = keys.getField("AGENT_SESSION_VIEW_TAB_CONTEXT").get(null) as DataKey<Any>
        val tab = key.getData(context) ?: return null
        val thread = (tab.javaClass.getField("selectedThreadId").get(tab) as? String)?.takeIf { it.isNotBlank() }
            ?: preparedThread(context) ?: return null
        val managerType = native.type("com.intellij.air.acp.AcpSessionManager", "com.intellij.air") ?: return null
        val manager = native.call(managerType.getField("Companion").get(null), "getInstanceIfCreated") ?: return null
        val store = native.field(manager, "runtimeStore") ?: return null
        val refs = native.call(store, "refsSnapshot") as List<*>
        val matches = refs.filterNotNull().mapNotNull { ref ->
            val runtime = native.call(store, "get", ref) ?: return@mapNotNull null
            if (native.call(runtime, "getThreadId-") != thread) return@mapNotNull null
            val projection = native.call(native.field(runtime, "projectionHandle")!!, "getProjection") ?: return@mapNotNull null
            val binding = native.call(projection, "getRuntimeBinding")
            val sid = binding?.let { native.call(it, "getAgentSessionId") } as? String ?: ""
            if (sid.isBlank()) {
                val entry = native.call(runtime, "getEntry") ?: return@mapNotNull null
                if (native.call(entry, "getDisplayName") != GenericAutoRegistration.AGENT) return@mapNotNull null
            } else if (!sid.startsWith("aiaps-chat-")) return@mapNotNull null
            Binding(manager, ref, runtime, sid, native.call(runtime, "getWorkingDir").toString())
        }
        matches.singleOrNull()
    }.getOrNull()
    fun record(binding: Binding): JsonObject {
        val file = ProfileConfiguration.directory.resolve("conversations").resolve(ProfileConfiguration.digest(Gson().toJson(binding.sessionId)) + ".json")
        val record = JsonParser.parseString(Files.readString(file)).asJsonObject
        check(record.get("sessionId").asString == binding.sessionId && record.get("cwd").asString.equals(binding.cwd, true)) { "Air conversation does not match its profile record" }
        return record
    }
    fun ready(binding: Binding) = (binding.sessionId.isBlank() || native.call(binding.manager, "isReadyForPrompt", binding.ref) == true) &&
        native.call(binding.manager, "acceptsPrompt", binding.ref) == true
    suspend fun control(binding: Binding, method: String, params: JsonObject = JsonObject()): JsonObject = withContext(Dispatchers.Default) {
        check(ready(binding)) { "Wait for the Air task to finish before switching profiles" }
        val owners = native.field(binding.manager, "owners")!!
        val owner = native.call(owners, "get", binding.ref) ?: error("Air session was closed")
        val snapshot = (native.field(owner, "connectionSnapshotState") as StateFlow<*>).value ?: error("Air session is connecting")
        val generation = native.call(snapshot, "getGeneration-")!!
        val admitted = native.suspendCall(owners, "admitCurrentProtocolSession-", binding.ref, generation) ?: error("Air session is no longer available")
        val session = admitted.javaClass.getField("session").get(admitted)
        check(session.javaClass.name.endsWith("AcpV1ProtocolSession")) { "This version of AI Multi supports ACP v1 only" }
        val resources = native.field(session, "resources")!!
        val clientSession = native.call(resources, "getClientSession")!!
        check(native.call(clientSession, "getSessionId-").toString() == binding.sessionId) { "Air session changed before profile selection" }
        params.addProperty("sessionId", binding.sessionId)
        params.addProperty("cwd", binding.cwd)
        val payload = Json.parseToJsonElement(params.toString())
        val result = if (method.startsWith("_ai_multi/")) {
            // Air's in-process facade registers a finite list of vendor methods.
            // Our own router controls use the admitted connection's existing lease,
            // not that facade's synthetic protocol or a new/unscoped connection.
            val connection = native.call(resources, "getConnection")!!
            val facade = native.field(connection, "facade")!!
            val lease = native.field(facade, "lease") ?: error("Air connection was released")
            check(native.call(lease, "isClosed") == false) { "Air connection was closed" }
            val wireClient = native.call(lease, "getClient")!!
            native.suspendCall(wireClient, "sendVendorRequest-", method, payload, binding.sessionId)
        } else {
            val client = native.call(clientSession, "getClient")!!
            val protocol = native.call(client, "getProtocol")!!
            native.suspendCall(protocol, "sendRequestRaw", method, payload, null)
        }
        JsonParser.parseString(result.toString()).asJsonObject
    }
    suspend fun connect(binding: Binding, reconnect: Boolean = false): Binding {
        if (binding.sessionId.isNotBlank() && !reconnect) {
            val projection = native.call(native.field(binding.runtime, "projectionHandle")!!, "getProjection")!!
            if (native.call(native.call(projection, "getThread")!!, "getStatus").toString() != "Preparing") return binding
        }
        val requestType = native.type("com.intellij.air.acp.AcpConnectionRequest", "com.intellij.air") ?: error("Unsupported Air connection API")
        val entry = native.call(binding.runtime, "getEntry")!!
        val request = requestType.constructors.single { it.parameterCount == 6 }.newInstance(entry, java.nio.file.Path.of(binding.cwd), false, true, null, false)
        native.suspendCall(native.field(binding.manager, "connectionExecutor")!!, "connect", binding.runtime, request)
        // The connection executor acquires ACP resources only. Let Air's owner finish
        // preparation through RehydrateCompleted, including its native ready event.
        check(native.call(binding.manager, "rehydrateOwnership", binding.ref) == true) { "Air could not finish preparing the chat" }
        withTimeout(30_000) {
            while (true) {
                val current = native.call(native.field(binding.runtime, "projectionHandle")!!, "getProjection")!!
                val status = native.call(native.call(current, "getThread")!!, "getStatus").toString()
                if (status != "Preparing" && native.call(binding.manager, "isReadyForPrompt", binding.ref) == true) break
                delay(50)
            }
        }
        val projection = native.call(native.field(binding.runtime, "projectionHandle")!!, "getProjection")!!
        val stored = native.call(projection, "getRuntimeBinding") ?: error("Air did not establish its native session")
        val sid = native.call(stored, "getAgentSessionId") as? String ?: error("Air did not report its session ID")
        check(sid.startsWith("aiaps-chat-")) { "Select a project Default Profile in AI Multi Profiles before creating this Air chat" }
        return binding.copy(sessionId = sid)
    }
    suspend fun choose(project: Project, original: Binding, profile: String) {
        if (switches.putIfAbsent(original.runtime, profile) != null) return
        ActivityTracker.getInstance().inc()
        try {
            chooseProfile(project, original, profile)
        } finally {
            switches.remove(original.runtime)
            ActivityTracker.getInstance().inc()
        }
    }
    private suspend fun chooseProfile(project: Project, original: Binding, profile: String) {
        val local = if (original.sessionId.isNotBlank()) runCatching { record(original) }.getOrNull() else null
        if (LocalChatState.readOnly(local)) {
            LocalChatState.select(local!!, profile)
            connect(original, reconnect = true)
            return
        }
        var binding = original
        var token: String? = null
        try {
            binding = connect(binding)
            val state = control(binding, "_ai_multi/describe")
            if (state.get("profile").asString == profile) return
            check(state.get("virtual").asBoolean && !state.get("busy").asBoolean) { "This Air chat is not ready for profile switching" }
            val params = JsonObject().apply { addProperty("profile", profile); add("revision", state.get("revision")) }
            var comment = ""
            if (state.get("exchanges").asInt == 0) params.addProperty("fresh", true)
            else {
                val history = NativeHistory.air(binding.runtime)
                val estimates = (0..20).associateWith { count -> control(binding, "_ai_multi/estimate", JsonObject().apply {
                    addProperty("profile", profile); addProperty("exchanges", count); add("history", history)
                }) }
                val from = service<AgentProfiles>().find(state.get("profile").asString)?.name ?: "Unavailable profile"
                val to = service<AgentProfiles>().find(profile)?.name ?: error("Profile was removed")
                val dialog = withContext(Dispatchers.EDT) {
                    ProfileTransferDialog(project, from, to, estimates, false).apply { disableSeparateChat(); show() }
                }
                if (!dialog.isOK) return
                comment = dialog.comment
                params.addProperty("exchanges", dialog.count); params.addProperty("comment", comment)
                params.addProperty("consent", true); params.add("attachmentIds", dialog.attachmentIds)
                params.add("history", history)
                params.add("nativeSource", JsonObject().apply { addProperty("host", "air"); addProperty("sessionId", native.call(binding.runtime, "getThreadId-").toString()); addProperty("eventId", history.lastOrNull()?.asJsonObject?.let { it.get("lastEventId") ?: it.get("id") }?.asString.orEmpty()) })
            }
            val switched = control(binding, "_ai_multi/switch", params)
            token = switched.get("token")?.takeUnless { it.isJsonNull }?.asString
            if (token != null) NativeRecoveryContinuation.continueSession(project, JsonObject().apply {
                addProperty("sessionId", binding.sessionId); addProperty("cwd", binding.cwd)
            }, token, comment)
        } catch (error: Exception) {
            if (token != null) withContext(NonCancellable) {
                runCatching { control(binding, "_ai_multi/abort_handoff", JsonObject().apply { addProperty("token", token) }) }
            }
            if (error is CancellationException) throw error
            val target = service<AgentProfiles>().find(profile)
            if (target != null && ProfileSettingsPrompt.explains(error)) ProfileSettingsPrompt.open(project, target)
            else withContext(Dispatchers.EDT) { com.intellij.openapi.ui.Messages.showErrorDialog(project,
                error.cause?.message ?: error.message ?: "Profile switch failed", "Could Not Switch Air Profile") }
        }
    }
}

@Service(Service.Level.APP)
internal class AirProfileTasks(val scope: CoroutineScope)

class AirProfileSelector : ComboBoxAction(), DumbAware {
    override fun createCustomComponent(presentation: Presentation, place: String): JComponent =
        dev.boyko.aiagentswitcher.air.AirProfileButton(this, presentation, place)

    override fun actionPerformed(e: AnActionEvent) {
        val button = e.presentation.getClientProperty(com.intellij.openapi.actionSystem.ex.CustomComponentAction.COMPONENT_KEY)
            ?: e.inputEvent?.component as? JComponent ?: return
        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createActionGroupPopup(
            "AI Multi profile", createPopupActionGroup(button, e.dataContext), e.dataContext,
            com.intellij.openapi.ui.popup.JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true
        ).showUnderneathOf(button)
    }
    private fun newChat(context: DataContext): Boolean = runCatching {
        val native = NativeRecoveryContinuation
        val keys = native.type("com.intellij.air.frontend.session.view.api.AgentSessionViewTabContextKt", "com.intellij.air") ?: return false
        @Suppress("UNCHECKED_CAST") val tabKey = keys.getField("AGENT_SESSION_VIEW_TAB_CONTEXT").get(null) as DataKey<Any>
        val tab = tabKey.getData(context)
        if (tab == null || !(tab.javaClass.getField("selectedThreadId").get(tab) as? String).isNullOrBlank()) return false
        val selectorKeys = native.type("com.intellij.air.frontend.prompt.composer.actions.toolbar.AirPromptSelectorKeys", "com.intellij.air") ?: return false
        @Suppress("UNCHECKED_CAST") val key = native.call(selectorKeys.getField("INSTANCE").get(null), "getSELECTORS") as DataKey<Map<*, *>>
        val selectors = key.getData(context) ?: return false
        val selector = selectors.entries.singleOrNull { it.key.toString() == "Agent" }?.value ?: return false
        val state = native.call(selector, "getState") ?: return false
        val actions = native.call(state, "getActions") ?: return false
        val nodes = native.call(actions, "getNodes") as Map<*, *>
        // Pending tab coordinates describe the original launch and can lag behind
        // selector changes. Read the current native selection by its routing ID.
        val selected = nodes["Air.Prompt.Select.Agent/acp.dynamic.6163702e61692d6d756c7469"]
            ?: nodes["Air.Prompt.Select.Agent/acp.ai-multi"] ?: return false
        native.call(selected, "getSelected") == true
    }.getOrDefault(false)
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        val composer = if (e.place == "Air.Prompt.Preferences") dev.boyko.aiagentswitcher.air.AirComposerProfiles.composer(e.dataContext) else null
        if (composer != null && e.project != null) {
            dev.boyko.aiagentswitcher.air.AirComposerProfiles.bind(e.project!!, composer)
            val profile = dev.boyko.aiagentswitcher.air.AirComposerProfiles.selected(e.project!!, composer)
            e.presentation.isEnabledAndVisible = true
            e.presentation.icon = MultiIcons.Profile
            e.presentation.disabledIcon = MultiIcons.Profile
            e.presentation.text = service<AgentProfiles>().find(profile)?.name ?: "Select profile…"
            e.presentation.description = "Select this composer's launch profile. Project Default Profile stays unchanged."
            return
        }
        val creating = e.place == "Air.Prompt.Preferences" && e.project != null && newChat(e.dataContext)
        val binding = if (creating) null else AirProfileBridge.binding(e.dataContext)
        e.presentation.isEnabledAndVisible = e.place == "Air.Prompt.Preferences" && (binding != null || creating)
        e.presentation.icon = MultiIcons.Profile
        e.presentation.disabledIcon = MultiIcons.Profile
        if (creating) {
            e.presentation.text = service<AgentProfiles>().find(e.project?.service<ProjectSelection>()?.selected.orEmpty())?.name ?: "Select profile…"
            e.presentation.description = "Profile launch integration is unavailable in this version of Air."
            e.presentation.isEnabled = false
        }
        if (binding != null) {
            val switching = AirProfileBridge.switching(binding)
            if (switching != null) {
                e.presentation.text = "Switching to ${service<AgentProfiles>().find(switching)?.name ?: "profile"}…"
                e.presentation.description = "Preparing the selected agent's session and settings."
                e.presentation.icon = AnimatedIcon.Default.INSTANCE
                e.presentation.disabledIcon = AnimatedIcon.Default.INSTANCE
                e.presentation.isEnabled = false
                return
            }
            val profile = if (binding.sessionId.isBlank()) e.project?.service<ProjectSelection>()?.selected
                else runCatching { AirProfileBridge.record(binding).get("profile").asString }.getOrNull()
            val awaiting = binding.sessionId.isBlank() && ProfileSettingsPrompt.waiting(profile.orEmpty(), "air")
            e.presentation.text = (if (awaiting) "Waiting for profile settings: " else "") + (service<AgentProfiles>().find(profile.orEmpty())?.name ?: "Unavailable")
            e.presentation.description = "Switch this chat's profile after the response finishes. Default Profile for the project stays unchanged."
            e.presentation.isEnabled = awaiting || AirProfileBridge.ready(binding) || runCatching { LocalChatState.readOnly(AirProfileBridge.record(binding)) }.getOrDefault(false)
        }
    }
    override fun createPopupActionGroup(button: JComponent, context: DataContext): DefaultActionGroup {
        val project = CommonDataKeys.PROJECT.getData(context) ?: return DefaultActionGroup()
        val composer = dev.boyko.aiagentswitcher.air.AirComposerProfiles.composer(context)
        if (composer != null) return DefaultActionGroup().apply {
            val current = dev.boyko.aiagentswitcher.air.AirComposerProfiles.selected(project, composer)
            service<AgentProfiles>().all().forEach { profile ->
                add(object : DumbAwareAction((if (profile.id == current) "✓ " else "") + profile.name) {
                    override fun actionPerformed(e: AnActionEvent) {
                        try {
                            dev.boyko.aiagentswitcher.air.AirComposerProfiles.choose(project, composer, profile.id)
                            ActivityTracker.getInstance().inc()
                        } catch (error: Exception) {
                            com.intellij.openapi.ui.Messages.showErrorDialog(project, error.cause?.message ?: error.message ?: "Profile selection failed", "Could Not Select Air Profile")
                        }
                    }
                })
            }
            addSeparator()
            add(object : DumbAwareAction("Manage Profiles…") {
                override fun actionPerformed(e: AnActionEvent) { ShowSettingsUtil.getInstance().showSettingsDialog(project, SwitcherConfigurable::class.java) }
            })
        }
        // An unsupported New Session API must not silently fall back to a live chat switch.
        if (newChat(context)) return DefaultActionGroup()
        val binding = AirProfileBridge.binding(context)
        if (binding != null && AirProfileBridge.switching(binding) != null) return DefaultActionGroup()
        if (binding == null && !newChat(context)) return DefaultActionGroup()
        val current = if (binding == null || binding.sessionId.isBlank()) project.service<ProjectSelection>().selected
            else AirProfileBridge.record(binding).get("profile").asString
        if (binding != null && binding.sessionId.isBlank() && ProfileSettingsPrompt.waiting(current, "air")) return DefaultActionGroup().apply {
            add(object : DumbAwareAction("Correct Profile Settings?") {
                override fun actionPerformed(e: AnActionEvent) {
                    val profile = service<AgentProfiles>().find(current) ?: return
                    val request = ProfileSettingsPrompt.failure(current, "air")
                    ProfileSettingsPrompt.open(project, profile, request?.message ?: "Correct the saved settings and click Apply.",
                        "air", request?.modelId.orEmpty(), request?.issues.orEmpty(), request?.liveOptions.orEmpty())
                }
            })
        }
        return DefaultActionGroup().apply {
            service<AgentProfiles>().all().forEach { profile ->
                add(object : DumbAwareAction((if (profile.id == current) "✓ " else "") + profile.name) {
                    override fun actionPerformed(e: AnActionEvent) {
                        service<AirProfileTasks>().scope.launch {
                            if (binding != null) AirProfileBridge.choose(project, binding, profile.id)
                        }
                    }
                })
            }
            addSeparator()
            add(object : DumbAwareAction("Manage Profiles…") {
                override fun actionPerformed(e: AnActionEvent) { ShowSettingsUtil.getInstance().showSettingsDialog(project, SwitcherConfigurable::class.java) }
            })
        }
    }
}
