package com.galaxy.airviewdictionary.ocrbench

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import androidx.test.platform.app.InstrumentationRegistry
import com.galaxy.airviewdictionary.data.local.capture.ImageCrop
import com.galaxy.airviewdictionary.data.local.preference.PreferenceRepository
import com.galaxy.airviewdictionary.data.local.vision.TextDetectMode
import com.galaxy.airviewdictionary.data.local.vision.VisionRepository
import com.galaxy.airviewdictionary.data.local.vision.model.Paragraph
import com.galaxy.airviewdictionary.data.local.vision.model.VisionResponse
import com.galaxy.airviewdictionary.data.remote.firebase.RemoteConfigRepository
import com.galaxy.airviewdictionary.data.remote.translation.NoTextInImageException
import com.galaxy.airviewdictionary.data.remote.translation.TranslationResponse
import com.galaxy.airviewdictionary.data.remote.translation.claude.ClaudeKit
import com.galaxy.airviewdictionary.data.remote.translation.goolge.GoogleWebKit
import com.galaxy.airviewdictionary.di.NetworkModule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * AI 이미지 번역(`.docs/vision-engine-design.md` §25)을 실제 Claude 키로 잰다 — 문자·모드별로 모델이 읽은 원문·번역·판정 언어와 걸린 시간.
 * 토큰은 `ClaudeKit` 이 logcat(`ClaudeKit` 태그, "image request:")에 남긴다. 디버그 앱에 Claude 키가 저장돼 있어야 돈다(없으면 건너뜀).
 *
 * **요금이 나간다** — 한 번에 (언어 수 × 모드 4 × 모델 수) 건. 조각은 앱과 같게 자른다: 단어 모드는 포인터 아래 검출 줄, 문장·문단 모드는
 * 그 문단, 영역 선택은 가운데 문단 둘레.
 *
 * 인자(`am instrument -e`): `models=모델|모델`(기본은 앱이 고르는 모델), `langs=he|bn|…`(기본 전부), `auto=0`(auto 표본 빼기),
 * `modes=WORD|SENTENCE|PARAGRAPH|SELECT`. 보고서는 `externalMediaDirs/claude_image/report.txt`, 조각 PNG 도 그곳에 둔다.
 */
class ClaudeImageLiveTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()

    @Test
    fun measure() = runBlocking {
        assumeTrue("Claude 키가 없다", ClaudeKit.getStoredApiKey(context) != null)
        val okHttp = NetworkModule.provideOkHttpClient()
        val prefs = PreferenceRepository(context)
        val kit = ClaudeKit(
            context,
            NetworkModule.provideClaudeService(NetworkModule.provideClaudeRetrofit(okHttp)),
            GoogleWebKit(NetworkModule.provideGoogleWebService(NetworkModule.provideGoogleWebRetrofit(okHttp))),
            prefs,
            RemoteConfigRepository(context),
        )
        val vision = VisionRepository(context)
        val outDir = File(context.externalMediaDirs.first(), "claude_image").apply { deleteRecursively(); mkdirs() }
        val report = StringBuilder()
        fun log(line: String) {
            report.appendLine(line)
            android.util.Log.i("ClaudeImageLive", line)
        }

        val langs = args.getString("langs")?.split('|')?.toSet()
        val modes = args.getString("modes")?.split('|')?.map { TextDetectMode.valueOf(it) }
            ?: listOf(TextDetectMode.WORD, TextDetectMode.SENTENCE, TextDetectMode.PARAGRAPH, TextDetectMode.SELECT)
        val samples = ImageTargetPage.UNREADABLE.filterKeys { langs == null || it in langs }.map { (lang, s) -> Triple(lang, lang, s) } +
            if (args.getString("auto") == "0") emptyList()
            else ImageTargetPage.AUTO.filterKeys { langs == null || it in langs }.map { (lang, s) -> Triple("auto", lang, s) }

        // markers=RING|HIGHLIGHT|CARET — 표시 모양을 바꿔 가며 잰다(프롬프트도 그 모양을 부른다)
        val markers = args.getString("markers")?.split('|')?.map { ImageCrop.PointerMarker.valueOf(it) } ?: listOf(ImageCrop.marker)
        val originalMarker = ImageCrop.marker
        // sizes=44|30 — 글자 크기(px). 1080 폭 폰에서 44px ≈ 본문 16sp, 30px ≈ 11sp
        val sizes = args.getString("sizes")?.split('|')?.map { it.toFloat() } ?: listOf(44f)
        val originalModel = prefs.claudeModelFlow.first()
        val models: List<String?> = args.getString("models")?.split('|') ?: listOf(null)
        try {
            for (model in models) for (marker in markers) {
                ImageCrop.marker = marker
                // 앱은 원격 목록 밖의 모델을 고르면 목록 첫째로 바꾼다 — 목록 밖 모델도 재도록 직접 지정한다
                kit.modelOverride = model
                log("=== model ${model ?: "(app default: ${originalModel ?: "first in list"})"} marker $marker")
                for (size in sizes) for ((source, lang, sentences) in samples) {
                    val page = ImageTargetPage.draw(sentences, rtl = lang in ImageTargetPage.RTL, textSize = size)
                    val targetLanguage = if (lang == "ko") "en" else "ko"
                    val tx = (vision.requestImageTargets(page.bitmap, source) as? VisionResponse.Success)?.result
                    if (tx == null) {
                        log("$source/$lang: 검출 실패")
                        continue
                    }
                    val pointer = page.pointer
                    for (mode in modes) {
                        val crop = cropFor(tx.paragraphs, page, pointer, mode)
                        if (crop == null) {
                            log("$source/$lang $mode: 대상 없음")
                            continue
                        }
                        val name = "${model?.substringBefore("-20") ?: "default"}_${marker}_${size.toInt()}_${source}_${lang}_$mode".replace('/', '_')
                        File(outDir, "$name.png").outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        val started = System.nanoTime()
                        val response = kit.requestImage(source, targetLanguage, crop, mode)
                        val ms = (System.nanoTime() - started) / 1_000_000
                        val expected = when (mode) {
                            TextDetectMode.WORD -> page.pointerWord
                            TextDetectMode.SENTENCE -> page.middleSentence
                            else -> page.texts[1]
                        }
                        val line = when (response) {
                            is TranslationResponse.Success -> {
                                val r = response.result
                                val cer = cer(r.sourceText.orEmpty(), expected)
                                "cer=%.2f want=「%s」 src=「%s」 tr=「%s」 lang=%s used=%s".format(cer, expected, r.sourceText, r.resultText, r.resolvedSourceLanguageCode, r.modelName)
                            }
                            is TranslationResponse.Error ->
                                if (response.t is NoTextInImageException) "글 없음" else "오류 ${response.t.javaClass.simpleName}: ${response.t.message}"
                        }
                        log("$source/$lang $mode ${crop.width}x${crop.height} ${ms}ms size=${size.toInt()} $line")
                        crop.recycle()
                    }
                    page.bitmap.recycle()
                }
            }
        } finally {
            ImageCrop.marker = originalMarker
            File(outDir, "report.txt").writeText(report.toString())
        }
    }

    /** 문자 오류율 — 줄바꿈·공백 차이는 세지 않는다. */
    private fun cer(got: String, want: String): Double {
        val a = got.replace(Regex("\\s+"), " ").trim()
        val b = want.replace(Regex("\\s+"), " ").trim()
        if (b.isEmpty()) return if (a.isEmpty()) 0.0 else 1.0
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1, prev[j] + 1, cur[j - 1] + 1)
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length].toDouble() / b.length
    }

    /** 앱과 같은 대상·조각(TargetHandleViewModel.imageTarget / requestImageTranslation). */
    private fun cropFor(paragraphs: List<Paragraph>, page: ImageTargetPage, pointer: Point, mode: TextDetectMode): Bitmap? {
        if (mode == TextDetectMode.SELECT) return ImageCrop.area(page.bitmap, page.middleArea)
        val paragraph = paragraphs.find { it.boundingBox.contains(pointer.x, pointer.y) } ?: return null
        val box: Rect
        val lineHeight: Int
        if (mode == TextDetectMode.WORD) {
            val line = paragraph.lines.find { it.boundingBox.contains(pointer.x, pointer.y) } ?: return null
            box = line.boundingBox
            lineHeight = box.height()
        } else {
            box = paragraph.boundingBox
            lineHeight = paragraph.lines.map { it.boundingBox.height() }.average().toInt()
        }
        val margin = if (mode == TextDetectMode.WORD) ImageCrop.LINE_MARGIN else ImageCrop.PARAGRAPH_MARGIN
        return ImageCrop.strip(page.bitmap, box.top, box.bottom, lineHeight, pointer, margin)
    }
}
