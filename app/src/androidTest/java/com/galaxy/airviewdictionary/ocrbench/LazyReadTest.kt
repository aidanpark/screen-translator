package com.galaxy.airviewdictionary.ocrbench

import com.galaxy.airviewdictionary.data.local.vision.ParagraphAssembler
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.galaxy.airviewdictionary.data.local.vision.VisionRepository
import com.galaxy.airviewdictionary.data.local.vision.kit.MlKitVisionKit
import com.galaxy.airviewdictionary.data.local.vision.kit.TextRecognizerType
import com.galaxy.airviewdictionary.data.local.vision.kit.VisionKit
import com.galaxy.airviewdictionary.data.local.vision.ocr.OcrBlock
import com.galaxy.airviewdictionary.data.local.vision.ocr.OcrLine
import com.galaxy.airviewdictionary.data.local.vision.ocr.OcrText
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.IdentityHashMap

/**
 * 검출만 된 화면의 길(`.docs/vision-engine-design.md` §10)을 PaddleOCR 없이 시험한다.
 *
 * [LazyKit] 은 ML Kit 결과에서 단어를 떼어 "줄 위치만 찾은" 결과처럼 주고, [VisionKit.recognize] 때 떼어 둔 단어를
 * 돌려준다. 화면은 글을 직접 그려 만든다 — 저장소에 PNG 를 두지 않고, 문단이 어디 있는지도 안다.
 *
 * 조립 품질(문단을 잘 묶는지)은 여기서 재지 않는다 — 검출기 줄로 묶는 품질은 하네스의 `detector-real` 갈래와 4단계 평가의
 * 몫이다. 여기서 보는 것은 길이 단어를 잃지 않고, 캐시가 서고, ML Kit 길은 손대지 않는다는 것이다.
 */
class LazyReadTest {

    /** ML Kit 을 감싸 검출과 읽기를 나눈 척한다. */
    private class LazyKit(private val inner: VisionKit) : VisionKit {
        override val name = "lazy(${inner.name})"
        override val linesFromDetector = true
        private val original = IdentityHashMap<OcrLine, OcrLine>()
        var recognizeCalls = 0
        var recognizedLines = 0

        override suspend fun detect(screen: Bitmap): OcrText {
            val full = inner.detect(screen)
            val blocks = full.blocks.map { block ->
                OcrBlock(block.boundingBox, block.lines.map { line ->
                    OcrLine(line.boundingBox, "", null, null).also { original[it] = line }
                })
            }
            return OcrText("", blocks)
        }

        override suspend fun recognize(screen: Bitmap, lines: List<OcrLine>): List<OcrLine> {
            recognizeCalls++
            recognizedLines += lines.count { it.words == null }
            return lines.map { if (it.words == null) original.getValue(it) else it }
        }

        override fun addObserver(lifecycle: Lifecycle) = inner.addObserver(lifecycle)
    }

    private val paragraphs = listOf(
        "The quick brown fox jumps over the lazy dog while the farmer watches from the porch and wonders " +
                "whether the dog will ever wake up before the sun goes down behind the hills.",
        "Screen translation reads the words under your finger. It finds the lines first, groups them into " +
                "paragraphs, and only then reads the paragraph you point at.",
        "A third paragraph keeps the test honest: three separate blocks of text with clear gaps between them " +
                "should come back as separate paragraphs that each hold their own words.",
    )

    /** 문단 셋을 그린 화면과 문단마다 그린 영역. */
    private fun drawScreen(): Pair<Bitmap, List<Rect>> {
        val bitmap = Bitmap.createBitmap(1080, 2000, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
        val paint = TextPaint().apply { isAntiAlias = true; color = Color.BLACK; textSize = 44f }
        val regions = mutableListOf<Rect>()
        var top = 120
        for (text in paragraphs) {
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, 960)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
            canvas.save(); canvas.translate(60f, top.toFloat()); layout.draw(canvas); canvas.restore()
            regions.add(Rect(60, top, 60 + 960, top + layout.height))
            top += layout.height + 160
        }
        return bitmap to regions
    }

    private fun wordsOf(p: com.galaxy.airviewdictionary.data.local.vision.model.Paragraph) =
        p.lines.flatMap { it.words }.map { "${it.boundingBox.toShortString()} ${it.representation}" }

