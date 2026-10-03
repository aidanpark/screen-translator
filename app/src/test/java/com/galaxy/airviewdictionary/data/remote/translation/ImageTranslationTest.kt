package com.galaxy.airviewdictionary.data.remote.translation

import com.galaxy.airviewdictionary.data.local.vision.TextDetectMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI 이미지 번역(`.docs/vision-engine-design.md` §25)의 경로 고르기·프롬프트·응답 읽기. */
class ImageTranslationTest {

    /** 원문 목록 114개 중 화면 글자를 읽을 엔진이 없는 25개(2026-09-27 센 것). */
    private val imageOnly = listOf(
        "bn", "gu", "pa", "or", "ta", "te", "kn", "ml", "si", "km", "lo", "my",
        "mk", "kk", "ky", "mn", "tg", "tt", "el", "he", "yi", "ka", "hy", "am", "ti",
    )

    @Test
    fun 읽을_엔진이_없는_25개_언어만_이미지_전용이다() {
        imageOnly.forEach { assertTrue(it, ImageTranslation.isImageOnlyLanguage(it)) }
        listOf("en", "ko", "ja", "zh-CN", "hi", "ar", "fa", "ru", "th", "de", "vi").forEach {
            assertFalse(it, ImageTranslation.isImageOnlyLanguage(it))
        }
    }

    @Test
    fun 이미지로_보내는_것은_Claude_에서_이미지_전용_언어뿐이다() {
        fun uses(kit: TranslationKitType, source: String, ready: Boolean = true, enabled: Boolean = true) =
            ImageTranslation.uses(kit, source, ready, enabled)

        // auto 는 OCR 글 번역 — 이미지로 보내면 단어·문장 모드의 하이라이트가 줄·문단으로 잡힌다(2.8.2)
        assertFalse(uses(TranslationKitType.CLAUDE, "auto"))
        assertFalse(uses(TranslationKitType.CLAUDE, "AUTO"))
        assertTrue(uses(TranslationKitType.CLAUDE, "he"))
        assertTrue(uses(TranslationKitType.CLAUDE, "bn"))
        // 읽을 수 있는 언어는 지금처럼 OCR 글 번역
        assertFalse(uses(TranslationKitType.CLAUDE, "ko"))
        assertFalse(uses(TranslationKitType.CLAUDE, "ar"))
        // 다른 엔진은 그대로
        for (kit in TranslationKitType.entries.filter { it != TranslationKitType.CLAUDE }) {
            assertFalse("$kit", uses(kit, "auto"))
            assertFalse("$kit", uses(kit, "he"))
        }
        // 키가 없거나 원격 스위치를 끄면 글로
        assertFalse(uses(TranslationKitType.CLAUDE, "he", ready = false))
        assertFalse(uses(TranslationKitType.CLAUDE, "he", enabled = false))
    }

    private fun prompt(mode: TextDetectMode, source: String? = null) =
        ImageTranslation.systemPrompt(source, "Korean", TranslationStrength.LITERAL, TranslationDomain.GENERAL, mode)

    @Test
    fun 포인터_모드는_표시_기준으로_단어_문장_문단을_찾게_한다() {
        val word = prompt(TextDetectMode.WORD)
        assertTrue(word.contains("translucent magenta highlight"))
        assertTrue(word.contains("single word at the highlight"))
        assertTrue(word.contains("Ignore all other text"))
        assertTrue(prompt(TextDetectMode.SENTENCE).contains("sentence that contains the text at the highlight"))
        assertTrue(prompt(TextDetectMode.PARAGRAPH).contains("paragraph or text block that contains the text at the highlight"))
    }

    @Test
    fun 영역_모드는_표시_없이_전체를_읽게_한다() {
        for (mode in listOf(TextDetectMode.SELECT, TextDetectMode.FIXED_AREA)) {
            val p = prompt(mode)
            assertTrue(p.contains("Read all the text in the image"))
            assertFalse(p.contains("highlight"))
            assertFalse(p.contains("Ignore all other text"))
        }
    }

    @Test
    fun 원문_언어는_단정하지_않고_힌트로_준다() {
        val named = prompt(TextDetectMode.SENTENCE, source = "Hebrew")
        assertTrue(named.contains("expected to be in Hebrew"))
        assertTrue(named.contains("translate it anyway"))
        assertFalse(named.contains("\"language\""))
    }

    @Test
    fun auto_는_언어도_돌려받는다() {
        val auto = prompt(TextDetectMode.SENTENCE)
        assertTrue(auto.contains("\"language\""))
        assertTrue(auto.contains("ISO 639-1"))
        assertFalse(auto.contains("expected to be in"))
    }

