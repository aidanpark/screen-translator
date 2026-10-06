package com.galaxy.airviewdictionary.data.remote.translation

import android.content.Context
import com.galaxy.airviewdictionary.data.local.secure.SecureStore
import com.galaxy.airviewdictionary.data.remote.translation.goolge.GoogleWebKit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * LLM 번역 엔진(OpenAI · Gemini · Claude)의 공통부(코드 정리 B3 — 세 엔진에 같은 코드가 들어 있었다). 사실상 전 언어를 번역하므로 언어 커버리지는
 * Google 과 같고, 시스템 프롬프트 · 모델 고르기 · 출력 정리가 같다. 엔진은 요청 본문을 만들고 응답에서 글을 꺼내는 일만 한다.
 */
abstract class AiTranslationKit(
    private val kitType: TranslationKitType,
    private val googleWebKit: GoogleWebKit,
) : TranslationKit() {

    override val supportedLanguagesAsSource: List<Language> by lazy {
        googleWebKit.supportedLanguagesAsSource.map { Language(it.code).apply { supportKitTypes.add(kitType) } }
    }

    override val supportedLanguagesAsTarget: List<Language> by lazy {
        googleWebKit.supportedLanguagesAsTarget.map { Language(it.code).apply { supportKitTypes.add(kitType) } }
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

    protected fun buildSystemPrompt(
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
     * 사용할 모델. 설정에서 고른 값([chosen])이 Remote Config 후보에 있으면 그것을, 아니면 후보의 첫 번째를, 후보가 없으면 고른 값이나 [default].
     * 원격에서 후보 목록이 바뀌어 저장된 모델이 목록에 없으면 첫 번째(기본) 모델로 돌아간다.
     */
    protected fun pickModel(chosen: String?, candidates: List<String>, default: String): String {
        val picked = chosen?.takeIf { it.isNotBlank() }
        return when {
            picked != null && picked in candidates -> picked
            candidates.isNotEmpty() -> candidates.first()
            else -> picked ?: default
        }
    }

    /** LLM 이 종종 붙이는 코드펜스/따옴표/여백을 정리한다. */
    protected fun cleanOutput(raw: String): String {
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
}

/** API 키 검증 결과. 네트워크 오류는 키 자체의 문제가 아니므로 무효와 구분한다. 키를 받는 엔진(DeepL · OpenAI · Gemini · Claude)이 같이 쓴다. */
enum class KeyValidationResult {
    VALID,
    INVALID,
    NETWORK_ERROR,
}

/**
 * 엔진의 개인 API 키([SecureStore] 에 암호화 저장)와 등록 여부. 엔진 전환기 노출과 설정의 활성 표시가 [activated] 를 따른다.
 * SecureStore 는 flow 를 제공하지 않으므로 키 저장 · 조회 시점에 갱신한다. 키를 받는 엔진마다 하나씩 둔다(코드 정리 B3).
 */
class ApiKeyStore(private val secureKey: String, private val tag: String) {

    private val _activated = MutableStateFlow(false)
    val activated: StateFlow<Boolean> = _activated.asStateFlow()

    fun refresh(context: Context) {
        _activated.value = get(context) != null
    }

    /** 설정에서 저장한 API 키. 없거나 공백이면 null. */
    fun get(context: Context): String? = SecureStore.get(context, secureKey)?.get()?.takeIf { it.isNotBlank() }

    /** 설정에서 입력한 API 키를 암호화 저장한다. 빈 문자열 저장은 키 삭제로 동작한다. */
    fun store(context: Context, apiKey: String) {
        SecureStore.set(context, secureKey, apiKey.trim())
        refresh(context)
        Timber.tag(tag).i("storeApiKey saved (${apiKey.trim().length} chars)")
    }
}
