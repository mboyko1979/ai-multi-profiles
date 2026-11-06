package dev.boyko.aiagentswitcher.air

import com.google.gson.JsonParser
import com.intellij.air.thread.view.AgentSessionBannerActivationHandler
import com.intellij.air.thread.view.AgentSessionBannerContext
import com.intellij.air.thread.view.AgentSessionBannerContribution
import com.intellij.air.thread.view.AgentSessionBannerContributor
import com.intellij.air.thread.view.AgentSessionConfirmation
import com.intellij.air.thread.view.AgentSessionConfirmationAction
import com.intellij.air.thread.view.AgentSessionConfirmationActionRole
import com.intellij.air.thread.view.AgentSessionHeaderContribution
import com.intellij.air.thread.view.AgentSessionInput
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.boyko.aiagentswitcher.SwitcherConfigurable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import javax.swing.JPanel

/** Link that opens AI Multi settings from an Air chat that has no project profile. */
class AirSetupProfileBanner : AgentSessionBannerContributor {
    override fun createContribution(context: AgentSessionBannerContext): AgentSessionBannerContribution? {
        if (!setupSession(context.sessionAddress.sessionId)) return AirChatRoutingRepair.contribution(context)
        val project = context.project
        val confirmation = AgentSessionConfirmation(
            "ai-multi-setup",
            "Choose a project profile",
            JBLabel("<html>This project has no AI Multi profile.<br>This chat does not send requests to any account.<br>Choose a profile, then start a new chat.</html>").apply {
                border = JBUI.Borders.empty(4, 0)
            },
            listOf(AgentSessionConfirmationAction(
                "open-settings",
                "Open Settings",
                AgentSessionConfirmationActionRole.ACCEPT,
                "Open AI Multi Profiles settings",
            ) {
                if (!project.isDisposed) ShowSettingsUtil.getInstance().showSettingsDialog(project, SwitcherConfigurable::class.java)
            }),
        )
        @Suppress("UNCHECKED_CAST")
        return AgentSessionBannerContribution(
            MutableStateFlow<AgentSessionHeaderContribution?>(null) as StateFlow<AgentSessionHeaderContribution>,
            MutableStateFlow(emptyList()),
            object : AgentSessionBannerActivationHandler {
                override suspend fun activated() = Unit
            },
            MutableStateFlow(AgentSessionInput(JPanel(), confirmation, true)),
        )
    }

    private fun setupSession(sessionId: String): Boolean {
        if (sessionId.startsWith("aiaps-setup-")) return true
        if (ownerKind(sessionId) == "setup") return true
        return mappedAgentSession(sessionId)?.let { it.startsWith("aiaps-setup-") || ownerKind(it) == "setup" } == true
    }

    private fun ownerKind(sessionId: String): String? = runCatching {
        val home = System.getProperty("aiaps.home", System.getProperty("user.home"))
        val file = Path.of(home, ".jetbrains", "ai-agent-project-switcher", "session-owners", sha256(gson.toJson(sessionId)) + ".json")
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || Files.size(file) > 64_000) return null
        JsonParser.parseString(Files.readString(file).trimStart('\uFEFF')).asJsonObject.get("kind")?.asString
    }.getOrNull()

    /** Air shows a local id. The setup id is stored next to it in the IDE config. */
    private fun mappedAgentSession(sessionId: String): String? = runCatching {
        val root = Path.of(PathManager.getConfigPath(), "air", "session-identities")
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) return null
        Files.newDirectoryStream(root).use { dirs ->
            for (dir in dirs) {
                val file = dir.resolve("session-identities.json")
                if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || Files.size(file) > 1_000_000) continue
                val registrations = JsonParser.parseString(Files.readString(file).trimStart('\uFEFF'))
                    .asJsonObject.getAsJsonArray("registrations") ?: continue
                for (element in registrations) {
                    val registration = element.asJsonObject
                    val identity = registration.getAsJsonObject("currentAgentIdentity") ?: continue
                    if (identity.get("namespaceId")?.asString != ourNamespace) continue
                    val agentSession = identity.get("sessionId")?.asString ?: continue
                    if (registration.get("localSessionId")?.asString == sessionId || agentSession == sessionId) return agentSession
                }
            }
        }
        null
    }.getOrNull()

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val gson = com.google.gson.GsonBuilder().disableHtmlEscaping().create()
        val ourNamespace = "acp.dynamic." + "acp.ai-multi".toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
    }
}
