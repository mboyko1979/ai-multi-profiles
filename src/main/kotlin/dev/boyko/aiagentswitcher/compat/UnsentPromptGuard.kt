@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package dev.boyko.aiagentswitcher.compat

import com.intellij.ml.llm.core.chat.session.ChatSession
import com.intellij.ml.llm.core.chat.session.FocusedChatSessionHost
import com.intellij.ml.llm.core.chat.ui.chat.agentSelector.actions.AiNewChatAndAgentSelectorAction
import com.intellij.ml.llm.core.chat.ui.chat.agentSelector.model.AiNewChatAndAgentSelectorModel
import com.intellij.ml.llm.core.chat.ui.chat.agentSelector.model.resolveCurrentChatSession
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.Messages
import dev.boyko.aiagentswitcher.GenericAutoRegistration
import dev.boyko.aiagentswitcher.ChatProfiles
import dev.boyko.aiagentswitcher.LocalChatState
import java.awt.Component
import java.awt.Container
import java.util.WeakHashMap
import kotlin.jvm.internal.CallableReference

class UnsentPromptGuardStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        ApplicationManager.getApplication().invokeLater { service<UnsentPromptGuard>().install() }
    }
}

/** Compatibility with AI Assistant 262.10968.223: guard before native empty-chat removal. */
@Service(Service.Level.APP)
class UnsentPromptGuard : Disposable {
    private val providers: MutableMap<AiNewChatAndAgentSelectorAction, (AnActionEvent) -> AiNewChatAndAgentSelectorModel?> = WeakHashMap()
    private val replacements = mutableMapOf<String, Pair<AnAction, AnAction>>()
    private var installed = false
    private var confirming = false

    fun install() {
        if (installed) return
        installed = true
        val manager = ActionManager.getInstance()
        hook(manager.getAction("NewChatAgentSelectorAction"))
        for (id in listOf("AIAssistant.Chat.Input.NewChat", "AIAssistant.ToolWindow.NewChatActionAlt")) {
            val native = manager.getAction(id) ?: continue
            val guarded = object : DumbAwareAction() {
                init { copyFrom(native) }
                override fun getActionUpdateThread() = native.actionUpdateThread
                override fun update(e: AnActionEvent) = native.update(e)
                override fun actionPerformed(e: AnActionEvent) {
                    val project = e.project ?: return
                    if (confirm(resolveCurrentChatSession(project, e.dataContext))) native.actionPerformed(e)
                }
            }
            manager.replaceAction(id, guarded)
            replacements[id] = native to guarded
        }
        for (id in listOf("AIAssistant.Chat.SendActions.Send", "AIAssistant.Chat.SendActions.SendToNewChat", "AIAssistant.Chat.SendActions.SendNow")) {
            val native = manager.getAction(id) ?: continue
            fun local(e: AnActionEvent): Boolean = e.project?.let { project ->
                resolveCurrentChatSession(project, e.dataContext)?.takeIf { it.currentChatMode.displayName == GenericAutoRegistration.AGENT }
                    ?.let { LocalChatState.readOnly(ChatProfiles.conversationRecord(it.uid)) }
            } == true
            val guarded = object : DumbAwareAction() {
                init { copyFrom(native) }
                override fun getActionUpdateThread() = native.actionUpdateThread
                override fun update(e: AnActionEvent) { native.update(e); if (local(e)) e.presentation.isEnabled = false }
                override fun actionPerformed(e: AnActionEvent) { if (!local(e)) native.actionPerformed(e) }
            }
            manager.replaceAction(id, guarded)
            replacements[id] = native to guarded
        }
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(AnActionListener.TOPIC, object : AnActionListener {
            override fun beforeActionPerformed(action: AnAction, event: AnActionEvent) { hook(action) }
        })
    }

    /** Also cover per-panel selector instances, including their dropdown's selectAgent path. */
    fun observe(component: Component) {
        install()
        if (component is ActionToolbar) hookGroup(component.actionGroup)
        if (component is Container) component.components.forEach(::observe)
    }

    private fun hookGroup(group: ActionGroup) {
        if (group is DefaultActionGroup)
            group.getChildren(ActionManager.getInstance()).forEach { if (it is ActionGroup) hookGroup(it) else hook(it) }
    }

    private fun hook(action: AnAction?) {
        if (action == null) return
        if (action.javaClass.name.endsWith(".AiNewChatActivatedButton")) {
            // The native split button delegates to its owning selector action.
            val callback = action.javaClass.getDeclaredField("onAction").apply { isAccessible = true }.get(action)
            hook((callback as? CallableReference)?.boundReceiver as? AnAction)
            return
        }
        if (action !is AiNewChatAndAgentSelectorAction || providers.containsKey(action)) return
        try {
            val field = action.javaClass.getDeclaredField("modelProvider").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val original = field.get(action) as (AnActionEvent) -> AiNewChatAndAgentSelectorModel?
            val guarded: (AnActionEvent) -> AiNewChatAndAgentSelectorModel? = { event ->
                original(event)?.let { model ->
                    val project = event.project
                    val chat = project?.let { resolveCurrentChatSession(it, event.dataContext) }
                    object : AiNewChatAndAgentSelectorModel by model {
                        override fun createNewChat(e: AnActionEvent) { if (confirm(chat)) model.createNewChat(e) }
                        override fun selectAgent(agentId: String) { if (confirm(chat)) model.selectAgent(agentId) }
                    }
                }
            }
            field.set(action, guarded)
            providers[action] = original
        } catch (e: Exception) {
            Logger.getInstance(UnsentPromptGuard::class.java).warn("Could not install AI Multi draft protection", e)
        }
    }

    fun confirmFocused(project: Project): Boolean {
        // Kotlin metadata marks the native backing property private; use its real public JVM getter.
        val host = project.service<FocusedChatSessionHost>()
        return confirm(host.javaClass.getMethod("getFocusedChatSession").invoke(host) as? ChatSession)
    }

    fun confirm(chat: ChatSession?): Boolean {
        if (chat == null || chat.currentChatMode.displayName != GenericAutoRegistration.AGENT || chat.messages.isNotEmpty()) return true
        val document = chat.associatedInput.value?.document ?: return true
        if (document.text.isEmpty()) return true
        if (confirming) return false
        confirming = true
        return try {
            Messages.showDialog(chat.project,
                "This new chat has an unsent prompt. Opening another chat may replace it and discard your text.\n\n" +
                    "Stay in this chat and send or copy the prompt first, or explicitly open a new chat. Nothing will be sent automatically.",
                "Unsent prompt — AI Multi",
                arrayOf("Stay in This Chat", "Open New Chat"), 0, Messages.getWarningIcon()) == 1
        } finally { confirming = false }
    }

    override fun dispose() {
        providers.forEach { (action, original) ->
            runCatching { action.javaClass.getDeclaredField("modelProvider").apply { isAccessible = true }.set(action, original) }
        }
        providers.clear()
        val manager = ActionManager.getInstance()
        replacements.forEach { (id, pair) -> if (manager.getAction(id) === pair.second) manager.replaceAction(id, pair.first) }
        replacements.clear()
    }
}
