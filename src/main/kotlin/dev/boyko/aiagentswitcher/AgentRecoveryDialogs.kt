package dev.boyko.aiagentswitcher

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.ProjectManager
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.concurrent.atomic.AtomicBoolean

/** Shared IDE dialog for either native ACP host. The router validates the approval again. */
@Service(Service.Level.APP)
internal class AgentRecoveryDialogs(private val scope: CoroutineScope) {
    private val started = AtomicBoolean()
    private val seen = mutableSetOf<String>()
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            val directory = Path.of(System.getProperty("aiaps.home", System.getProperty("user.home")),
                ".jetbrains", "ai-agent-project-switcher", "agent-recovery")
            while (isActive) {
                if (Files.isDirectory(directory, NOFOLLOW_LINKS)) {
                    Files.list(directory).use { files -> files.filter { it.fileName.toString().endsWith(".request.json") }
                        .toList().forEach { file ->
                            val nonce = file.fileName.toString().removeSuffix(".request.json")
                            if (nonce.matches(Regex("[0-9a-f-]{36}")) && nonce !in seen) {
                                try { handle(directory, file, nonce) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (error: Exception) { Logger.getInstance(javaClass).warn("Could not complete agent recovery dialog", error) }
                            }
                        }
                    }
                }
                delay(250)
            }
        }
    }
    private suspend fun handle(directory: Path, file: Path, nonce: String) {
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || Files.size(file) > 8_000_000) return
        val request = JsonParser.parseString(Files.readString(file)).asJsonObject
        if (request.get("nonce")?.asString != nonce) return
        val pid = request.get("pid").asLong
        if (!ProcessHandle.of(pid).map { it.isAlive }.orElse(false) ||
            System.currentTimeMillis() - request.get("createdAt").asLong !in 0..600_000) return
        val cwd = Path.of(request.get("cwd").asString).toAbsolutePath().normalize().toString()
        val projects = ProjectManager.getInstance().openProjects.filterNot { it.isDisposed }
        val project = projects.firstOrNull {
            !it.isDisposed && it.basePath?.let { base -> Path.of(base).toAbsolutePath().normalize().toString().equals(cwd, ignoreCase = true) } == true
        } ?: projects.singleOrNull()
            ?: (com.intellij.openapi.wm.WindowManager.getInstance().mostRecentFocusedWindow as? com.intellij.openapi.wm.IdeFrame)?.project
                ?.takeUnless { it.isDisposed } ?: return
        seen.add(nonce)
        if (request.get("kind")?.asString in listOf("defaults-bootstrap", "profile-settings-error")) {
            val profileId = request.get("profile").asString
            val profiles = com.intellij.openapi.components.service<AgentProfiles>()
            val result = runCatching {
                val profile = profiles.find(profileId) ?: error("Profile was removed")
                check(profile.agentId == request.get("agentId").asString) { "The profile agent changed during defaults discovery" }
                if (request.get("kind").asString == "defaults-bootstrap")
                    ProfileDefaultsBootstrap.ensure(project, profileId, profile.agentId)
                else {
                    val host = request.get("host")?.asString.orEmpty()
                    val modelId = request.get("modelId")?.asString.orEmpty()
                    val issues = request.getAsJsonArray("issues")?.map { item -> item.asJsonObject.let {
                        ProfileSettingsPrompt.Issue(it.get("modelId")?.asString.orEmpty(), it.get("configId")?.asString.orEmpty(),
                            it.get("value")?.asString.orEmpty(), it.get("reason")?.asString.orEmpty())
                    } }.orEmpty()
                    val options = request.getAsJsonArray("options")?.let { options ->
                        ProfileConfiguration.parseCapabilities(JsonObject().apply {
                            addProperty("observedAt", System.currentTimeMillis()); add("options", options)
                        }).options
                    }.orEmpty()
                    ProfileSettingsPrompt.open(project, profile,
                        "${profile.name} / ${if (host == "air") "Air" else "AI Assistant"}: ${request.get("reason").asString}. Correct the highlighted settings and click Apply. This chat is waiting; closing Settings keeps it waiting.",
                        host, modelId, issues, options)
                }
            }
            result.exceptionOrNull()?.let { error ->
                profiles.find(profileId)?.let { ProfileSettingsPrompt.open(project, it,
                    "Could not obtain agent defaults for ${it.name}: ${error.message}") }
            }
            val answer = JsonObject().apply {
                addProperty("nonce", nonce); addProperty("approved", result.isSuccess)
                result.exceptionOrNull()?.let { addProperty("error", it.message ?: "Could not obtain agent defaults") }
            }
            val tmp = Files.createTempFile(directory, ".configuration-", ".tmp")
            try { Files.writeString(tmp, answer.toString()); Files.move(tmp, directory.resolve("$nonce.response.json"), ATOMIC_MOVE) }
            finally { Files.deleteIfExists(tmp); Files.deleteIfExists(file); seen.remove(nonce) }
            return
        }
        val templates = request.getAsJsonObject("estimates")?.entrySet()?.associate { it.key.toInt() to it.value.asJsonObject }.orEmpty()
        val history = if (templates.isEmpty()) com.google.gson.JsonArray() else NativeHistory.forRecovery(request)
        val estimates = if (templates.isEmpty()) templates else NativeHistory.recoveryEstimates(history, templates)
        fun description(key: String): String = request.getAsJsonObject(key).let {
            "${it.get("profileName").asString} · ${it.get("agentName").asString}"
        }
        val answer = withContext(Dispatchers.EDT) {
            if (request.get("kind")?.asString == "profile-missing") {
                val available = request.getAsJsonArray("profiles").mapNotNull { item ->
                    val id = item.asJsonObject.get("id").asString
                    com.intellij.openapi.components.service<AgentProfiles>().find(id)
                }
                val dialog = DeletedProfileDialog(project, request.getAsJsonObject("from").get("profileName").asString, available)
                val approved = dialog.showAndGet()
                return@withContext JsonObject().apply {
                    addProperty("nonce", nonce); addProperty("approved", approved)
                    if (approved) { addProperty("readOnly", dialog.readOnly); addProperty("profile", dialog.profile) }
                }
            }
            if (request.get("kind")?.asString == "restore-existing") {
                val from = request.getAsJsonObject("from").get("agentId").asString
                require(from.isNotBlank() && from == request.getAsJsonObject("to").get("agentId").asString)
                val approved = com.intellij.openapi.ui.Messages.showYesNoDialog(project,
                    "This chat was saved with an older launch fingerprint that does not match the current launch. The old record cannot identify the difference or verify the original executable and account.\n\n" +
                        "Profile: "+description("to")+"\n\nConfirm that this profile still uses the original account and configuration. Restore the original agent session with this profile?",
                    "Restore Existing Chat", "Restore", "Cancel", com.intellij.openapi.ui.Messages.getQuestionIcon()) == com.intellij.openapi.ui.Messages.YES
                return@withContext JsonObject().apply {
                    addProperty("nonce", nonce); addProperty("approved", approved); addProperty("restoreExisting", true)
                }
            }
            val dialog = ProfileTransferDialog(project, description("from"), description("to"), estimates, false, recovery = true, recoveryDirectory = cwd,
                deletedProfile = request.get("kind")?.asString == "profile_deleted",
                disconnected = request.get("kind")?.asString == "profile_switch")
            JsonObject().apply {
                addProperty("nonce", nonce)
                addProperty("approved", dialog.showAndGet())
                if (dialog.isOK) {
                    addProperty("newChat", dialog.newChat); addProperty("exchanges", dialog.count)
                    addProperty("comment", dialog.comment); add("attachmentIds", dialog.attachmentIds)
                    addProperty("consent", !dialog.newChat)
                    // Only the explicitly selected text crosses this short-lived rendezvous.
                    // It is deleted by the router and is never stored in a conversation record.
                    add("history", NativeHistory.selectedText(history, dialog.count.takeUnless { dialog.newChat } ?: 0))
                }
            }
        }
        val response = directory.resolve("$nonce.response.json")
        val tmp = Files.createTempFile(directory, ".approval-", ".tmp")
        try { Files.writeString(tmp, answer.toString()); Files.move(tmp, response, ATOMIC_MOVE) }
        finally { Files.deleteIfExists(tmp) }
        if (answer.get("approved").asBoolean) scope.launch(Dispatchers.IO) {
            val ready = directory.resolve("$nonce.ready.json")
            try {
            for (attempt in 0..600) {
                if (project.isDisposed || !ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) return@launch
                if (Files.isRegularFile(ready, NOFOLLOW_LINKS)) {
                    val result = JsonParser.parseString(Files.readString(ready)).asJsonObject
                    if (result.get("nonce")?.asString != nonce || result.get("pid")?.asLong != pid) return@launch
                    try {
                        if (!result.get("token").isJsonNull)
                            NativeRecoveryContinuation.continueSession(project, request, result.get("token").asString, answer.get("comment").asString)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        Logger.getInstance(javaClass).warn("The approved agent recovery is waiting for a native prompt", error)
                    } finally { Files.deleteIfExists(ready); Files.deleteIfExists(file) }
                    return@launch
                }
                delay(100)
            }
            } finally {
                // A killed router must not leave the selected transfer text in a rendezvous file.
                Files.deleteIfExists(response); Files.deleteIfExists(file); Files.deleteIfExists(ready)
            }
        }
    }
}
