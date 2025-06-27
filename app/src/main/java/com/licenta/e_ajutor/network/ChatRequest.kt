package com.licenta.e_ajutor.network

import kotlinx.serialization.Serializable

@Serializable
data class ChatRequest (
    val model: String = "gpt-4o",
    val messages: List<AiChatMessage>
)

@Serializable
data class AiChatMessage(
    val role: String,
    val content: String
)

