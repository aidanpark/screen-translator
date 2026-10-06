package com.galaxy.airviewdictionary.ocrbench

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.test.platform.app.InstrumentationRegistry
import com.galaxy.airviewdictionary.R
import com.galaxy.airviewdictionary.data.local.vision.VisionRepository
import com.galaxy.airviewdictionary.data.local.vision.kit.paddle.PaddleKits
import com.galaxy.airviewdictionary.data.local.vision.kit.paddle.PaddleModelFiles
import com.galaxy.airviewdictionary.data.local.vision.model.VisionResponse
import com.galaxy.airviewdictionary.data.local.vision.ocr.OcrLine
import com.galaxy.airviewdictionary.data.remote.firebase.RemoteConfigRepository
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Firebase
import com.google.firebase.remoteconfig.remoteConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

/**
 * ONNX Runtime 을 늦게, 실패에 안전하게 올리는가(`.docs/vision-engine-design.md` §22).
 *
 * [a_ortIsLoadedOnlyWhenAPaddleModelIsNeeded] 는 ONNX Runtime 이 아직 올라가지 않은 새 프로세스에서만 뜻이 있다 — 이 클래스만 따로 돌리고,
 * 이름 순으로 그것을 먼저 돌린다.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PaddleFailSafeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** ONNX Runtime 이 네이티브 라이브러리를 올렸는가(`OnnxRuntime.init` 이 세우는 값). 클래스 초기화는 라이브러리를 올리지 않는다. */
    private fun ortLoaded(): Boolean =
        Class.forName("ai.onnxruntime.OnnxRuntime").getDeclaredField("loaded").apply { isAccessible = true }.getBoolean(null)

    private fun screen(text: String): Bitmap {
        val bitmap = Bitmap.createBitmap(1080, 1400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
        val paint = TextPaint().apply { isAntiAlias = true; color = Color.BLACK; textSize = 44f }
        var top = 120
        repeat(3) {
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, 960).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
            canvas.save(); canvas.translate(60f, top.toFloat()); layout.draw(canvas); canvas.restore()
            top += layout.height + 120
        }
        return bitmap
    }

    private val arabic = "القاهرة هي عاصمة جمهورية مصر العربية وأكبر مدنها، وتقع على ضفاف نهر النيل في شمال البلاد."
    private val english = "Cairo is the capital and largest city of Egypt and the Arab world, near the Nile Delta."

    private fun setSwitch(enabled: Boolean) {
        Tasks.await(Firebase.remoteConfig.setDefaultsAsync(mapOf(
            RemoteConfigRepository.PADDLE_OCR_ENABLED to enabled,
            RemoteConfigRepository.PADDLE_OCR_AUTO_ENABLED to enabled,
        )))
    }

    @After
    fun restore() {
        Tasks.await(Firebase.remoteConfig.setDefaultsAsync(R.xml.remote_config_defaults))
    }

    @Test
    fun a_ortIsLoadedOnlyWhenAPaddleModelIsNeeded() = runBlocking {
        assertFalse("시험 전에 이미 올라가 있다 — 이 클래스만 따로 돌린다", ortLoaded())
        val repository = VisionRepository(context)
        repository.request(screen(english), "en")
        assertFalse("저장소를 만들고 라틴 화면을 읽어도 ONNX Runtime 을 올리지 않는다", ortLoaded())

        setSwitch(false)
        repository.request(screen(arabic), "ar")
        repository.request(screen(arabic), "auto")
        assertFalse("스위치가 꺼져 있으면 아랍어·auto 도 ONNX Runtime 을 올리지 않는다", ortLoaded())

        setSwitch(true)
        val response = repository.request(screen(arabic), "ar")
        assertTrue("켜면 PP-OCRv5 가 읽는다: $response", response is VisionResponse.Success && response.result.unread != null)
        assertTrue("PP-OCRv5 를 쓰면 올라간다", ortLoaded())
    }

    /** 모델 파일은 있는데 세션을 만들 수 없으면(깨진 모델) 그 엔진은 이 프로세스에서 준비되지 않은 것으로 남고, 모델을 다시 읽지 않는다. */
    @Test
    fun b_brokenModelFallsBackAndIsNotRetried() = runBlocking {
        val reads = mutableMapOf<String, Int>()
        val files = object : PaddleModelFiles(context) {
            override fun read(name: String): ByteArray? {
                reads[name] = (reads[name] ?: 0) + 1
                return if (name == "arabic_rec.onnx") ByteArray(1024) { 7 } else super.read(name)
            }
        }
        val kits = PaddleKits(files)
        val arabicKit = kits.kitFor("ar")
        assertNotNull("파일이 있으니 처음에는 준비된 것으로 본다", arabicKit)
        val failure = runCatching { arabicKit!!.detect(screen(arabic)) }.exceptionOrNull()
        assertNotNull("깨진 인식기로는 검출 전에 실패해야 한다(부르는 쪽이 ML Kit 으로 다시 검출한다)", failure)
        assertNull("실패한 엔진은 더는 고르지 않는다", kits.kitFor("ar"))
        assertNull("같은 모델을 쓰는 다른 아랍 문자 언어도", kits.kitFor("fa"))
        assertNotNull("다른 문자권 엔진은 그대로다", kits.kitFor("ru"))

        val candidates = screen(arabic).let { s -> kits.autoDetect(s)?.let { kits.autoCandidates(s, it) } ?: emptyList() }
        assertTrue("auto 표본에서도 빠진다: ${candidates.map { it.kit.name }}", candidates.none { it.kit.name == "PADDLE_ARABIC" })
        assertEquals("깨진 모델은 한 번만 읽는다", 1, reads["arabic_rec.onnx"])
    }

    /** 불변 화면에서 화면 전체와 같은 상자를 자르면 `createBitmap` 이 화면 자체를 돌려준다 — 인식기가 부르는 쪽의 화면을 지우면 안 된다. */
    @Test
    fun c_wholeScreenBoxDoesNotRecycleTheScreen() = runBlocking {
        val drawn = Bitmap.createBitmap(640, 48, Bitmap.Config.ARGB_8888)
        Canvas(drawn).apply { drawColor(Color.WHITE) }
            .drawText("Москва — столица России", 10f, 34f, TextPaint().apply { isAntiAlias = true; color = Color.BLACK; textSize = 30f })
        val screen = drawn.copy(Bitmap.Config.ARGB_8888, false)
        val kit = PaddleKits(PaddleModelFiles(context)).kitFor("ru")!!
        val read = kit.recognize(screen, listOf(OcrLine(Rect(0, 0, screen.width, screen.height), "", null, null)))
        assertFalse("화면이 지워졌다", screen.isRecycled)
        assertTrue("읽은 글: ${read[0].text}", read[0].text.contains("Москва"))
    }
}
