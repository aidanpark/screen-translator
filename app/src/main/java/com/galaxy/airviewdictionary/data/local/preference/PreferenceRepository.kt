package com.galaxy.airviewdictionary.data.local.preference

import com.galaxy.airviewdictionary.data.remote.translation.ImageTranslation
import android.content.Context
import android.speech.tts.Voice
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.galaxy.airviewdictionary.data.remote.translation.TranslationKitType
import com.galaxy.airviewdictionary.data.local.tts.TTSReadTarget
import com.galaxy.airviewdictionary.data.local.vision.TextDetectMode
import com.galaxy.airviewdictionary.ui.screen.overlay.menubar.MenuConfig
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import com.galaxy.airviewdictionary.data.remote.translation.TranslationContextMode
import com.galaxy.airviewdictionary.data.remote.translation.TranslationDomain
import com.galaxy.airviewdictionary.data.remote.translation.TranslationStrength
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton


val Context.preferenceDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "user_preferences"
)

@Singleton
class PreferenceRepository @Inject constructor(@ApplicationContext val context: Context) {

    private val TAG = javaClass.simpleName

    companion object PreferencesKeys {
        val WAS_TRAILER_SHOWN = booleanPreferencesKey("was_trailer_shown")
        val IS_REVIEW_DONE = booleanPreferencesKey("is_review_done")

        val IS_SAY_HERE_R_SHOWN = booleanPreferencesKey("is_say_here_r_shown")
        val IS_SAY_HERE_L_SHOWN = booleanPreferencesKey("is_say_here_l_shown")

        // 설정 화면이 처음 닫힌 뒤, 핸들 더블탭으로 설정을 다시 열 수 있음을 한 번만 안내했는지
        val IS_SETTINGS_REOPEN_HINT_SHOWN = booleanPreferencesKey("is_settings_reopen_hint_shown")

        // 첫 실행 때 한 번씩만 뜨는 오버레이 안내(핸들 "여기 있어요" 말풍선 좌/우, 설정 재진입 코치마크)의 플래그.
        // "사용법 안내" 를 누르면 이 목록만 지워 안내를 다시 보여준다.
        // 온보딩 완료(WAS_TRAILER_SHOWN)·리뷰 요청(IS_REVIEW_DONE)처럼 안내 표시가 아닌 값은 넣지 않는다.
        val OVERLAY_GUIDE_SHOWN_KEYS: List<Preferences.Key<Boolean>> = listOf(
            IS_SAY_HERE_L_SHOWN,
            IS_SAY_HERE_R_SHOWN,
            IS_SETTINGS_REOPEN_HINT_SHOWN,
        )

        val TEXT_DETECT_MODE: Preferences.Key<String> = stringPreferencesKey("text_detect_mode")
        val SOURCE_LANGUAGE_CODE = stringPreferencesKey("source_language_code")
        val TARGET_LANGUAGE_CODE = stringPreferencesKey("target_language_code")
        val TRANSLATION_KIT_TYPE = stringPreferencesKey("translation_kit_type")
        // OpenAI 번역에 사용할 모델. 미설정이면 OpenAiKit 이 Remote Config 후보의 첫 번째로 폴백한다.
        val OPENAI_MODEL = stringPreferencesKey("openai_model")
        // OpenAI 번역의 문맥/스타일 옵션. 값은 TranslationContextMode/Strength/Domain 의 enum 이름.
        val OPENAI_CONTEXT_MODE = stringPreferencesKey("openai_context_mode")
        val OPENAI_TRANSLATION_STRENGTH = stringPreferencesKey("openai_translation_strength")
        val OPENAI_TRANSLATION_DOMAIN = stringPreferencesKey("openai_translation_domain")
        val GEMINI_CONTEXT_MODE = stringPreferencesKey("gemini_context_mode")
        val GEMINI_TRANSLATION_STRENGTH = stringPreferencesKey("gemini_translation_strength")
        val GEMINI_TRANSLATION_DOMAIN = stringPreferencesKey("gemini_translation_domain")
        val CLAUDE_CONTEXT_MODE = stringPreferencesKey("claude_context_mode")
        val CLAUDE_TRANSLATION_STRENGTH = stringPreferencesKey("claude_translation_strength")
        val CLAUDE_TRANSLATION_DOMAIN = stringPreferencesKey("claude_translation_domain")
        // Gemini 번역에 사용할 모델. 미설정이면 GeminiKit 이 Remote Config 후보의 첫 번째로 폴백한다.
        val GEMINI_MODEL = stringPreferencesKey("gemini_model")
        // Claude 번역에 사용할 모델. 미설정이면 ClaudeKit 이 Remote Config 후보의 첫 번째로 폴백한다.
        val CLAUDE_MODEL = stringPreferencesKey("claude_model")

        val DRAG_HANDLE_DOCKING = booleanPreferencesKey("drag_handle_docking")
        val DOCKING_DELAY = longPreferencesKey("docking_delay")
        val DRAG_HANDLE_HAPTIC = booleanPreferencesKey("drag_handle_haptic")
        val MENU_BAR_VISIBILITY = booleanPreferencesKey("menu_bar_visibility")
        val MENU_BAR_TRANSPARENCY = floatPreferencesKey("menu_bar_transparency")
        val MENU_BAR_COMPOSITION = stringPreferencesKey("menu_bar_composition")
        val TRANSLATION_TRANSPARENCY = floatPreferencesKey("translation_transparency")
        val TRANSLATION_CLOSE_DELAY = longPreferencesKey("translation_close_delay")
        val REPLY_TRANSPARENCY = floatPreferencesKey("reply_transparency")
        val AUTOMATIC_TRANSLATION_PLAYBACK = booleanPreferencesKey("automatic_translation_playback")
        val TTS_SPEECH_RATE = floatPreferencesKey("tts_speech_rate")
        val TTS_ORDERED_VOICE_NAMES = stringPreferencesKey("tts_ordered_voice_names")
        val TTS_READ_TARGET = stringPreferencesKey("tts_read_target")
        // 마지막 번역에 실제 사용된 소스 언어(auto 감지 결과 포함). 소스가 auto 일 때
        // TTS 목소리 목록 정렬 등에서 기준 언어로 쓴다.
        val LAST_USED_SOURCE_LANGUAGE_CODE = stringPreferencesKey("last_used_source_language_code")

        val SOURCE_LANGUAGE_CODE_HISTORY = stringPreferencesKey("source_language_code_history")
        val TARGET_LANGUAGE_CODE_HISTORY = stringPreferencesKey("target_language_code_history")

        // 광고 로드 연속 실패 횟수. 한 번이라도 성공하면 0 으로 돌아간다.
        val AD_LOAD_FAILURE_STREAK = intPreferencesKey("ad_load_failure_streak")

        // 광고 게이트를 열지 않을 만료 시각(epoch millis). 지나면 다시 광고를 시도한다.
        // 프로세스가 죽어도 유지돼야 해서 메모리(AdGateState)가 아니라 여기에 둔다.
        val AD_GATE_SUPPRESSED_UNTIL = longPreferencesKey("ad_gate_suppressed_until")

        // 광고 게이트가 열린 횟수(누적). 두 번째 게이트부터 인앱 리뷰를 청한다([AdGateActivity]).
        val AD_GATE_OPEN_COUNT = intPreferencesKey("ad_gate_open_count")
    }

