@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package dev.boyko.aiagentswitcher.compat

import com.agentclientprotocol.model.SessionConfigOption
import com.agentclientprotocol.model.SessionConfigOptionValue
import com.agentclientprotocol.model.ModelId
import com.agentclientprotocol.model.SessionModeId
import com.intellij.ml.llm.agents.acp.api.AcpAgentId
import com.intellij.ml.llm.agents.acp.client.settings.*
import com.intellij.ml.llm.core.chat.session.ChatSessionVm
import com.intellij.ml.llm.core.chat.ui.chat.input.chatModeSelector.NewChatMode
import com.intellij.ml.llm.core.chat.ui.chat.input.toggle.*
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.application.EDT
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.impl.ExtensionPointImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.boyko.aiagentswitcher.GenericAutoRegistration
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ProfileToolbarStartup : ProjectActivity {
    override suspend fun execute(project: Project) { service<ProfileToolbarIsolation>().install() }
}

/** Keep the native controls, but give each AI Multi chat its own presentation cache.
 * These IDs exist only inside toolbar view models; ACP registrations and sessions retain their IDs.
 */
@Service(Service.Level.APP)
class ProfileToolbarIsolation : Disposable {
    // The host asks for separate control flows for its model menu and bottom toolbar.
    // A flow-local flag cannot publish the menu's refresh to the visible toolbar.
    private val refreshingChats = MutableStateFlow<Set<String>>(emptySet())
    private fun refreshing(uid: String) = refreshingChats.map { uid in it }.distinctUntilChanged()
    private fun busy(uid: String) = uid in refreshingChats.value
    @Synchronized private fun begin(uid: String): Boolean {
        if (busy(uid)) return false
        refreshingChats.value = refreshingChats.value + uid
        return true
    }
    @Synchronized private fun finish(uid: String) { refreshingChats.value = refreshingChats.value - uid }
    private var installed = false
    @Synchronized fun install() {
        if (installed) return
        val point = ExtensionPointName.create<AiChatToolbarToggleProvider>("com.intellij.aiChatToggleProvider").point
        val providers = point.extensionList.map { provider ->
            if (provider is AcpConfigOptionToggleProvider || provider is AcpModelToggleProvider || provider is AcpModeToggleProvider)
                ScopedProvider(provider) else provider
        }
        (point as ExtensionPointImpl<AiChatToolbarToggleProvider>).maskAll(providers, this, true)
        installed = true
    }
    override fun dispose() = Unit // maskAll restores the original extension list on disposal.

    private class ScopedProvider(private val native: AiChatToolbarToggleProvider) : AiChatToolbarToggleProvider {
        override fun getControl(chatSession: ChatSessionVm, scope: CoroutineScope) = native.getControl(scoped(chatSession, scope), scope)
        override fun getControlsFlow(chatSession: ChatSessionVm, scope: CoroutineScope): Flow<List<AiChatToolbarControl>> {
            val view = scoped(chatSession, scope)
            val controls = native.getControlsFlow(view, scope)
            val refreshState = service<ProfileToolbarIsolation>()
            if (chatSession.currentChatMode.displayName != GenericAutoRegistration.AGENT)
                return controls
            // Compose snapshots Swing icons with rememberIconPainter; AnimatedIcon's
            // Swing repaint callback cannot animate that cached bitmap. Publish frames.
            val frames = refreshState.refreshing(chatSession.uid).flatMapLatest { busy ->
                if (!busy) flowOf(-1) else flow {
                    var frame = 0
                    while (currentCoroutineContext().isActive) {
                        emit(frame)
                        frame = (frame + 1) % com.intellij.ui.AnimatedIcon.Default.ICONS.size
                        delay(com.intellij.ui.AnimatedIcon.Default.DELAY.toLong())
                    }
                }
            }
            val source = when (native) {
                is AcpModeToggleProvider -> combine(controls, AcpConfigOptionsManager.getInstance(chatSession.project).configState) { legacy, states ->
                    // The config mode supersedes session/modes; collaboration mode
                    // has its own category and remains independently visible.
                    legacy to (states[cacheId(chatSession.uid)]?.options?.any { it.category?.value == "mode" } == true)
                }
                is AcpModelToggleProvider -> combine(controls, AcpConfigOptionToggleProvider().getControlsFlow(view, scope)) { legacy, config ->
                    legacy to config.any { it is AiChatToolbarControl.ModelSelector }
                }
                else -> controls.map { it to false }
            }
            // Legacy session/models survives a profile switch in the native client.
            // A model config option is the current provider's authoritative selector.
            // Suppression is explicit: it must also discard cached progress controls.
            return flow {
                val presentation = RefreshPresentation()
                val bound = combine(source, PreparedAssistantProfiles.selectionChanges) { controls, _ ->
                    if (PreparedAssistantProfiles.selected(chatSession.session) == null) controls
                    else emptyList<AiChatToolbarControl>() to false
                }
                combine(bound, frames) { (list, suppressed), frame ->
                    presentation.render(list, frame, suppressed).map { control ->
                        if (control is AiChatToolbarControl.ModelSelector) RefreshableModels(control, chatSession, refreshState)
                        else control
                    }
                }.collect { emit(it) }
            }
        }
    }

