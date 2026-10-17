package dev.boyko.aiagentswitcher.compat

import com.intellij.ml.llm.core.chat.session.ChatSessionVm
import com.intellij.ml.llm.core.chat.session.ChatSessionState
import com.intellij.ml.llm.core.chat.ui.chat.input.toggle.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import dev.boyko.aiagentswitcher.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.*

/** Internal AI Assistant adapter, checked against 262.10968.223. No UI tree injection. */
class AiProfileToggleProvider : AiChatToolbarToggleProvider {
    override fun getControl(chatSession: ChatSessionVm, scope: CoroutineScope): AiChatToolbarControl {
        scope.launch { ChatTitleRepair.repair(chatSession) }
        return Selector(chatSession, enabled(chatSession, scope))
    }

    override fun getControlsFlow(chatSession: ChatSessionVm, scope: CoroutineScope): Flow<List<AiChatToolbarControl>> {
        if (chatSession.currentChatMode.displayName != GenericAutoRegistration.AGENT) return flowOf(emptyList())
        scope.launch { ChatTitleRepair.repair(chatSession) }
        val revision = MutableStateFlow(0L)
        val connection = ApplicationManager.getApplication().messageBus.connect(chatSession)
        connection.subscribe(ProfilesListener.TOPIC, ProfilesListener { revision.value += 1 })
        scope.coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { connection.disconnect() }
        val enabled = enabled(chatSession, scope)
        return combine(revision, chatSession.stateFlow.onStart { emit(chatSession.state) }) { _, _ ->
            listOf(Selector(chatSession, enabled))
        }
    }

    private fun enabled(chat: ChatSessionVm, scope: CoroutineScope) =
        chat.stateFlow.onStart { emit(chat.state) }.map { it == ChatSessionState.Idle || LocalChatState.readOnly(ChatProfiles.conversationRecord(chat.uid)) }
            .stateIn(scope, SharingStarted.Eagerly, chat.state == ChatSessionState.Idle)

    private class Selector(private val chat: ChatSessionVm, override val isEnabled: StateFlow<Boolean>) : AiChatToolbarControl.Selector() {
        private val profiles = service<AgentProfiles>().all()
        private val current = chat.project.service<ProjectSelection>().selected
        private val shown = PreparedAssistantProfiles.selected(chat.session) ?: ChatProfiles.boundProfile(chat.uid) ?: ProfileChatCreator.pendingProfile(chat.uid) ?: current
        private val manage = Option("Manage Profiles…", "Open AI Multi Profiles Settings", null)
        private val missing = Option(if (shown != "Unassigned" && profiles.none { it.id == shown }) "Deleted profile" else "Profile not selected",
            "Choose an available profile to continue this chat", null)
        private val choices = profiles.map {
            val icon = ProfileIcons.forAgent(it.agentId)
            val identity = if (it.id == shown) activeIdentity(it) else ProfileAccounts.request(it)
            Option(it.name, "${it.agentName} · ${identity.email ?: "Account unknown"}\nSwitch this chat's profile. Default Profile for the project stays unchanged.", icon)
        }
        override val options = listOf(missing) + choices + manage
        override val selectedOption = MutableStateFlow(choices.getOrNull(profiles.indexOfFirst { it.id == shown }) ?: missing)
        override val placement = AiChatToolbarPlacement.BOTTOM
        override val order = -100
        // The control must be available before ACP initialization succeeds.
        override val isVisible = MutableStateFlow(chat.currentChatMode.displayName == GenericAutoRegistration.AGENT)
        override val tooltip: String get() {
            val profile = profiles.firstOrNull { it.id == shown }
            val record = ChatProfiles.conversationRecord(chat.uid)?.takeIf { it.get("profile")?.asString == shown }
            val segment = record?.getAsJsonArray("segments")?.firstOrNull {
                it.asJsonObject.get("id")?.asString == record.get("currentSegment")?.asString
            }?.asJsonObject
            val account = segment?.getAsJsonObject("accountIdentity")
            val discovered = if (account == null && profile != null) ProfileAccounts.request(profile) else null
            val email = account?.get("email")?.takeUnless { it.isJsonNull }?.asString ?: discovered?.email ?: "Account unknown"
            val warnings = segment?.getAsJsonArray("configurationWarnings")?.map { it.asString }.orEmpty()
            val provider = account?.get("provider")?.takeUnless { it.isJsonNull }?.asString ?: discovered?.provider.orEmpty()
            val identity = if (provider.isBlank()) email else "$provider · $email"
            // Runtime model/effort/mode remain in the existing reactive native controls.
            // The profile hint is compact and carries identity, never a duplicated stale settings snapshot.
            return (listOfNotNull(profile?.name?.let { "Profile: $it" }, identity) + warnings.take(3) +
                "Switch this chat's profile after the response finishes. Default Profile for the project stays unchanged.").joinToString("\n")
        }
        override val popupTitle = "AI Multi profile"
        // The native toolbar uses popupDescription for its hover hint as well.
        override val popupDescription: String get() = tooltip
        private fun activeIdentity(profile: AgentProfiles.Entry): AgentAccountIdentity {
            if (ChatProfiles.boundProfile(chat.uid) != profile.id) return ProfileAccounts.request(profile)
            val record = ChatProfiles.conversationRecord(chat.uid)
            val account = record?.getAsJsonArray("segments")?.firstOrNull {
                it.asJsonObject.get("id")?.asString == record.get("currentSegment")?.asString
            }?.asJsonObject?.getAsJsonObject("accountIdentity")
            return if (account == null) ProfileAccounts.request(profile) else AgentAccountIdentity(
                email = account.get("email")?.takeUnless { it.isJsonNull }?.asString,
                status = account.get("status")?.takeUnless { it.isJsonNull }?.asString ?: "Account unknown")
        }
        override suspend fun onOptionSelected(option: Option) {
            if (chat.currentChatMode.displayName != GenericAutoRegistration.AGENT) return
            if ((chat.state != ChatSessionState.Idle && !LocalChatState.readOnly(ChatProfiles.conversationRecord(chat.uid))) || ChatSwitchUi.isBusy(chat.project)) return
            if (option == manage) {
                ApplicationManager.getApplication().invokeLater {
                    if (!chat.project.isDisposed) ShowSettingsUtil.getInstance().showSettingsDialog(chat.project, SwitcherConfigurable::class.java)
                }
                return
            }
            val id = profiles.getOrNull(choices.indexOf(option))?.id ?: "Unassigned"
            val active = PreparedAssistantProfiles.selected(chat.session) ?: ChatProfiles.boundProfile(chat.uid) ?: ProfileChatCreator.pendingProfile(chat.uid)
                ?: chat.project.service<ProjectSelection>().selected
            if (id == active || chat.project.isDisposed) return
            CurrentChatProfileSwitch.choose(chat, id)
        }
    }
}
