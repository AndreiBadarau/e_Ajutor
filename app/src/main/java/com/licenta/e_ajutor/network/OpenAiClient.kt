package com.licenta.e_ajutor.network

import android.util.Log
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.licenta.e_ajutor.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit


object OpenAiClient {
    private const val BASE_URL = "https://api.openai.com/"

    // Configure JSON parsing
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    // OkHttp client that injects the Authorization header
    private val httpClient: OkHttpClient by lazy {
        Log.d("OpenAIClientInit", "Key = ${BuildConfig.OPENAI_API_KEY.take(8)}…")
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader(
                        "Authorization",
                        "Bearer ${BuildConfig.OPENAI_API_KEY}"
                    )
                    .build()
                chain.proceed(request)
            })
            .build()
    }

    val api: OpenAiApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(httpClient)
            .addConverterFactory(
                json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(OpenAiApi::class.java)
    }

}