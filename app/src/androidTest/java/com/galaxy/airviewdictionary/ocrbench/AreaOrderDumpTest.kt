package com.galaxy.airviewdictionary.ocrbench

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.galaxy.airviewdictionary.data.local.vision.VisionRepository
import com.galaxy.airviewdictionary.data.local.vision.model.VisionResponse
import com.galaxy.airviewdictionary.data.local.vision.ocr.ReadingOrder
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import java.io.File
import kotlin.math.min

/**
 * 영역 선택·고정 영역의 글 순서(`.docs/vision-engine-design.md` §24)를 실제 캡처로 잰다 — 채점은 오프라인이다.
 *
 * 앱 외부 미디어의 `area_eval/in/real_<언어><번호>.png` 를 화면 크기(위쪽 1440×3200)로 잘라 화면 전체를 영역으로 읽고
 * (`readAll`), 새 순서(문단으로 단을 가른 글)와 옛 순서(행 규칙만, [ReadingOrder.text])를 `area_eval/out/` 에 떨어뜨린다.
 * 캡처는 공개 저장소에 올리지 않으므로 에셋이 아니라 기기에 밀어 넣는다(`adb push`).
 */
class AreaOrderDumpTest {

    @Test
    fun dumpAreaText() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.externalMediaDirs.first(), "area_eval")
        val out = File(root, "out").apply { mkdirs() }
        val repository = VisionRepository(context)
        val files = File(root, "in").listFiles { f -> f.name.endsWith(".png") }.orEmpty().sortedBy { it.name }
        for (file in files) {
            val name = file.name.removePrefix("real_").removeSuffix(".png")
            val lang = name.trimEnd { it.isDigit() }
            val page = BitmapFactory.decodeFile(file.path)
            val screen = Bitmap.createBitmap(page, 0, 0, page.width, min(page.height, 3200))
            val response = repository.request(screen, lang, readAll = true)
            if (response !is VisionResponse.Success) continue
            val tx = response.result
            // 줄마다 상자·글·조립기가 묶은 문단 번호 — 순서 규칙은 오프라인에서 이것으로 다시 짠다
            val (paragraphs, sources) = repository.detectorParagraphs(screen, tx.ocr.lines, lang)
            val paragraphOf = java.util.IdentityHashMap<com.galaxy.airviewdictionary.data.local.vision.ocr.OcrLine, Int>()
            paragraphs.forEachIndexed { i, p -> sources.getValue(p).forEach { paragraphOf[it] = i } }
            val lines = org.json.JSONArray()
            for (line in tx.ocr.lines) {
                val b = line.boundingBox ?: continue
                lines.put(JSONObject().put("b", org.json.JSONArray(listOf(b.left, b.top, b.right, b.bottom))).put("t", line.text).put("p", paragraphOf[line] ?: -1))
            }
            File(out, "$name.json").writeText(
                JSONObject().put("lang", lang).put("new", tx.ocr.text).put("old", ReadingOrder.text(tx.ocr.lines)).put("lines", lines).toString()
            )
            android.util.Log.i("AreaOrderDump", "$name 줄 ${tx.ocr.lines.size}")
        }
    }

    /**
     * 읽기 없이 문단 묶기만 — `area_eval/lines/<이름>.json`(줄 상자·글, 파이썬 포트가 뽑은 것)의 줄마다 조립기가 묶은 문단 번호를 붙여
     * `area_eval/grouped/` 에 떨어뜨린다. 순서 규칙은 오프라인에서 이것으로 짠다. 기기가 데워져도 1분 안쪽이다.
     */
    @Test
    fun groupLines() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.externalMediaDirs.first(), "area_eval")
        val out = File(root, "grouped").apply { mkdirs() }
        val repository = VisionRepository()
        for (file in File(root, "lines").listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }) {
            val page = JSONObject(file.readText())
            val lang = page.getString("lang")
            val screen = Bitmap.createBitmap(page.getInt("w"), page.getInt("h"), Bitmap.Config.ALPHA_8)
            val json = page.getJSONArray("lines")
            val lines = (0 until json.length()).map { i ->
                val o = json.getJSONObject(i)
                val b = o.getJSONArray("b")
                com.galaxy.airviewdictionary.data.local.vision.ocr.OcrLine(
                    android.graphics.Rect(b.getInt(0), b.getInt(1), b.getInt(2), b.getInt(3)), o.getString("t"), null, emptyList(),
                )
            }
            val (paragraphs, sources) = repository.detectorParagraphs(screen, lines, lang)
            val paragraphOf = java.util.IdentityHashMap<com.galaxy.airviewdictionary.data.local.vision.ocr.OcrLine, Int>()
            paragraphs.forEachIndexed { i, p -> sources.getValue(p).forEach { paragraphOf[it] = i } }
            lines.forEachIndexed { i, line -> json.getJSONObject(i).put("p", paragraphOf[line] ?: -1) }
            File(out, file.name).writeText(page.toString())
            screen.recycle()
        }
    }
}
