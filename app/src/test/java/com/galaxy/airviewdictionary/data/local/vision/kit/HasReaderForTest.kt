package com.galaxy.airviewdictionary.data.local.vision.kit

import org.junit.Assert.assertEquals
import org.junit.Test

/** 원문 언어로 고를 수 있는가 — 그 문자를 읽을 엔진이 있는가(`.docs/vision-engine-design.md` §21, §23). */
class HasReaderForTest {

    @Test
    fun readableLanguagesAreSelectable() {
        val readable = listOf(
            "auto", "en", "fr", "vi", "tr", "uz", "ku", "sr",           // 라틴(세르비아어는 라틴으로도 쓴다)
            "ko", "ja", "zh-CN", "zh-TW", "hi", "mr", "ne",             // ML Kit 전용 인식기
            "ar", "fa", "ur", "ps", "ckb", "ug", "sd", "ru", "uk", "be", "bg", "th", // PP-OCRv5
        )
        assertEquals(emptyList<String>(), readable.filterNot { VisionKitSelector.hasReaderFor(it) })
    }

    @Test
    fun unreadableScriptsAreNotSelectable() {
        val unreadable = listOf("he", "yi", "el", "ka", "hy", "mk", "kk", "ky", "mn", "tg", "tt", "bn", "gu", "pa", "or", "ta", "te", "kn", "ml", "si", "km", "lo", "my", "am", "ti")
        assertEquals(emptyList<String>(), unreadable.filter { VisionKitSelector.hasReaderFor(it) })
    }

    /** 저장값은 대소문자가 섞여 있을 수 있다. */
    @Test
    fun caseDoesNotMatter() {
        val readable = listOf("ZH-TW", "Zh-cn", "JA", "AR", "RU", "HI", "EN", "AUTO")
        assertEquals(emptyList<String>(), readable.filterNot { VisionKitSelector.hasReaderFor(it) })
        val unreadable = listOf("HE", "El", "KA", "BN", "TA")
        assertEquals(emptyList<String>(), unreadable.filter { VisionKitSelector.hasReaderFor(it) })
    }

    /** 예전 버전이 저장했을 수 있는 코드 가운데 읽을 엔진이 없는 비라틴 문자. */
    @Test
    fun legacyCodesWithoutReaderAreNotSelectable() {
        val legacy = listOf(
            "prs", "ks", "yue", "lzh", "dv", "bo", "as", "mni", "iu", "ba", "iw", "ji",
            "sr-Cyrl", "sr-cyrl", "SR-CYRL", "sr_Cyrl", "sr-Cyrl-RS",
        )
        assertEquals(emptyList<String>(), legacy.filter { VisionKitSelector.hasReaderFor(it) })
    }

    /** 세르비아어는 라틴 표기로 읽힌다 — 키릴 표기를 콕 집은 코드만 막는다. */
    @Test
    fun serbianIsSelectableUnlessCyrillicIsExplicit() {
        val serbian = listOf("sr", "SR", "sr-Latn", "sr-RS")
        assertEquals(emptyList<String>(), serbian.filterNot { VisionKitSelector.hasReaderFor(it) })
    }

    /** 예전 버전의 데바나가리 언어 코드는 데바나가리 인식기가 읽는다. */
    @Test
    fun legacyDevanagariCodesAreSelectable() {
        val devanagari = listOf("mai", "bho", "brx", "doi", "gom")
        assertEquals(emptyList<String>(), devanagari.filterNot { VisionKitSelector.hasReaderFor(it) })
    }

    /** auto 로 두어도 읽히는 언어 — 읽을 엔진이 있는 언어이고, PP-OCRv5 문자권은 auto 표본 스위치가 켜져 있을 때만이다. */
    @Test
    fun autoReadsLanguagesWithAReader() {
        val autoReadable = listOf("en", "fr", "vi", "ko", "ja", "zh-CN", "hi", "mr", "sr", "ES", "pt_BR")
        assertEquals(emptyList<String>(), autoReadable.filterNot { VisionKitSelector.autoCanRead(it, paddleAutoOn = false) })
        val unreadable = listOf("he", "el", "ka", "bn", "ta", "am", "sr-Cyrl")
        assertEquals(emptyList<String>(), unreadable.filter { VisionKitSelector.autoCanRead(it, paddleAutoOn = true) })
    }

    @Test
    fun autoReadsPaddleScriptsOnlyWhenTheAutoSwitchIsOn() {
        val paddle = listOf("ar", "fa", "ur", "ru", "uk", "bg", "th")
        assertEquals(emptyList<String>(), paddle.filter { VisionKitSelector.autoCanRead(it, paddleAutoOn = false) })
        assertEquals(emptyList<String>(), paddle.filterNot { VisionKitSelector.autoCanRead(it, paddleAutoOn = true) })
    }
}
