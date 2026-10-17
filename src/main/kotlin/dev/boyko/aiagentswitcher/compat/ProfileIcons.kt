package dev.boyko.aiagentswitcher.compat

import com.intellij.ui.RowIcon
import dev.boyko.aiagentswitcher.MultiIcons
import javax.swing.Icon
import com.intellij.util.ui.EmptyIcon

internal object ProfileIcons {
    fun forAgent(agentId: String): Icon =
        RowIcon(MultiIcons.Profile, MultiIcons.Separator, InstalledAgents.icon(agentId) ?: MultiIcons.Agent)

    /** Swing rows keep each icon's natural size; never put this into Compose's 16px icon slot. */
    fun forChat(agentId: String): Icon = RowIcon(MultiIcons.Agent, EmptyIcon.create(6, 16),
        MultiIcons.Profile, EmptyIcon.create(6, 16), InstalledAgents.icon(agentId) ?: MultiIcons.Agent)
}
