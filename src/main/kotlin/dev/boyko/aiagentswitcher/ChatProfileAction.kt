package dev.boyko.aiagentswitcher

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project

/** Native Tools-menu fallback; its caption describes the project's new sessions. */
class ChatProfileAction : ActionGroup("AI Profile", true) {
    override fun update(e: AnActionEvent) {
        val project = e.project
        val selected = project?.service<ProjectSelection>()?.selected ?: "Unassigned"
        val name = service<AgentProfiles>().find(selected)?.name ?: "Profile not selected"
        e.presentation.text = "AI Multi: $name"
        e.presentation.description = "Default project profile. Choosing another profile opens a new chat."
        e.presentation.isEnabledAndVisible = project != null
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val project = e?.project ?: return emptyArray()
        val current = project.service<ProjectSelection>().selected
        val actions = mutableListOf<AnAction>()
        actions.add(object : DumbAwareAction((if (current == "Unassigned") "✓ " else "") + "Profile not selected") {
            override fun actionPerformed(e: AnActionEvent) {
                e.project?.let { choose(it, "Unassigned") }
            }
        })
        for (profile in service<AgentProfiles>().all()) {
            val label = (if (profile.id == current) "✓ " else "") + profile.name
            actions.add(object : DumbAwareAction(label) {
                override fun actionPerformed(e: AnActionEvent) {
                    val p = e.project ?: return
                    choose(p, profile.id)
                }
            })
        }
        actions.add(Separator.getInstance())
        actions.add(object : DumbAwareAction("Manage AI Profiles…") {
            override fun actionPerformed(e: AnActionEvent) {
                val p: Project = e.project ?: return
                ShowSettingsUtil.getInstance().showSettingsDialog(p, SwitcherConfigurable::class.java)
            }
        })
        return actions.toTypedArray()
    }

    private fun choose(project: Project, id: String) {
        if (project.service<ProjectSelection>().selected == id) return
        ChatProfileSwitch.launch(project, id, null)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
