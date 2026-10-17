package dev.boyko.aiagentswitcher

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import java.nio.file.Files
import java.nio.file.Path

fun interface ProfilesListener {
    fun changed()
    companion object { val TOPIC = Topic.create("AI agent profiles", ProfilesListener::class.java) }
}

internal object ProfileAssignments {
    fun select(project: Project, id: String) {
        require(id == "Unassigned" || service<AgentProfiles>().find(id) != null) { "Profile no longer exists" }
        GenericAutoRegistration.assignAsync(project, id)
    }
}

internal fun parseEnvironment(text: String): Map<String, String> = text.lines().filter { it.isNotBlank() }.associate {
    val index = it.indexOf('=')
    if (index <= 0) throw IllegalArgumentException("Environment entries must use NAME=value")
    it.substring(0, index).trim() to it.substring(index + 1)
}

internal fun validateProfile(entry: AgentProfiles.Entry, profiles: List<AgentProfiles.Entry>): String? {
    if (entry.name.isBlank()) return "Enter a profile name"
    if (profiles.any { it.id != entry.id && it.name.equals(entry.name, true) }) return "Profile names must be unique"
    if (entry.agentId.isNotBlank() && !entry.advancedOverrides) return null // Native launch is resolved and validated on Apply.
    if (entry.command.isBlank() && entry.agentId.isBlank()) return "Choose an installed agent"
    if (entry.command.isBlank()) return "Enter an ACP executable"
    if (entry.command.contains('\n') || entry.command.contains('\u0000')) return "Executable contains invalid characters"
    try {
        if ((entry.command.contains('/') || entry.command.contains('\\')) && !Files.isRegularFile(Path.of(entry.command))) return "Executable file does not exist"
        if (entry.workingDirectory.isNotBlank() && !Files.isDirectory(Path.of(entry.workingDirectory))) return "Working directory does not exist"
        val env = parseEnvironment(entry.environment)
        if (env.keys.any { !it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }) return "Environment names must contain letters, digits or underscores"
    } catch (e: Exception) { return e.message ?: "Invalid path or environment" }
    return null
}