    @Test
    fun detectedOnlyScreenReadsParagraphsOnDemand() = runBlocking {
        val repository = VisionRepository()
        val (bitmap, regions) = drawScreen()
        val lazy = LazyKit(MlKitVisionKit(TextRecognizerType.TEXT))
        val detected = lazy.detect(bitmap)
        val lineCount = detected.lines.size

        val tx = repository.transactionOf(bitmap, lazy, detected, "en", readAll = false)
        assertNotNull("검출만 된 화면이어야 한다", tx.unread)
        assertTrue("문단이 있어야 한다", tx.paragraphs.isNotEmpty())
        assertTrue("검출 문단에는 글이 없다", tx.paragraphs.all { it.representation.isBlank() })
        assertEquals("읽기 전에는 아무것도 읽지 않는다", 0, lazy.recognizeCalls)
        log("줄 $lineCount 개 → 검출 문단 ${tx.paragraphs.size}개")
        for ((i, region) in regions.withIndex()) {
            val hits = tx.paragraphs.count { region.contains(it.boundingBox.centerX(), it.boundingBox.centerY()) }
            log("그린 문단 ${i + 1}: 검출 문단 $hits 개")
        }

        val read = tx.paragraphs.map { p ->
            val r = repository.readParagraph(tx, p)
            assertNotNull("읽은 문단에 단어가 있어야 한다", r)
            r!!
            // 읽은 문단은 검출 문단 안에 있다(ML Kit 단어 상자는 줄 상자 안이다). 몇 픽셀의 여유를 둔다.
            val box = Rect(p.boundingBox).apply { inset(-4, -4) }
            assertTrue("읽은 문단 ${r.boundingBox} 이 검출 문단 ${p.boundingBox} 밖이다", box.contains(r.boundingBox))
            log("읽음: ${r.representation}")
            r
        }
        assertEquals("문단마다 한 번씩 읽는다", tx.paragraphs.size, lazy.recognizeCalls)
        assertEquals("모든 줄을 한 번씩 읽는다", lineCount, lazy.recognizedLines)

        // 다시 읽어도 엔진을 부르지 않고 같은 객체를 준다.
        tx.paragraphs.forEachIndexed { i, p -> assertSame(read[i], repository.readParagraph(tx, p)) }
        assertEquals(tx.paragraphs.size, lazy.recognizeCalls)
        assertEquals(read, tx.readParagraphs())

        // 잃은 단어가 없다 — 다 읽힌 화면의 단어 변환과 단어 집합이 같다.
        val all = ParagraphAssembler.ocrLinesToWords(bitmap, repository.read(bitmap, "en").lines, com.galaxy.airviewdictionary.data.local.vision.WritingDirection.LTR)
            .map { "${it.boundingBox.toShortString()} ${it.representation}" }.sorted()
        assertEquals(all, read.flatMap { wordsOf(it) }.sorted())

        // 그린 문단의 첫 단어가 그 자리의 읽은 문단에 나온다.
        for ((i, region) in regions.withIndex()) {
            val text = read.filter { region.contains(it.boundingBox.centerX(), it.boundingBox.centerY()) }
                .joinToString(" ") { it.representation }
            val first = paragraphs[i].substringBefore(' ')
            assertTrue("그린 문단 ${i + 1} 의 '$first' 가 없다: $text", text.contains(first))
        }
    }

    @Test
    fun readAllMatchesTheFullyReadPath() = runBlocking {
        val repository = VisionRepository()
        val (bitmap, _) = drawScreen()
        val lazy = LazyKit(MlKitVisionKit(TextRecognizerType.TEXT))

        val tx = repository.transactionOf(bitmap, lazy, lazy.detect(bitmap), "en", readAll = true)
        assertNull("끝까지 읽은 화면이다", tx.unread)
        val kit = MlKitVisionKit(TextRecognizerType.TEXT)
        val eager = repository.transactionOf(bitmap, kit, kit.detect(bitmap), "en", readAll = false)
        assertEquals(eager.paragraphs.map { it.representation }, tx.paragraphs.map { it.representation })
        assertEquals(eager.paragraphs.map { it.boundingBox }, tx.paragraphs.map { it.boundingBox })
    }

    @Test
    fun fullyReadScreenPassesThrough() = runBlocking {
        val repository = VisionRepository()
        val (bitmap, _) = drawScreen()
        val kit = MlKitVisionKit(TextRecognizerType.TEXT)

        val tx = repository.transactionOf(bitmap, kit, kit.detect(bitmap), "en", readAll = false)
        assertNull("ML Kit 은 다 읽는다", tx.unread)
        tx.paragraphs.forEach { assertSame(it, repository.readParagraph(tx, it)) }
        assertSame(tx.paragraphs, tx.readParagraphs())
    }

    /** auto: ML Kit 후보 다섯을 다 돌려 하나를 고르고, 글로 언어를 감지하고, 다 읽힌 화면을 준다. */
    @Test
    fun requestAutoReadsEverything() = runBlocking {
        val repository = VisionRepository()
        val (bitmap, _) = drawScreen()

        val auto = repository.request(bitmap, "auto")
        assertTrue("auto 요청이 실패했다: $auto", auto is com.galaxy.airviewdictionary.data.local.vision.model.VisionResponse.Success)
        val tx = (auto as com.galaxy.airviewdictionary.data.local.vision.model.VisionResponse.Success).result
        assertNull("auto 는 끝까지 읽는다", tx.unread)
        assertEquals("en", tx.detectedLanguageCode)

        val en = (repository.request(bitmap, "en") as com.galaxy.airviewdictionary.data.local.vision.model.VisionResponse.Success).result
        log("auto: ${tx.paragraphs.size} 문단, en: ${en.paragraphs.size} 문단")
        assertEquals(en.paragraphs.map { it.representation }, tx.paragraphs.map { it.representation })
    }

    private fun log(message: String) = android.util.Log.i("LazyRead", message)
}
