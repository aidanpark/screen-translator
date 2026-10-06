package com.galaxy.airviewdictionary.ocrbench

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Debug
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.galaxy.airviewdictionary.data.local.vision.VisionRepository
import com.galaxy.airviewdictionary.data.local.vision.kit.paddle.PaddleKits
import com.galaxy.airviewdictionary.data.local.vision.kit.paddle.PaddleModelFiles
import com.galaxy.airviewdictionary.data.local.vision.ocr.OcrLine
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * PP-OCRv5 가 앱 메모리에 얹는 몫(`.docs/vision-engine-design.md` §20). 결과는 logcat 태그 PaddleMemory.
 *  - [stages]: ML Kit 만 쓴 뒤 / PP-OCRv5 까지 쓴 뒤의 네이티브 힙
 *  - [arena]: ONNX Runtime CPU 아레나(작업 메모리를 최대치로 붙잡아 둠) 켬·끔의 메모리와 속도
 *  - [wideLine]: 아주 넓은 줄(가로 화면·태블릿의 작은 글씨)을 읽는 동안의 최대 네이티브 힙(§22)
 */
class PaddleMemoryTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun mb(bytes: Long) = "%.0fMB".format(bytes / 1048576.0)
    private fun native() = Debug.getNativeHeapAllocatedSize()
    private fun pssMb() = "%.0fMB".format(Debug.getPss() / 1024.0)

    private fun screen(text: String): Bitmap {
        val bitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
        val paint = TextPaint().apply { isAntiAlias = true; color = Color.BLACK; textSize = 44f }
        var top = 120
        repeat(6) {
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, 960).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
            canvas.save(); canvas.translate(60f, top.toFloat()); layout.draw(canvas); canvas.restore()
            top += layout.height + 120
        }
        return bitmap
    }

    @Test
    fun stages() { runBlocking {
        val repository = VisionRepository(context)
        val english = screen("Cairo is the capital and largest city of Egypt and the Arab world. It is home to more than nine million people and lies near the Nile Delta.")
        val arabic = screen("القاهرة هي عاصمة جمهورية مصر العربية وأكبر مدنها، وتقع على ضفاف نهر النيل في شمال البلاد، وهي من أكبر المدن في أفريقيا والشرق الأوسط.")
        System.gc(); Log.i("PaddleMemory", "시작: native ${mb(native())}, PSS ${pssMb()}")
        repository.request(english, "en")
        System.gc(); Log.i("PaddleMemory", "ML Kit 라틴 1개 뒤: native ${mb(native())}, PSS ${pssMb()}")
        repository.request(english, "ko"); repository.request(english, "ja"); repository.request(english, "zh-CN"); repository.request(english, "hi")
        System.gc(); Log.i("PaddleMemory", "ML Kit 5개 뒤: native ${mb(native())}, PSS ${pssMb()}")
        repository.request(arabic, "auto", readAll = true)
        System.gc(); Log.i("PaddleMemory", "auto 아랍어(PP-OCRv5 검출+인식 3개) 뒤: native ${mb(native())}, PSS ${pssMb()}")
        repository.request(arabic, "auto", readAll = true)
        System.gc(); Log.i("PaddleMemory", "한 번 더: native ${mb(native())}, PSS ${pssMb()}")
    } }

    @Test
    fun arena() {
        fun model(name: String) = context.assets.open("paddle/$name").use { it.readBytes() }
        val env = OrtEnvironment.getEnvironment()
        val det = model("det.onnx")
        val recs = listOf("arabic_rec.onnx", "eslav_rec.onnx", "th_rec.onnx").map { model(it) }
        fun run(session: OrtSession, shape: LongArray) {
            val n = shape.fold(1L) { a, b -> a * b }.toInt()
            OnnxTensor.createTensor(env, FloatBuffer.allocate(n), shape).use { t -> session.run(mapOf(session.inputNames.first() to t)).close() }
        }
        for (arenaOn in listOf(true, false)) {
            System.gc(); val before = native()
            val options = { threads: Int -> OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads)
                if (!arenaOn) { setCPUArenaAllocator(false); setMemoryPatternOptimization(false) }
            } }
            val detS = env.createSession(det, options(4))
            val recS = recs.map { env.createSession(it, options(2)) }
            System.gc(); val loaded = native()
            val t0 = System.nanoTime()
            repeat(3) { run(detS, longArrayOf(1, 3, 960, 448)) }
            val detMs = (System.nanoTime() - t0) / 3_000_000
            val t1 = System.nanoTime()
            repeat(3) { recS.forEach { s -> run(s, longArrayOf(4, 3, 48, 1100)) } }
            val recMs = (System.nanoTime() - t1) / 3_000_000
            System.gc(); val after = native()
            Log.i("PaddleMemory", "아레나 ${if (arenaOn) "켬" else "끔"}: 적재 +${mb(loaded - before)}, 실행 뒤 +${mb(after - before)}, 검출 ${detMs}ms, 인식(4줄×3모델) ${recMs}ms")
            detS.close(); recS.forEach { it.close() }
        }
    }

    /**
     * 2400×14 픽셀 줄(인식기 입력 폭 약 8200) 하나, 그리고 그런 줄 넷을 한 문단으로 읽는 동안의 최대 네이티브 힙. 읽는 동안 2ms 마다 표본을 뜬다.
     * 인식기 목(neck)의 전역 어텐션 때문에 메모리가 폭의 제곱에 가깝게 는다.
     */
    @Test
    fun wideLine() {
        runBlocking {
            val sentence = "Москва — столица России, крупнейший по численности населения город страны и её экономический центр. "
            val paint = TextPaint().apply { isAntiAlias = true; color = Color.BLACK; textSize = 11f }
            val bitmap = Bitmap.createBitmap(2400, 200, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
            var text = sentence
            while (paint.measureText(text) < 2400) text += sentence
            val metrics = paint.fontMetricsInt
            val baselines = listOf(30, 70, 110, 150)
            baselines.forEach { canvas.drawText(text, 0f, it.toFloat(), paint) }
            val boxes = baselines.map { Rect(0, it + metrics.ascent - 1, 2400, it + metrics.descent + 1) }
            val kits = PaddleKits(PaddleModelFiles(context))
            val kit = kits.kitFor("ru")!!
            kit.recognize(bitmap, listOf(OcrLine(Rect(0, boxes[0].top, 600, boxes[0].bottom), "", null, null))) // 세션 적재·예열

            suspend fun peak(lines: List<Rect>): Triple<Long, Long, List<OcrLine>> {
                System.gc(); Thread.sleep(200)
                val before = native()
                val max = AtomicLong(before)
                val running = AtomicBoolean(true)
                val sampler = Thread { while (running.get()) { max.accumulateAndGet(native(), ::maxOf); Thread.sleep(2) } }.apply { start() }
                val t = System.nanoTime()
                val read = kit.recognize(bitmap, lines.map { OcrLine(it, "", null, null) })
                val ms = (System.nanoTime() - t) / 1_000_000
                running.set(false); sampler.join()
                return Triple(max.get() - before, ms, read)
            }
            repeat(2) { round ->
                val (one, oneMs, oneRead) = peak(boxes.take(1))
                val (four, fourMs, _) = peak(boxes)
                Log.i("PaddleMemory", "넓은 줄 ${boxes[0].width()}x${boxes[0].height()} (${round + 1}회): 한 줄 최대 +${mb(one)} ${oneMs}ms, 네 줄 최대 +${mb(four)} ${fourMs}ms")
                if (round == 0) Log.i("PaddleMemory", "넓은 줄 글: ${oneRead[0].text.take(300)}")
            }
        }
    }
}
