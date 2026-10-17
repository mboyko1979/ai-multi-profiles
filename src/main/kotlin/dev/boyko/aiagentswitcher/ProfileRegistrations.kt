package dev.boyko.aiagentswitcher

import com.google.gson.JsonObject

/** Retire old profile aliases from the ACP registry shared by AI Assistant and Air.
 * Profiles belong to AI Multi and must not be published as separate agents.
 */
internal object ProfileRegistrations {
    const val PREFIX = "AI Multi · "
    fun cleanup(servers: JsonObject, previous: JsonObject): JsonObject {
        for ((name, entry) in previous.entrySet()) {
            if (name.startsWith(PREFIX) && servers.get(name) == entry) servers.remove(name)
        }
        return JsonObject()
    }
}
