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
import com.galaxy.airviewdictionary.data.local.vision.kit.MlKitVisionKit
import com.galaxy.airviewdictionary.data.local.vision.kit.TextRecognizerType
import com.galaxy.airviewdictionary.data.local.vision.kit.paddle.PaddleKits
import com.galaxy.airviewdictionary.data.local.vision.kit.paddle.PaddleModelFiles
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * 읽을 엔진이 없는 문자(원문 목록에서 비활성인 25개 언어의 문자)에서 줄 위치를 찾는가 — AI 이미지 번역이 자를 영역을 어디서 얻을지 정하려고 잰다.
 * ML Kit 라틴 인식기(모델 팩이 없을 때 쓰는 엔진)와 PP-OCRv5 검출기가 그린 줄을 얼마나 덮는지 로그로 남긴다(단정 없음).
 */
class UnreadableScriptDetectionTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val samples = linkedMapOf(
        "he" to "ירושלים היא עיר הבירה של ישראל והעיר הגדולה ביותר במדינה.",
        "el" to "Η Αθήνα είναι η πρωτεύουσα και η μεγαλύτερη πόλη της Ελλάδας.",
        "ka" to "თბილისი არის საქართველოს დედაქალაქი და უდიდესი ქალაქი.",
        "hy" to "Երևանը Հայաստանի մայրաքաղաքն է և ամենամեծ քաղաքը։",
        "ta" to "சென்னை தமிழ்நாட்டின் தலைநகரம் மற்றும் பெரிய நகரம் ஆகும்.",
        "bn" to "ঢাকা বাংলাদেশের রাজধানী এবং সবচেয়ে বড় শহর।",
        "si" to "කොළඹ ශ්‍රී ලංකාවේ විශාලතම නගරය සහ වාණිජ අගනුවරයි.",
        "km" to "ភ្នំពេញ គឺជារាជធានី និងជាទីក្រុងធំជាងគេនៃប្រទេសកម្ពុជា។",
        "lo" to "ວຽງຈັນ ແມ່ນນະຄອນຫຼວງ ແລະ ເມືອງໃຫຍ່ທີ່ສຸດຂອງປະເທດລາວ.",
        "my" to "နေပြည်တော်သည် မြန်မာနိုင်ငံ၏ မြို့တော် ဖြစ်သည်။",
        "am" to "አዲስ አበባ የኢትዮጵያ ዋና ከተማ እና ትልቁ ከተማ ናት።",
        "kk" to "Астана — Қазақстан Республикасының астанасы және ірі қаласы.",
    )

    @Test
    fun detectLinesOfUnreadableScripts() = runBlocking {
        val latin = MlKitVisionKit(TextRecognizerType.TEXT)
        val paddle = PaddleKits(PaddleModelFiles(context)).kitFor("ar")!!
        val paint = TextPaint().apply { isAntiAlias = true; color = Color.BLACK; textSize = 44f }
        for ((lang, sentence) in samples) {
            val bitmap = Bitmap.createBitmap(1080, 1600, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
            val drawn = mutableListOf<Rect>()
            var top = 120
            repeat(3) {
                val text = "$sentence $sentence"
                val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, 960).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
                canvas.save(); canvas.translate(60f, top.toFloat()); layout.draw(canvas); canvas.restore()
                for (i in 0 until layout.lineCount) {
                    drawn += Rect(60 + layout.getLineLeft(i).toInt(), top + layout.getLineTop(i), 60 + layout.getLineRight(i).toInt(), top + layout.getLineBottom(i))
                }
                top += layout.height + 140
            }
            fun covered(boxes: List<Rect>) = drawn.count { d -> boxes.any { it.contains(d.centerX(), d.centerY()) || Rect.intersects(it, d) && it.height() > d.height() / 2 } }
            val mlkit = latin.detect(bitmap)
            val mlkitBoxes = mlkit.lines.mapNotNull { it.boundingBox }
            val paddleBoxes = paddle.detect(bitmap).lines.mapNotNull { it.boundingBox }
            // 덮은 폭: 그린 줄 폭 가운데 검출 상자가 가로로 덮는 비율
            fun widthCover(boxes: List<Rect>) = drawn.sumOf { d ->
                boxes.filter { Rect.intersects(it, d) }.sumOf { (minOf(it.right, d.right) - maxOf(it.left, d.left)).coerceAtLeast(0) }.coerceAtMost(d.width())
            }.toDouble() / drawn.sumOf { it.width() }
            Log.i(
                "UnreadableDetect",
                "%s 그린 줄 %d | ML Kit 줄 %d, 닿은 줄 %d, 폭 %.0f%% 글 '%s' | PP-OCRv5 줄 %d, 닿은 줄 %d, 폭 %.0f%%".format(
                    lang, drawn.size, mlkitBoxes.size, covered(mlkitBoxes), widthCover(mlkitBoxes) * 100, mlkit.text.take(30).replace("\n", " "),
                    paddleBoxes.size, covered(paddleBoxes), widthCover(paddleBoxes) * 100,
                )
            )
            bitmap.recycle()
        }
    }
}
