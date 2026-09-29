package com.galaxy.airviewdictionary.data.local.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** auto 의 라틴 멈춤 규칙(`.docs/perf-experiment-plan.md` §5). 평가 스크립트 `tools/ocrsamples/autopick/stop2.py` 와 같은 계산이어야 한다. */
class AutoLatinStopTest {

    private fun box(l: Int, t: Int, r: Int, b: Int) = intArrayOf(l, t, r, b)

    @Test
    fun coverageIsTheShareOfDetectedAreaUnderLatinLines() {
        val detected = listOf(box(0, 0, 399, 39))
        assertEquals(1.0, AutoLatinStop.coverage(listOf(box(0, 0, 399, 39)), detected, 400, 40), 1e-9)
        assertEquals(0.5, AutoLatinStop.coverage(listOf(box(0, 0, 199, 39)), detected, 400, 40), 1e-9)
        assertEquals(0.0, AutoLatinStop.coverage(emptyList(), detected, 400, 40), 1e-9)
    }

    @Test
    fun noDetectorBoxesMeansNoCoverage() {
        assertEquals(0.0, AutoLatinStop.coverage(listOf(box(0, 0, 10, 10)), emptyList(), 100, 100), 1e-9)
    }

    /** 한글 줄을 라틴 인식기가 버린 화면 — 영어 줄은 깨끗하지만 검출된 글자 자리의 절반만 덮는다. */
    @Test
    fun droppedLinesFailTheCoverageCheck() {
        val texts = listOf("Read the full article here", "Subscribe to our newsletter")
        val lines = listOf(box(0, 0, 399, 39), box(0, 50, 399, 89))
        val detected = lines + listOf(box(0, 100, 399, 139), box(0, 150, 399, 189))
        assertTrue(AutoLatinStop.passesTextChecks(texts))
        assertFalse(AutoLatinStop.passesCoverage(lines, detected, 400, 200))
        assertTrue(AutoLatinStop.passesCoverage(lines, lines, 400, 200))
    }

    @Test
    fun garbageSymbolsAndShortTextFailTheCheapChecks() {
        assertFalse(AutoLatinStop.passesTextChecks(listOf("41|(94: coffee }), EDIEE|", "AI|E{g0} coffee #")))
        assertFalse(AutoLatinStop.passesTextChecks(listOf("Hello", "OK")))
    }

    @Test
    fun lineLanguagesCountOnlyLatinReaderLanguages() {
        val texts = listOf("aaaaaaaaaa", "bbbbbbbbbb")
        assertTrue(AutoLatinStop.passesLineLanguages(texts, listOf("en", "de")))
        assertTrue(AutoLatinStop.passesLineLanguages(texts, listOf("en", "hi-Latn")))
        assertFalse(AutoLatinStop.passesLineLanguages(texts, listOf("en", "und")))
        assertFalse(AutoLatinStop.passesLineLanguages(texts, listOf("en", "zh")))
        assertFalse(AutoLatinStop.passesLineLanguages(texts, listOf("ja", "ko")))
        assertFalse(AutoLatinStop.passesLineLanguages(texts, listOf("en", "hi")))
    }
}
