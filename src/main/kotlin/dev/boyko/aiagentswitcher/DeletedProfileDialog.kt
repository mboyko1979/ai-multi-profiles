package dev.boyko.aiagentswitcher

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.dsl.builder.panel
import javax.swing.Action
import javax.swing.JComponent
import java.awt.event.ActionEvent

/** Choosing a target does not grant consent to share history; the normal transfer dialog follows. */
internal class DeletedProfileDialog(project: Project, original: String, profiles: List<AgentProfiles.Entry>) : DialogWrapper(project) {
    private val originalName = original
    private val choices = ComboBox(profiles.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { "${it.name} · ${it.agentName.ifBlank { it.agentId }}" }
    }
    var readOnly = false
        private set
    val profile get() = (choices.selectedItem as? AgentProfiles.Entry)?.id
    private val localHistory = object : DialogWrapperAction("View Local History") {
        override fun doAction(e: ActionEvent) { readOnly = true; close(OK_EXIT_CODE) }
    }
    init {
        title = "Profile Deleted — AI Multi"
        setOKButtonText("Choose Profile")
        init()
        setOKActionEnabled(profiles.isNotEmpty())
    }
    override fun createActions(): Array<Action> = arrayOf(okAction, localHistory, cancelAction)
    override fun createCenterPanel(): JComponent = panel {
        row { label("The profile used by this chat was deleted: $originalName") }
        row { comment("Choose another profile to continue, or view the history saved on this computer.") }
        row("Profile:") { cell(choices) }
        row { comment("Local history opens read-only. No agent session will be loaded. You can choose another profile later.") }
    }
}
