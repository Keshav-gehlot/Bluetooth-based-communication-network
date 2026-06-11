package com.meshchat.util

fun generateRoomCode(): String {
    val allowedChars = ('A'..'Z') + ('0'..'9')
    return (1..5).map { allowedChars.random() }.joinToString("")
}
