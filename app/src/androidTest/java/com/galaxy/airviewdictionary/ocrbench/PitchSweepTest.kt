package com.galaxy.airviewdictionary.ocrbench

import com.galaxy.airviewdictionary.data.local.vision.ParagraphAssembler
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.test.platform.app.InstrumentationRegistry
import com.galaxy.airviewdictionary.data.local.vision.AssemblyParams
import com.galaxy.airviewdictionary.data.local.vision.VisionRepository
import com.galaxy.airviewdictionary.data.local.vision.WritingDirection
import com.galaxy.airviewdictionary.data.local.vision.model.Line
import com.galaxy.airviewdictionary.data.local.vision.model.Word
import org.json.JSONArray
import org.junit.Test

/**
 * 문단 묶기를 11개 문자 x 2배치 표본으로 평가하고 줄간격 한계비를 훑는다.
 *
 * 쉬운 배치는 문단 사이가 넉넉하고, 어려운 배치는 제목·목록·설명이 바짝 붙어 있다.
 * 두 가지를 함께 봐야 한다 — 묶임만 보면 "전부 한 문단"이 만점을 받고,
 * 오염만 보면 "전부 따로"가 만점을 받는다.
 */
class PitchSweepTest {

    private val testContext get() = InstrumentationRegistry.getInstrumentation().context
    private val rtl = setOf("ar", "fa", "he")
    private val langs = listOf("en", "ko", "ja", "zh", "hi", "el", "he", "ar", "fa", "ru", "th")

    private class Block(val id: Int, val rect: Rect)

    private fun read(name: String) = JSONArray(
        testContext.assets.open(name).use { String(it.readBytes(), Charsets.UTF_8) }
    )

    private fun blocks(lang: String, kind: String) = read("layr_${lang}_$kind.json").let { j ->
        (0 until j.length()).map { i ->
            val o = j.getJSONObject(i)
            Block(o.getInt("id"), Rect(o.getInt("l"), o.getInt("t"), o.getInt("r"), o.getInt("b")))
        }
    }

    private fun lines(lang: String, kind: String, variant: String, d: WritingDirection) =
        read("L_${lang}_${kind}_$variant.json").let { j ->
            (0 until j.length()).map { i ->
                val o = j.getJSONObject(i)
                val box = Rect(o.getInt("l"), o.getInt("t"), o.getInt("r"), o.getInt("b"))
                Line(mutableListOf(Word(box, "", d, emptyList(), box.height().toDouble())), d)
            }
        }

    /**
     * 표본마다 안전 구간을 찾는다.
     *
     * - 묶임완성: 그 표본의 모든 문단이 한 덩어리가 되는 가장 작은 한계비
     * - 오염시작: 다른 블록이 섞이기 시작하는 가장 작은 한계비
     *
     * 둘 사이가 그 표본의 안전 구간이다. 모든 표본의 구간이 겹치는 값이 있으면
     * 문자별 표 없이 상수 하나로 된다.
     */
    @Test
    fun perSampleSafeRange() {
        val repository = VisionRepository()
        val canvas = Bitmap.createBitmap(1440, 5000, Bitmap.Config.ARGB_8888)
        val steps = (90..170).map { it / 100.0 }

        for (variant in listOf("box", "ink", "med")) {
            log("=== $variant ===")
            for (lang in langs) {
                for (kind in listOf("easy", "tight", "dense")) {
                    val d = if (lang in rtl) WritingDirection.RTL else WritingDirection.LTR
                    val bs = blocks(lang, kind)
                    var full = Double.NaN
                    var dirty = Double.NaN
                    var maxGrouped = 0
                    var expected = 0
                    for (limit in steps) {
                        val params = AssemblyParams.reference(false, lang, linesFromDetector = true).copy(LINE_PITCH_LIMIT = limit)
                        val ls = lines(lang, kind, variant, d)
                        fun blockOf(l: Line) = bs.firstOrNull {
                            it.rect.contains(l.boundingBox.centerX(), l.boundingBox.centerY())
                        }?.id ?: -1
                        val ps = ParagraphAssembler.groupLinesIntoParagraphs(ls, d, params)
                        var g = 0; var e = 0; var c = 0
                        for (b in bs) {
                            val own = ls.filter { blockOf(it) == b.id }
                            if (own.size < 2) continue
                            e += own.size
                            val best = ps.maxByOrNull { p -> p.lines.count { it in own } }
                            g += best?.lines?.count { it in own } ?: 0
                            c += best?.lines?.count { it !in own && blockOf(it) >= 0 } ?: 0
                        }
                        expected = e
                        if (g > maxGrouped) maxGrouped = g
                        if (full.isNaN() && e > 0 && g == e) full = limit
                        if (dirty.isNaN() && c > 0) dirty = limit
                    }
                    log("$lang/$kind 기대$expected 최대묶임$maxGrouped" +
                            " 묶임완성=${if (full.isNaN()) "없음" else full.toString()}" +
                            " 오염시작=${if (dirty.isNaN()) "없음" else dirty.toString()}")
                }
            }
        }
    }

    @Test
    fun sweepPitchLimit() {
        val repository = VisionRepository()
        val canvas = Bitmap.createBitmap(1440, 5000, Bitmap.Config.ARGB_8888)

        for (variant in listOf("box", "ink", "med")) {
            for (limit in listOf(1.05, 1.10, 1.25, 1.60)) {
                val g = mutableMapOf("easy" to 0, "tight" to 0)
                val e = mutableMapOf("easy" to 0, "tight" to 0)
                val c = mutableMapOf("easy" to 0, "tight" to 0)
                for (lang in langs) {
                    val d = if (lang in rtl) WritingDirection.RTL else WritingDirection.LTR
                    for (kind in listOf("easy", "tight")) {
                        val params = AssemblyParams.reference(false, lang, linesFromDetector = true).copy(LINE_PITCH_LIMIT = limit)
                        val ls = lines(lang, kind, variant, d)
                        val bs = blocks(lang, kind)
                        fun blockOf(l: Line) = bs.firstOrNull {
                            it.rect.contains(l.boundingBox.centerX(), l.boundingBox.centerY())
                        }?.id ?: -1
                        val ps = ParagraphAssembler.groupLinesIntoParagraphs(ls, d, params)
                        for (b in bs) {
                            val own = ls.filter { blockOf(it) == b.id }
                            if (own.size < 2) continue
                            e[kind] = e[kind]!! + own.size
                            val best = ps.maxByOrNull { p -> p.lines.count { it in own } }
                            g[kind] = g[kind]!! + (best?.lines?.count { it in own } ?: 0)
                            c[kind] = c[kind]!! + (best?.lines?.count { it !in own && blockOf(it) >= 0 } ?: 0)
                        }
                    }
                }
                log("[$variant] $limit → 쉬움 ${g["easy"]}/${e["easy"]}(오염${c["easy"]})" +
                        "  어려움 ${g["tight"]}/${e["tight"]}(오염${c["tight"]})")
            }
        }
    }

    private fun log(message: String) = android.util.Log.i("PitchSweep", message)
}
