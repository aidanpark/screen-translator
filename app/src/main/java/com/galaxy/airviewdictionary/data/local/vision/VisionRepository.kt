package com.galaxy.airviewdictionary.data.local.vision

import com.galaxy.airviewdictionary.data.local.vision.ParagraphAssembler.detectVerticalWriting
import com.galaxy.airviewdictionary.data.local.vision.ParagraphAssembler.groupLinesIntoParagraphs
import com.galaxy.airviewdictionary.data.local.vision.ParagraphAssembler.detectAndSplitParagraphs
import com.galaxy.airviewdictionary.data.local.vision.ParagraphAssembler.ocrWordsToWords
import com.galaxy.airviewdictionary.data.local.vision.ParagraphAssembler.sortLinesToWords
import com.galaxy.airviewdictionary.data.local.vision.ParagraphAssembler.groupWordsIntoLines
import com.galaxy.airviewdictionary.data.local.vision.ParagraphAssembler.ocrLinesToWords
import com.galaxy.airviewdictionary.data.local.vision.ParagraphAssembler.splitColumns
import com.galaxy.airviewdictionary.data.local.vision.ParagraphAssembler.clampToBitmap
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.lifecycle.Lifecycle
import com.galaxy.airviewdictionary.data.local.vision.model.Char
import com.galaxy.airviewdictionary.data.local.vision.model.ImageTargets
import com.galaxy.airviewdictionary.extensions._cutDecimal
import com.galaxy.airviewdictionary.extensions.isValid
import com.galaxy.airviewdictionary.data.remote.translation.Language
import com.galaxy.airviewdictionary.data.local.vision.model.Line
import com.galaxy.airviewdictionary.data.local.vision.model.Paragraph
import com.galaxy.airviewdictionary.data.local.vision.model.VisionResult
import com.galaxy.airviewdictionary.data.local.vision.model.VisionResponse
import com.galaxy.airviewdictionary.data.local.vision.model.Word
import com.galaxy.airviewdictionary.data.local.vision.kit.VisionKit
import com.galaxy.airviewdictionary.data.local.vision.kit.VisionKitSelector
import com.galaxy.airviewdictionary.data.local.vision.kit.paddle.PaddleKits
import com.galaxy.airviewdictionary.data.local.vision.kit.paddle.ScriptGate
import com.galaxy.airviewdictionary.data.local.vision.kit.paddle.PaddleSwitch
import com.galaxy.airviewdictionary.data.local.vision.kit.paddle.UrduLetters
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.galaxy.airviewdictionary.data.local.vision.ocr.OcrBlock
import com.galaxy.airviewdictionary.data.local.vision.ocr.OcrLine
import com.galaxy.airviewdictionary.data.local.vision.ocr.OcrText
import com.galaxy.airviewdictionary.data.local.vision.ocr.OcrWord
import com.galaxy.airviewdictionary.data.local.vision.ocr.ReadingOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.IdentityHashMap
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume


@Singleton
class VisionRepository @Inject constructor(@ApplicationContext context: Context?) {

    /** 조립기 시험용 — 문맥이 없으면 ML Kit 만 쓴다(PP-OCRv5 모델을 찾을 수 없다). */
    constructor() : this(null)

    private val TAG = javaClass.simpleName

    private val kits = VisionKitSelector(context)

    fun addObserver(lifecycle: Lifecycle) {
        kits.addObserver(lifecycle)
    }

