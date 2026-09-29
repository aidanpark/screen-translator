package com.galaxy.airviewdictionary.ocrbench

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.text.TextPaint
import androidx.test.platform.app.InstrumentationRegistry
import com.galaxy.airviewdictionary.data.local.vision.kit.paddle.PaddleKits
import com.galaxy.airviewdictionary.data.local.vision.kit.paddle.PaddleModelFiles
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PP-OCRv5 가 세운 단어 상자가 줄 끝의 글자를 덮는가. CTC 는 글자 안쪽에서 방출하므로 방출 시점만으로 세운 상자는 줄의 첫 글자 왼끝과 마지막
 * 글자 오른끝을 놓친다 — 단어 모드에서 줄 끝 단어의 바깥쪽을 가리키면 빗나갔다.
 */
class PaddleWordBoxTest {

    private val kits = PaddleKits(PaddleModelFiles(InstrumentationRegistry.getInstrumentation().targetContext))

    /** 흰 바탕에 [text] 한 줄을 그린다. */
    private fun line(text: String): Bitmap {
        val bitmap = Bitmap.createBitmap(1080, 300, Bitmap.Config.ARGB_8888)
        val paint = TextPaint().apply { isAntiAlias = true; color = Color.BLACK; textSize = 48f }
        Canvas(bitmap).apply { drawColor(Color.WHITE) }.drawText(text, 60f, 170f, paint)
        return bitmap
    }

    /** [box] 의 세로 범위 안에서 잉크(진한 픽셀)가 있는 가장 왼쪽·오른쪽 열. */
    private fun inkColumns(bitmap: Bitmap, box: Rect): IntRange {
        fun inked(x: Int) = (box.top until box.bottom).any { y -> Color.red(bitmap.getPixel(x, y)) < 128 }
        val left = (box.left until box.right).first { inked(it) }
        val right = (box.left until box.right).last { inked(it) }
        return left..right
    }

    private fun check(language: String, text: String) = runBlocking {
        val kit = checkNotNull(kits.kitFor(language)) { "$language 모델이 없다" }
        val screen = line(text)
        val detected = kit.detect(screen).lines
        assertEquals("한 줄이어야 한다", 1, detected.size)
        val read = kit.recognize(screen, detected).single()
        val box = read.boundingBox!!
        val words = read.words!!
        assertTrue("단어가 여럿이어야 한다: ${read.text}", words.size >= 2)
        val ink = inkColumns(screen, box)
        val left = words.minOf { it.boundingBox!!.left }
        val right = words.maxOf { it.boundingBox!!.right }
        assertEquals("가장 왼쪽 단어가 줄 상자 왼끝에서 시작해야 한다", box.left, left)
        assertEquals("가장 오른쪽 단어가 줄 상자 오른끝까지 가야 한다", box.right, right)
        assertTrue("단어 상자가 잉크를 덮어야 한다: 잉크 $ink, 단어 $left..$right", left <= ink.first && right > ink.last)
        for (word in words) for (symbol in word.symbols) {
            assertTrue("글자 상자가 줄 상자 안이어야 한다: ${symbol.boundingBox} / $box", box.contains(symbol.boundingBox!!))
        }
    }

    @Test
    fun russianLineEndsAreCovered() = check("ru", "Москва — столица России и крупнейший город")

    @Test
    fun arabicLineEndsAreCovered() = check("ar", "القاهرة هي عاصمة مصر وأكبر مدنها")
}
