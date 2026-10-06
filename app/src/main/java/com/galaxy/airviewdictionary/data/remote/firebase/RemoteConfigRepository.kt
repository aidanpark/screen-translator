package com.galaxy.airviewdictionary.data.remote.firebase

import android.content.Context
import com.galaxy.airviewdictionary.R
import com.galaxy.airviewdictionary.data.local.ads.AdGatePolicy
import org.json.JSONObject
import com.google.gson.JsonParser
import com.google.firebase.Firebase
import com.google.firebase.remoteconfig.ConfigUpdate
import com.google.firebase.remoteconfig.ConfigUpdateListener
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigException
import com.google.firebase.remoteconfig.FirebaseRemoteConfigValue
import com.google.firebase.remoteconfig.get
import com.google.firebase.remoteconfig.remoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class RemoteConfigRepository @Inject constructor(@ApplicationContext val context: Context) {

    private val TAG = javaClass.simpleName

    companion object PreferencesKeys {
        const val SERVICE_AVAILABLE_KEY = "service_available"
        const val LATEST_VERSION_CODE_KEY = "latest_version_code"
        const val FORCE_UPDATE_VERSION_CODE_KEY = "force_update_version_code"
        const val AD_UNIT_ID = "ad_unit_id"

        // AI 번역 엔진별 모델 후보. 설정 UI 가 이 목록을 노출하고, 각 Kit 이 앞에서부터 시도한다.
        // { "openai": [...], "gemini": [...], "claude": [...] } 형식의 한 항목.
        // 2.6.0~2.7.1 의 openai_/gemini_/claude_translate_models 세 항목을 합친 것이다.
        const val TRANSLATE_MODELS = "translate_models"

        // 광고 게이트 동작 정책. JSON 한 항목으로 담는다.
        // { "failure_threshold": 3, "backoff_hours": 24, "skip_cooldown_seconds": 60 }
        // 자세한 의미는 [AdGatePolicy] 참조.
        const val AD_GATE_FAILURE_BACKOFF = "ad_gate_failure_backoff"

        // PP-OCRv5 끄기 스위치(.docs/vision-engine-design.md §19). 둘 다 기본 켜짐 — 값을 못 받았으면 켜진 것으로 본다.
        // PADDLE_OCR_ENABLED 를 끄면 PP-OCRv5 를 아예 쓰지 않는다(지정 언어·auto 모두 ML Kit 으로).
        // PADDLE_OCR_AUTO_ENABLED 를 끄면 auto 에서만 PP-OCRv5 표본을 돌리지 않는다.
        const val PADDLE_OCR_ENABLED = "paddle_ocr_enabled"
        const val PADDLE_OCR_AUTO_ENABLED = "paddle_ocr_auto_enabled"

        // CLAUDE_IMAGE_ENABLED 를 끄면 Claude 도 OCR 글을 번역한다 — 화면 이미지를 보내지 않는다(§25).
        const val CLAUDE_IMAGE_ENABLED = "claude_image_enabled"

        /**
         * 끄기 스위치 하나(PP-OCRv5 §19 · Claude 이미지 §25). 값을 아직 못 받았으면(기본값도 설정 전이면 `VALUE_SOURCE_STATIC`) 켜진 것으로 본다 — 앱
         * 시작 직후나 시험에서 꺼지면 안 된다. 주입 없이 부르는 곳(`PaddleSwitch`, `ImageTranslation.Switch`)이 같이 쓴다(코드 정리 B7).
         */
        fun switchOn(key: String): Boolean = runCatching {
            val value = Firebase.remoteConfig.getValue(key)
            value.source == FirebaseRemoteConfig.VALUE_SOURCE_STATIC || value.asBoolean()
        }.getOrDefault(true)

        /**
         * [SERVICE_AVAILABLE_KEY] 의 값(`{"default": true, "KR": false}` 형식)에서 [country] 의 서비스 가능 여부. 값이 없거나 형식이 깨졌으면
         * 열린 것으로 본다 — 막으려면 콘솔에 false 를 명시한다(앱 기본값 XML 도 true). Gson 으로 읽는다 — JVM 단위 시험에서도 돈다.
         * 값이 문자열 "true" · "false"(대소문자 무시)여도 불리언으로 읽는다 — 예전 org.json 의 `getBoolean` · `optBoolean` 과 같다.
         */
        fun serviceAvailable(raw: String?, country: String): Boolean = try {
            val json = raw?.takeIf { it.isNotBlank() }?.let { JsonParser.parseString(it) }?.takeIf { it.isJsonObject }?.asJsonObject
            fun flag(name: String): Boolean? {
                val value = json?.get(name)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
                return when {
                    value.isBoolean -> value.asBoolean
                    value.isString && value.asString.equals("true", ignoreCase = true) -> true
                    value.isString && value.asString.equals("false", ignoreCase = true) -> false
                    else -> null
                }
            }
            flag(country) ?: flag("default") ?: true
        } catch (e: Exception) {
            Timber.tag("RemoteConfigRepository").w(e, "$SERVICE_AVAILABLE_KEY JSON 파싱 실패: '$raw'")
            true
        }
    }

    /**
     * 광고 게이트 정책. Remote Config 의 JSON 을 파싱한다.
     * 값이 비었으면 [AdGatePolicy.DEFAULT], 형식이 깨졌으면 [AdGatePolicy.FALLBACK](억제 끔) 을 돌려준다.
     * 누락된 필드는 DEFAULT 값으로 채워, 항목 일부만 설정해도 나머지는 앱 기본값대로 동작한다
     * (코드 정리 A7 — 예전에는 FALLBACK 으로 채워 일부만 바꾸면 억제가 조용히 꺼졌다).
     */
    fun getAdGatePolicy(): AdGatePolicy {
        val raw = remoteConfig[AD_GATE_FAILURE_BACKOFF].asString()
        if (raw.isBlank()) return AdGatePolicy.DEFAULT
        return try {
            val json = JSONObject(raw)
            AdGatePolicy(
                failureThreshold = json.optInt(
                    "failure_threshold", AdGatePolicy.DEFAULT.failureThreshold
                ),
                backoffHours = json.optInt(
                    "backoff_hours", AdGatePolicy.DEFAULT.backoffHours
                ),
                skipCooldownSeconds = json.optInt(
                    "skip_cooldown_seconds", AdGatePolicy.DEFAULT.skipCooldownSeconds
                ),
            )
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "$AD_GATE_FAILURE_BACKOFF JSON 파싱 실패: '$raw'")
            AdGatePolicy.FALLBACK
        }
    }

    /** 리워드 광고 단위 ID. */
    fun adUnitId(): String = remoteConfig[AD_UNIT_ID].asString()

    /** OpenAI 번역 모델 후보 목록. (기본값은 res/xml/remote_config_defaults.xml 참조) */
    fun getOpenAiTranslateModels(): List<String> = getTranslateModels("openai")

    /** Gemini 번역 모델 후보 목록. */
    fun getGeminiTranslateModels(): List<String> = getTranslateModels("gemini")

    /** Claude 번역 모델 후보 목록. */
    fun getClaudeTranslateModels(): List<String> = getTranslateModels("claude")

    /**
     * [TRANSLATE_MODELS] JSON 에서 엔진 하나의 모델 후보를 꺼낸다.
     * 항목이 없거나 형식이 깨졌으면 빈 목록 — 설정 UI 는 빈 목록을 이미 처리한다.
     */
    private fun getTranslateModels(engine: String): List<String> {
        val raw = remoteConfig[TRANSLATE_MODELS].asString()
        if (raw.isBlank()) return emptyList()
        return try {
            val array = JSONObject(raw).optJSONArray(engine) ?: return emptyList()
            (0 until array.length())
                .map { array.optString(it).trim() }
                .filter { it.isNotBlank() }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "$TRANSLATE_MODELS JSON 파싱 실패: '$raw'")
            emptyList()
        }
    }

    private val remoteConfig: FirebaseRemoteConfig = Firebase.remoteConfig

    private val _remoteConfigFlow = MutableStateFlow<Map<String, FirebaseRemoteConfigValue>>(emptyMap())

    val remoteConfigFlow: StateFlow<Map<String, FirebaseRemoteConfigValue>> get() = _remoteConfigFlow

    private fun retrieveConfig() {
        Timber.tag(TAG).d("SERVICE_AVAILABLE_KEY ${remoteConfig[SERVICE_AVAILABLE_KEY].asString()}")
        Timber.tag(TAG).d("LATEST_VERSION_CODE_KEY ${remoteConfig[LATEST_VERSION_CODE_KEY].asString()}")
        Timber.tag(TAG).d("FORCE_UPDATE_VERSION_CODE_KEY ${remoteConfig[FORCE_UPDATE_VERSION_CODE_KEY].asString()}")
        Timber.tag(TAG).d("AD_UNIT_ID ${remoteConfig[AD_UNIT_ID].asString()}")
        Timber.tag(TAG).d("TRANSLATE_MODELS ${remoteConfig[TRANSLATE_MODELS].asString()}")
        _remoteConfigFlow.value = remoteConfig.all
    }

    init {
        remoteConfig.setConfigSettingsAsync(remoteConfigSettings {
            minimumFetchIntervalInSeconds = 60 * 60 * 24
        })

        // 기본값을 넣은 뒤 지금 값(지난번에 활성화한 값 + 기본값)을 한 번 내보낸다 — fetch 가 실패해도(오프라인) 흐름을 구독하는 곳이 직접 읽는
        // 곳과 같은 값을 본다(코드 정리 B7 — 예전에는 fetch 성공 전까지 빈 맵이라 최신 버전 코드가 0 이었다)
        remoteConfig.setDefaultsAsync(R.xml.remote_config_defaults)
            .addOnCompleteListener { retrieveConfig() }

        // [START fetch_config_with_callback]
        remoteConfig.fetchAndActivate()
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val updated = task.result
                    Timber.tag(TAG).d("Config params updated: $updated")
                    retrieveConfig()
                } else {
                    Timber.tag(TAG).d("Fetch failed")
                }
            }
        // [END fetch_config_with_callback]

        // [START add_config_update_listener]
        remoteConfig.addOnConfigUpdateListener(object : ConfigUpdateListener {
            override fun onUpdate(configUpdate: ConfigUpdate) {
                Timber.tag(TAG).i("Updated keys: %s", configUpdate.updatedKeys)

                remoteConfig.activate().addOnCompleteListener {
                    Timber.tag(TAG).i("------------------- onUpdate ------------------")
                    retrieveConfig()
                }
            }

            override fun onError(error: FirebaseRemoteConfigException) {
                Timber.tag(TAG).w("Config update error with code: %s", error.code)
            }
        })
    }
}










