package com.meshchat.domain.model

data class ChatPayload(
    val dst: String,
    val text: String
) {
    fun encode(): String = buildString {
        append(dst)
        append('\n')
        append(text)
    }

    companion object {
        fun decode(raw: String): ChatPayload? {
            val separatorIndex = raw.indexOf('\n')
            if (separatorIndex <= 0) return null
            val dst = raw.substring(0, separatorIndex)
            val text = raw.substring(separatorIndex + 1)
            return ChatPayload(dst = dst, text = text)
        }
    }
}