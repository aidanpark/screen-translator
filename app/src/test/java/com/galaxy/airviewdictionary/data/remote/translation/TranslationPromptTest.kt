package com.galaxy.airviewdictionary.data.remote.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 세 AI 킷(OpenAI/Gemini/Claude)이 공유하는 프롬프트 생성 검증.
 *
 * 킷마다 요청 본문 모양은 다르지만 프롬프트는 이 함수들이 전부 만든다.
 * Gemini 로만 실제 호출을 확인할 수 있어도, 여기서 통과하면 나머지 둘의
 * 프롬프트도 같은 내용이 된다.
 */
class TranslationPromptTest {

    private fun prompt(
        source: String? = null,
        target: String = "Korean",
        strength: TranslationStrength = TranslationStrength.DEFAULT,
        domain: TranslationDomain = TranslationDomain.DEFAULT,
        hasContext: Boolean = false,
    ) = buildTranslationSystemPrompt(source, target, strength, domain, hasContext)

    @Test
    fun 기본값은_직역과_일반이다() {
        assertEquals(TranslationContextMode.NEARBY, TranslationContextMode.DEFAULT)
        assertEquals(TranslationStrength.LITERAL, TranslationStrength.DEFAULT)
        assertEquals(TranslationDomain.GENERAL, TranslationDomain.DEFAULT)
    }

    @Test
    fun 알_수_없는_저장값은_기본값으로_떨어진다() {
        // 원격/구버전 값이 남아 있어도 앱이 깨지지 않아야 한다.
        assertEquals(TranslationContextMode.DEFAULT, TranslationContextMode.from("LEGACY"))
        assertEquals(TranslationStrength.DEFAULT, TranslationStrength.from(null))
        assertEquals(TranslationDomain.DEFAULT, TranslationDomain.from(""))
    }

    @Test
    fun 출력형식_제약이_스타일_지시보다_앞선다() {
        // 순서가 뒤집히면 분야/강도 문구가 "번역문만 출력" 규칙을 흔든다.
        val p = prompt(strength = TranslationStrength.FREE, domain = TranslationDomain.GAME)
        val onlyIdx = p.indexOf("Output ONLY the translated text")
        val strengthIdx = p.indexOf(TranslationStrength.FREE.promptClause)
        val domainIdx = p.indexOf(TranslationDomain.GAME.promptClause!!)
        assertTrue(onlyIdx in 0 until strengthIdx)
        assertTrue(strengthIdx < domainIdx)
    }

    @Test
    fun 강도와_분야가_프롬프트에_반영된다() {
        TranslationStrength.entries.forEach { s ->
            assertTrue(s.name, prompt(strength = s).contains(s.promptClause))
        }
        TranslationDomain.entries.forEach { d ->
            val p = prompt(domain = d)
            if (d.promptClause == null) {
                // 일반(GENERAL)은 덧붙일 문구가 없어야 한다 — 토큰 낭비 방지.
                assertEquals(prompt(), p)
            } else {
                assertTrue(d.name, p.contains(d.promptClause))
            }
        }
    }

    @Test
    fun 자동감지는_원문언어를_적지_않고_판정도_청하지_않는다() {
        // 판정한 언어를 돌려받는 곳이 없다 — 돌려받던 것은 걷어낸 이미지 경로뿐이었다(§24.13).
        val auto = prompt(source = null)
        assertTrue(auto.contains("Translate the user's text into Korean."))
        assertFalse(auto.contains("Detect"))
        assertFalse(auto.contains(" from "))
        assertTrue(prompt(source = "English").contains("from English into Korean"))
    }

    @Test
    fun 문맥이_없으면_문맥_지시가_붙지_않는다() {
        val p = prompt(hasContext = false)
        assertFalse(p.contains("<context>"))
        assertFalse(p.contains("Translate ONLY the contents"))
    }

    @Test
    fun 문맥이_있으면_번역_대상을_한정하는_지시가_붙는다() {
        val p = prompt(hasContext = true)
        assertTrue(p.contains("Translate ONLY the contents of the <text> block"))
        assertTrue(p.contains("Never translate, quote or mention the <context> block"))
    }

    @Test
    fun 유저메시지는_문맥이_있을_때만_태그로_감싼다() {
        assertEquals("안녕", buildTranslationUserMessage("안녕", null))
        assertEquals("안녕", buildTranslationUserMessage("안녕", "   "))

        val wrapped = buildTranslationUserMessage("안녕", "주변 문장")
        assertEquals("<context>\n주변 문장\n</context>\n<text>\n안녕\n</text>", wrapped)
    }
}
