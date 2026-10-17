@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package dev.boyko.aiagentswitcher.compat

import com.agentclientprotocol.model.AuthMethod
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.ml.llm.agents.acp.client.AcpChatOptionChooser
import com.intellij.ml.llm.agents.acp.client.AcpEvent
import com.intellij.ml.llm.agents.acp.client.AcpUserResponse
import com.intellij.ml.llm.agents.acp.client.auth.AcpAuthenticationChooser
import com.intellij.ml.llm.aui.events.api.ChoiceInputRequest
import com.intellij.ml.llm.aui.events.api.ChoiceInputResponse
import com.intellij.ml.llm.aui.events.api.ToolBlockUpdatedEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.Json

/** Use the chat's ACP chooser on a profile-owned connection, without creating a chat. */
internal object ProfileAuthenticationChooser {
    fun choose(methods: JsonArray, show: (String, ChoiceInputRequest, (String) -> Unit) -> Unit): JsonObject? = runBlocking {
        val json = Json { ignoreUnknownKeys = true }
        val advertised = methods.map { json.decodeFromString(AuthMethod.serializer(), it.toString()) }
        val events = Channel<AcpEvent>(Channel.UNLIMITED)
        val responses = MutableSharedFlow<AcpUserResponse>(replay = 1)
        val renderer = launch {
            for (event in events) {
                val block = (event as? AcpEvent.CustomAgentEvent)?.agentEvent as? ToolBlockUpdatedEvent ?: continue
                val request = block.approvalInputRequest as? ChoiceInputRequest ?: continue
                show(block.title ?: block.text.orEmpty(), request) { id ->
                    responses.tryEmit(AcpUserResponse.RequestResponse(ChoiceInputResponse(request.id, id)))
                }
            }
        }
        try {
            val result = AcpAuthenticationChooser.chooseAuthOption(
                AcpChatOptionChooser(events, responses), false, emptyList(), advertised,
                false, "profile-authorization", false)
            val method = (result.selection as? AcpAuthenticationChooser.AuthSelection.Agent)?.acpAuthMethod
            methods.firstOrNull { it.asJsonObject.get("id").asString == method?.id?.value }?.asJsonObject
        } finally {
            renderer.cancel(); events.close()
        }
    }
}
