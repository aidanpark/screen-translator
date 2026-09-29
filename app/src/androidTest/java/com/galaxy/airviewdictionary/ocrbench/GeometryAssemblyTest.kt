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
 * 실제 PP-OCRv5 결과를 기하 조립기에 먹여 줄·문단·문장이 제대로 묶이는지 본다.
 *
 * 지금까지 아랍어에서 조립이 엉뚱했던 것이 알고리즘의 한계인지 입력이 쓰레기였기 때문인지
 * 구분할 수 없었다. ML Kit 이 아랍어를 못 읽어 조립기에 가짜 단어가 들어갔기 때문이다.
 * 제대로 읽은 단어를 넣어보면 그 구분이 된다.
 *
 * 입력은 실기기 화면 캡처(아랍어 위키백과)를 PP-OCRv5 로 읽고 CTC 시간축으로 단어를 쪼갠 것이다.
 */
class GeometryAssemblyTest {

    private val testContext get() = InstrumentationRegistry.getInstrumentation().context

    @Test
    fun assembleArabicWordsIntoParagraphs() {
        val json = testContext.assets.open("words_ar.json").use {
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
        log("입력 단어 ${words.size}개")

        val repository = VisionRepository()
        val params = AssemblyParams.reference(false, "ar", linesFromDetector = true)
        val canvas = Bitmap.createBitmap(1440, 3120, Bitmap.Config.ARGB_8888)

        // ① 지금 방식 — 단어에서 줄을 다시 유도한다.
        val derived = repository.groupWordsIntoLines(words, WritingDirection.RTL, params)
        log("① 단어에서 유도한 줄: ${derived.size}개 (단어/줄 평균 ${"%.1f".format(words.size.toFloat() / derived.size)})")
        log("   문단 ${repository.groupLinesIntoParagraphs(derived, WritingDirection.RTL, params).size}개")

        // ② 검출기가 준 줄을 그대로 쓴다. 같은 줄 상자에서 나온 단어는 같은 줄이다.
        val byDetectedLine = words.groupBy { it.boundingBox.top to it.boundingBox.bottom }
        val detected = byDetectedLine.values.map { ws ->
            Line(ws.sortedByDescending { it.boundingBox.right }.toMutableList(), WritingDirection.RTL)
        }
        log("② 검출기가 준 줄: ${detected.size}개 (단어/줄 평균 ${"%.1f".format(words.size.toFloat() / detected.size)})")

        val paragraphs = repository.groupLinesIntoParagraphs(detected, WritingDirection.RTL, params)
        log("   문단 ${paragraphs.size}개")
        paragraphs.forEachIndexed { i, p ->
            p.languageCode = "ar"
            log("   문단$i (${p.lines.size}줄, 문장 ${p.sentences.size}개) ${p.representation.take(60)}")
        }
    }

    private fun log(message: String) {
        android.util.Log.i("GeometryAssembly", message)
    }
}