    private class RefreshPresentation {
        private var previous = emptyList<AiChatToolbarControl>()
        fun render(controls: List<AiChatToolbarControl>, frame: Int, suppressed: Boolean): List<AiChatToolbarControl> {
            if (suppressed) { previous = emptyList(); return emptyList() }
            // Keep existing slots during a temporary catalog gap, but never revive
            // a legacy control that the authoritative config options replaced.
            if (controls.isNotEmpty() || frame < 0) previous = controls
            return (if (frame >= 0 && controls.isEmpty()) previous else controls).map { control ->
                if (frame >= 0) RefreshProgress(control, frame) else control
            }
        }
    }

    private class RefreshProgress(private val native: AiChatToolbarControl, frame: Int) : AiChatToolbarControl.Selector() {
        private val caption = when (native) {
            is AiChatToolbarControl.ModelSelector -> "Refreshing models…"
            is AiChatToolbarControl.Selector -> (native.selectedOption.value?.text ?: "Refreshing") + "…"
            is AiChatToolbarControl.Toggle -> native.toggleText + "…"
            else -> "Refreshing…"
        }
        private val progress = Option(caption, "Reloading models and session options for this profile", com.intellij.ui.AnimatedIcon.Default.ICONS[frame])
        override val options = listOf(progress)
        override val selectedOption = MutableStateFlow(progress)
        override val isEnabled = MutableStateFlow(false)
        override val tooltip = "Reloading models and session options for this profile"
        override val isVisible = if (native is AiChatToolbarControl.ModelSelector) MutableStateFlow(true) else native.isVisible
        // ModelSelector is routed to the bottom model group regardless of placement.
        // A regular Selector must explicitly request BOTTOM to stay beside the profile.
        override val placement = if (native is AiChatToolbarControl.ModelSelector) AiChatToolbarPlacement.BOTTOM else native.placement
        override val order get() = native.order
        // Native descriptor IDs use the control hash; retain one ID across frames.
        override fun hashCode() = native.hashCode()
        override suspend fun onOptionSelected(option: Option) = Unit
    }

