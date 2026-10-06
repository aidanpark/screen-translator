package com.galaxy.airviewdictionary.data.remote.translation

import com.galaxy.airviewdictionary.data.remote.translation.Language


abstract class TranslationKit {

    protected val TAG: String = javaClass.simpleName

    abstract fun available(): Boolean

    abstract val supportedLanguagesAsSource: List<Language>

    abstract val supportedLanguagesAsTarget: List<Language>

    /**
     * Language codes for source languages supported by Translator.
     */
    abstract fun isSupportedAsSource(code: String, targetLanguageCode: String): Boolean

    /**
     * Language codes for target languages supported by Translator.
     */
    abstract fun isSupportedAsTarget(code: String, sourceLanguageCode: String): Boolean

    abstract fun isLanguageSwappable(sourceLanguageCode: String, targetLanguageCode: String): Boolean

    /**
     * 번역을 요청한다. 응답을 기다리는 동안 일시 중단(suspend)할 뿐 스레드를 막지 않는다 — 코루틴 안에서 부른다.
     *
     * [TranslationResponse] Translation response object
     */
    abstract suspend fun request(
        sourceLanguageCode: String,
        targetLanguageCode: String,
        sourceText: String
    ): TranslationResponse

    /**
     * 주변 텍스트를 문맥으로 함께 받는 번역 요청.
     *
     * 프롬프트 개념이 있는 AI 엔진만 [contextText] 를 활용한다.
     * 나머지 엔진은 기본 구현대로 문맥을 무시하고 대상 문장만 번역한다.
     */
    open suspend fun request(
        sourceLanguageCode: String,
        targetLanguageCode: String,
        sourceText: String,
        contextText: String?,
    ): TranslationResponse = request(sourceLanguageCode, targetLanguageCode, sourceText)

}


