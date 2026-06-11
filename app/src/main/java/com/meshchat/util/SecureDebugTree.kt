package com.meshchat.util

import timber.log.Timber
import java.util.regex.Pattern

class SecureDebugTree : Timber.DebugTree() {
    // Regex matches base64 like strings (32+ chars)
    private val base64Pattern = Pattern.compile("[A-Za-z0-9+/]{32,}={0,2}")
    
    // Redact UUID-like node identifiers, payloads, and key material fields.
    private val nodeIdPattern = Pattern.compile("nodeId=[^,\\s]+")
    private val payloadPattern = Pattern.compile("payload=\\[.*?\\]|payload=[^,\\s]+|authTag=[^,\\s]+|tag=[^,\\s]+")
    private val keyFieldPattern = Pattern.compile("(key|meshKey|wrappedKey|publicKey)=[^,\\s]+")

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        var redactedMessage = message
        
        redactedMessage = base64Pattern.matcher(redactedMessage).replaceAll("[REDACTED_KEY]")
        redactedMessage = nodeIdPattern.matcher(redactedMessage).replaceAll("nodeId=[REDACTED]")
        redactedMessage = payloadPattern.matcher(redactedMessage).replaceAll("[REDACTED_DATA]")
        redactedMessage = keyFieldPattern.matcher(redactedMessage).replaceAll("$1=[REDACTED]")
        
        super.log(priority, tag, redactedMessage, t)
    }
}
