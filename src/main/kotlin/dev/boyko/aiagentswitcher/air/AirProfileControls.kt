package dev.boyko.aiagentswitcher.air

import com.intellij.air.frontend.prompt.composer.actions.toolbar.AirPromptSelectorId
import com.intellij.air.frontend.prompt.composer.actions.toolbar.CompactAwareActionButtonWithText
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import dev.boyko.aiagentswitcher.AgentProfiles
import dev.boyko.aiagentswitcher.AirProfileBridge
import dev.boyko.aiagentswitcher.GenericAutoRegistration
import dev.boyko.aiagentswitcher.MultiIcons
import dev.boyko.aiagentswitcher.NativeRecoveryContinuation
import dev.boyko.aiagentswitcher.ProfileAccounts
import dev.boyko.aiagentswitcher.ProjectSelection

/** Preserve Air's dynamic actions and other extensions; move only our profile control. */
class AirProfilePreferencesGroup : DefaultActionGroup(), DumbAware {
    private val accountActions = java.util.IdentityHashMap<AnAction, AnAction>()
    private val agentActions = java.util.IdentityHashMap<AnAction, AnAction>()
    private val native = NativeRecoveryContinuation.type(
        "com.intellij.air.frontend.prompt.composer.actions.AirPromptSelectorToolbarGroup", "com.intellij.air"
    )!!.getConstructor().newInstance() as DefaultActionGroup

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val manager = ActionManager.getInstance()
        val profile = manager.getAction("dev.boyko.aiagentswitcher.AirProfileSelector")
        val children = (native.getChildren(e).toList() + super.getChildren(e)).distinct().filter { it != profile }
        if (profile == null) return children.toTypedArray()
        val composer = e?.let { AirComposerProfiles.composer(it.dataContext) }
        var inserted = false
        fun arrange(actions: List<AnAction>): Array<AnAction> = buildList {
            for (action in actions) {
                if (action == profile) continue
                if (action is ActionGroup && !action.isPopup) {
                    // Air's model puts the actual selectors inside a dynamic root group.
                    val nested = arrange(action.getChildren(e).toList())
                    add(object : ActionGroup(), DumbAware {
                        override fun getActionUpdateThread() = action.actionUpdateThread
                        override fun update(event: AnActionEvent) = action.update(event)
                        override fun getChildren(event: AnActionEvent?) = nested
                    })
                } else {
                    val id = manager.getId(action)
                    add(
                        when {
                            id == AirPromptSelectorId.AiSource.actionId && e != null &&
                                    (composer != null || AirAccountPresentation.acquiredAiMulti(e.dataContext)) ->
                                synchronized(accountActions) { accountActions.getOrPut(action) { AirAccountAction(action) } }

                            id == AirPromptSelectorId.Agent.actionId && composer != null ->
                                synchronized(agentActions) { agentActions.getOrPut(action) { AirAgentAction(action) } }

                            else -> action
                        })
                }
                if (!inserted && manager.getId(action) == AirPromptSelectorId.Agent.actionId) {
                    add(profile)
                    inserted = true
                }
            }
        }.toTypedArray()

        val arranged = arrange(children)
        return if (inserted) arranged else arrayOf(profile) + arranged
    }
}

/** Read Air's existing account presentation; choosing or displaying a profile needs no session. */
private object AirAccountPresentation {
    private val native get() = NativeRecoveryContinuation
    private val reportedUnsupportedApi = java.util.concurrent.atomic.AtomicBoolean()

