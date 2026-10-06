package com.galaxy.airviewdictionary.data.remote.translation.openai

import com.galaxy.airviewdictionary.data.remote.translation.KeyValidationResult
import com.galaxy.airviewdictionary.data.remote.translation.ApiKeyStore
import com.galaxy.airviewdictionary.data.remote.translation.AiTranslationKit
import android.content.Context
import com.galaxy.airviewdictionary.data.local.preference.PreferenceRepository
import com.galaxy.airviewdictionary.data.local.secure.SecureStore
import com.galaxy.airviewdictionary.data.local.secure.SecureStoreKey
import com.galaxy.airviewdictionary.data.remote.firebase.RemoteConfigRepository
import com.galaxy.airviewdictionary.data.remote.translation.Transaction
import com.galaxy.airviewdictionary.data.remote.translation.TranslationKitType
import com.galaxy.airviewdictionary.data.remote.translation.buildTranslationUserMessage
import com.galaxy.airviewdictionary.data.remote.translation.TranslationResponse
import com.galaxy.airviewdictionary.data.remote.translation.goolge.GoogleWebKit
import com.galaxy.airviewdictionary.di.OpenAiRetrofit
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OpenAI 번역 엔진. 전용 번역 API 대신 Chat Completions 에 번역 프롬프트를 보내 사용한다.
 * 사용자가 발급받은 개인 API 키로 동작하며, 키는 설정 > API Key > OpenAI 에서 [SecureStore] 에 암호화 저장된다.
 * 사용할 모델은 설정에서 고르고, 후보 목록은 Firebase Remote Config
 * ([RemoteConfigRepository.TRANSLATE_MODELS])로 관리한다.
 * 저장된 키가 없으면 엔진은 비활성 상태이며 엔진 전환기에 노출되지 않는다.
 */
@Singleton
class OpenAiKit @Inject constructor(
    @ApplicationContext private val context: Context,
    @OpenAiRetrofit private val service: OpenAiService,
    googleWebKit: GoogleWebKit,
    private val preferenceRepository: PreferenceRepository,
    private val remoteConfigRepository: RemoteConfigRepository,
) : AiTranslationKit(TranslationKitType.OPENAI, googleWebKit) {

    override fun available(): Boolean {
        return getStoredApiKey(context) != null
    }

    init {
        refreshAvailability(context)
    }

    /**
     * 사용할 모델. 설정에서 고른 값이 Remote Config 후보에 있으면 그것을, 아니면 후보의 첫 번째를, 후보가 없으면 고른 값이나 기본값.
     */
    private suspend fun resolveModel(): String {
        return pickModel(preferenceRepository.openAiModelFlow.first(), remoteConfigRepository.getOpenAiTranslateModels(), DEFAULT_MODEL)
    }

    override suspend fun request(
        sourceLanguageCode: String,
        targetLanguageCode: String,
        sourceText: String
    ): TranslationResponse = request(sourceLanguageCode, targetLanguageCode, sourceText, null)

    override suspend fun request(
        sourceLanguageCode: String,
        targetLanguageCode: String,
        sourceText: String,
        contextText: String?,
    ): TranslationResponse {
        return try {
            val apiKey = getStoredApiKey(context) ?: throw IllegalStateException("OpenAI API key is not set.")
            val model = resolveModel()
            val strength = preferenceRepository.openAiTranslationStrengthFlow.first()
            val domain = preferenceRepository.openAiTranslationDomainFlow.first()
            val effectiveContext = contextText?.takeIf { it.isNotBlank() }
            val requestBody = mapOf(
                "model" to model,
                "messages" to listOf(
                    mapOf(
                        "role" to "system",
                        "content" to buildSystemPrompt(
                            sourceLanguageCode = sourceLanguageCode,
                            targetLanguageCode = targetLanguageCode,
                            strength = strength,
                            domain = domain,
                            hasContext = effectiveContext != null,
                        ),
                    ),
                    mapOf("role" to "user", "content" to buildTranslationUserMessage(sourceText, effectiveContext)),
                ),
                // temperature 를 보내지 않는다. 최신 세대(gpt-5.6-*)는 Claude 와 마찬가지로
                // 이 파라미터를 받지 않아 400 이 날 수 있다 — 목록 첫 모델이라 위험이 크다.
                // 생략하면 모델 기본값으로 동작하므로 어느 세대에서든 안전하다.
            )
            val json = Gson().toJson(requestBody).toRequestBody("application/json".toMediaType())
            val response = withContext(Dispatchers.IO) {
                service.chatCompletion("Bearer $apiKey", json)
            }
            val resultText = cleanOutput(response.choices.firstOrNull()?.message?.content.orEmpty())
            TranslationResponse.Success(
                Transaction(
                    targetLanguageCode = targetLanguageCode,
                    sourceText = sourceText,
                    translationKitType = TranslationKitType.OPENAI,
                    // AI 엔진은 원문 언어를 돌려주지 않는다. 지정 번역이면 그 언어가 곧 원문 언어다.
                    resolvedSourceLanguageCode = sourceLanguageCode.takeIf { it != "auto" },
                    resultText = resultText,
                    modelName = model,
                )
            )
        } catch (e: CancellationException) {
            // 취소는 오류가 아니다. 여기서 삼키면 핸들이 떠나 취소된 요청이
            // 실패 안내로 둔갑하고, 상위 코루틴은 취소된 줄 모른 채 계속 진행한다.
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w("request error: ${e.message}")
            TranslationResponse.Error(e)
        }
    }

    companion object {
        const val BASE_URL = "https://api.openai.com/"
        const val DEFAULT_MODEL = "gpt-4o-mini"

        // OpenAI 계정/키 발급 안내 링크
        const val URL_API_KEYS = "https://platform.openai.com/api-keys"
        const val URL_BILLING = "https://platform.openai.com/settings/organization/billing/overview"

        /**
         * 모델 목록([GET /v1/models])을 조회해 키 유효성을 검증한다. 토큰을 소모하지 않는다.
         */
        suspend fun validateApiKey(apiKey: String): KeyValidationResult = withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder()
                    .url("${BASE_URL}v1/models")
                    .header("Authorization", "Bearer ${apiKey.trim()}")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> KeyValidationResult.VALID
                        response.code == 401 -> KeyValidationResult.INVALID
                        else -> KeyValidationResult.NETWORK_ERROR
                    }
                }
            } catch (e: Exception) {
                Timber.tag("OpenAiKit").w("validateApiKey error: $e")
                KeyValidationResult.NETWORK_ERROR
            }
        }

        /** 개인 API 키와 등록 여부([ApiKeyStore]). 엔진 전환기 노출과 설정의 활성 표시가 [keyActivatedStateFlow] 를 따른다. */
        private val keys = ApiKeyStore(SecureStoreKey.OPENAI_API_KEY, "OpenAiKit")

        val keyActivatedStateFlow: StateFlow<Boolean> get() = keys.activated

        fun refreshAvailability(context: Context) = keys.refresh(context)

        fun getStoredApiKey(context: Context): String? = keys.get(context)

        fun storeApiKey(context: Context, apiKey: String) = keys.store(context, apiKey)
    }
}
