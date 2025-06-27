package com.licenta.e_ajutor.network

import kotlinx.serialization.Serializable

@Serializable
data class Choice(val message: AiChatMessage)

@Serializable
data class ChatResponse(
    val choices: List<Choice>
)