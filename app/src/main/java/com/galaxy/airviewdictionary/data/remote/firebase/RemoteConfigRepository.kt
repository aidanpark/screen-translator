package com.galaxy.airviewdictionary.data.remote.firebase

import android.content.Context
import com.galaxy.airviewdictionary.R
import com.galaxy.airviewdictionary.data.local.ads.AdGatePolicy
import org.json.JSONObject
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
    }

    /**
     * 광고 게이트 정책. Remote Config 의 JSON 을 파싱한다.
     * 값이 비었거나 형식이 깨졌으면 [AdGatePolicy.FALLBACK] 을 돌려준다.
     * 누락된 필드는 FALLBACK 값으로 채워, 항목 일부만 설정해도 동작한다.
     */
    fun getAdGatePolicy(): AdGatePolicy {
        val raw = remoteConfig[AD_GATE_FAILURE_BACKOFF].asString()
        if (raw.isBlank()) return AdGatePolicy.FALLBACK
        return try {
            val json = JSONObject(raw)
            AdGatePolicy(
                failureThreshold = json.optInt(
                    "failure_threshold", AdGatePolicy.FALLBACK.failureThreshold
                ),
                backoffHours = json.optInt(
                    "backoff_hours", AdGatePolicy.FALLBACK.backoffHours
                ),
                skipCooldownSeconds = json.optInt(
                    "skip_cooldown_seconds", AdGatePolicy.FALLBACK.skipCooldownSeconds
                ),
            )
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "$AD_GATE_FAILURE_BACKOFF JSON 파싱 실패: '$raw'")
            AdGatePolicy.FALLBACK
        }
    }

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

        remoteConfig.setDefaultsAsync(R.xml.remote_config_defaults)

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
                Timber.tag(TAG).i(TAG, "Updated keys: %s", configUpdate.updatedKeys)

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










