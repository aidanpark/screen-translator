package com.galaxy.airviewdictionary.data.remote.translation.gemini

import android.content.Context
import com.galaxy.airviewdictionary.data.local.preference.PreferenceRepository
import com.galaxy.airviewdictionary.data.local.secure.SecureStore
import com.galaxy.airviewdictionary.data.local.secure.SecureStoreKey
import com.galaxy.airviewdictionary.data.remote.firebase.RemoteConfigRepository
import com.galaxy.airviewdictionary.data.remote.translation.Language
import com.galaxy.airviewdictionary.data.remote.translation.Transaction
import com.galaxy.airviewdictionary.data.remote.translation.TranslationKit
import com.galaxy.airviewdictionary.data.remote.translation.TranslationKitType
import com.galaxy.airviewdictionary.data.remote.translation.TranslationDomain
import com.galaxy.airviewdictionary.data.remote.translation.TranslationStrength
import com.galaxy.airviewdictionary.data.remote.translation.buildTranslationSystemPrompt
import com.galaxy.airviewdictionary.data.remote.translation.buildTranslationUserMessage
import com.galaxy.airviewdictionary.data.remote.translation.TranslationResponse
import com.galaxy.airviewdictionary.data.remote.translation.goolge.GoogleWebKit
import com.galaxy.airviewdictionary.di.GeminiRetrofit
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * Google Gemini 번역 엔진. 전용 번역 API 대신 Generative Language API 의 generateContent 에
 * 번역 프롬프트를 보내 사용한다. 사용자가 발급받은 개인 API 키로 동작하며,
 * 키는 설정 > API Key > Gemini 에서 [SecureStore] 에 암호화 저장된다.
 * 사용할 모델은 설정에서 고르고, 후보 목록은 Firebase Remote Config
 * ([RemoteConfigRepository.TRANSLATE_MODELS])로 관리한다.
 * 저장된 키가 없으면 엔진은 비활성 상태이며 엔진 전환기에 노출되지 않는다.
 */