    private val preferenceDataStore: DataStore<Preferences> = context.preferenceDataStore

    private val gson = Gson()

    private val preferenceFlow: Flow<Preferences> = preferenceDataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }

    fun <T> update(key: Preferences.Key<T>, value: T) {
        CoroutineScope(Dispatchers.IO).launch {
            preferenceDataStore.edit { preferences ->
                preferences[key] = value
            }
        }
    }

    val adLoadFailureStreakFlow: Flow<Int> = preferenceFlow.map { preferences ->
        preferences[AD_LOAD_FAILURE_STREAK] ?: 0
    }

    val adGateSuppressedUntilFlow: Flow<Long> = preferenceFlow.map { preferences ->
        preferences[AD_GATE_SUPPRESSED_UNTIL] ?: 0L
    }

    val adGateOpenCountFlow: Flow<Int> = preferenceFlow.map { preferences ->
        preferences[AD_GATE_OPEN_COUNT] ?: 0
    }

    val wasTrailerShownFlow: Flow<Boolean> = preferenceFlow.map { preferences ->
        preferences[WAS_TRAILER_SHOWN] ?: false
    }

    val isReviewDoneFlow: Flow<Boolean> = preferenceFlow.map { preferences ->
        preferences[IS_REVIEW_DONE] ?: false
    }

    val isSayHereRShownFlow: Flow<Boolean> = preferenceFlow.map { preferences ->
        preferences[IS_SAY_HERE_R_SHOWN] ?: false
    }

    val isSayHereLShownFlow: Flow<Boolean> = preferenceFlow.map { preferences ->
        preferences[IS_SAY_HERE_L_SHOWN] ?: false
    }

    val isSettingsReopenHintShownFlow: Flow<Boolean> = preferenceFlow.map { preferences ->
        preferences[IS_SETTINGS_REOPEN_HINT_SHOWN] ?: false
    }

    /**
     * 오버레이 안내를 첫 실행 상태로 되돌린다. 안내 쪽은 뜰 조건마다 플래그를 새로 읽으므로
     * 앱을 다시 시작하지 않아도 다음 조건(설정 닫힘, 핸들 도킹)에서 바로 다시 뜬다.
     * 호출부가 완료를 기다릴 수 있도록 suspend 로 둔다. 저장에 실패해도 호출부 동작은 막지 않는다.
     */
    suspend fun resetOverlayGuides() {
        try {
            preferenceDataStore.edit { preferences ->
                preferences.clearOverlayGuideShownFlags()
            }
        } catch (e: IOException) {
            Timber.tag(TAG).w(e, "resetOverlayGuides() failed")
        }
    }

    /**
     * 광고 게이트가 열린 횟수를 하나 올리고 올린 값을 돌려준다. 저장에 실패하면 0.
     * 게이트는 이 값을 들고 있지 않고 쓸 때 [adGateOpenCountFlow] 로 다시 읽는다 — 액티비티가 재생성되면 필드는 잃는다.
     */
    suspend fun incrementAdGateOpenCount(): Int = try {
        var count = 0
        preferenceDataStore.edit { preferences ->
            count = (preferences[AD_GATE_OPEN_COUNT] ?: 0) + 1
            preferences[AD_GATE_OPEN_COUNT] = count
        }
        count
    } catch (e: IOException) {
        Timber.tag(TAG).w(e, "incrementAdGateOpenCount() failed")
        0
    }

    /**
     * 저장된 문자열을 enum 으로 변환한다.
     * 앱 업데이트로 값이 제거/변경되어 더 이상 존재하지 않는 경우(예: 과거 선택한
     * 번역 엔진이 삭제된 경우) 크래시 대신 기본값으로 안전하게 폴백한다.
     */
    private inline fun <reified T : Enum<T>> safeEnumValueOf(value: String?, default: T): T {
        if (value == null) return default
        return try {
            enumValueOf<T>(value)
        } catch (e: IllegalArgumentException) {
            default
        }
    }

    val textDetectModeFlow: Flow<TextDetectMode> = preferenceFlow.map { preferences ->
        safeEnumValueOf(preferences[TEXT_DETECT_MODE], TextDetectMode.SENTENCE)
    }

    val sourceLanguageCodeFlow: Flow<String> = preferenceFlow.map { preferences ->
        Timber.tag(TAG).d(" preferences[SOURCE_LANGUAGE_CODE] ${preferences[SOURCE_LANGUAGE_CODE]} getCurrentLocale().language ${getCurrentLocale().language}")
        // 기본값은 auto(자동 감지). 소스 언어가 원문과 어긋나 OCR 인식기가 잘못 선택되는 문제를 방지한다.
        // 화면 글자를 읽을 엔진이 없는 언어는 Claude 이미지 번역으로만 원문이 된다 — 엔진이 Claude 가 아니거나 원격 스위치를 껐으면 auto 로
        // 읽는다(.docs/vision-engine-design.md §21·§25). Claude 키를 지울 때는 설정이 저장값을 auto 로 바꾼다.
        val code = preferences[SOURCE_LANGUAGE_CODE] ?: "auto"
        val claude = safeEnumValueOf(preferences[TRANSLATION_KIT_TYPE], TranslationKitType.GOOGLE) == TranslationKitType.CLAUDE
        code.takeIf { !ImageTranslation.isImageOnlyLanguage(it) || claude && ImageTranslation.Switch.enabled } ?: "auto"
    }

    val targetLanguageCodeFlow: Flow<String> = preferenceFlow.map { preferences ->
        Timber.tag(TAG).d(" preferences[TARGET_LANGUAGE_CODE] ${preferences[TARGET_LANGUAGE_CODE]}")
        // 기본 번역 대상은 사용자 시스템 언어. (소스는 auto → "아무 외국어 → 내 언어"가 기본이 된다)
        preferences[TARGET_LANGUAGE_CODE] ?: getCurrentLocale().language
    }

    // 마지막 번역에 실제 사용된 소스 언어. 번역 이력이 없으면 null.
    val lastUsedSourceLanguageCodeFlow: Flow<String?> = preferenceFlow.map { preferences ->
        preferences[LAST_USED_SOURCE_LANGUAGE_CODE]
    }

    val translationKitTypeFlow: Flow<TranslationKitType> = preferenceFlow.map { preferences ->
        // 과거에 저장된, 지금은 없는 엔진 값이라도 안전하게 GOOGLE 로 폴백
        safeEnumValueOf(preferences[TRANSLATION_KIT_TYPE], TranslationKitType.GOOGLE)
    }

    // OpenAI 번역 모델 선택값. 미설정이면 null (OpenAiKit 이 Remote Config 후보로 폴백).
    val openAiModelFlow: Flow<String?> = preferenceFlow.map { preferences ->
        preferences[OPENAI_MODEL]
    }

    // OpenAI 번역의 문맥 범위. 미설정이면 기본값(주변 문장).
    val openAiContextModeFlow: Flow<TranslationContextMode> = preferenceFlow.map { preferences ->
        TranslationContextMode.from(preferences[OPENAI_CONTEXT_MODE])
    }

    // OpenAI 번역의 번역 강도. 미설정이면 기본값(직역).
    val openAiTranslationStrengthFlow: Flow<TranslationStrength> = preferenceFlow.map { preferences ->
        TranslationStrength.from(preferences[OPENAI_TRANSLATION_STRENGTH])
    }

    // OpenAI 번역의 분야. 미설정이면 기본값(일반).
    val openAiTranslationDomainFlow: Flow<TranslationDomain> = preferenceFlow.map { preferences ->
        TranslationDomain.from(preferences[OPENAI_TRANSLATION_DOMAIN])
    }

    val geminiContextModeFlow: Flow<TranslationContextMode> = preferenceFlow.map { preferences ->
        TranslationContextMode.from(preferences[GEMINI_CONTEXT_MODE])
    }

    val geminiTranslationStrengthFlow: Flow<TranslationStrength> = preferenceFlow.map { preferences ->
        TranslationStrength.from(preferences[GEMINI_TRANSLATION_STRENGTH])
    }

    val geminiTranslationDomainFlow: Flow<TranslationDomain> = preferenceFlow.map { preferences ->
        TranslationDomain.from(preferences[GEMINI_TRANSLATION_DOMAIN])
    }

    val claudeContextModeFlow: Flow<TranslationContextMode> = preferenceFlow.map { preferences ->
        TranslationContextMode.from(preferences[CLAUDE_CONTEXT_MODE])
    }

    val claudeTranslationStrengthFlow: Flow<TranslationStrength> = preferenceFlow.map { preferences ->
        TranslationStrength.from(preferences[CLAUDE_TRANSLATION_STRENGTH])
    }

    val claudeTranslationDomainFlow: Flow<TranslationDomain> = preferenceFlow.map { preferences ->
        TranslationDomain.from(preferences[CLAUDE_TRANSLATION_DOMAIN])
    }

    /**
     * 현재 번역 엔진의 문맥 설정.
     *
     * 문맥은 프롬프트가 있는 AI 엔진에만 의미가 있으므로, 나머지 엔진은 항상 [TranslationContextMode.OFF] 다.
     * (문맥 수집 자체를 건너뛰어 불필요한 작업을 막는다)
     */
    fun contextModeFlow(kitType: TranslationKitType): Flow<TranslationContextMode> = when (kitType) {
        TranslationKitType.OPENAI -> openAiContextModeFlow
        TranslationKitType.GEMINI -> geminiContextModeFlow
        TranslationKitType.CLAUDE -> claudeContextModeFlow
        else -> flowOf(TranslationContextMode.OFF)
    }

    // Gemini 번역 모델 선택값. 미설정이면 null (GeminiKit 이 Remote Config 후보로 폴백).
    val geminiModelFlow: Flow<String?> = preferenceFlow.map { preferences ->
        preferences[GEMINI_MODEL]
    }

    // Claude 번역 모델 선택값. 미설정이면 null (ClaudeKit 이 Remote Config 후보로 폴백).
    val claudeModelFlow: Flow<String?> = preferenceFlow.map { preferences ->
        preferences[CLAUDE_MODEL]
    }

    val dragHandleDockingFlow: Flow<Boolean> = preferenceFlow.map { preferences ->
        preferences[DRAG_HANDLE_DOCKING] ?: false
    }

    val dockingDelayFlow: Flow<Long> = preferenceFlow.map { preferences ->
        preferences[DOCKING_DELAY] ?: 15000L
    }

    val dragHandleHapticFlow: Flow<Boolean> = preferenceFlow.map { preferences ->
        preferences[DRAG_HANDLE_HAPTIC] ?: false
    }

    val menuBarVisibilityFlow: Flow<Boolean> = preferenceFlow.map { preferences ->
        preferences[MENU_BAR_VISIBILITY] ?: true
    }

    val menuBarTransparencyFlow: Flow<Float> = preferenceFlow.map { preferences ->
        preferences[MENU_BAR_TRANSPARENCY] ?: 0.905f
    }

    val menuBarConfigFlow: Flow<MenuConfig> = preferenceFlow.map { preferences ->
        safeEnumValueOf(preferences[MENU_BAR_COMPOSITION], MenuConfig.WHOLE)
    }

    val translationTransparencyFlow: Flow<Float> = preferenceFlow.map { preferences ->
        preferences[TRANSLATION_TRANSPARENCY] ?: 0.905f
    }

    val translationCloseDelayFlow: Flow<Long> = preferenceFlow.map { preferences ->
        preferences[TRANSLATION_CLOSE_DELAY] ?: 1600L
    }

    val replyTransparencyFlow: Flow<Float> = preferenceFlow.map { preferences ->
        preferences[REPLY_TRANSPARENCY] ?: 0.905f
    }

    val automaticTranslationPlaybackFlow: Flow<Boolean> = preferenceFlow.map { preferences ->
        preferences[AUTOMATIC_TRANSLATION_PLAYBACK] ?: false
    }

    val ttsSpeechRateFlow: Flow<Float> = preferenceFlow.map { preferences ->
        preferences[TTS_SPEECH_RATE] ?: 1.0f
    }

    val ttsReadTargetFlow: Flow<TTSReadTarget> = preferenceFlow.map { preferences ->
        safeEnumValueOf(preferences[TTS_READ_TARGET], TTSReadTarget.SOURCE)
    }

    val ttsOrderedVoiceNamesFlow: Flow<List<String>> = preferenceFlow.map { preferences ->
        preferences[TTS_ORDERED_VOICE_NAMES]?.let { jsonString ->
            gson.fromJson(jsonString, object : TypeToken<List<String>>() {}.type)
        } ?: listOf()
    }

    val sourceLanguageCodeHistoryFlow: Flow<List<String>> = preferenceFlow.map { preferences ->
        preferences[SOURCE_LANGUAGE_CODE_HISTORY]?.let { jsonString ->
            gson.fromJson(jsonString, object : TypeToken<List<String>>() {}.type)
        } ?: listOf()
    }

    val targetLanguageCodeHistoryFlow: Flow<List<String>> = preferenceFlow.map { preferences ->
        preferences[TARGET_LANGUAGE_CODE_HISTORY]?.let { jsonString ->
            gson.fromJson(jsonString, object : TypeToken<List<String>>() {}.type)
        } ?: listOf()
    }

    fun addOrUpdateLanguageHistory(newLanguageCode: String, isSourceLanguage: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            // 언어 저장
            preferenceDataStore.edit { preferences ->
                preferences[if (isSourceLanguage) SOURCE_LANGUAGE_CODE else TARGET_LANGUAGE_CODE] = newLanguageCode
            }

            // 히스토리 저장
            val currentHistory = if (isSourceLanguage) sourceLanguageCodeHistoryFlow.first() else targetLanguageCodeHistoryFlow.first() // 현재 저장된 언어 코드 리스트
            // 새 언어 코드를 리스트의 시작 부분에 추가
            val updatedList = mutableListOf(newLanguageCode)
            // 새 언어 코드가 이미 리스트에 있으면 제거하고, 그렇지 않으면 유지 (대소문자 구분 없음)
            currentHistory.filter { !it.equals(newLanguageCode, ignoreCase = true) }.forEach { updatedList.add(it) }
            // 리스트의 크기가 5를 초과하지 않도록 조정
            val finalList = if (updatedList.size > 5) updatedList.take(5) else updatedList
            // 변경된 리스트를 JSON 문자열로 변환 후 저장
            val jsonString = gson.toJson(finalList)
            update(if (isSourceLanguage) SOURCE_LANGUAGE_CODE_HISTORY else TARGET_LANGUAGE_CODE_HISTORY, jsonString)
        }
    }

    fun addOrUpdateOrderedVoiceNames(orderedVoices: List<Voice>) {
        CoroutineScope(Dispatchers.IO).launch {
            val orderedVoiceNames: List<String> = orderedVoices.map { voice -> voice.name }
            val jsonString = gson.toJson(orderedVoiceNames)
            update(TTS_ORDERED_VOICE_NAMES, jsonString)
        }
    }

    private fun getCurrentLocale(): Locale {
        return context.resources.configuration.locales.get(0)
    }

}

/**
 * [PreferenceRepository.OVERLAY_GUIDE_SHOWN_KEYS] 를 지운다. 미설정은 "아직 안 보여줌" 으로 읽힌다.
 * (DataStore 없이 단위 테스트할 수 있도록 편집 로직만 분리)
 */
internal fun MutablePreferences.clearOverlayGuideShownFlags() {
    PreferenceRepository.OVERLAY_GUIDE_SHOWN_KEYS.forEach { key -> remove(key) }
}
