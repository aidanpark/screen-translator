package com.galaxy.airviewdictionary.ocrbench

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import androidx.test.platform.app.InstrumentationRegistry
import com.galaxy.airviewdictionary.data.local.vision.kit.VisionKitSelector
import com.galaxy.airviewdictionary.ui.screen.overlay.selection.createOverlaidBitmap
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * auto 의 인식기 고르기를 비교하려고(`.docs/vision-engine-design.md` §11) 후보 다섯의 줄을 덤프한다.
 *
 * 표본마다 셋 — 화면 전체, 그리고 영역 선택을 흉내 낸 잘라내기 둘(영역 밖을 검게 칠한다. 영역은 표본 이름으로 정한 난수라
 * 가장자리가 글줄을 자르기도 한다). 규칙 비교는 이 덤프로 오프라인에서 한다.
 *
 * 스테이징은 [OcrDumpTest] 와 같다 — `real_<이름>.png` 를 에셋에 잠깐 놓고 돌린다.
 */
class AutoPickDumpTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().context
    private val appContext get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun dumpCandidates() = runBlocking {
        // kits=TEXT 처럼 주면 그 인식기만 덤프한다(성능 P4-2 — PP-OCRv5 문자 화면의 라틴 인식기)
        val only = InstrumentationRegistry.getArguments().getString("kits")?.split(",")?.map { it.trim() }
        val kits = VisionKitSelector().candidatesFor("auto").filter { only == null || it.name in only }
        val given = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val outDir = File(given ?: appContext.externalMediaDirs.first().path, "autopick").apply { mkdirs() }
        // 앱 외부 미디어의 autopick_png/ 에 real_<이름>.png 가 있으면 에셋 대신 거기서 읽는다(많은 표본을 APK 재빌드 없이)
        val pngDir = File(appContext.externalMediaDirs.first(), "autopick_png").takeIf { it.isDirectory }
        val names = (pngDir?.list()?.toList() ?: context.assets.list("")!!.toList())
            .filter { it.startsWith("real_") && it.endsWith(".png") }
            .map { it.removePrefix("real_").removeSuffix(".png") }.sorted()

        for (name in names) {
            if (File(outDir, "$name.json").exists()) continue
            val bytes = pngDir?.let { File(it, "real_$name.png").readBytes() } ?: context.assets.open("real_$name.png").use { it.readBytes() }
            val screen = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val variants = JSONArray()
            val random = Random(name.hashCode())
            for (kind in listOf("full", "crop1", "crop2")) {
                val rect = if (kind == "full") null else cropRect(screen, random)
                val image = rect?.let { createOverlaidBitmap(screen, it) } ?: screen
                val candidates = JSONArray()
                for (kit in kits) {
                    val started = System.currentTimeMillis()
                    val lines = JSONArray()
                    val text = try {
                        val ocr = kit.detect(image)
                        for (line in ocr.lines) {
                            val b = line.boundingBox
                            lines.put(JSONObject().put("c", line.confidence?.toDouble() ?: JSONObject.NULL).put("t", line.text)
                                .put("b", if (b == null) JSONObject.NULL else JSONArray(listOf(b.left, b.top, b.right, b.bottom))))
                        }
                        ocr.text
                    } catch (e: Exception) {
                        null
                    }
                    candidates.put(JSONObject().put("kit", kit.name).put("text", text ?: JSONObject.NULL)
                        .put("lines", lines).put("ms", System.currentTimeMillis() - started))
                }
                variants.put(JSONObject().put("kind", kind)
                    .put("rect", rect?.let { JSONArray(listOf(it.left, it.top, it.right, it.bottom)) } ?: JSONObject.NULL)
                    .put("candidates", candidates))
                if (image !== screen) image.recycle()
            }
            File(outDir, "$name.json").writeText(JSONObject().put("name", name).put("variants", variants).toString())
            screen.recycle()
            android.util.Log.i("AutoPickDump", "$name 끝")
        }
    }

    /**
     * 프로덕션 auto 가 실제로 고른 글을 같은 세 이미지에서 떨어뜨린다 — 오프라인 규칙 계산과 구현이 같은지 대조한다.
     * 출력은 `autopick_prod/<이름>.json`.
     */
    @Test
    fun dumpProductionAutoPick() = runBlocking {
        val repository = com.galaxy.airviewdictionary.data.local.vision.VisionRepository()
        val given = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val outDir = File(given ?: appContext.externalMediaDirs.first().path, "autopick_prod").apply { mkdirs() }
        val names = context.assets.list("")!!.filter { it.startsWith("real_") && it.endsWith(".png") }
            .map { it.removePrefix("real_").removeSuffix(".png") }.sorted()
        for (name in names) {
            val bytes = context.assets.open("real_$name.png").use { it.readBytes() }
            val screen = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val random = Random(name.hashCode())
            val variants = JSONArray()
            for (kind in listOf("full", "crop1", "crop2")) {
                val rect = if (kind == "full") null else cropRect(screen, random)
                val image = rect?.let { createOverlaidBitmap(screen, it) } ?: screen
                val text = try { repository.read(image, "auto").text } catch (e: Exception) { null }
                variants.put(JSONObject().put("kind", kind).put("text", text ?: JSONObject.NULL))
                if (image !== screen) image.recycle()
            }
            File(outDir, "$name.json").writeText(JSONObject().put("name", name).put("variants", variants).toString())
            screen.recycle()
        }
    }

    /** 영역 선택처럼 — 폭 40~95%, 높이 12~35%, 상태바·주소창 아래 본문 쪽. */
    private fun cropRect(screen: Bitmap, random: Random): Rect {
        val w = (screen.width * random.nextDouble(0.40, 0.95)).toInt()
        val h = (screen.height * random.nextDouble(0.12, 0.35)).toInt()
        val left = random.nextInt(0, screen.width - w + 1)
        val top = random.nextInt(200, maxOf(201, screen.height - h - 150))
        return Rect(left, top, left + w, top + h)
    }
}
