package com.meshchat.core

sealed class AnomalyEvent {
    data class ReplayAttack(val packetId: String, val senderId: String) : AnomalyEvent()
    data class RateLimitExceeded(val senderId: String) : AnomalyEvent()
    data class HopLimitExceeded(val packetId: String) : AnomalyEvent()
    data class InvalidSignature(val packetId: String) : AnomalyEvent()
}
