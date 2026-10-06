package com.galaxy.airviewdictionary.data.remote.translation


/**
 * 번역 파이프라인(포인터·고정 영역)이 원문 언어를 다루는 규칙 한 곳(`.docs/vision-engine-design.md` §23).
 */
object TranslationSourceLanguage {

    private const val AUTO = "auto"

    /** 원문 언어를 몰라도 번역하는 LLM 엔진. auto 면 원문 언어를 비워 보낸다. */
    private val AI_KITS = setOf(TranslationKitType.OPENAI, TranslationKitType.GEMINI, TranslationKitType.CLAUDE)

    /**
     * 번역 엔진에 넘길 원문 언어.
     *
     * - 원문 언어를 직접 골랐으면 [identified](= 그 언어)를 그대로.
     * - auto 이고 AI 엔진이면 "auto" — 프롬프트에 원문 언어를 적지 않는다(모델은 글을 읽으며 안다). ML Kit 언어 식별은 짧은 글에서
     *   자주 틀린다("chat" → 프랑스어). 그 값을 원문으로 박아 넣으면 모델이 틀린 언어로 읽는다.
     * - auto 이고 Google·DeepL 이면 [identified](번역할 글로 식별한 언어)를 그대로 — 화면 전체로 식별하면 오버레이·브라우저 UI 글이
     *   섞여 엉뚱한 언어가 나오던 것을 막는 규칙이다. 다만 식별하지 못했으면("und"·빈 값) "auto" 로 보낸다. DeepL 은
     *   source_lang="und" 를 받지 않는다.
     */
    fun forKit(kitType: TranslationKitType, preference: String, identified: String): String = when {
        preference.equals(AUTO, ignoreCase = true) && kitType in AI_KITS -> AUTO
        else -> identified.trim().takeUnless { it.isEmpty() || it.equals("und", ignoreCase = true) } ?: AUTO
    }

    /**
     * 번역 결과의 확정 원문 언어([com.galaxy.airviewdictionary.data.remote.translation.Transaction.resolvedSourceLanguageCode]).
     * 엔진이 판정했으면 그 값, 못 했으면 OCR 글로 식별한 [ocrCode]. 소문자로 맞추고 "auto"·"und"·빈 값은 판정 못 한 것으로 본다.
     * 쓰기 방향·TTS·답장·애널리틱스가 이 값을 믿는다.
     */
    fun resolved(kitCode: String?, ocrCode: String?): String? = normalized(kitCode) ?: normalized(ocrCode)

    /** 언어 코드로 쓸 수 있는 값이면 소문자로, 아니면("auto"·"und"·빈 값) null. */
    fun normalized(code: String?): String? =
        code?.trim()?.lowercase()?.takeUnless { it.isEmpty() || it == AUTO || it == "und" }
}
