package com.galaxy.airviewdictionary.data.remote.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI 이미지 번역의 하이라이트 좁히기(§25) — 줄·문단 글에서 고른 단어·문장의 자리를 줄 폭에 비례해 옮긴다. */
class ImageTargetNarrowingTest {

    private fun box(l: Int, t: Int, r: Int, b: Int) = intArrayOf(l, t, r, b)

    /** 높이 20 짜리 줄 — 여백(0.3 × 20 = 6)과 최소 폭(16)을 셈하기 쉽게. */
    private val line = box(0, 0, 200, 20)

    @Test
    fun 단어는_줄_글에서의_자리만큼_줄_폭에_비례해_잡힌다() {
        // 19 글자에 폭 200 → 글자당 200/19. "cccc" 는 10~14번째 글자, 좌우 여백 6
        val spans = ImageTargetNarrowing.narrow(listOf(line), "aaaa bbbb cccc dddd", "cccc", null, rtl = false)!!
        assertEquals(1, spans.size)
        val s = spans[0]
        assertEquals(listOf(0, 20), listOf(s[1], s[3]))
        assertEquals(10 * 200.0 / 19 - 6, s[0].toDouble(), 1.0)
        assertEquals(14 * 200.0 / 19 + 6, s[2].toDouble(), 1.0)
    }

    @Test
    fun 오른쪽에서_왼쪽으로_읽는_글은_줄의_오른쪽부터_센다() {
        val ltr = ImageTargetNarrowing.narrow(listOf(line), "aaaa bbbb cccc dddd", "aaaa", null, rtl = false)!![0]
        val rtl = ImageTargetNarrowing.narrow(listOf(line), "aaaa bbbb cccc dddd", "aaaa", null, rtl = true)!![0]
        assertTrue("LTR 첫 단어는 왼쪽 끝", ltr[0] == 0)
        assertTrue("RTL 첫 단어는 오른쪽 끝", rtl[2] == 200)
    }

    @Test
    fun 같은_글이_여럿이면_포인터에_가까운_것을_고른다() {
        val near = ImageTargetNarrowing.narrow(listOf(line), "go to go", "go", intArrayOf(190, 10), rtl = false)!![0]
        assertTrue("오른쪽 'go'", near[0] > 100)
        val far = ImageTargetNarrowing.narrow(listOf(line), "go to go", "go", intArrayOf(5, 10), rtl = false)!![0]
        assertTrue("왼쪽 'go'", far[2] < 100)
    }

    @Test
    fun 문장은_여러_줄에_걸치면_줄마다_상자가_생긴다() {
        val lines = listOf(box(0, 0, 100, 20), box(0, 30, 100, 50), box(0, 60, 40, 80))
        // 글 24글자(폭 240 = 100+100+40). "Bb bb. Cc" 가 둘째 줄 가운데에서 셋째 줄로 넘어간다
        val context = "Aaaa aaaa. Bbbb bbbb. Cc"
        val spans = ImageTargetNarrowing.narrow(lines, context, "Bbbb bbbb. Cc", null, rtl = false)!!
        assertEquals(listOf(30, 60), spans.map { it[1] }.filter { it >= 30 })
        assertTrue("첫 줄에서 시작하지 않는다", spans.none { it[1] == 0 } || spans.first()[0] > 50)
    }

    @Test
    fun 공백과_대소문자와_끝_문장부호는_달라도_찾는다() {
        assertEquals(1, ImageTargetNarrowing.narrow(listOf(line), "Hello  world\nagain", "WORLD", null, false)!!.size)
        assertEquals(1, ImageTargetNarrowing.narrow(listOf(line), "Hello world again", "world,", null, false)!!.size)
    }

    @Test
    fun 못_찾으면_null() {
        assertNull(ImageTargetNarrowing.narrow(listOf(line), "Hello world", "שלום", null, false))
        assertNull(ImageTargetNarrowing.narrow(emptyList(), "Hello world", "world", null, false))
    }

    @Test
    fun 결합_부호는_폭이_없다() {
        // 히브리 모음점(U+05B8)은 앞 글자에 얹힌다 — 셈에서 빠져야 뒤 단어가 밀리지 않는다
        assertEquals(0.0, ImageTargetNarrowing.weight(0x05B8), 0.0)
        assertEquals(1.0, ImageTargetNarrowing.weight('ש'.code), 0.0)
    }

    @Test
    fun 짧은_단어도_최소_폭은_가진다() {
        val s = ImageTargetNarrowing.narrow(listOf(line), "a bcdefghijklmnopqrstuvwxyz", "a", null, false)!![0]
        assertTrue(s[2] - s[0] >= 16)
    }
}
