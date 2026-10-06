package com.galaxy.airviewdictionary.data.remote.translation.claude

import com.galaxy.airviewdictionary.data.remote.translation.KeyValidationResult
import com.galaxy.airviewdictionary.data.remote.translation.ApiKeyStore
import com.galaxy.airviewdictionary.data.remote.translation.AiTranslationKit
import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import androidx.annotation.VisibleForTesting
import com.galaxy.airviewdictionary.data.local.vision.TextDetectMode
import com.galaxy.airviewdictionary.data.local.preference.PreferenceRepository
import com.galaxy.airviewdictionary.data.local.secure.SecureStore
import com.galaxy.airviewdictionary.data.local.secure.SecureStoreKey
import com.galaxy.airviewdictionary.data.remote.firebase.RemoteConfigRepository
import com.galaxy.airviewdictionary.data.remote.translation.ImageTranslation
import com.galaxy.airviewdictionary.data.remote.translation.NoTextInImageException
import com.galaxy.airviewdictionary.data.remote.translation.Transaction
import com.galaxy.airviewdictionary.data.remote.translation.TranslationKitType
import com.galaxy.airviewdictionary.data.remote.translation.buildTranslationUserMessage
import com.galaxy.airviewdictionary.data.remote.translation.TranslationResponse
import com.galaxy.airviewdictionary.data.remote.translation.goolge.GoogleWebKit
import com.galaxy.airviewdictionary.di.ClaudeRetrofit
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
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Anthropic Claude 번역 엔진. 전용 번역 API 대신 Messages API 에 번역 프롬프트를 보내 사용한다.
 * 사용자가 발급받은 개인 API 키로 동작하며, 키는 설정 > API Key > Claude 에서
 * [SecureStore] 에 암호화 저장된다. 사용할 모델은 설정에서 고르고, 후보 목록은 Firebase Remote Config
 * ([RemoteConfigRepository.TRANSLATE_MODELS])로 관리한다.
 * 저장된 키가 없으면 엔진은 비활성 상태이며 엔진 전환기에 노출되지 않는다.
 */
