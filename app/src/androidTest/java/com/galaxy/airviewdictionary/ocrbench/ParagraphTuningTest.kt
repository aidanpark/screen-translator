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
 * 문단 묶기 상수를 검출기 줄 기준으로 다시 맞춘다.
 *
 * 지금 값들은 ML Kit 의 박스 습성에 맞춰 조정된 것이라 PP-OCRv5 검출기 줄에는 이식되지 않는다.
 * 단어→줄 단계에서 이미 그랬다 — 한계비 0.63 인데 실제 간격비가 0.34~0.68 로 나와 절반이 떨어졌다.
 *
 * 판정 기준: 본문 영역(세로 1110~1890)의 줄들이 **하나의 문단**으로 묶여야 한다.
 * 실측 출발점은 9줄 중 7줄이다.
 */
class ParagraphTuningTest {

    private val testContext get() = InstrumentationRegistry.getInstrumentation().context
    // 본문 문단은 검출 줄 top=1157 ~ bot=2070 에 걸쳐 있다. 모두 9줄이다.
    private val bodyRange = 1140..2080

    private fun detectorLines(asset: String = "words_ar.json"): List<Line> {
        val json = testContext.assets.open(asset).use {
            JSONArray(String(it.readBytes(), Charsets.UTF_8))
        }
        val words = (0 until json.length()).map { i ->
            val o = json.getJSONObject(i)
            Word(
                boundingBox = Rect(o.getInt("l"), o.getInt("t"), o.getInt("r"), o.getInt("b")),
                representation = o.getString("text"),
                writingDirection = WritingDirection.RTL,
                chars = emptyList(),
                presetFontHeight = (o.getInt("b") - o.getInt("t")).toDouble(),
            )
        }
        return words.groupBy { it.boundingBox.top to it.boundingBox.bottom }
            .values.map { ws ->
                Line(ws.sortedByDescending { it.boundingBox.right }.toMutableList(), WritingDirection.RTL)
            }
    }

    /** 본문 줄들이 한 문단에 몇 개나 함께 묶였는지. */
    private fun bodyLinesGrouped(repository: VisionRepository, lines: List<Line>, params: AssemblyParams): Pair<Int, Int> {
        val bodyLines = lines.filter { it.boundingBox.centerY() in bodyRange }
        val paragraphs = repository.groupLinesIntoParagraphs(lines, WritingDirection.RTL, params)
        val best = paragraphs.maxOfOrNull { p ->
            p.lines.count { it.boundingBox.centerY() in bodyRange }
        } ?: 0
        return best to bodyLines.size
    }

    /**
     * 검출 줄 박스를 잉크 띠로 세로만 조인 입력. 첨자가 박스 높이를 부풀리는 문제를 없앤다.
     * 상수는 건드리지 않는다 — 원래 값으로 9/9 가 나오는지 본다.
     */
    @Test
    fun inkTightenedBoxesWithOriginalConstants() {
        val repository = VisionRepository()
        val params = AssemblyParams.reference(false, "ar", linesFromDetector = true)
        val canvas = Bitmap.createBitmap(1440, 3120, Bitmap.Config.ARGB_8888)

        for (asset in listOf("words_ar.json", "words_ar_ink.json", "words_ar_med.json")) {
            val lines = detectorLines(asset)
            val heights = lines.filter { it.boundingBox.centerY() in bodyRange }
                .map { it.boundingBox.height() }.sorted()
            val (got, total) = bodyLinesGrouped(repository, lines, params)
            log("$asset → $got/$total  본문 줄높이 $heights")
        }
    }

    @Test
    fun tuneParagraphConstants() {
        val lines = detectorLines()
        val repository = VisionRepository()
        val params = AssemblyParams.reference(false, "ar", linesFromDetector = true)
        val canvas = Bitmap.createBitmap(1440, 3120, Bitmap.Config.ARGB_8888)

        val (base, total) = bodyLinesGrouped(repository, lines, params)
        log("기준값: 본문 $total 줄 중 $base 줄이 한 문단")

        // 어느 줄이 어느 문단으로 갔는지 본다. 문턱값을 다 풀어도 안 바뀌었으니
        // 문턱이 아니라 다른 곳에서 갈린다.
        val paragraphs = repository.groupLinesIntoParagraphs(lines, WritingDirection.RTL, params)
        lines.filter { it.boundingBox.centerY() in bodyRange }
            .sortedBy { it.boundingBox.top }
            .forEach { line ->
                val at = paragraphs.indexOfFirst { p -> p.lines.any { it === line } }
                log("본문줄 top=${line.boundingBox.top} h=${line.boundingBox.height()}" +
                        " x=[${line.boundingBox.left},${line.boundingBox.right}]" +
                        " 단어${line.words.size} → 문단$at")
            }
        paragraphs.forEachIndexed { i, p ->
            log("  문단$i ${p.lines.size}줄 y=[${p.boundingBox.top},${p.boundingBox.bottom}] x=[${p.boundingBox.left},${p.boundingBox.right}]")
        }

        // 두 조건이 직렬이라 함께 풀어야 통과한다. 하나씩 바꾸면 앞의 조건에서 막혀 변화가 없다.
        for (fh in listOf(0.68, 0.60, 0.55, 0.50, 0.40)) {
            for (af in listOf(0.62, 0.55, 0.50, 0.40, 0.30)) {
                val tuned = params.copy(LINE_FONT_HEIGHT_SIMILARITY_MINIMUM_RATIO = fh, LINE_FONT_HEIGHT_SPACING_AFFINITY_LIMIT = af)
                val got = bodyLinesGrouped(repository, lines, tuned).first
                if (got > 7) log("폰트높이유사 $fh + affinity $af → $got/$total")
            }
        }
        log("--- 위에 아무것도 없으면 두 상수만으로는 안 된다는 뜻 ---")
    }

    private fun log(message: String) = android.util.Log.i("ParagraphTuning", message)
}
