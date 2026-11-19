package dev.boyko.aiagentswitcher.air

import com.intellij.openapi.application.EDT
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.ui.AnimatedIcon
import com.google.gson.JsonObject
import dev.boyko.aiagentswitcher.AgentProfiles
import dev.boyko.aiagentswitcher.AirProfileBridge
import dev.boyko.aiagentswitcher.AirProfileTasks
import dev.boyko.aiagentswitcher.GenericAutoRegistration
import dev.boyko.aiagentswitcher.GrokPermissionMode
import dev.boyko.aiagentswitcher.ProjectSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JComponent

/** Composer dropdown for Grok's permission mode. Air has no native one.
 *  An open chat is stopped and resumed. A new composer restarts its preparation.
 */
class AirPermissionModeSelector : ComboBoxAction(), DumbAware {
    private val pending = ConcurrentHashMap<String, String>()

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent =
        AirProfileButton(this, presentation, place)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val profile = profile(e)
        val mode = profile?.let { shown(e, it) }
        e.presentation.isEnabledAndVisible = profile != null && mode != null
        if (profile == null || mode == null) return
        val applying = pending[profile.id] != null
        e.presentation.text = if (applying) "Applying…" else GrokPermissionMode.label(mode)
        e.presentation.description = if (applying) "Stopping this chat and resuming it in the selected mode"
            else GrokPermissionMode.modes.find { it.id == mode }?.description ?: "Permission mode"
        e.presentation.icon = if (applying) AnimatedIcon.Default.INSTANCE else null
        e.presentation.disabledIcon = e.presentation.icon
        e.presentation.isEnabled = !applying
    }

    override fun actionPerformed(e: AnActionEvent) {
        val button = e.presentation.getClientProperty(com.intellij.openapi.actionSystem.ex.CustomComponentAction.COMPONENT_KEY)
            ?: e.inputEvent?.component as? JComponent ?: return
        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createActionGroupPopup(
            "Permission mode", createPopupActionGroup(button, e.dataContext), e.dataContext,
            com.intellij.openapi.ui.popup.JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true
        ).showUnderneathOf(button)
    }

    override fun createPopupActionGroup(button: JComponent, context: DataContext): DefaultActionGroup {
        val event = AnActionEvent.createFromDataContext("Air.Prompt.Preferences", null, context)
        val profile = profile(event) ?: return DefaultActionGroup()
        val current = pending[profile.id] ?: shown(event, profile)
        return DefaultActionGroup().apply {
            GrokPermissionMode.modes.forEach { mode ->
                add(object : DumbAwareAction((if (mode.id == current) "✓ " else "") + mode.label, mode.description, null) {
                    override fun actionPerformed(e: AnActionEvent) {
                        // The checked row must be sent again. The chip can already say Ask
                        // while the live process is still in the mode it read at startup.
                        if (pending.putIfAbsent(profile.id, mode.id) != null) return
                        com.intellij.ide.ActivityTracker.getInstance().inc()
                        val project = e.project ?: event.project
                        // The launch bar often has no selected thread, so composer() looks like a new chat
                        // even while this conversation is open. An open chat must win: otherwise the chip
                        // shows the preset and the click never stops the live session.
                        val binding = openChat(context)
                        val composer = if (binding == null) AirComposerProfiles.composer(context) else null
                        service<AirProfileTasks>().scope.launch {
                            try {
                                // The file is the mode a new process reads. Write it before the restart,
                                // and do not use session/set_mode: Air holds that call until the turn ends.
                                GrokPermissionMode.remember(profile.id, mode.id)
                                if (binding != null) withTimeout(45_000) {
                                    AirProfileBridge.control(binding, "_ai_multi/set_permission_mode", JsonObject().apply { addProperty("modeId", mode.id) })
                                }
                                if (project != null) GenericAutoRegistration.syncRoutesNow(project)
                                if (composer != null) withContext(Dispatchers.EDT) { AirComposerProfiles.restartPreparation(composer) }
                            } catch (error: Exception) {
                                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                                withContext(Dispatchers.EDT) {
                                    Messages.showErrorDialog(project,
                                        error.cause?.message ?: error.message ?: "Could not change the permission mode",
                                        "Could Not Change Permission Mode")
                                }
                            } finally {
                                pending.remove(profile.id)
                                withContext(Dispatchers.EDT) { com.intellij.ide.ActivityTracker.getInstance().inc() }
                            }
                        }
                    }
                })
            }
        }
    }

    private fun profile(e: AnActionEvent): AgentProfiles.Entry? {
        // Launch is the composer bar next to the model chip. Preferences is the session lane.
        if (e.place != "Air.Prompt.Preferences" && e.place != "Air.Prompt.Launch") return null
        val project = e.project ?: return null
        val binding = openChat(e.dataContext)
        val id = if (binding != null) {
            runCatching { AirProfileBridge.record(binding).get("profile")?.asString }.getOrNull()
        } else {
            val composer = AirComposerProfiles.composer(e.dataContext)
            if (composer != null) AirComposerProfiles.selected(project, composer)
            else {
                val unbound = AirProfileBridge.binding(e.dataContext) ?: return null
                if (unbound.sessionId.isBlank()) project.service<ProjectSelection>().selected else null
            }
        } ?: return null
        return service<AgentProfiles>().find(id)?.takeIf { GrokPermissionMode.isGrok(it) }
    }

    /** Live AI Multi chat on this composer, including the launch bar with an empty thread id. */
    private fun openChat(context: DataContext) =
        AirProfileBridge.binding(context)?.takeIf { it.sessionId.startsWith("aiaps-chat-") }

    private fun shown(e: AnActionEvent, profile: AgentProfiles.Entry): String {
        pending[profile.id]?.let { return it }
        val binding = openChat(e.dataContext)
        if (binding != null) {
            val live = runCatching { GrokPermissionMode.live(AirProfileBridge.record(binding)) }.getOrNull()
            if (live != null) return live
        }
        return GrokPermissionMode.saved(profile)
    }
}
