package com.licenta.e_ajutor.network

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface OpenAiApi {
    /**
     * Calls the OpenAI Chat Completions endpoint,
     *
     * @param request the payload containing model, messages, etc.
     * @return a ChatResponse wrapping the list of choices
     */
    @POST("v1/chat/completions")
    suspend fun createChat(
        @Body request: ChatRequest
    ): ChatResponse
}
