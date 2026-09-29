package com.galaxy.airviewdictionary.ocrbench

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.test.platform.app.InstrumentationRegistry
import com.galaxy.airviewdictionary.data.local.vision.VisionRepository
import com.galaxy.airviewdictionary.data.local.vision.model.VisionResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 영역 선택·고정 영역의 글 순서(`.docs/vision-engine-design.md` §24) — PP-OCRv5 로 화면 전체를 읽어(`readAll`) 두 단은 단 단위로,
 * 표는 행 단위로 이어지는가. 글을 직접 그려 어느 문장이 어디 있는지 안다.
 */
class AreaReadingOrderTest {

    private val repository = VisionRepository(InstrumentationRegistry.getInstrumentation().targetContext)
    private val paint = TextPaint().apply { isAntiAlias = true; color = Color.BLACK; textSize = 40f }

    private fun Canvas.paragraph(text: String, left: Int, top: Int, width: Int): Int {
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        save(); translate(left.toFloat(), top.toFloat()); layout.draw(this); restore()
        return layout.height
    }

    /** [text] 에서 [marks] 가 이 순서로 나오는가. 못 찾은 표지는 실패로 본다. */
    private fun assertInOrder(text: String, vararg marks: String) {
        val at = marks.map { text.indexOf(it) }
        assertTrue("표지를 모두 읽어야 한다: ${marks.zip(at)}\n$text", at.all { it >= 0 })
        assertTrue("순서가 ${marks.toList()} 여야 한다: $at\n$text", at.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun twoColumnsReadColumnByColumn() = runBlocking {
        val bitmap = Bitmap.createBitmap(1080, 1400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
        canvas.paragraph("Москва столица России", 40, 60, 1000)
        canvas.paragraph(
            "Первый столбец начинается здесь и продолжается несколькими строками текста о реке Волге и её притоках.",
            40, 200, 470,
        )
        canvas.paragraph(
            "Второй столбец рассказывает о Санкт-Петербурге, его мостах, каналах и белых ночах летом.",
            570, 200, 470,
        )
        val tx = (repository.request(bitmap, "ru", readAll = true) as VisionResponse.Success).result
        assertInOrder(tx.ocr.text, "Москва", "Первый", "притоках", "Петербурге", "летом")
    }

    @Test
    fun tableReadsRowByRow() = runBlocking {
        val bitmap = Bitmap.createBitmap(1080, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
        val rows = listOf("Столица" to "Москва", "Население" to "Сто сорок шесть миллионов", "Валюта" to "Российский рубль")
        rows.forEachIndexed { i, (name, value) ->
            canvas.paragraph(name, 40, 80 + i * 110, 400)
            canvas.paragraph(value, 520, 80 + i * 110, 520)
        }
        val tx = (repository.request(bitmap, "ru", readAll = true) as VisionResponse.Success).result
        assertInOrder(tx.ocr.text, "Столица", "Москва", "Население", "Сто сорок", "Валюта", "рубль")
    }
}
