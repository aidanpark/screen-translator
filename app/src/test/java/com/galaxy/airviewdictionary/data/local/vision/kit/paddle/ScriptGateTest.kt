package com.galaxy.airviewdictionary.data.local.vision.kit.paddle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.abs

/** auto 의 지원되지 않는 문자권 관문(성능 P7) — 규칙과 전처리. 평가 도구(`tools/scriptid/gate.py`, `train.py`)와 같은 계산이어야 한다. */
class ScriptGateTest {

    private fun p(script: String, prob: Float): FloatArray {
        val k = ScriptGate.SCRIPTS.indexOf(script)
        return FloatArray(ScriptGate.SCRIPTS.size) { if (it == k) prob else (1 - prob) / (ScriptGate.SCRIPTS.size - 1) }
    }

    @Test
    fun 줄의_절반_이상이_같은_지원되지_않는_문자권이면_개입한다() {
        val v = ScriptGate.judge(listOf(p("hebrew", 0.9f), p("hebrew", 0.8f), p("latin", 0.9f), p("hebrew", 0.7f)))
        assertEquals("hebrew", v?.script)
        assertEquals("he", v?.language)
    }

    @Test
    fun 지원_문자권이_다수면_개입하지_않는다() {
        assertNull(ScriptGate.judge(listOf(p("latin", 0.9f), p("latin", 0.9f), p("hebrew", 0.9f), p("latin", 0.9f))))
        assertNull(ScriptGate.judge(listOf(p("cyrillic", 0.9f), p("cyrillic", 0.9f), p("cyrillic", 0.9f))))
    }

    @Test
    fun 줄이_모자라거나_확신이_낮으면_개입하지_않는다() {
        assertNull(ScriptGate.judge(listOf(p("hebrew", 0.9f), p("hebrew", 0.9f))))
        assertNull(ScriptGate.judge(listOf(p("khmer", 0.3f), p("khmer", 0.4f), p("khmer", 0.45f))))
    }

    @Test
    fun 가로줄을_넓은_순으로_고른다() {
        val boxes = listOf(
            intArrayOf(0, 0, 100, 20),   // 가로줄
            intArrayOf(0, 0, 30, 20),    // 폭 < 높이 × 2 — 빠진다
            intArrayOf(0, 0, 300, 20),   // 가장 넓다
            intArrayOf(0, 0, 200, 5),    // 너무 낮다(폭 1080 화면에서 9px 미만)
        )
        val picked = ScriptGate.pickLines(boxes, 1080)
        assertEquals(listOf(300, 100), picked.map { it[2] - it[0] })
    }

    @Test
    fun 표준화된_입력은_평균_0_표준편차_1() {
        val crop = IntArray(40 * 120) { i -> if ((i / 7) % 3 == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val x = ScriptGate.cropInput(crop, 120, 40)
        assertEquals(ScriptGate.H * ScriptGate.W, x.size)
        val mean = x.average()
        val std = kotlin.math.sqrt(x.map { (it - mean) * (it - mean) }.average())
        assert(abs(mean) < 1e-3) { "mean $mean" }
        assert(abs(std - 1) < 0.01) { "std $std" }
    }

    @Test
    fun 쌍선형_축소는_Pillow_처럼_범위를_평균한다() {
        // 0/255 가 한 칸씩 번갈아 나오는 줄을 절반으로 줄이면 Pillow BILINEAR 는 회색(약 128)이 된다(단순 보간이면 0 이나 255)
        val sw = 64; val sh = 64
        val src = FloatArray(sw * sh) { i -> if ((i % sw) % 2 == 0) 0f else 255f }
        val out = ScriptGate.resize(src, sw, sh, 32, 32, 32)
        val mid = out[16 * 32 + 16]
        assert(mid in 100f..155f) { "mid $mid" }
    }
}
