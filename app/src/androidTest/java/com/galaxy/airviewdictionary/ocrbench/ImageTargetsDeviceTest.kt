package com.galaxy.airviewdictionary.ocrbench

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import androidx.test.platform.app.InstrumentationRegistry
import com.galaxy.airviewdictionary.data.local.capture.ImageCrop
import com.galaxy.airviewdictionary.data.local.vision.VisionRepository
import com.galaxy.airviewdictionary.data.local.vision.model.ImageTargets
import com.galaxy.airviewdictionary.data.local.vision.model.VisionResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * AI 이미지 번역(`.docs/vision-engine-design.md` §25)의 대상 찾기와 자르기 — 키 없이 도는 부분. PP-OCRv5 검출기가 읽을 엔진이 없는
 * 문자에서도 문단·줄을 찾아 포인터 아래 대상을 정하는지, 자른 조각이 화면 폭 전체이고 포인터 자리에 표시가 있는지 본다.
 */
class ImageTargetsDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun 읽을_엔진이_없는_문자도_문단과_줄을_찾는다() = runBlocking {
        val vision = VisionRepository(context)
        for ((lang, sentences) in ImageTargetPage.UNREADABLE) {
            val page = ImageTargetPage.draw(sentences, rtl = lang in ImageTargetPage.RTL)
            val response = vision.requestImageTargets(page.bitmap, lang)
            assertNotNull("$lang: 모델 팩이 있어야 한다", response)
            val tx = (response as VisionResponse.Success).result
            assertEquals(lang, ImageTargets.Detected, tx.image)
            assertNull("$lang: 읽지 않는다", tx.unread)
            // 문단 묶기가 한 문단을 둘로 가를 수는 있다(그리스어 첫 문단, 2026-09-29) — 가운데 문단만 정확히 본다
            assertTrue("$lang: 문단 수 ${tx.paragraphs.map { it.boundingBox }}", tx.paragraphs.size >= 3)

            val pointer = page.pointer
            val paragraph = tx.paragraphs.find { it.boundingBox.contains(pointer.x, pointer.y) }
            assertNotNull("$lang: 포인터 아래 문단", paragraph)
            // 문장·문단 모드의 조각(문단 상자 ± 줄 높이 × PARAGRAPH_MARGIN)이 그린 가운데 문단을 다 담는다. 묶기가 문단을 가르면 이웃 줄이 메운다
            val lineHeight = paragraph!!.lines.map { it.boundingBox.height() }.average()
            val reach = (lineHeight * ImageCrop.PARAGRAPH_MARGIN).toInt()
            val boxes = tx.paragraphs.map { it.boundingBox }
            page.paragraphs[1].forEach {
                assertTrue("$lang: 줄 $it 이 조각 밖 — 문단 ${paragraph.boundingBox}, 전체 $boxes", it.top >= paragraph.boundingBox.top - reach && it.bottom <= paragraph.boundingBox.bottom + reach)
            }
            // 다른 문단의 줄은 포인터 문단에 들지 않는다
            (page.paragraphs[0] + page.paragraphs[2]).forEach { assertFalse("$lang: 남의 줄 $it", paragraph.boundingBox.contains(it.centerX(), it.centerY())) }
            // 단어 모드의 대상 — 포인터 아래 검출 줄은 그린 줄과 세로로 겹친다
            val line = paragraph.lines.find { it.boundingBox.contains(pointer.x, pointer.y) }
            assertNotNull("$lang: 포인터 아래 줄", line)
            val drawn = page.paragraphs[1].first { it.contains(pointer.x, pointer.y) }
            val overlap = minOf(line!!.boundingBox.bottom, drawn.bottom) - maxOf(line.boundingBox.top, drawn.top)
            assertTrue("$lang: 줄 겹침 ${line.boundingBox} vs $drawn", overlap >= drawn.height() / 2)
            page.bitmap.recycle()
        }
    }

    @Test
    fun 띠는_화면_폭_전체이고_포인터에_표시를_그린다() {
        val screen = white(1440, 3120)
        val pointer = Point(700, 1050)
        val crop = ImageCrop.strip(screen, 1000, 1100, 50, pointer)!!
        val scale = 1024f / 1440
        val margin = (50 * 0.35).roundToInt()
        assertEquals(1024, crop.width)
        assertEquals(((100 + 2 * margin) * scale).roundToInt(), crop.height)
        // 표시 — 줄인 뒤에 그린 반투명 자홍 원판. 흰 바탕 위라 분홍으로 보이고, 원판 밖은 흰색 그대로다
        assertEquals(ImageCrop.PointerMarker.HIGHLIGHT, ImageCrop.marker)
        val cx = (pointer.x * scale).roundToInt()
        val cy = ((pointer.y - (1000 - margin)) * scale).roundToInt()
        assertTrue(isPink(crop.getPixel(cx, cy)))
        assertEquals(Color.WHITE, crop.getPixel(cx + 40, cy))
        // 화면 캡처는 건드리지 않는다
        assertFalse(screen.isRecycled)
        assertEquals(Color.WHITE, screen.getPixel(pointer.x, pointer.y))
        crop.recycle()
    }

    @Test
    fun 포인터가_상자_밖이면_포인터_줄까지_넣는다() {
        val screen = white(1000, 2400)
        val crop = ImageCrop.strip(screen, 500, 560, 40, Point(300, 700))!!
        // 긴 변이 1024 이하면 줄이지 않는다
        assertEquals(1000, crop.width)
        assertEquals(700 + 40 - (500 - 14), crop.height)
        crop.recycle()
    }

    @Test
    fun 영역은_그대로_자르고_표시가_없다() {
        val screen = white(1080, 2400)
        val crop = ImageCrop.area(screen, Rect(200, 300, 800, 500))!!
        assertEquals(600, crop.width)
        assertEquals(200, crop.height)
        for (x in 0 until crop.width step 7) for (y in 0 until crop.height step 7) assertEquals(Color.WHITE, crop.getPixel(x, y))
        crop.recycle()
    }

    @Test
    fun 화면_전체를_잘라도_캡처를_돌려주지_않는다() {
        val screen = white(800, 400)
        val crop = ImageCrop.strip(screen, 0, 400, 40, Point(10, 10))!!
        assertNotSame(screen, crop)
        assertTrue(crop.isMutable)
        crop.recycle()
        assertFalse(screen.isRecycled)
        val area = ImageCrop.area(screen, Rect(0, 0, 800, 400))!!
        assertNotSame(screen, area)
        area.recycle()
        assertFalse(screen.isRecycled)
    }

    @Test
    fun 화면_밖_영역은_null() {
        val screen = white(800, 400)
        assertNull(ImageCrop.area(screen, Rect(900, 0, 1000, 100)))
    }

    private fun white(w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

    /** 흰 바탕 위 반투명 자홍 — 빨강·파랑은 그대로, 초록만 빠진다. */
    private fun isPink(pixel: Int) = Color.red(pixel) > 240 && Color.blue(pixel) > 240 && Color.green(pixel) in 120..200
}
