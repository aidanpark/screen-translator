package com.galaxy.airviewdictionary.ocrbench

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.galaxy.airviewdictionary.data.local.vision.VisionRepository
import com.galaxy.airviewdictionary.data.local.vision.kit.paddle.PaddleKits
import com.galaxy.airviewdictionary.data.local.vision.kit.paddle.PaddleModelFiles
import com.galaxy.airviewdictionary.data.local.vision.model.VisionResponse
import com.galaxy.airviewdictionary.data.local.vision.ocr.OcrLine
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 가리킨 문단 읽기가 이웃 문단 읽기(번역 문맥, `.docs/vision-engine-design.md` §18)에 막히지 않는가.
 *  - 이웃 읽기는 가리킨 다른 문단 읽기에 양보한다(`UnreadParagraphs.getOrRead` 의 background)
 *  - 취소된 읽기는 도는 추론을 멈춘다(`terminable`) — 네이티브 추론은 취소 확인이 닿지 않아 전에는 도는 줄을 끝까지 읽었다
 */
class ReadPriorityTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun ms(from: Long) = (System.nanoTime() - from) / 1_000_000

    /** 폭 2400 에 작은 글씨로 한 줄 — 입력 폭이 상한을 넘어 조각 셋으로 읽는다(한 줄에 1초 남짓). */
    private fun wideLine(): Pair<Bitmap, Rect> {
        val sentence = "Москва — столица России, крупнейший по численности населения город страны и её экономический центр. "
        val paint = TextPaint().apply { isAntiAlias = true; color = Color.BLACK; textSize = 11f }
        val bitmap = Bitmap.createBitmap(2400, 60, Bitmap.Config.ARGB_8888)
        var text = sentence
        while (paint.measureText(text) < 2400) text += sentence
        Canvas(bitmap).apply { drawColor(Color.WHITE) }.drawText(text, 0f, 30f, paint)
        val metrics = paint.fontMetricsInt
        return bitmap to Rect(0, 30 + metrics.ascent - 1, 2400, 30 + metrics.descent + 1)
    }

    @Test
    fun cancelStopsInferenceInFlight() = runBlocking {
        val kit = PaddleKits(PaddleModelFiles(context)).kitFor("ru")!!
        val (bitmap, box) = wideLine()
        val line = OcrLine(box, "", null, null)
        kit.recognize(bitmap, listOf(OcrLine(Rect(0, box.top, 600, box.bottom), "", null, null))) // 세션 적재·예열

        val whole = System.nanoTime()
        kit.recognize(bitmap, listOf(line))
        val full = ms(whole)

        val job = launch(Dispatchers.Default) { kit.recognize(bitmap, listOf(line)) }
        delay(150)
        val cancelled = System.nanoTime()
        job.cancel()
        job.join()
        val after = ms(cancelled)
        Log.i("ReadPriority", "넓은 줄 한 번 읽기 ${full}ms, 150ms 에 취소한 뒤 끝나기까지 ${after}ms")
        assertTrue("시험이 뜻을 가지려면 한 줄 읽기가 취소 시점보다 한참 길어야 한다: ${full}ms", full > 500)
        assertTrue("취소하면 도는 추론이 곧 멈춰야 한다: ${after}ms (한 줄 ${full}ms)", after < full / 3)
    }

    private val russian = listOf(
        "Москва — столица России и крупнейший по численности населения город страны, её политический и экономический центр.",
        "Санкт-Петербург — второй по численности населения город России, расположенный на берегах реки Невы.",
        "Новосибирск — крупнейший город Сибири и третий по численности населения город России после Москвы.",
    )

    private fun screen(): Bitmap {
        val bitmap = Bitmap.createBitmap(1080, 2000, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
        val paint = TextPaint().apply { isAntiAlias = true; color = Color.BLACK; textSize = 44f }
        var top = 120
        for (text in russian) {
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, 960).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
            canvas.save(); canvas.translate(60f, top.toFloat()); layout.draw(canvas); canvas.restore()
            top += layout.height + 160
        }
        return bitmap
    }

    @Test
    fun pointedParagraphPreemptsNeighbourRead() = runBlocking {
        val repository = VisionRepository(context)
        val tx = (repository.request(screen(), "ru") as VisionResponse.Success).result
        val unread = assertNotNullAndGet(tx.unread)
        assertEquals("문단 셋이어야 한다", 3, tx.paragraphs.size)
        val (neighbour, pointed, other) = tx.paragraphs

        // 이웃 읽기가 도는 중에 다른 문단을 가리킨다 — 이웃 읽기는 멈추고 null, 가리킨 문단은 기다리지 않고 읽힌다
        val slowNeighbour = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            unread.getOrRead(neighbour, background = true) { delay(10_000); null }
        }
        delay(100)
        val started = System.nanoTime()
        val read = repository.readParagraph(tx, pointed)
        val waited = ms(started)
        assertNotNull("가리킨 문단이 읽혀야 한다", read)
        assertTrue("가리킨 문단이 이웃 읽기를 기다리면 안 된다: ${waited}ms", waited < 5_000)
        assertNull("양보한 이웃 읽기는 null", slowNeighbour.await())
        assertTrue("양보한 이웃은 캐시하지 않는다 — 다음에 다시 읽는다", unread.needsReading(neighbour))

        // 같은 문단이면 양보시키지 않고 그 결과를 함께 기다린다
        val sameNeighbour = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            repository.readParagraph(tx, other, background = true)
        }
        delay(50)
        val same = repository.readParagraph(tx, other)
        assertNotNull(same)
        assertSame("같은 문단은 한 번만 읽어 같은 결과를 준다", same, sameNeighbour.await())

        // 이미 읽은 문단은 도는 이웃 읽기가 잠금을 쥐고 있어도 곧바로 나온다
        val holding = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            unread.getOrRead(neighbour, background = true) { delay(3_000); null }
        }
        delay(100)
        val cachedStarted = System.nanoTime()
        assertSame(read, repository.readParagraph(tx, pointed))
        assertTrue("캐시된 문단은 잠금을 기다리지 않는다: ${ms(cachedStarted)}ms", ms(cachedStarted) < 500)
        holding.cancel()
    }

    private fun <T : Any> assertNotNullAndGet(value: T?): T {
        assertNotNull("검출만 된 화면이어야 한다", value)
        return value!!
    }
}
