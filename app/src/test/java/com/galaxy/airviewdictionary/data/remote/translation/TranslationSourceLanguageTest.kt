package com.galaxy.airviewdictionary.data.remote.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 번역 엔진에 넘기는 원문 언어와 확정 원문 언어의 규칙(`.docs/vision-engine-design.md` §23). */
class TranslationSourceLanguageTest {

    private val aiKits = listOf(TranslationKitType.OPENAI, TranslationKitType.GEMINI, TranslationKitType.CLAUDE)
    private val mtKits = listOf(TranslationKitType.GOOGLE, TranslationKitType.DEEPL)

    /** auto 면 AI 엔진은 식별값과 무관하게 "auto" — 모델이 판정한다("chat" 을 프랑스어로 박아 넣지 않는다). */
    @Test
    fun aiKitsDetectTheLanguageThemselvesOnAuto() {
        for (kit in aiKits) for (identified in listOf("fr", "und", "", "ja")) {
            assertEquals("$kit/$identified", "auto", TranslationSourceLanguage.forKit(kit, "auto", identified))
        }
        assertEquals("auto", TranslationSourceLanguage.forKit(TranslationKitType.CLAUDE, "AUTO", "fr"))
    }

    /** auto 면 Google·DeepL 은 번역할 글로 식별한 언어를 받는다. 식별하지 못했으면 "auto" — DeepL 은 "und" 를 받지 않는다. */
    @Test
    fun machineTranslationKitsGetTheIdentifiedLanguageOrAuto() {
        for (kit in mtKits) {
            assertEquals("fr", TranslationSourceLanguage.forKit(kit, "auto", "fr"))
            assertEquals("auto", TranslationSourceLanguage.forKit(kit, "auto", "und"))
            assertEquals("auto", TranslationSourceLanguage.forKit(kit, "auto", "UND"))
            assertEquals("auto", TranslationSourceLanguage.forKit(kit, "auto", ""))
            assertEquals("auto", TranslationSourceLanguage.forKit(kit, "auto", "auto"))
        }
    }

    /** 원문 언어를 직접 골랐으면 모든 엔진이 그 언어를 받는다. */
    @Test
    fun chosenLanguageIsPassedToEveryKit() {
        for (kit in aiKits + mtKits) {
            assertEquals("ja", TranslationSourceLanguage.forKit(kit, "ja", "ja"))
            assertEquals("zh-TW", TranslationSourceLanguage.forKit(kit, "zh-TW", "zh-TW"))
        }
    }

    /** 확정 원문 언어 — 엔진 값이 먼저, 판정 못 했으면(null·"und"·"auto") OCR 식별값. 소문자로 맞춘다. */
    @Test
    fun resolvedPrefersTheKitAndFallsBackToOcr() {
        assertEquals("en", TranslationSourceLanguage.resolved("EN", "fr"))
        assertEquals("fr", TranslationSourceLanguage.resolved(null, "fr"))
        assertEquals("fr", TranslationSourceLanguage.resolved("und", "fr"))
        assertEquals("fr", TranslationSourceLanguage.resolved("auto", "FR"))
        assertEquals("zh-cn", TranslationSourceLanguage.resolved("zh-CN", null))
        assertNull(TranslationSourceLanguage.resolved(null, "und"))
        assertNull(TranslationSourceLanguage.resolved("und", "auto"))
        assertNull(TranslationSourceLanguage.resolved(" ", ""))
        assertNull(TranslationSourceLanguage.resolved(null, null))
    }
}
