package com.galaxy.airviewdictionary.ocrbench

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
 * 문단 묶기를 여러 언어·여러 문단 표본으로 평가한다.
 *
 * 정답은 브라우저에서 뽑은 각 문단/제목의 실제 좌표다. 검출된 줄은 자기 중심이 든
 * 정답 블록에 속한 것으로 본다.
 *
 * 두 가지를 함께 잰다 — 같은 문단이 한 덩어리로 묶였는가(묶임), 그리고 다른 블록이
 * 섞여 들어오지 않았는가(오염). 한쪽만 보면 "전부 한 문단"이 만점을 받는다.
 */
class ParagraphSampleTest {

    private val testContext get() = InstrumentationRegistry.getInstrumentation().context
    private val rtl = setOf("ar", "fa")

    private class Block(val id: Int, val tag: String, val rect: Rect)

    private fun truth(lang: String): List<Block> {
        val json = JSONArray(testContext.assets.open("rects_$lang.json").use {
            String(it.readBytes(), Charsets.UTF_8)
        })
        return (0 until json.length()).map { i ->
            val o = json.getJSONObject(i)
            Block(o.getInt("id"), o.getString("tag"),
                Rect(o.getInt("l"), o.getInt("t"), o.getInt("r"), o.getInt("b")))
        }
    }

    private fun lines(lang: String, variant: String, direction: WritingDirection): List<Line> {
        val json = JSONArray(testContext.assets.open("s_${lang}_$variant.json").use {
            String(it.readBytes(), Charsets.UTF_8)
        })
        return (0 until json.length()).map { i ->
            val o = json.getJSONObject(i)
            val box = Rect(o.getInt("l"), o.getInt("t"), o.getInt("r"), o.getInt("b"))
            Line(mutableListOf(
                Word(box, o.getString("text"), direction, emptyList(), box.height().toDouble())
            ), direction)
        }
    }

    @Test
    fun evaluateAcrossSamples() {
        val repository = VisionRepository()
        val canvas = Bitmap.createBitmap(1440, 4200, Bitmap.Config.ARGB_8888)

        for (variant in listOf("box", "ink", "med")) {
            var grouped = 0; var expected = 0; var contaminated = 0
            val detail = StringBuilder()
            for (lang in listOf("ar", "fa", "ru", "th")) {
                val direction = if (lang in rtl) WritingDirection.RTL else WritingDirection.LTR
                val params = AssemblyParams.reference(false, lang, linesFromDetector = true)
                val ls = lines(lang, variant, direction)
                val blocks = truth(lang)

                fun blockOf(line: Line): Int =
                    blocks.firstOrNull { it.rect.contains(line.boundingBox.centerX(), line.boundingBox.centerY()) }?.id ?: -1

                val paragraphs = repository.groupLinesIntoParagraphs(ls, direction, params)
                var langGrouped = 0; var langExpected = 0; var langBad = 0
                for (block in blocks) {
                    val own = ls.filter { blockOf(it) == block.id }
                    if (own.size < 2) continue   // 한 줄짜리 블록은 묶임을 평가할 수 없다
                    langExpected += own.size
                    val best = paragraphs.maxByOrNull { p -> p.lines.count { it in own } }
                    val hit = best?.lines?.count { it in own } ?: 0
                    langGrouped += hit
                    // 그 문단에 다른 블록의 줄이 섞였는가
                    langBad += best?.lines?.count { it !in own } ?: 0
                }
                grouped += langGrouped; expected += langExpected; contaminated += langBad
                detail.append("$lang ${langGrouped}/${langExpected}(오염${langBad}) ")
            }
            log("[$variant] 묶임 $grouped/$expected  오염 $contaminated   $detail")
        }
    }

    private fun log(message: String) = android.util.Log.i("ParagraphSample", message)
}
