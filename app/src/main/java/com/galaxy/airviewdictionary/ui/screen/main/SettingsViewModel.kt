package com.galaxy.airviewdictionary.ui.screen.main

import android.app.Activity
import androidx.compose.foundation.ScrollState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.galaxy.airviewdictionary.data.local.preference.PreferenceRepository
import com.galaxy.airviewdictionary.data.local.secure.SecureRepository
import com.galaxy.airviewdictionary.data.local.tts.TTSReadTarget
import com.galaxy.airviewdictionary.data.local.tts.TTSRepository
import com.galaxy.airviewdictionary.data.remote.firebase.AnalyticsRepository
import com.galaxy.airviewdictionary.data.remote.firebase.RemoteConfigRepository
import com.galaxy.airviewdictionary.data.remote.translation.TranslationKitType
import com.galaxy.airviewdictionary.data.remote.translation.TranslationRepository
import com.galaxy.airviewdictionary.ui.screen.overlay.menubar.MenuConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    val secureRepository: SecureRepository,
    val remoteConfigRepository: RemoteConfigRepository,
    val preferenceRepository: PreferenceRepository,
    val translationRepository: TranslationRepository,
    val ttsRepository: TTSRepository,
    val analyticsRepository: AnalyticsRepository
) : ViewModel() {

    private val TAG = javaClass.simpleName

    val scrollState = ScrollState(initial = 0)

    /**
     * 엔진 키를 지운 뒤의 정리 — 고른 엔진이 [kitType] 이면 Google 로 되돌리고 엔진별 정리([extra])를 한다. 대화상자의 코루틴 범위는 대화상자가
     * 닫히며 취소되므로 뷰모델 범위에서 한다(코드 리뷰 지적 — 키를 지우고 바로 닫으면 엔진 되돌리기와 원문 언어 정리가 빠질 수 있었다).
     */
    fun afterKeyRemoved(kitType: TranslationKitType, extra: suspend () -> Unit = {}) {
        viewModelScope.launch {
            if (preferenceRepository.translationKitTypeFlow.first() == kitType) {
                preferenceRepository.update(PreferenceRepository.TRANSLATION_KIT_TYPE, TranslationKitType.GOOGLE.name)
            }
            extra()
        }
    }

    fun updateDragHandleDocking(dragHandleDocking: Boolean) {
        preferenceRepository.update(PreferenceRepository.DRAG_HANDLE_DOCKING, dragHandleDocking)
    }

    fun updateDockingDelay(dockingDelay: Long) {
        preferenceRepository.update(PreferenceRepository.DOCKING_DELAY, dockingDelay)
    }

    fun updateDragHandleHaptic(dragHandleHaptic: Boolean) {
        preferenceRepository.update(PreferenceRepository.DRAG_HANDLE_HAPTIC, dragHandleHaptic)
    }

    fun updateMenuBarVisibility(menuVisibility: Boolean) {
        preferenceRepository.update(PreferenceRepository.MENU_BAR_VISIBILITY, menuVisibility)
    }

    fun updateMenuBarTransparency(transparency: Float) {
        preferenceRepository.update(PreferenceRepository.MENU_BAR_TRANSPARENCY, transparency)
    }

    fun updateMenuBarConfig(menuBarConfig: MenuConfig) {
        preferenceRepository.update(PreferenceRepository.MENU_BAR_COMPOSITION, menuBarConfig.name)
    }

    fun updateTranslationTransparency(transparency: Float) {
        preferenceRepository.update(PreferenceRepository.TRANSLATION_TRANSPARENCY, transparency)
    }

    fun updateTranslationCloseDelay(translationCloseDelay: Long) {
        preferenceRepository.update(PreferenceRepository.TRANSLATION_CLOSE_DELAY, translationCloseDelay)
    }

    fun updateReplyTransparency(transparency: Float) {
        preferenceRepository.update(PreferenceRepository.REPLY_TRANSPARENCY, transparency)
    }

    fun updateAutomaticTranslationPlayback(automaticTranslationPlayback: Boolean) {
        preferenceRepository.update(PreferenceRepository.AUTOMATIC_TRANSLATION_PLAYBACK, automaticTranslationPlayback)
    }

    fun updateTtsSpeechRate(speechRate: Float) {
        preferenceRepository.update(PreferenceRepository.TTS_SPEECH_RATE, speechRate)
    }

    fun updateTtsReadTarget(readTarget: TTSReadTarget) {
        preferenceRepository.update(PreferenceRepository.TTS_READ_TARGET, readTarget.name)
    }

    fun isLanguageSwappable(sourceLanguageCode: String, targetLanguageCode: String, kitType: TranslationKitType): Boolean {
        return translationRepository.isLanguageSwappable(sourceLanguageCode, targetLanguageCode, kitType)
    }

    val latestVersionCodeFlow: Flow<Long> =
        remoteConfigRepository.remoteConfigFlow
            .map { remoteConfig ->
                remoteConfig[RemoteConfigRepository.LATEST_VERSION_CODE_KEY]?.asLong() ?: 0
            }

    init {
        Timber.tag(TAG).i("#### init ####")
        translationRepository.acquire()
        ttsRepository.acquire()
    }

    override fun onCleared() {
        translationRepository.release()
        ttsRepository.release()
        super.onCleared()
    }
}
