package dev.boyko.aiagentswitcher

/** Installed Air exposes sendPrompt's default bridge with a hyphenated Kotlin name. */
class AirPromptAdmissionFixture {
    class SessionPromptRequest

    companion object {
        @JvmStatic
        fun `sendPrompt-moved$default`(
            manager: AirPromptAdmissionFixture?,
            ref: Any?,
            request: SessionPromptRequest?,
            operation: Long,
            steer: Boolean,
            continuation: Any?,
            mask: Int,
            marker: Any?,
        ): Any? = request
    }
}

class AirPromptAdmissionMismatch {
    companion object {
        @JvmStatic
        fun `sendPrompt-short$default`(manager: Any?): Any? = null
    }
}
