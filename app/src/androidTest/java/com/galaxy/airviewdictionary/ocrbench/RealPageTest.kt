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
 * 실제 웹 페이지(11개 언어 위키백과 모바일)로 문단 묶기를 평가한다.
 *
 * 앞선 표본은 전부 직접 만든 단순 HTML 이었다 — 단 하나, 같은 폰트, UI 없음.
 * 실제 페이지는 링크·각주·표·이미지 설명이 섞여 있어 훨씬 거칠다.
 * 정답은 DOM 에서 뽑은 문단 좌표라 라벨링 없이 정확하다.
 */
class RealPageTest {

    private val testContext get() = InstrumentationRegistry.getInstrumentation().context
    private val rtl = setOf("ar", "fa", "he")
    private val langs = listOf("en", "ko", "ja", "zh", "hi", "el", "he", "ar", "fa", "ru", "th")

    private fun read(name: String) = JSONArray(
        testContext.assets.open(name).use { String(it.readBytes(), Charsets.UTF_8) })

    @Test
    fun evaluateRealPages() {
        val repository = VisionRepository()
        val canvas = Bitmap.createBitmap(1440, 4800, Bitmap.Config.ARGB_8888)

        for (variant in listOf("box", "ink", "med")) {
            for (limit in listOf(1.05, 1.10, 1.25)) {
                var grouped = 0; var expected = 0; var dirty = 0
                val detail = StringBuilder()
                for (lang in langs) {
                    val d = if (lang in rtl) WritingDirection.RTL else WritingDirection.LTR
                    val params = AssemblyParams.reference(false, lang, linesFromDetector = true).copy(LINE_PITCH_LIMIT = limit)
                    val bj = read("vis_$lang.json")
                    val blocks = (0 until bj.length()).map { i ->
                        val o = bj.getJSONObject(i)
                        o.getInt("id") to Rect(o.getInt("l"), o.getInt("t"), o.getInt("r"), o.getInt("b"))
                    }
                    val lj = read("R_${lang}_$variant.json")
                    val ls = (0 until lj.length()).map { i ->
                        val o = lj.getJSONObject(i)
                        val box = Rect(o.getInt("l"), o.getInt("t"), o.getInt("r"), o.getInt("b"))
                        Line(mutableListOf(Word(box, "", d, emptyList(), box.height().toDouble())), d)
                    }
                    fun blockOf(l: Line) = blocks.firstOrNull {
                        it.second.contains(l.boundingBox.centerX(), l.boundingBox.centerY())
                    }?.first ?: -1
                    val ps = ParagraphAssembler.groupLinesIntoParagraphs(ls, d, params)
                    var g = 0; var e = 0; var c = 0
                    for ((id, _) in blocks) {
                        val own = ls.filter { blockOf(it) == id }
                        if (own.size < 2) continue
                        e += own.size
                        val best = ps.maxByOrNull { p -> p.lines.count { it in own } }
                        g += best?.lines?.count { it in own } ?: 0
                        c += best?.lines?.count { it !in own && blockOf(it) >= 0 } ?: 0
                    }
                    grouped += g; expected += e; dirty += c
                    if (e > 0) detail.append("$lang $g/$e" + (if (c > 0) "(오염$c)" else "") + " ")
                }
                log("[$variant] $limit → 묶임 $grouped/$expected 오염 $dirty | $detail")
            }
        }
    }

    private fun log(message: String) = android.util.Log.i("RealPage", message)
}
