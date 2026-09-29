package com.galaxy.airviewdictionary.ui.screen.overlay.languagelist

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.galaxy.airviewdictionary.data.local.preference.PreferenceRepository
import com.galaxy.airviewdictionary.data.remote.translation.ImageTranslation
import com.galaxy.airviewdictionary.data.remote.translation.TranslationKitType
import com.galaxy.airviewdictionary.data.remote.translation.TranslationRepository
import com.galaxy.airviewdictionary.data.remote.translation.Language
import com.galaxy.airviewdictionary.data.remote.translation.claude.ClaudeKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber


@Suppress("UNCHECKED_CAST")
class LanguageListViewModelFactory(
    private val applicationContext: Context,
    private val preferenceRepository: PreferenceRepository,
    private val translationRepository: TranslationRepository,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LanguageListViewModel::class.java)) {
            return LanguageListViewModel(
                applicationContext = applicationContext,
                preferenceRepository = preferenceRepository,
                translationRepository = translationRepository,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel Class")
    }
}

class LanguageListViewModel(
    private val applicationContext: Context,
    val preferenceRepository: PreferenceRepository,
    val translationRepository: TranslationRepository,
) : ViewModel() {

    private val TAG = javaClass.simpleName

    val supportedLanguagesAsSource: List<Language> = translationRepository.supportedLanguagesAsSource

    val supportedLanguagesAsTarget: List<Language> = translationRepository.supportedLanguagesAsTarget

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //                                                                                            //
    //                                       preference                                           //
    //                                                                                            //
    ////////////////////////////////////////////////////////////////////////////////////////////////

    val sourceLanguageCodeHistoryFlow = preferenceRepository.sourceLanguageCodeHistoryFlow
        .map { stringList ->
            stringList.map { languageCode -> translationRepository.getSupportedSourceLanguage(languageCode) }
        }

    val targetLanguageCodeHistoryFlow = preferenceRepository.targetLanguageCodeHistoryFlow
        .map { stringList ->
            stringList.map { languageCode -> translationRepository.getSupportedTargetLanguage(languageCode) }
        }

    /**
     * language 와 oppositeLanguage 는 반드시 공통의 TranslationKitType 을 가지고 있어야 한다.
     */
    fun updateLanguage(isSourceLanguage: Boolean, language: Language, oppositeLanguage: Language) {
        viewModelScope.launch {
            // 화면 글자를 읽을 엔진이 없는 언어는 Claude 이미지 번역으로만 원문이 된다 — Claude 키가 있어야 한다(목록에서도 비활성, §25).
            // 지원 엔진이 Claude 하나라 아래에서 엔진도 Claude 로 바뀐다
            if (isSourceLanguage && ImageTranslation.isImageOnlyLanguage(language.code) &&
                !(ImageTranslation.Switch.enabled && withContext(Dispatchers.IO) { ClaudeKit.getStoredApiKey(applicationContext) != null })
            ) return@launch
            val kitType: TranslationKitType = preferenceRepository.translationKitTypeFlow.first()
            val commonKitTypes = mutableListOf<TranslationKitType>().apply {
                if (language.supportKitTypes.contains(kitType) && oppositeLanguage.supportKitTypes.contains(kitType)) {
                    add(kitType) // kitType을 가장 앞으로 추가
                }
                addAll(language.supportKitTypes.intersect(oppositeLanguage.supportKitTypes.toSet()).filter { it != kitType })
            }

            // commonKitTypes를 순회하며 isSupported 확인
            for (currentKitType in commonKitTypes) {
                val isSupported = if (isSourceLanguage) {
                    translationRepository.isSupportedAsSource(currentKitType, language.code, oppositeLanguage.code)
                } else {
                    translationRepository.isSupportedAsTarget(currentKitType, language.code, oppositeLanguage.code)
                }
                Timber.tag(TAG).d("updateLanguage kitType $kitType language $language oppositeLanguage $oppositeLanguage isSupported $isSupported")
                if (isSupported) {
                    preferenceRepository.addOrUpdateLanguageHistory(language.code, isSourceLanguage)
                    preferenceRepository.update(PreferenceRepository.TRANSLATION_KIT_TYPE, currentKitType.name)
                    break // 지원되는 kitType이 발견되면 순회 종료
                }
            }
        }
    }

    init {
        Timber.tag(TAG).i("#### init ####")
        translationRepository.acquire()
    }

    override fun onCleared() {
        translationRepository.release()
        super.onCleared()
    }
}