    fun acquiredAiMulti(context: DataContext): Boolean = compatible {
        val type =
            native.type("com.intellij.air.frontend.session.view.api.AgentSessionViewTabContextKt", "com.intellij.air")
                ?: return@compatible false
        @Suppress("UNCHECKED_CAST") val key = type.getField("AGENT_SESSION_VIEW_TAB_CONTEXT").get(null) as DataKey<Any>
        val tab = key.getData(context) ?: return@compatible false
        if ((tab.javaClass.getField("selectedThreadId").get(tab) as? String).isNullOrBlank()) return@compatible false
        val coordinates = native.call(tab, "getThreadCoordinates") ?: return@compatible false
        if (native.call(coordinates, "getAgentId-") in setOf("acp.ai-multi", "acp.dynamic.6163702e61692d6d756c7469"))
            return@compatible true
        // Native launch metadata can identify the bound agent. An already owned
        // routed session also identifies this tab, without acquiring a session.
        AirProfileBridge.binding(context)?.sessionId?.startsWith("aiaps-chat-") == true
    } ?: false

    fun chipText(context: DataContext): String? = compatible {
        val keys = native.type(
            "com.intellij.air.frontend.prompt.composer.actions.toolbar.AirPromptSelectorKeys",
            "com.intellij.air"
        )
            ?.getField("INSTANCE")?.get(null) ?: return@compatible null
        @Suppress("UNCHECKED_CAST") val key = native.call(keys, "getSELECTORS") as DataKey<Map<*, *>>
        val selector = key.getData(context)?.entries?.singleOrNull { it.key.toString() == "AiSource" }?.value
            ?: return@compatible null
        val state = native.call(selector, "getState") ?: return@compatible null
        // Pinned Air 262 selector binding; the native lane owns all account state.
        val commands = native.field(state, "commandService") ?: return@compatible null
        val lane = native.field(commands, "lane") ?: return@compatible null
        val account = native.call(lane, "getAiSource") ?: return@compatible null
        val accountState = native.call(native.call(account, "getAiSourceState")!!, "getValue") ?: return@compatible null
        val source = native.call(accountState, "getPresentationOrNull") ?: return@compatible null
        native.call(source, "getChipText") as? String
    }

    fun chipIcon(): javax.swing.Icon? = compatible {
        val type = native.type("com.intellij.air.frontend.llm.api.AirAiSourceIndicatorKt", "com.intellij.air")
            ?: return@compatible null
        native.call(type, "getAIR_AI_SOURCE_CHIP_ICON") as? javax.swing.Icon
    }

    /** Empty native segments win over presentation.text; drop them so the local caption can paint. */
    fun preferPresentationText(presentation: Presentation) {
        compatible {
            val keys = native.type(
                "com.intellij.air.frontend.prompt.composer.actions.toolbar.AirPromptSelectorKeys",
                "com.intellij.air"
            )
                ?.getField("INSTANCE")?.get(null) ?: return@compatible

            @Suppress("UNCHECKED_CAST")
            val key = native.call(keys, "getSEGMENTS") as com.intellij.openapi.util.Key<List<*>>
            val segments = presentation.getClientProperty(key) ?: return@compatible
            if (segments.all { native.call(it!!, "getText")?.toString().isNullOrBlank() })
                presentation.putClientProperty(key, null)
        }
    }

    fun localEmail(project: Project?, composer: AirComposerProfiles.Composer?, context: DataContext): String? {
        val id = when {
            composer != null && project != null -> AirComposerProfiles.selected(project, composer)
            else -> runCatching {
                val binding = AirProfileBridge.binding(context) ?: return@runCatching null
                if (binding.sessionId.isBlank()) project?.service<ProjectSelection>()?.selected
                else AirProfileBridge.record(binding).get("profile")?.asString
            }.getOrNull()
        } ?: return null
        val profile = service<AgentProfiles>().find(id) ?: return null
        return ProfileAccounts.request(profile).email ?: ProfileAccounts.discover(profile).email
    }