    @Test
    fun 거절_금지와_빈_답_규칙이_문체_지시보다_뒤에_온다() {
        val p = prompt(TextDetectMode.WORD, source = "Bengali")
        val style = p.indexOf(TranslationStrength.LITERAL.promptClause)
        assertTrue(style >= 0)
        assertTrue(p.indexOf("never refuse") > style)
        assertTrue(p.endsWith("leave every field empty."))
    }

    @Test
    fun 스키마는_필드를_모두_요구하고_다른_필드를_막는다() {
        val named = ImageTranslation.responseSchema(auto = false)
        assertEquals(listOf("source", "translation"), named["required"])
        assertEquals(false, named["additionalProperties"])
        val auto = ImageTranslation.responseSchema(auto = true)
        assertEquals(listOf("language", "source", "translation"), auto["required"])
        @Suppress("UNCHECKED_CAST")
        assertEquals(setOf("language", "source", "translation"), (auto["properties"] as Map<String, Any>).keys)
    }

    @Test
    fun 단어_문장_모드는_줄_문단_전체_글도_받는다() {
        assertEquals(listOf("source", "translation", "context"), ImageTranslation.responseSchema(false, TextDetectMode.WORD)["required"])
        assertEquals(listOf("source", "translation", "context"), ImageTranslation.responseSchema(false, TextDetectMode.SENTENCE)["required"])
        assertEquals(listOf("source", "translation"), ImageTranslation.responseSchema(false, TextDetectMode.PARAGRAPH)["required"])
        assertEquals(listOf("source", "translation"), ImageTranslation.responseSchema(false, TextDetectMode.SELECT)["required"])
        assertTrue(prompt(TextDetectMode.WORD, "Hebrew").contains("\"context\", give the whole line"))
        assertTrue(prompt(TextDetectMode.SENTENCE, "Hebrew").contains("\"context\", give the whole paragraph"))
        assertTrue(!prompt(TextDetectMode.PARAGRAPH, "Hebrew").contains("\"context\""))
        assertEquals("שלום עולם", ImageTranslation.parse("""{"source":"עולם","translation":"세상","context":" שלום עולם "}""")!!.context)
        assertNull(ImageTranslation.parse("""{"source":"a","translation":"b"}""")!!.context)
    }

    @Test
    fun 응답을_읽는다() {
        val r = ImageTranslation.parse("""{"language":"HE","source":" שלום עולם ","translation":"안녕 세상"}""")!!
        assertEquals("שלום עולם", r.source)
        assertEquals("안녕 세상", r.translation)
        assertEquals("he", r.language)
        assertFalse(r.isEmpty)

        assertEquals("zh-hant", ImageTranslation.parse("""{"language":"zh-Hant","source":"a","translation":"b"}""")!!.language)
        // 판정 못 한 언어·언어 코드가 아닌 값은 null
        assertNull(ImageTranslation.parse("""{"language":"und","source":"a","translation":"b"}""")!!.language)
        assertNull(ImageTranslation.parse("""{"language":"Hebrew language","source":"a","translation":"b"}""")!!.language)
        assertNull(ImageTranslation.parse("""{"source":"a","translation":"b"}""")!!.language)
    }

    @Test
    fun 빈_답은_글이_없다는_뜻이다() {
        assertTrue(ImageTranslation.parse("""{"source":"","translation":" "}""")!!.isEmpty)
    }

    @Test
    fun JSON_이_아니거나_필드가_없으면_null() {
        assertNull(ImageTranslation.parse("I cannot read this image."))
        assertNull(ImageTranslation.parse("""{"source":"a"}"""))
        assertNull(ImageTranslation.parse("""["a","b"]"""))
        assertNull(ImageTranslation.parse("""{"source":{"x":1},"translation":"b"}"""))
        assertNull(ImageTranslation.parse(""))
    }

    @Test
    fun 언어_이름은_영어로() {
        assertEquals("Hebrew", ImageTranslation.englishName("he"))
        assertTrue(ImageTranslation.englishName("bn") in setOf("Bangla", "Bengali"))
        assertEquals("Korean", ImageTranslation.englishName("ko"))
        assertEquals("xx-unknown", ImageTranslation.englishName("xx-unknown"))
    }

    @Test
    fun 지문은_몇_글자_달라도_같은_글로_본다() {
        val a = "Лшгш ецгт ипшиши гаи шфуф цтиа ешпшвшцшуш вща нуши"
        assertTrue(ImageTranslation.sameFingerprint(a, a))
        assertTrue(ImageTranslation.sameFingerprint(a, a.replaceFirst('ш', 'щ').replaceFirst('ф', 'в')))
        assertFalse(ImageTranslation.sameFingerprint(a, "Пвотд фрыа ыпвл ьтывоа лдыв фж ыло двлоы"))
        assertFalse(ImageTranslation.sameFingerprint(a, ""))
        assertTrue(ImageTranslation.sameFingerprint("", ""))
        assertNotNull(ImageTranslation.sameFingerprint("a".repeat(2000), "a".repeat(1999) + "b"))
    }
}
