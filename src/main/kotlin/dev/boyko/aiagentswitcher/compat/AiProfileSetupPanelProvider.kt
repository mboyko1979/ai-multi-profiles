package dev.boyko.aiagentswitcher.compat

import com.intellij.ml.llm.core.chat.session.ChatSession
import com.intellij.ml.llm.core.chat.ui.chat.AIAssistantChatPanel
import com.intellij.ml.llm.core.chat.ui.chat.AIAssistantChatPanelComponent
import com.intellij.ml.llm.core.chat.ui.chat.AIAssistantChatPanelComponentProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.ui.components.JBLabel
import java.awt.BorderLayout
import javax.swing.JPanel
import dev.boyko.aiagentswitcher.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

/** Native chat panel extension, verified with AI Assistant 262.10968.223. */
class AiProfileSetupPanelProvider : AIAssistantChatPanelComponentProvider {
    override fun createChatPanelComponent(project: Project, chatPanel: AIAssistantChatPanel,
                                         chatSession: ChatSession, coroutineScope: CoroutineScope): AIAssistantChatPanelComponent {
        if (chatSession.currentChatMode.displayName != GenericAutoRegistration.AGENT) {
            return object : AIAssistantChatPanelComponent {
                private val component = JPanel().apply { isVisible = false; isOpaque = false }
                private val visible = AtomicBooleanProperty(false)
                override fun getJComponent() = component
                override fun getIsVisibleProperty() = visible
            }
        }
        val scope = coroutineScope
        ProfileStartupStatus.observe(chatSession, scope)
        ChatSwitchUi.register(project, chatPanel)
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) service<UnsentPromptGuard>().observe(chatPanel)
        }
        val visible = AtomicBooleanProperty(false)
        val waiting = panel {
            row { label("This chat is waiting for profile settings to be corrected.").bold() }
            row { text("Correct the highlighted values in AI Multi Profiles and click Apply to continue opening this chat.") }
            row { link("Open Settings") {
                val id = ProfileChatCreator.pendingProfile(chatSession.uid) ?: ChatProfiles.boundProfile(chatSession.uid)
                    ?: project.service<ProjectSelection>().selected
                service<AgentProfiles>().find(id)?.let { profile ->
                    val request = ProfileSettingsPrompt.failure(id, "assistant")
                    ProfileSettingsPrompt.open(project, profile, request?.message ?: "Correct the saved profile settings and click Apply.",
                        "assistant", request?.modelId.orEmpty(), request?.issues.orEmpty(), request?.liveOptions.orEmpty())
                }
            } }
        }
        val guidance = panel {
            row { label("Choose a project profile").bold() }
            row {
                text("Select a profile in the AI Multi menu. Choosing a profile opens a new chat. " +
                     "No account is selected automatically.")
                    .applyToComponent { foreground = UIUtil.getContextHelpForeground() }
            }
            row {
                link("Open Settings") {
                    if (!project.isDisposed) ShowSettingsUtil.getInstance().showSettingsDialog(project, SwitcherConfigurable::class.java)
                }
            }
        }
        val component = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            isVisible = false
            isOpaque = false
            border = JBUI.Borders.empty(12)
            add(guidance, BorderLayout.CENTER)
            add(waiting, BorderLayout.SOUTH)
        }
        val revision = MutableStateFlow(0L)
        scope.launch { while (kotlinx.coroutines.currentCoroutineContext().isActive) { kotlinx.coroutines.delay(250); revision.value += 1 } }
        val connection = ApplicationManager.getApplication().messageBus.connect(chatSession)
        connection.subscribe(ProfilesListener.TOPIC, ProfilesListener { revision.value += 1 })
        scope.coroutineContext[Job]?.invokeOnCompletion { connection.disconnect() }
        scope.launch(Dispatchers.EDT) {
            combine(revision, chatSession.stateFlow.onStart { emit(chatSession.state) }) { _, _ ->
                chatSession.currentChatMode.displayName == GenericAutoRegistration.AGENT
            }.collect { multi ->
                val id = ProfileChatCreator.pendingProfile(chatSession.uid) ?: ChatProfiles.boundProfile(chatSession.uid) ?: project.service<ProjectSelection>().selected
                val profile = service<AgentProfiles>().find(id)
                val show = multi && profile == null && !project.isDisposed
                val awaiting = multi && ProfileSettingsPrompt.waiting(id, "assistant") && ChatProfiles.conversationRecord(chatSession.uid) == null && !project.isDisposed
                guidance.isVisible = show
                waiting.isVisible = awaiting
                component.isVisible = show || awaiting
                visible.set(show || awaiting)
                component.revalidate()
            }
        }
        return object : AIAssistantChatPanelComponent {
            override fun getJComponent() = component
            override fun getIsVisibleProperty() = visible
        }
    }
}