    private fun <T> compatible(read: () -> T): T? = try {
        read()
    } catch (error: Exception) {
        val cause = (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
        if (cause is com.intellij.openapi.diagnostic.ControlFlowException || cause is java.util.concurrent.CancellationException) throw cause
        if (reportedUnsupportedApi.compareAndSet(false, true))
            com.intellij.openapi.diagnostic.Logger.getInstance(AirAccountPresentation::class.java)
                .warn("Air account caption binding is unavailable; retaining the native account control", cause)
        null
    }
}

/** Air keeps the button, tooltip, account menu and lifecycle; only its caption is expanded. */
private class AirAccountAction(private val delegate: AnAction) : AnAction(),
    com.intellij.openapi.actionSystem.ex.CustomComponentAction, RightAlignedToolbarAction, DumbAware {
    init {
        copyFrom(delegate)
    }

    override fun getActionUpdateThread() = delegate.actionUpdateThread
    override fun update(e: AnActionEvent) {
        delegate.update(e)
        val composer = AirComposerProfiles.composer(e.dataContext)
        if (composer == null && !AirAccountPresentation.acquiredAiMulti(e.dataContext)) return
        val nativeText = AirAccountPresentation.chipText(e.dataContext)?.takeIf { it.isNotBlank() }
        val caption = nativeText ?: AirAccountPresentation.localEmail(e.project, composer, e.dataContext) ?: return
        e.presentation.text = caption
        e.presentation.isVisible = true
        e.presentation.isEnabled = true
        if (e.presentation.icon == null) {
            val icon = AirAccountPresentation.chipIcon()
            e.presentation.icon = icon
            e.presentation.disabledIcon = icon
        }
        AirAccountPresentation.preferPresentationText(e.presentation)
    }

    override fun actionPerformed(e: AnActionEvent) = delegate.actionPerformed(e)
    override fun createCustomComponent(presentation: Presentation, place: String): javax.swing.JComponent =
        (delegate as com.intellij.openapi.actionSystem.ex.CustomComponentAction).createCustomComponent(
            presentation,
            place
        )

    override fun updateCustomComponent(component: javax.swing.JComponent, presentation: Presentation) {
        AirAccountPresentation.preferPresentationText(presentation)
        (delegate as com.intellij.openapi.actionSystem.ex.CustomComponentAction).updateCustomComponent(
            component,
            presentation
        )
    }
}

/** Bound-agent seed owns cache/icon for models; the agent chip stays AI Multi. */
private class AirAgentAction(private val delegate: AnAction) : AnAction(),
    com.intellij.openapi.actionSystem.ex.CustomComponentAction, DumbAware {
    init {
        copyFrom(delegate)
    }

    override fun getActionUpdateThread() = delegate.actionUpdateThread
    override fun update(e: AnActionEvent) {
        delegate.update(e)
        e.presentation.text = GenericAutoRegistration.AGENT
        e.presentation.icon = MultiIcons.Agent
        e.presentation.disabledIcon = MultiIcons.Agent
    }

    override fun actionPerformed(e: AnActionEvent) = delegate.actionPerformed(e)
    override fun createCustomComponent(presentation: Presentation, place: String): javax.swing.JComponent =
        (delegate as com.intellij.openapi.actionSystem.ex.CustomComponentAction).createCustomComponent(
            presentation,
            place
        )

    override fun updateCustomComponent(component: javax.swing.JComponent, presentation: Presentation) =
        (delegate as com.intellij.openapi.actionSystem.ex.CustomComponentAction).updateCustomComponent(
            component,
            presentation
        )
}

/** Use the same borderless, compact-aware text button and palette as Air selectors. */
class AirProfileButton(action: AnAction, presentation: Presentation, place: String) :
    CompactAwareActionButtonWithText(action, presentation, place, JBUI.size(24)) {
    init {
        val style = NativeRecoveryContinuation.type(
            "com.intellij.air.frontend.prompt.composer.actions.toolbar.AirPromptActionButtonStyle", "com.intellij.air"
        )!!.getField("INSTANCE").get(null)
        NativeRecoveryContinuation.call(style, "apply", this)
    }

    override fun shallPaintDownArrow() = false
}