@Singleton
class GeminiKit @Inject constructor(
    @ApplicationContext private val context: Context,
    @GeminiRetrofit private val service: GeminiService,
    private val googleWebKit: GoogleWebKit,
    private val preferenceRepository: PreferenceRepository,
    private val remoteConfigRepository: RemoteConfigRepository,
) : TranslationKit() {

    override fun available(): Boolean {
        return getStoredApiKey(context) != null
    }

    init {
        refreshAvailability(context)
    }

    // Gemini 는 사실상 전 언어를 번역하므로 Google 과 동일한 언어 커버리지를 사용한다.
    override val supportedLanguagesAsSource: List<Language> by lazy {
        googleWebKit.supportedLanguagesAsSource.map {
            Language(it.code).apply { supportKitTypes.add(TranslationKitType.GEMINI) }
        }
    }

    override val supportedLanguagesAsTarget: List<Language> by lazy {
        googleWebKit.supportedLanguagesAsTarget.map {
            Language(it.code).apply { supportKitTypes.add(TranslationKitType.GEMINI) }
        }
    }

    override fun isSupportedAsSource(code: String, targetLanguageCode: String): Boolean {
        return supportedLanguagesAsSource.any { it.code.equals(code, ignoreCase = true) } &&
                supportedLanguagesAsTarget.any { it.code.equals(targetLanguageCode, ignoreCase = true) }
    }

    override fun isSupportedAsTarget(code: String, sourceLanguageCode: String): Boolean {
        return supportedLanguagesAsTarget.any { it.code.equals(code, ignoreCase = true) } &&
                supportedLanguagesAsSource.any { it.code.equals(sourceLanguageCode, ignoreCase = true) }
    }

    override fun isLanguageSwappable(sourceLanguageCode: String, targetLanguageCode: String): Boolean {
        return isSupportedAsSource(targetLanguageCode, sourceLanguageCode) &&
                isSupportedAsTarget(sourceLanguageCode, targetLanguageCode)
    }

    private fun buildSystemPrompt(
        sourceLanguageCode: String,
        targetLanguageCode: String,
        strength: TranslationStrength,
        domain: TranslationDomain,
        hasContext: Boolean,
    ): String = buildTranslationSystemPrompt(
        sourceLanguageName = if (sourceLanguageCode == "auto") null else Language(sourceLanguageCode).displayName,
        targetLanguageName = Language(targetLanguageCode).displayName,
        strength = strength,
        domain = domain,
        hasContext = hasContext,
    )

    /**
     * 사용할 모델. 설정에서 고른 값이 있고 현재 후보에 있으면 그것을, 아니면 후보의 첫 번째를, 그마저 없으면 기본값.
     */
    private suspend fun resolveModel(): String {
        val chosen = preferenceRepository.geminiModelFlow.first()?.takeIf { it.isNotBlank() }
        val candidates = remoteConfigRepository.getGeminiTranslateModels()
        return when {
            chosen != null && chosen in candidates -> chosen
            candidates.isNotEmpty() -> candidates.first()
            else -> chosen ?: DEFAULT_MODEL
        }
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
            val apiKey = getStoredApiKey(context) ?: throw IllegalStateException("Gemini API key is not set.")
            val model = resolveModel()
            val strength = preferenceRepository.geminiTranslationStrengthFlow.first()
            val domain = preferenceRepository.geminiTranslationDomainFlow.first()
            val effectiveContext = contextText?.takeIf { it.isNotBlank() }
            val requestBody = mapOf(
                "systemInstruction" to mapOf(
                    "parts" to listOf(
                        mapOf(
                            "text" to buildSystemPrompt(
                                sourceLanguageCode = sourceLanguageCode,
                                targetLanguageCode = targetLanguageCode,
                                strength = strength,
                                domain = domain,
                                hasContext = effectiveContext != null,
                            )
                        )
                    )
                ),
                "contents" to listOf(
                    mapOf(
                        "role" to "user",
                        "parts" to listOf(
                            mapOf("text" to buildTranslationUserMessage(sourceText, effectiveContext))
                        ),
                    )
                ),
                "generationConfig" to mapOf("temperature" to 0),
            )
            val json = Gson().toJson(requestBody).toRequestBody("application/json".toMediaType())
            val response = withContext(Dispatchers.IO) {
                service.generateContent(model, apiKey, json)
            }
            val raw = response.candidates
                ?.firstOrNull()?.content
                ?.parts?.firstOrNull()?.text
                .orEmpty()
            TranslationResponse.Success(
                Transaction(
                    targetLanguageCode = targetLanguageCode,
                    sourceText = sourceText,
                    translationKitType = TranslationKitType.GEMINI,
                    // AI 엔진은 원문 언어를 돌려주지 않는다. 지정 번역이면 그 언어가 곧 원문 언어다.
                    resolvedSourceLanguageCode = sourceLanguageCode.takeIf { it != "auto" },
                    resultText = cleanOutput(raw),
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

    /** LLM 이 종종 붙이는 코드펜스/따옴표/여백을 정리한다. */
    private fun cleanOutput(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```").substringAfter('\n', "").trim()
            text = text.removeSuffix("```").trim()
        }
        if (text.length >= 2 &&
            ((text.first() == '"' && text.last() == '"') || (text.first() == '\'' && text.last() == '\''))
        ) {
            text = text.substring(1, text.length - 1).trim()
        }
        return text
    }

    /**
     * API 키 검증 결과. 네트워크 오류는 키 자체의 문제가 아니므로 무효와 구분한다.
     */
    enum class KeyValidationResult {
        VALID,
        INVALID,
        NETWORK_ERROR,
    }

    companion object {
        const val BASE_URL = "https://generativelanguage.googleapis.com/"

        const val DEFAULT_MODEL = "gemini-flash-lite-latest"

        // Gemini API 키 발급/사용량 안내 링크
        const val URL_API_KEYS = "https://aistudio.google.com/apikey"
        const val URL_BILLING = "https://ai.google.dev/pricing"

        /**
         * 모델 목록([GET /v1beta/models])을 조회해 키 유효성을 검증한다. 토큰을 소모하지 않는다.
         */
        suspend fun validateApiKey(apiKey: String): KeyValidationResult = withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder()
                    .url("${BASE_URL}v1beta/models")
                    .header("x-goog-api-key", apiKey.trim())
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> KeyValidationResult.VALID
                        response.code == 400 || response.code == 401 || response.code == 403 -> KeyValidationResult.INVALID
                        else -> KeyValidationResult.NETWORK_ERROR
                    }
                }
            } catch (e: Exception) {
                Timber.tag("GeminiKit").w("validateApiKey error: $e")
                KeyValidationResult.NETWORK_ERROR
            }
        }

        /**
         * 저장된 API 키 존재 여부. 엔진 전환기 노출과 설정의 활성 표시가 이 값을 따른다.
         */
        private val _keyActivatedStateFlow = MutableStateFlow(false)
        val keyActivatedStateFlow: StateFlow<Boolean> = _keyActivatedStateFlow.asStateFlow()

        fun refreshAvailability(context: Context) {
            _keyActivatedStateFlow.value = getStoredApiKey(context) != null
        }

        /** 설정에서 저장한 API 키. 없거나 공백이면 null. */
        fun getStoredApiKey(context: Context): String? {
            return SecureStore.get(context, SecureStoreKey.GEMINI_API_KEY)?.get()?.takeIf { it.isNotBlank() }
        }

        /** 설정에서 입력한 API 키를 암호화 저장한다. 빈 문자열 저장은 키 삭제로 동작한다. */
        fun storeApiKey(context: Context, apiKey: String) {
            SecureStore.set(context, SecureStoreKey.GEMINI_API_KEY, apiKey.trim())
            refreshAvailability(context)
            Timber.tag("GeminiKit").i("storeApiKey saved (${apiKey.trim().length} chars)")
        }
    }
}