    private class RefreshableModels(private val native: AiChatToolbarControl.ModelSelector, private val chat: ChatSessionVm,
                                    private val refreshState: ProfileToolbarIsolation) : AiChatToolbarControl.ModelSelector() {
        private val refresh = ModelMeta("Refresh models…", "Reload models and session options for this profile", "ai-multi:refresh-catalog")
        override val models get() = native.models + refresh
        override val levels get() = native.levels
        override val selectedModel get() = native.selectedModel
        override val selectedLevel get() = native.selectedLevel
        override val isVisible get() = native.isVisible
        override val placement get() = native.placement
        override val order get() = native.order
        override suspend fun onModelSelected(model: ModelMeta) {
            if (model != refresh) { if (!refreshState.busy(chat.uid)) native.onModelSelected(model); return }
            if (chat.state != com.intellij.ml.llm.core.chat.session.ChatSessionState.Idle) return
            if (!refreshState.begin(chat.uid)) return
            try {
                val result = ChatHandoffClient(chat).control("_ai_multi/refresh_options")
                ProfileCatalogPresentation.publishLegacy(chat, result)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                withContext(Dispatchers.EDT) {
                    com.intellij.openapi.ui.Messages.showErrorDialog(chat.project,
                        "Could not refresh models. Check profile authorization in AI Multi Profiles and retry.", "Refresh models")
                }
            } finally { refreshState.finish(chat.uid) }
        }
        override suspend fun onReasoningLevelSelected(level: ReasoningLevelMeta) {
            if (!refreshState.busy(chat.uid)) native.onReasoningLevelSelected(level)
        }
    }

    companion object {
        fun cacheId(uid: String) = AcpAgentId.parse("acp.ai-multi-chat-$uid")

        private fun scoped(chat: ChatSessionVm, scope: CoroutineScope): ChatSessionVm {
            val original = chat.currentChatMode as? NewChatMode.Agent ?: return chat
            if (original.displayName != GenericAutoRegistration.AGENT) return chat
            val id = cacheId(chat.uid)
            val mode = NewChatMode.Agent(original.agentModel.copy(id = id.fullId))
            // Read only this chat's accepted session. No target agent is started by a preview.
            scope.launch(Dispatchers.Default) {
                ProfileCatalogPresentation.seed(chat)
                flow {
                    while (currentCoroutineContext().isActive) {
                        emit(ChatHandoffClient(chat).nativeSessionOrNull())
                        delay(100)
                    }
                }.distinctUntilChanged().filterNotNull().flatMapLatest { session ->
                    val optionsFlow = if (session.configOptionsSupported) session.configOptions else flowOf(emptyList())
                    val modelsFlow: Flow<ModelId?> = if (session.modelsSupported) session.currentModel else flowOf(null)
                    val modesFlow: Flow<SessionModeId?> = if (session.modesSupported) session.currentMode else flowOf(null)
                    combine(optionsFlow, modelsFlow, modesFlow) { options, model, modeId ->
                        val acceptedOptions = if (session.configOptionsSupported)
                            ProfileAgentPreferences.synchronize(chat.session, session) else options
                        val config = AcpConfigOptionsManager.getInstance(chat.project)
                        val values = acceptedOptions.mapNotNull { option ->
                            when (option) {
                                is SessionConfigOption.Select -> option.id to SessionConfigOptionValue.StringValue(option.currentValue.value)
                                is SessionConfigOption.BooleanOption -> option.id to SessionConfigOptionValue.BoolValue(option.currentValue)
                                else -> null
                            }
                        }.toMap()
                        config.announceConfigOptions(id, acceptedOptions, values)
                        config.setSessionSelectedValuesOverride(chat.uid, values)
                        val recorded = dev.boyko.aiagentswitcher.ChatProfiles.conversationRecord(chat.uid)?.getAsJsonObject("sessionOptions")
                        if (recorded != null && (recorded.has("models") || recorded.has("modes"))) ProfileCatalogPresentation.publishLegacy(chat, recorded)
                        else {
                            if (session.modelsSupported) AcpModelManager.registerSupportedModels(chat.project, id, session,
                                session.availableModels.firstOrNull { it.modelId == model })
                            if (session.modesSupported) AcpModeManager.registerSupportedModes(chat.project, id, session, modeId)
                        }
                    }
                }.collect()
            }
            return object : ChatSessionVm by chat { override val currentChatMode = mode }
        }
    }
}