    /**
     * 화면을 인식해 문단으로 조립한다.
     *
     * 검출이 싼 엔진은 줄 위치만 찾고 문단을 줄 상자로 묶어 돌려준다 — 글은 포인터가 멈춘 문단만 [readParagraph] 로 읽는다.
     * 화면 전체 글이 필요한 곳(선택·고정 영역)은 [readAll] 로 끝까지 읽힌 결과를 받는다. ML Kit 은 검출에서 다 읽으므로
     * 어느 쪽이든 결과가 같다.
     *
     * [waitOnGate] 는 auto 의 지원되지 않는 문자권 관문(성능 P7)이 개입했을 때 이미 넣은 ML Kit 인식을 끝까지 기다릴지다. 기다리지 않으면
     * 빨리 반환하지만 그 작업은 ML Kit 안에서 계속 돈다 — 쉬지 않고 캡처하는 고정 영역은 기다려서 작업이 쌓이지 않게 한다.
     */
    suspend fun request(bitmap: Bitmap, sourceLanguageCode: String, readAll: Boolean = false, waitOnGate: Boolean = false): VisionResponse = coroutineScope {
        Timber.tag(TAG).i("#### request() ####  $sourceLanguageCode")
        try {
            val detected = detect(bitmap, sourceLanguageCode, waitOnGate)
            detected.unsupportedScript?.let { verdict ->
                // 관문이 개입했다(성능 P7) — 글을 읽지 않고 검출 줄로 묶은 이미지 대상으로 돌려준다(Claude 이미지 번역 또는 안내)
                val paragraphs = imageParagraphs(bitmap, detected.ocr.lines, sourceLanguageCode)
                return@coroutineScope VisionResponse.Success(
                    VisionResult(bitmap, detected.ocr, sourceLanguageCode, paragraphs, image = ImageTargets.Detected, unsupportedScript = verdict)
                )
            }
            // auto 에서 PP-OCRv5 가 이기면 그 언어를 지정한 것처럼 조립한다 — 검출만 된 화면으로 두고 가리킨 문단만 읽는다(§13)
            val language = if (detected.paddleWon) detected.identifiedLanguageCode!! else sourceLanguageCode
            VisionResponse.Success(
                transactionOf(bitmap, detected.kit, detected.ocr, language, readAll, detected.identifiedLanguageCode)
            )
        } catch (e: CancellationException) {
            // 취소는 실패가 아니다 — 새 제스처가 앞 요청을 취소한다(TargetHandleViewModel.requestCapture)
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "request 실패")
            VisionResponse.Error(e)
        }
    }

    /**
     * AI 이미지 번역의 대상 찾기(§25) — 글은 읽지 않고 PP-OCRv5 검출기로 줄 위치만 찾아 문단으로 묶는다. 검출기는 문자와 무관하게
     * 줄을 찾으므로 읽을 엔진이 없는 문자에도 쓴다. 모델 팩이 아직 없거나 스위치로 꺼 두었으면 null — 부르는 쪽이 정한다.
     * 쓰기 방향은 원문 언어로 정하며 문단 묶기에만 쓰인다.
     */
    suspend fun requestImageTargets(bitmap: Bitmap, sourceLanguageCode: String): VisionResponse? {
        val paddle = kits.paddle ?: return null
        return try {
            val detected = paddle.detectLines(bitmap) ?: return null
            val paragraphs = imageParagraphs(bitmap, detected.lines, sourceLanguageCode)
            Timber.tag(TAG).i("image targets: ${detected.lines.size} lines -> ${paragraphs.size} paragraphs")
            VisionResponse.Success(VisionResult(bitmap, detected, sourceLanguageCode, paragraphs, image = ImageTargets.Detected))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 세션을 처음 만들다 실패하면 PP-OCRv5 는 이 프로세스에서 꺼진다(§22) — 모델 팩이 없는 것과 같이 다룬다
            Timber.tag(TAG).e(e, "image targets 실패")
            null
        }
    }

    /**
     * 고정 영역이 AI 이미지 번역에서 "글이 바뀌었나" 를 가를 지문(§25). 읽을 엔진이 없는 문자라 뜻 있는 글은 아니다.
     * PP-OCRv5 가 없으면 라틴 인식기의 글(같은 화면도 캡처마다 조금씩 다르다).
     */
    suspend fun imageFingerprint(bitmap: Bitmap): String {
        val fromPaddle = try {
            kits.paddle?.fingerprint(bitmap)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e("PP-OCRv5 지문 실패: ${e.message}")
            null
        }
        return fromPaddle ?: kits.candidatesFor("en").first().detect(bitmap).text
    }

    /**
     * [kit] 이 검출한 결과를 [VisionResult] 로 만든다. 검출기가 줄을 주는 엔진이고 끝까지 읽을 필요가 없으면 줄 상자로 문단만 묶는다.
     * 표본이 줄을 이미 다 읽은 화면(auto 에서 PP-OCRv5 가 이긴 4줄 이하 화면)도 같다 — 다 읽혔다고 ML Kit 의 길(단어에서 줄을 유도하고
     * 세로쓰기를 판정하는 조립)로 보내면 같은 화면을 언어를 지정했을 때와 다르게 묶는다. 읽힌 줄은 [readParagraph] 가 다시 읽지 않는다.
     * auto 로 남은 화면(ML Kit)은 언제나 끝까지 읽는다 — 언어 감지가 글을 필요로 한다. 엔진을 고르며 이미 감지했으면
     * [identifiedLanguageCode] 로 받는다.
     */
    internal suspend fun transactionOf(
        bitmap: Bitmap,
        kit: VisionKit,
        detected: OcrText,
        sourceLanguageCode: String,
        readAll: Boolean,
        identifiedLanguageCode: String? = null,
    ): VisionResult {
        if (!readAll && sourceLanguageCode != "auto" && kit.linesFromDetector) {
            return withContext(Dispatchers.Default) {
                detectedToResult(bitmap, kit, urduLettersIfNeeded(detected, sourceLanguageCode), sourceLanguageCode)
            }
        }
        val read = recognizeAll(kit, detected, bitmap)
        // 검출기 줄은 문단으로 묶어 단을 가른 순서로 잇는다 — 글 전체를 번역하는 영역 선택·고정 영역이 쓴다(§24)
        val ordered = if (kit.linesFromDetector && sourceLanguageCode != "auto") {
            read.copy(text = withContext(Dispatchers.Default) { paragraphOrderedText(bitmap, read.lines, sourceLanguageCode) })
        } else read
        val text = urduLettersIfNeeded(ordered, sourceLanguageCode)

        // OCR 결과를 Paragraphs 로 변환한다
        val (detectedLanguageCode, analyzedParagraphs) = withContext(Dispatchers.Default) {
            var _sourceLanguageCode = sourceLanguageCode
            if (sourceLanguageCode == "auto") {
                _sourceLanguageCode = identifiedLanguageCode ?: identifyLanguage(text.text)
                Timber.tag(TAG).i("_sourceLanguageCode : $_sourceLanguageCode")
            }

            val isVerticalWriting = detectVerticalWriting(text)
            val writingDirection = Language.writingDirection(_sourceLanguageCode, isVerticalWriting)
            if (writingDirection == WritingDirection.LTR || writingDirection == WritingDirection.RTL) {
                _sourceLanguageCode to textToParagraphs(bitmap, text, _sourceLanguageCode, writingDirection)
            } else {
                _sourceLanguageCode to textToVerticalParagraphs(bitmap, text, _sourceLanguageCode, writingDirection)
            }
        }

        return VisionResult(bitmap, text, detectedLanguageCode, analyzedParagraphs)
    }

    /**
     * 검출만 된 화면을 줄 상자로 문단에 묶는다. 하네스의 `detector-real` 갈래가 재는 길과 같다 — 줄마다 단어 하나(상자 = 줄 상자,
     * 폰트높이 = 상자 높이)를 세운 [Line] 을 검출기 기준값으로 문단에 묶고 쪼개기 후처리를 한다. 세로쓰기는 판정하지 않는다
     * (세로쓰기 문자는 ML Kit 이 다 읽는다).
     */
    private fun detectedToResult(bitmap: Bitmap, kit: VisionKit, detected: OcrText, sourceLanguageCode: String): VisionResult {
        val (paragraphs, sources) = detectorParagraphs(bitmap, detected.lines, sourceLanguageCode)
        paragraphs.forEach { it.languageCode = sourceLanguageCode }
        Timber.tag(TAG).i("detected only: ${detected.lines.size} lines -> ${paragraphs.size} paragraphs")
        return VisionResult(bitmap, detected, sourceLanguageCode, paragraphs, UnreadParagraphs(kit, sources))
    }

    /** 이미지 번역 대상 문단 — 검출 줄을 문단으로 묶고 원문 언어를 단다. 관문이 개입한 화면과 이미지 번역 대상 찾기가 같이 쓴다(코드 정리 B5). */
    private suspend fun imageParagraphs(bitmap: Bitmap, lines: List<OcrLine>, sourceLanguageCode: String): List<Paragraph> =
        withContext(Dispatchers.Default) { detectorParagraphs(bitmap, lines, sourceLanguageCode).first }
            .onEach { it.languageCode = sourceLanguageCode }

    /**
     * 검출기 줄을 줄 상자로 문단에 묶는다([detectedToResult] 참고). 문단마다 그 문단을 이루는 원 줄(문단 안 순서)을 함께 준다.
     * 상자가 없거나 비트맵 밖인 줄은 어느 문단에도 들지 않는다.
     */
    internal fun detectorParagraphs(
        bitmap: Bitmap,
        ocrLines: List<OcrLine>,
        sourceLanguageCode: String,
    ): Pair<List<Paragraph>, IdentityHashMap<Paragraph, List<OcrLine>>> {
        val params = AssemblyParams.reference(false, sourceLanguageCode, linesFromDetector = true)
        val writingDirection = Language.writingDirection(sourceLanguageCode, false)

        val sourceOf = IdentityHashMap<Word, OcrLine>()
        val lines = ocrLines.mapNotNull { ocrLine ->
            val box = ocrLine.boundingBox?.let { clampToBitmap(it, bitmap) } ?: return@mapNotNull null
            if (box.width() <= 0 || box.height() <= 0) return@mapNotNull null
            val placeholder = Word(box, "", writingDirection, emptyList(), box.height().toDouble())
            sourceOf[placeholder] = ocrLine
            Line(mutableListOf(placeholder), writingDirection)
        }

        val paragraphs = splitColumns(groupLinesIntoParagraphs(lines, writingDirection, params), writingDirection)

        // 문단 → 원 줄. 묶는 단계가 줄 객체를 새로 만들어도 단어 객체는 그대로 옮기므로 단어로 되찾는다.
        val sources = IdentityHashMap<Paragraph, List<OcrLine>>()
        for (paragraph in paragraphs) {
            sources[paragraph] = paragraph.lines.flatMap { it.words }.mapNotNull { sourceOf[it] }
        }
        return paragraphs to sources
    }

    /**
     * 다 읽힌 검출기 줄을 문단으로 묶어 읽는 순서로 잇는다(§24). 두 단 배치에서 행마다 좌우 단이 섞이지 않게 문단 상자로 단을 가른다
     * ([ReadingOrder.joinParagraphs]). 문단에 들지 못한 줄은 한 줄짜리 문단으로 함께 둔다.
     */
    private fun paragraphOrderedText(bitmap: Bitmap, lines: List<OcrLine>, sourceLanguageCode: String): String {
        val (paragraphs, sources) = detectorParagraphs(bitmap, lines, sourceLanguageCode)
        val grouped = paragraphs.map { sources.getValue(it) }
        val placed = java.util.Collections.newSetFromMap(IdentityHashMap<OcrLine, Boolean>()).apply { grouped.forEach { addAll(it) } }
        val rightToLeft = Language.writingDirection(sourceLanguageCode, false) == WritingDirection.RTL
        return ReadingOrder.text(grouped + lines.filter { it !in placed }.map { listOf(it) }, rightToLeft)
    }

    /**
     * 포인터가 가리킨 문단의 글을 채운다. 다 읽힌 화면(ML Kit)이면 [paragraph] 를 그대로 돌려준다.
     * 검출만 된 화면이면 그 문단의 줄만 읽어 줄마다 [Line] 으로 세운다 — 줄은 검출기가 이미 정했으므로 단어에서 다시 유도하지
     * 않는다. 읽었는데 남은 단어가 없으면 null.
     *
     * [background] 는 문맥으로 쓸 이웃 문단 읽기다 — 포인터가 가리킨 문단 읽기가 오면 양보하고 null 을 돌려준다([UnreadParagraphs.getOrRead]).
     */
    suspend fun readParagraph(transaction: VisionResult, paragraph: Paragraph, background: Boolean = false): Paragraph? {
        val unread = transaction.unread ?: return paragraph
        return unread.getOrRead(paragraph, background) { sources ->
            val read = unread.kit.recognize(transaction.bitmap, sources)
                .let { lines -> if (isUrdu(transaction.detectedLanguageCode)) lines.map { UrduLetters.fix(it) } else lines }
            check(read.size == sources.size) { "${unread.kit.name} 이 읽으면서 줄 수를 바꿨다" }
            withContext(Dispatchers.Default) {
                val direction = paragraph.writingDirection
                val lines = read.mapNotNull { line ->
                    val words = ocrWordsToWords(transaction.bitmap, sortLinesToWords(listOf(line), direction), direction)
                    if (words.isEmpty()) null
                    else Line(mutableListOf(), direction).apply { words.forEach { addWord(it) } }
                }
                if (lines.isEmpty()) null
                else Paragraph(lines.toMutableList(), direction).also { it.languageCode = paragraph.languageCode }
            }
        }
    }

    /** 우르두어면 아랍어 `ه` 를 우르두 글자로 되돌린다(§15.1). 다른 언어는 그대로. */
    private fun urduLettersIfNeeded(ocr: OcrText, languageCode: String): OcrText =
        if (isUrdu(languageCode)) UrduLetters.fix(ocr) else ocr

    private fun isUrdu(languageCode: String) = languageCode.substringBefore('-') == "ur"

    /**
     * 소스 언어에 맞는 엔진으로 화면을 끝까지 읽는다(검출 + 모든 줄 읽기).
     * 덤프 도구도 이 함수로 읽는다 — 프로덕션과 같은 길이다.
     */
    internal suspend fun read(bitmap: Bitmap, sourceLanguageCode: String): OcrText {
        val detected = detect(bitmap, sourceLanguageCode)
        return recognizeAll(detected.kit, detected.ocr, bitmap)
    }

    /** 검출 결과와 그것을 낸 엔진. auto 는 엔진을 고르며 감지한 언어도 준다. [paddleWon] 은 auto 에서 PP-OCRv5 가 이긴 경우. */
    internal class Detected(
        val kit: VisionKit, val ocr: OcrText, val identifiedLanguageCode: String?, val paddleWon: Boolean = false,
        /** auto 가 라틴 인식기 하나로 끝냈다(성능 P4-2). */
        val latinStopped: Boolean = false,
        /** auto 의 지원되지 않는 문자권 관문이 개입했다(성능 P7). 그때 [ocr] 는 PP-OCRv5 검출(줄 위치만)이다. */
        val unsupportedScript: ScriptGate.Verdict? = null,
    )

    /**
     * 소스 언어에 맞는 엔진 후보로 검출한다. 후보가 여럿이면(auto) 모두 돌려 결과 하나를 고른다.
     * 고른 결과를 낸 엔진도 함께 돌려준다 — 남은 줄은 그 엔진이 읽는다. [waitOnGate] 는 [request] 를 본다.
     */
    internal suspend fun detect(bitmap: Bitmap, sourceLanguageCode: String, waitOnGate: Boolean = false): Detected = coroutineScope {
        // auto 는 PP-OCRv5 표본도 같이 돌린다. ML Kit 의 직렬 줄(§10.6) 밖이라 동시에 돈다(§13.3). 스위치로 끄면 ML Kit 만(§19)
        // 검출과 표본 읽기를 나눈다 — 검출은 라틴 멈춤의 "덮은 비율"에도 쓴다(성능 P4-2, .docs/perf-experiment-plan.md §5)
        val paddleDetection: Deferred<OcrText?>? = if (sourceLanguageCode == "auto" && PaddleSwitch.autoEnabled) kits.paddle?.let { paddle ->
            async {
                try {
                    paddle.autoDetect(bitmap)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag(TAG).e("PP-OCRv5 auto 검출 실패: ${e.message}")
                    null
                }
            }
        } else null
        val paddleSample: Deferred<List<PaddleKits.AutoCandidate>>? = paddleDetection?.let { detection ->
            async {
                try {
                    detection.await()?.let { kits.paddle!!.autoCandidates(bitmap, it) } ?: emptyList()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag(TAG).e("PP-OCRv5 auto 표본 실패: ${e.message}")
                    emptyList<PaddleKits.AutoCandidate>()
                }
            }
        }

        // 지원되지 않는 문자권 관문(성능 P7, .docs/perf-experiment-plan.md §7) — 검출이 끝나는 대로 판별한다. 라틴 인식기와 동시에 돌고,
        // 판정은 관문이 먼저다: 개입하면 라틴 결과를 버리고 나머지 인식기를 시작하지 않는다
        val gate: Deferred<ScriptGate.Verdict?>? = paddleDetection?.takeIf { ScriptGate.enabled }?.let { detection ->
            async {
                try {
                    detection.await()?.let { kits.paddle!!.scriptGate(bitmap, it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag(TAG).e("관문 판별 실패: ${e.message}")
                    null
                }
            }
        }
        suspend fun gated(kit: VisionKit): Detected? {
            val verdict = gate?.await() ?: return null
            val detection = paddleDetection.await() ?: return null
            paddleSample?.cancel()
            Timber.tag(TAG).i("auto: 지원되지 않는 문자권 관문 개입 (${verdict.script})")
            return Detected(kit, detection, verdict.language, unsupportedScript = verdict)
        }

        // 조건에 맞는 엔진으로 OCR 을 수행한다
        // 자기 범위에서 돌린다 — 바깥 범위의 async 면 부르는 쪽을 취소해도 인식기가 계속 돌고, detect 가 그 끝을 기다린다
        // (관문이 개입한 화면이 나머지 넷이 끝날 때까지 0.6~1초 늦게 반환되었다, 성능 P7 §7.5)
        suspend fun detectWith(candidates: List<VisionKit>): List<Pair<VisionKit, OcrText>> = coroutineScope {
            candidates.map { kit ->
                async {
                    try {
                        Timber.tag(TAG).d("kit : ${kit.name}")
                        val text: OcrText = kit.detect(bitmap)
                        Timber.tag(TAG).d("_processSuspend text : ${text.text}")
                        kit to text
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.tag(TAG).e("Error processing text recognition: ${e.message}")
                        null // 실패할 경우 null 반환
                    }
                }
            }.awaitAll().filterNotNull()
        }

        // 고르기는 모델 팩이 준비됐는지 본다 — 파일을 볼 수 있어 주 스레드에서 하지 않는다
        val candidates = withContext(Dispatchers.Default) { kits.candidatesFor(sourceLanguageCode) }
        var results: List<Pair<VisionKit, OcrText>>
        if (paddleDetection != null && candidates.size > 1 && AutoLatinStop.enabled) {
            // auto — 라틴 인식기 하나를 먼저 돌려, 라틴 화면이 확실하면 거기서 끝낸다(성능 P4-2). 나머지 넷은 멈추지 않기로 정한 뒤에야
            // 넣는다 — ML Kit 은 넣은 작업을 취소하지 못하고 한 줄로 처리한다(§10.6). 후보 순서의 첫째가 라틴이다(VisionKitSelector.all).
            val latin = async { detectWith(candidates.take(1)) }
            // 관문이 라틴 인식기보다 먼저 판정하면 라틴 결과를 기다리지 않는다(성능 P7 §7.5). 넣어 둔 라틴 작업은 ML Kit 안에서 끝까지 실행된다
            if (gate != null && select { gate.onAwait { true }; latin.onAwait { false } }) {
                gated(candidates.first())?.let { if (waitOnGate) latin.join() else latin.cancel(); return@coroutineScope it }
            }
            val latinResults = latin.await()
            val latinOcr = latinResults.firstOrNull()?.second
            if (latinOcr == null || !AutoLatinStop.passesTextChecks(latinOcr.lines.map { it.text })) {
                // 라틴 화면이 아니다 — 나머지 넷을 관문을 기다리지 않고 바로 넣는다. 관문을 기다렸다 넣으면 검출 · 판별만큼(S26 100~440ms)
                // 지원 문자권 화면이 느려졌다(성능 P7 기기 측정). 관문이 개입하면 그 결과를 기다리지 않고 버린다(ML Kit 대기는 취소된다)
                val rest = async { detectWith(candidates.drop(1)) }
                gated(candidates.first())?.let { if (waitOnGate) rest.join() else rest.cancel(); return@coroutineScope it }
                results = latinResults + rest.await()
            } else {
                gated(candidates.first())?.let { return@coroutineScope it }
                if (latinStops(latinOcr, paddleDetection, paddleSample!!, bitmap)) {
                    paddleSample.cancel()
                    val language = identifyLanguage(latinOcr.text)
                    Timber.tag(TAG).i("auto: 라틴에서 멈춤 ($language)")
                    return@coroutineScope Detected(candidates.first(), latinOcr, language, latinStopped = true)
                }
                results = latinResults + detectWith(candidates.drop(1))
            }
        } else {
            gated(candidates.first())?.let { return@coroutineScope it }
            results = detectWith(candidates)
        }
        if (results.isEmpty() && paddleSample == null) {
            // PP-OCRv5 가 세션을 처음 만들다 실패하면 그 엔진은 이 프로세스에서 꺼진다(§22) — 이제 고르는 엔진(ML Kit)으로 같은 화면을 다시 검출한다
            val fallback = withContext(Dispatchers.Default) { kits.candidatesFor(sourceLanguageCode) }
            if (fallback != candidates) results = detectWith(fallback)
        }
        if (paddleSample == null) {
            if (results.isEmpty()) throw Exception("No text recognized")
            if (results.size == 1) return@coroutineScope Detected(results[0].first, results[0].second, null)
        }

        // auto — 신뢰도로 바탕 후보를 고르고, 그 글의 언어를 감지해 그 언어를 지정했을 때 쓰는 엔진의 후보로 바꾼다
        // (.docs/vision-engine-design.md §11). 신뢰도는 엔진끼리 잴 수 있는 값이 아니어서 바탕만 정한다.
        val base = results.maxByOrNull { autoConfidenceScore(it.second) }
        val language = base?.let { identifyLanguage(it.second.text) } ?: "und"

        // PP-OCRv5 후보(§13.4). ML Kit 글이 비라틴 ML Kit 언어로 감지되면 ML Kit 을 믿는다 — 그 언어들은 제 문자가 있어야 감지된다
        if (paddleSample != null) {
            val paddle = kits.paddle!!
            val winner = if (language.substringBefore('-') in MLKIT_FIRST) null else paddleSample.await().firstOrNull()
            if (winner != null) {
                val paddleLanguage = paddle.languageOf(winner, identifyLanguage(winner.sampleText))
                Timber.tag(TAG).i("auto: ML Kit ${base?.first?.name} ($language) -> ${winner.kit.name} ($paddleLanguage)")
                return@coroutineScope Detected(winner.kit, winner.ocr, paddleLanguage, paddleWon = true)
            }
            paddleSample.cancel()
        }
        if (base == null) throw Exception("No text recognized")
        val preferred = if (language == "und") null else kits.candidatesFor(language).singleOrNull()
        val chosen = results.firstOrNull { it.first === preferred } ?: base
        Timber.tag(TAG).i("auto: base ${base.first.name}, language $language -> ${chosen.first.name}")
        Detected(chosen.first, chosen.second, language)
    }

    /**
     * 읽지 않은 줄을 모두 읽는다. 이미 다 읽혀 있으면(ML Kit) 받은 것을 그대로 돌려준다.
     * 글 전체는 읽는 순서로 잇는다 — 검출기가 준 줄 순서는 읽는 순서가 아니다([ReadingOrder], §23).
     */
    private suspend fun recognizeAll(kit: VisionKit, ocr: OcrText, bitmap: Bitmap): OcrText {
        if (ocr.isFullyRead) return ocr
        val read = kit.recognize(bitmap, ocr.lines)
        check(read.size == ocr.lines.size) { "${kit.name} 이 읽으면서 줄 수를 바꿨다" }
        var i = 0
        val blocks = ocr.blocks.map { block -> OcrBlock(block.boundingBox, block.lines.map { read[i++] }) }
        return OcrText(text = ReadingOrder.text(read), blocks = blocks)
    }

    /**
     * auto 에서 라틴 인식기 결과로 끝내도 되는가([AutoLatinStop], 성능 P4-2). 싼 신호(글자 수·쓰레기 기호·PP-OCRv5 검출 대비 덮은 비율)부터 보고,
     * 서면 줄마다 언어를 감지하고, 마지막으로 PP-OCRv5 표본이 제 문자를 인정한 후보가 없을 때만 멈춘다(키릴을 라틴 인식기가 닮은 글자로 읽는 화면).
     * PP-OCRv5 검출이 없으면(모델 준비 전 등) 멈추지 않는다.
     */
    private suspend fun latinStops(
        latin: OcrText,
        paddleDetection: Deferred<OcrText?>,
        paddleSample: Deferred<List<PaddleKits.AutoCandidate>>,
        bitmap: Bitmap,
    ): Boolean {
        val lines = latin.lines
        val texts = lines.map { it.text }
        // 싼 것부터 — 라틴 글만으로 가려지면 검출을 기다리지 않는다(비라틴 화면이 나머지 인식기를 늦게 시작하지 않게). 모두 "그리고"라 순서는 결과를 바꾸지 않는다
        if (!AutoLatinStop.passesTextChecks(texts)) return false
        val detected = paddleDetection.await() ?: return false
        val detectorBoxes = detected.lines.mapNotNull { it.boundingBox?.toBox() }
        if (!AutoLatinStop.passesCoverage(lines.map { it.boundingBox?.toBox() }, detectorBoxes, bitmap.width, bitmap.height)) return false
        if (!AutoLatinStop.passesLineLanguages(texts, identifyLanguages(texts))) return false
        return paddleSample.await().isEmpty()
    }

    private fun Rect.toBox() = intArrayOf(left, top, right, bottom)

    /** 여러 글의 언어를 한 번에 감지한다 — 감지기 하나로 동시에. 빈 글은 und. */
    private suspend fun identifyLanguages(texts: List<String>): List<String> = coroutineScope {
        val identifier = LanguageIdentification.getClient()
        try {
            texts.map { text ->
                async {
                    if (text.isBlank()) "und" else suspendCancellableCoroutine { continuation ->
                        identifier.identifyLanguage(text)
                            .addOnSuccessListener { continuation.resume(it) }
                            .addOnFailureListener { continuation.resume("und") }
                    }
                }
            }.awaitAll()
        } finally {
            identifier.close()
        }
    }

    /**
     * 주어진 텍스트의 언어를 ML Kit 으로 판정한다. 판정 불가 시 "und".
     * 자동 감지 번역에서 화면 전체가 아니라 실제 번역 대상 문장으로 감지할 때도 재사용한다.
     */
    suspend fun identifyLanguage(text: String): String {
        // 감지기는 쓰고 닫는다 — 닫지 않으면 요청마다 네이티브 감지기가 남는다(코드 정리 A4, [identifyLanguages] 와 같다)
        val languageIdentifier = LanguageIdentification.getClient()
        return try {
            suspendCancellableCoroutine { continuation ->
                languageIdentifier.identifyLanguage(text)
                    .addOnSuccessListener { languageCode -> continuation.resume(languageCode) }
                    .addOnFailureListener { _ -> continuation.resume("und") }
            }
        } finally {
            languageIdentifier.close()
        }
    }

    /**
     * WritingDirection.LTR, WritingDirection.RTL
     */
    private fun textToParagraphs(bitmap: Bitmap, text: OcrText, sourceLanguageCode: String, writingDirection: WritingDirection): List<Paragraph> {
        Timber.tag(TAG).i("#### textToParagraphs() ####  ${"\n" + text.text}")
        val params = AssemblyParams.reference(false, sourceLanguageCode)

        val elements: List<OcrWord> = sortLinesToWords(text.lines, writingDirection)

        elements.forEach {
            Timber.tag(TAG).i("element : ${it.boundingBox} ${it.text} ${(it.boundingBox!!.width().toDouble() / it.boundingBox!!.height())._cutDecimal()}")
        }

        val words: List<Word> = ocrWordsToWords(bitmap, elements, writingDirection)

        val lines: List<Line> = groupWordsIntoLines(words, writingDirection, params)

        lines.forEach { Timber.tag(TAG).i("groupWordsIntoLines result : ${it.boundingBox}, ${it.representation}, ${it.words}") }

        var paragraphs: List<Paragraph> = groupLinesIntoParagraphs(lines, writingDirection, params)

        paragraphs.forEach { Timber.tag(TAG).i("groupLinesIntoParagraphs result : ${it.hasParallelLines} ${it.boundingBox} ${it.representation}") }

        paragraphs = splitColumns(paragraphs, writingDirection)

        paragraphs.forEach {
            Timber.tag(TAG).i("paragraphs ${it.boundingBox} ${it.representation} ")
        }

        // 문장 경계를 로케일 규칙으로 찾으려면 문단이 제 언어를 알아야 한다.
        paragraphs.forEach { it.languageCode = sourceLanguageCode }

        return paragraphs
    }

    /**
     * WritingDirection.TTB_LTR, WritingDirection.TTB_RTL
     */
    private fun textToVerticalParagraphs(bitmap: Bitmap, text: OcrText, sourceLanguageCode: String, writingDirection: WritingDirection): List<Paragraph> {
        Timber.tag(TAG).i("#### textToVerticalParagraphs() ####  ${"\n" + text.text}")

        val textLines: List<OcrLine> =
            text.blocks
                .flatMap { textBlock ->
                    textBlock.lines.filter { it.boundingBox.isValid() }
                }.sortedWith(
                    Comparator { line1, line2 ->
                        val rightComparison = line2.boundingBox!!.right.compareTo(line1.boundingBox!!.right)
                        if (rightComparison != 0) rightComparison else line1.boundingBox!!.top.compareTo(line2.boundingBox!!.top)
                    }
                )

        textLines.forEach { Timber.tag(TAG).i("textLine : ${it.boundingBox}, ${it.text}") }

        val verticalLines = mutableListOf<Line>()
        val horizontalTextLines = mutableListOf<OcrLine>()

        textLines
            .filter { it.boundingBox.isValid() }
            .forEach { textLine ->
                if (textLine.boundingBox!!.height() > textLine.boundingBox!!.width()) {
                    val line = textLine.toLine(writingDirection)
                    verticalLines.add(line)
                } else {
                    horizontalTextLines.add(textLine)
                }
            }

        /** ####################################### verticalParagraphs ###################################### */
        val verticalParams = AssemblyParams.reference(true, sourceLanguageCode)
        var verticalParagraphs: MutableList<Paragraph> =
            groupLinesIntoParagraphs(verticalLines, writingDirection, verticalParams)
                .toMutableList()
        verticalParagraphs.forEach { Timber.tag(TAG).i("groupLinesIntoParagraphs result : ${it.boundingBox} ${it.representation}") }

        /**
         * [groupLinesIntoParagraphs] 로 클러스터링 하는 경우 세로로 단락 구분이 되어 있는것을 감지하는 것이 어려우므로
         * 세로단락 구분을 [detectAndSplitParagraphs] 으로 확인한다. (검증되지 않음)
         */
        verticalParagraphs = verticalParagraphs.flatMap { paragraph ->
            val splitParagraphs = if (verticalParams.VERTICAL_SPLIT) detectAndSplitParagraphs(paragraph, writingDirection)
            else listOf(paragraph)
            splitParagraphs.forEach {
                Timber.tag(TAG).d("detectAndSplitParagraphs ${it.boundingBox} ${it.representation} ")
            }
            splitParagraphs
        }.toMutableList()

        /** ####################################### horizontalParagraphs ###################################### */
        val horizontalParams = AssemblyParams.reference(false, sourceLanguageCode)
        val horizontalWritingDirection = Language.writingDirection(sourceLanguageCode, false)
        val words: List<Word> = ocrLinesToWords(bitmap, horizontalTextLines, horizontalWritingDirection)
        val lines: List<Line> = groupWordsIntoLines(words, horizontalWritingDirection, horizontalParams)
        val horizontalParagraphs = splitColumns(groupLinesIntoParagraphs(lines, horizontalWritingDirection, horizontalParams), horizontalWritingDirection)

        verticalParagraphs.forEach { Timber.tag(TAG).i("verticalParagraphs : ${it.boundingBox} ${it.representation}") }
        horizontalParagraphs.forEach { Timber.tag(TAG).i("horizontalParagraphs : ${it.boundingBox} ${it.representation}") }

        verticalParagraphs.addAll(horizontalParagraphs)

        // 가로 경로와 같은 이유 — 문장 경계를 로케일 규칙으로 찾으려면 언어를 알아야 한다.
        verticalParagraphs.forEach { it.languageCode = sourceLanguageCode }

        return verticalParagraphs
    }
}

/** 기호 상자가 없으면 멈춘다(`!!`) — 예전 ML Kit 확장 함수와 같다. 고치는 것은 따로 한다. */
fun OcrWord.toWord(writingDirection: WritingDirection): Word {
    val chars = this.symbols.map { symbol ->
        Char(symbol.boundingBox!!, symbol.text, writingDirection)
    }
    return Word(this.boundingBox!!, this.text, writingDirection, chars)
}

fun OcrLine.toLine(writingDirection: WritingDirection): Line {
    val words = this.readWords.map { element ->
        element.toWord(writingDirection)
    }.toMutableList()
    return Line(words, writingDirection)
}

/** 읽힌 줄의 단어. 조립은 읽힌 줄에만 한다 — 검출만 된 줄이 여기 오면 길을 잘못 탄 것이다. */
internal val OcrLine.readWords: List<OcrWord>
    get() = checkNotNull(words) { "검출만 되고 읽지 않은 줄이다" }

/**
 * ML Kit 글이 이 언어로 감지되면 auto 가 PP-OCRv5 를 보지 않는다(§13.4 — 제 문자가 있어야 감지되는 비라틴 ML Kit 언어). 인식기 고르기와 같은 표다
 * (데바나가리 표의 예전 저장값 다섯은 언어 감지가 내놓지 않아 판정은 예전 7개 표와 같다, 코드 정리 B4).
 */
private val MLKIT_FIRST = VisionKitSelector.MLKIT_SCRIPT_LANGUAGES + VisionKitSelector.DEVANAGARI_LANGUAGES

/**
 * auto 에서 바탕 후보를 고르는 점수. 줄마다 (신뢰도 − 0.5) × 공백 뺀 글자 수의 합 — 확신이 반도 안 되는 줄은 깎는다.
 * 줄 신뢰도의 단순 합은 쓰레기 줄을 더 읽은 후보를 이기게 하고, 평균은 일부만 읽은 후보를 이기게 한다(§11). 신뢰도가 없으면 0.
 */
internal fun autoConfidenceScore(ocr: OcrText): Double =
    ocr.lines.sumOf { line -> ((line.confidence ?: 0f) - 0.5) * line.text.count { !it.isWhitespace() } }
