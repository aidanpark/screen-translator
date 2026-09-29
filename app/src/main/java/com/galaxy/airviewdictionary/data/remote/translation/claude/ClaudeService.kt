package com.galaxy.airviewdictionary.data.remote.translation.claude

import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST

/**
 * Anthropic Claude Messages API. 전용 번역 엔드포인트가 없으므로 messages 에 번역 프롬프트를 보낸다.
 * 인증은 x-api-key 헤더, API 버전은 anthropic-version 헤더로 전달한다.
 */
interface ClaudeService {

    @Headers("Content-Type: application/json", "anthropic-version: 2023-06-01")
    @POST("v1/messages")
    suspend fun messages(
        @Header("x-api-key") apiKey: String,
        @Body body: RequestBody,
    ): ClaudeResponse
}

data class ClaudeResponse(
    val content: List<ClaudeContentBlock>?,
    /** end_turn · max_tokens · refusal 등. 구조화 출력은 refusal·max_tokens 면 스키마를 지키지 못할 수 있다. */
    val stop_reason: String? = null,
    val usage: ClaudeUsage? = null,
)
data class ClaudeContentBlock(val type: String?, val text: String?)
data class ClaudeUsage(val input_tokens: Int?, val output_tokens: Int?)
