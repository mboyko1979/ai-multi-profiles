package dev.boyko.aiagentswitcher.compat

import com.google.gson.JsonObject
import com.intellij.ml.llm.core.chat.session.ChatSessionVm
import com.intellij.ml.llm.core.chat.session.ChatSessionState
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import dev.boyko.aiagentswitcher.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal object CurrentChatProfileSwitch {
    private fun params(id: String, count: Any = 5) = JsonObject().apply {
        addProperty("profile", id)
        if (count is Int) addProperty("exchanges", count) else addProperty("exchanges", "all")
    }
    suspend fun choose(chat: ChatSessionVm, id: String) {
        if (id == "Unassigned") return // Default Profile is changed only in project settings.
        val local = ChatProfiles.conversationRecord(chat.uid)
        if (LocalChatState.readOnly(local)) {
            LocalChatState.select(local!!, id)
            ChatHandoffClient(chat).reconnect()
            return
        }
        if (chat.state != ChatSessionState.Idle) return
        if ((PreparedAssistantProfiles.selected(chat.session) ?: local?.get("profile")?.asString
            ?: ProfileChatCreator.pendingProfile(chat.uid)) == id) return
        // Profile selection keeps this draft and its native input document. The
        // prepared providers remain live until the first prompt commits one.
        if (local?.getAsJsonArray("exchanges")?.isEmpty == true || (local == null && chat.session.messages.isEmpty())) {
            try {
                // An unassigned setup has no prepared provider to retain yet;
                // preserve the native initial-profile launch boundary.
                if (local?.get("kind")?.asString == "setup" ||
                    (local == null && ProfileChatCreator.pendingProfile(chat.uid) == null && chat.project.service<ProjectSelection>().selected == "Unassigned"))
                    openProfileChat(chat, id)
                else PreparedAssistantProfiles.choose(chat, id)
            }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                com.intellij.openapi.diagnostic.Logger.getInstance(CurrentChatProfileSwitch::class.java).warn("Could not bind the selected prepared profile", error)
                ChatProfileSwitch.report(chat.project, "Could not select launch profile", error.cause?.message ?: error.message ?: "Profile selection failed")
            }
            return
        }
        var preparedClient: ChatHandoffClient? = null
        var preparedToken: String? = null
        // Acquisition must return its cleanup handle even if the caller is cancelled
        // while EDT installs the overlay.
        val ui = withContext(NonCancellable + Dispatchers.EDT) { ChatSwitchUi.begin(chat.project) } ?: return
        try {
            // A terminal provider error can discard the native client session. Calling
            // describe through withClientSession would restore the exhausted provider
            // before the user ever sees the transfer dialog. Stage the explicit target
            // for the router's consented recovery path instead.
            if (local?.get("sessionId")?.asString?.startsWith("aiaps-chat-") == true &&
                ChatHandoffClient(chat).nativeSessionOrNull() == null) {
                LocalChatState.selectDisconnected(local, id)
                ChatHandoffClient(chat).reconnect()
                return
            }
            ChatTitleRepair.repair(chat)
            val client = ChatHandoffClient(chat)
            val history = withContext(Dispatchers.IO) { NativeHistory.assistant(chat.uid) }
            fun transferParams(count: Int) = params(id, count).apply { add("history", history) }
            val state = client.control("_ai_multi/describe")
            if (state.get("busy")?.asBoolean == true || chat.state != ChatSessionState.Idle) return
            // Local setup sessions have no provider or history to hand off.
            // Bind the first real chat directly, leaving the project default alone.
            if (state.get("profile")?.asString == "Unassigned") {
                openProfileChat(chat, id)
                return
            }
            // ACP initializes on focus, before the user sends anything. There is no
            // history to transfer or task to continue in this still-empty chat.
            if (state.get("virtual")?.asBoolean == true && state.get("exchanges")?.asInt == 0 &&
                state.get("busy")?.asBoolean == false) {
                PreparedAssistantProfiles.choose(chat, id)
                return
            }
            val from = service<AgentProfiles>().find(state.get("profile").asString)?.name ?: "Unavailable profile"
            val to = service<AgentProfiles>().find(id)?.name ?: error("Profile was removed")
            val estimates = linkedMapOf<Int, JsonObject>()
            for (count in 0..20) estimates[count] = client.control("_ai_multi/estimate", transferParams(count))
            if (chat.state != ChatSessionState.Idle) return
            val dialog = withContext(Dispatchers.EDT) { ProfileTransferDialog(chat.project, from, to, estimates, state.get("virtual")?.asBoolean != true).also { it.show() } }
            if (!dialog.isOK) return
            check(chat.state == ChatSessionState.Idle) { "Stop the active task before switching profiles" }
            if (dialog.newChat) {
                PreparedAssistantProfiles.choose(chat, id)
            } else {
                client.verifyTaskAdapter()
                val p = transferParams(dialog.count).apply {
                    addProperty("comment", dialog.comment); addProperty("consent", true); add("attachmentIds", dialog.attachmentIds)
                    add("nativeSource", JsonObject().apply { addProperty("host", "assistant"); addProperty("sessionId", chat.uid); addProperty("eventId", history.lastOrNull()?.asJsonObject?.let { it.get("lastEventId") ?: it.get("id") }?.asString.orEmpty()) })
                }
                val estimate = client.control("_ai_multi/estimate", p.deepCopy())
                p.add("revision", estimate.get("revision"))
                val switched = client.control("_ai_multi/switch", p)
                client.updateSteeringSupport(switched.get("supportsSteering")?.asBoolean == true)
                ApplicationManager.getApplication().messageBus.syncPublisher(ProfilesListener.TOPIC).changed()
                if (switched.has("token") && !switched.get("token").isJsonNull) {
                    val token = switched.get("token").asString
                    preparedClient = client; preparedToken = token
                    client.continueNow(token, dialog.comment) {
                        // Once the native task starts, its own progress/cancel controls take over.
                        withContext(Dispatchers.EDT) { ui.close() }
                    }
                }
            }
        } catch (e: Exception) {
            // Roll back only if no provider prompt consumed the handoff. Never undo a running/completed task.
            if (preparedToken != null) withContext(NonCancellable) {
                runCatching {
                    val aborted = preparedClient?.control("_ai_multi/abort_handoff", JsonObject().apply { addProperty("token", preparedToken) })
                    if (aborted?.get("aborted")?.asBoolean == true)
                        preparedClient.updateSteeringSupport(aborted.get("supportsSteering")?.asBoolean == true)
                    ApplicationManager.getApplication().messageBus.syncPublisher(ProfilesListener.TOPIC).changed()
                }
            }
            if (e is CancellationException) throw e
            val target = service<AgentProfiles>().find(id)
            if (target != null && ProfileSettingsPrompt.explains(e)) ProfileSettingsPrompt.open(chat.project, target)
            else ChatProfileSwitch.report(chat.project, "Could not switch chat profile", e.cause?.message ?: e.message ?: "Profile switch failed")
        } finally {
            withContext(NonCancellable + Dispatchers.EDT) { ui.close() }
        }
    }

    private suspend fun openProfileChat(chat: ChatSessionVm, id: String) {
        ProfileChatCreator.create(chat.project, chat.currentChatMode, id)
        ApplicationManager.getApplication().messageBus.syncPublisher(ProfilesListener.TOPIC).changed()
    }

}