@Singleton
class ClaudeKit @Inject constructor(
    @ApplicationContext private val context: Context,
    @ClaudeRetrofit private val service: ClaudeService,
    googleWebKit: GoogleWebKit,
    private val preferenceRepository: PreferenceRepository,
    private val remoteConfigRepository: RemoteConfigRepository,
) : AiTranslationKit(TranslationKitType.CLAUDE, googleWebKit) {

    override fun available(): Boolean {
        return getStoredApiKey(context) != null
    }

    init {
        refreshAvailability(context)
    }

    /** 기기 실측 시험이 원격 목록 밖의 모델을 잴 때 쓴다(`ClaudeImageLiveTest`). 앱은 쓰지 않는다. */
    @VisibleForTesting
    var modelOverride: String? = null

    /**
     * 사용할 모델. 설정에서 고른 값이 있고 현재 후보에 있으면 그것을, 아니면 후보의 첫 번째를, 그마저 없으면 기본값.
     */
    private suspend fun resolveModel(): String {
        modelOverride?.let { return it }
        return pickModel(preferenceRepository.claudeModelFlow.first(), remoteConfigRepository.getClaudeTranslateModels(), DEFAULT_MODEL)
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
            val apiKey = getStoredApiKey(context) ?: throw IllegalStateException("Claude API key is not set.")
            val model = resolveModel()
            val strength = preferenceRepository.claudeTranslationStrengthFlow.first()
            val domain = preferenceRepository.claudeTranslationDomainFlow.first()
            val effectiveContext = contextText?.takeIf { it.isNotBlank() }
            val requestBody = mapOf(
                "model" to model,
                "max_tokens" to 4096,
                // temperature 를 보내지 않는다.
                // Claude Sonnet 5 / Opus 5 / Opus 4.8 은 `temperature` is deprecated for this model
                // 으로 400 을 낸다(2026-09-20 실측). Haiku 4.5 는 있으나 없으나 동일하게 동작하므로
                // 모델별 분기 대신 아예 뺀다. 결정성은 system 프롬프트로 확보한다.
                //
                // effort 도 넣지 않는다: Sonnet 5 는 output_config.effort=low 로 약 0.7초 빨라지지만
                // Haiku 4.5 는 effort 를 지원하지 않아 400 이 난다. 모델별 분기가 필요한데
                // RC 로 모델이 바뀌는 구조라 목록 하드코딩이 쉽게 어긋난다.
                "system" to buildSystemPrompt(
                    sourceLanguageCode = sourceLanguageCode,
                    targetLanguageCode = targetLanguageCode,
                    strength = strength,
                    domain = domain,
                    hasContext = effectiveContext != null,
                ),
                "messages" to listOf(
                    mapOf(
                        "role" to "user",
                        "content" to buildTranslationUserMessage(sourceText, effectiveContext),
                    )
                ),
            )
            val json = Gson().toJson(requestBody).toRequestBody("application/json".toMediaType())
            val response = withContext(Dispatchers.IO) {
                service.messages(apiKey, json)
            }
            val raw = (response.content?.firstOrNull { it.type == "text" } ?: response.content?.firstOrNull())
                ?.text.orEmpty()
            TranslationResponse.Success(
                Transaction(
                    targetLanguageCode = targetLanguageCode,
                    sourceText = sourceText,
                    translationKitType = TranslationKitType.CLAUDE,
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

    /**
     * 화면 조각을 보내 읽기와 번역을 함께 맡긴다(AI 이미지 번역, `.docs/vision-engine-design.md` §25). [image] 는 `ImageCrop` 이 만든 조각이고,
     * [mode] 가 포인터 모드면 그 안에 포인터 표시가 그려져 있다. [sourceLanguageCode] 가 auto 면 모델이 판정한 언어도 돌려받는다.
     *
     * 응답은 구조화 출력(JSON 스키마)으로 받는다 — 앞머리 채우기(prefill)는 지금 모델(Sonnet 5, Opus 5 …)에서 400 이다. 스키마를 지원하지 않는
     * 모델을 Remote Config 목록에 넣으면 이 요청이 실패한다(지금 목록 Haiku 4.5·Sonnet 5·Opus 5 는 모두 지원). 문맥 글은 보내지 않는다 —
     * 조각에 주변 글이 이미 들어 있다. 글이 없다는 답이면 [NoTextInImageException].
     */
    suspend fun requestImage(
        sourceLanguageCode: String,
        targetLanguageCode: String,
        image: Bitmap,
        mode: TextDetectMode,
    ): TranslationResponse {
        return try {
            val apiKey = getStoredApiKey(context) ?: throw IllegalStateException("Claude API key is not set.")
            val model = resolveModel()
            val strength = preferenceRepository.claudeTranslationStrengthFlow.first()
            val domain = preferenceRepository.claudeTranslationDomainFlow.first()
            val auto = sourceLanguageCode.equals("auto", ignoreCase = true)

            val encodeStart = System.nanoTime()
            val imageBase64 = withContext(Dispatchers.Default) { image.toJpegBase64() }
            val encodeMs = (System.nanoTime() - encodeStart) / 1_000_000

            val requestBody = mapOf(
                "model" to model,
                "max_tokens" to 4096,
                // temperature·effort 는 텍스트 요청과 같은 이유로 보내지 않는다(위 request 참고)
                "system" to ImageTranslation.systemPrompt(
                    sourceLanguageName = if (auto) null else ImageTranslation.englishName(sourceLanguageCode),
                    targetLanguageName = ImageTranslation.englishName(targetLanguageCode),
                    strength = strength,
                    domain = domain,
                    mode = mode,
                ),
                "output_config" to mapOf(
                    "format" to mapOf("type" to "json_schema", "schema" to ImageTranslation.responseSchema(auto, mode)),
                ),
                "messages" to listOf(
                    mapOf(
                        "role" to "user",
                        "content" to listOf(
                            mapOf(
                                "type" to "image",
                                "source" to mapOf("type" to "base64", "media_type" to "image/jpeg", "data" to imageBase64),
                            )
                        ),
                    )
                ),
            )
            val json = Gson().toJson(requestBody).toRequestBody("application/json".toMediaType())
            val callStart = System.nanoTime()
            val response = withContext(Dispatchers.IO) { service.messages(apiKey, json) }
            val callMs = (System.nanoTime() - callStart) / 1_000_000
            // 이 경로의 성패는 지연과 토큰 비용에서 갈린다(§25). 릴리스에서는 R8 이 걷어낸다
            Timber.tag(TAG).i(
                "image request: ${image.width}x${image.height} jpeg=${imageBase64.length / 1024}KB encode=${encodeMs}ms call=${callMs}ms" +
                    " in=${response.usage?.input_tokens} out=${response.usage?.output_tokens} stop=${response.stop_reason} mode=$mode model=$model"
            )
            if (response.stop_reason == "refusal") throw IllegalStateException("Claude declined to read the image.")
            // 생각(thinking) 블록이 앞에 올 수 있다 — 첫 text 블록이 JSON 이다
            val raw = response.content?.firstOrNull { it.type == "text" }?.text.orEmpty()
            val reading = ImageTranslation.parse(raw)
                ?: throw IllegalStateException("Unexpected reply (${response.stop_reason}): ${raw.take(120)}")
            if (reading.isEmpty) throw NoTextInImageException()

            TranslationResponse.Success(
                Transaction(
                    targetLanguageCode = targetLanguageCode,
                    // 모델이 조각에서 직접 읽은 원문 — 번역창과 TTS 가 이것을 쓴다
                    sourceText = reading.source.takeIf { it.isNotBlank() },
                    translationKitType = TranslationKitType.CLAUDE,
                    // auto 면 모델이 판정한 언어, 원문 언어를 골랐으면 그 언어
                    resolvedSourceLanguageCode = if (auto) reading.language else sourceLanguageCode,
                    resultText = reading.translation,
                    modelName = model,
                    imageContext = reading.context,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w("image request error: ${e.message}")
            TranslationResponse.Error(e)
        }
    }

    /** 화면 캡처는 사진이 아니라 UI 라, 품질을 조금 낮춰도 글자 가독성은 유지된다. PNG 보다 훨씬 작다. */
    private fun Bitmap.toJpegBase64(): String {
        val stream = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    companion object {
        const val BASE_URL = "https://api.anthropic.com/"

        /** 원격 목록을 못 읽을 때의 모델 — 목록의 첫째와 같게 둔다(`remote_config_defaults.xml` 의 translate_models). */
        const val DEFAULT_MODEL = "claude-sonnet-5-5"

        private const val JPEG_QUALITY = 85

        // Claude API 키 발급/사용량 안내 링크
        const val URL_API_KEYS = "https://console.anthropic.com/settings/keys"
        const val URL_BILLING = "https://console.anthropic.com/settings/billing"

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
                    .header("x-api-key", apiKey.trim())
                    .header("anthropic-version", "2023-06-01")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> KeyValidationResult.VALID
                        response.code == 401 || response.code == 403 -> KeyValidationResult.INVALID
                        else -> KeyValidationResult.NETWORK_ERROR
                    }
                }
            } catch (e: Exception) {
                Timber.tag("ClaudeKit").w("validateApiKey error: $e")
                KeyValidationResult.NETWORK_ERROR
            }
        }

        /** 개인 API 키와 등록 여부([ApiKeyStore]). 엔진 전환기 노출과 설정의 활성 표시가 [keyActivatedStateFlow] 를 따른다. */
        private val keys = ApiKeyStore(SecureStoreKey.CLAUDE_API_KEY, "ClaudeKit")

        val keyActivatedStateFlow: StateFlow<Boolean> get() = keys.activated

        fun refreshAvailability(context: Context) = keys.refresh(context)

        fun getStoredApiKey(context: Context): String? = keys.get(context)

        fun storeApiKey(context: Context, apiKey: String) = keys.store(context, apiKey)
    }
}
