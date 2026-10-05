package com.galaxy.airviewdictionary.data.local.vision

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
import com.galaxy.airviewdictionary.data.local.vision.model.Transaction
import com.galaxy.airviewdictionary.data.local.vision.model.VisionResponse
import com.galaxy.airviewdictionary.data.local.vision.model.VisionSingleLineText
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
import kotlin.math.abs
import kotlin.math.min
import kotlin.properties.Delegates


/**
 * 1.5 폰트높이로 넓힌 단어 간격 가운데, 예전 한계(0.63)를 넘는 틈만 표의 열 틈 후보로 본다(3라운드 E2′).
 * 행 안의 벌어진 틈을 모을 때도 같은 값을 쓴다 — 그보다 좁은 틈은 보통 단어 사이다.
 */
private const val COLUMN_GAP_MIN_RATIO = 0.63

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
                val paragraphs = withContext(Dispatchers.Default) { detectorParagraphs(bitmap, detected.ocr.lines, sourceLanguageCode).first }
                paragraphs.forEach { it.languageCode = sourceLanguageCode }
                return@coroutineScope VisionResponse.Success(
                    Transaction(bitmap, detected.ocr, sourceLanguageCode, paragraphs, image = ImageTargets.Detected, unsupportedScript = verdict)
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
     * auto 는 가로쓰기(왼쪽에서 오른쪽)로 묶는다 — 쓰기 방향은 문단 묶기에만 쓰인다.
     */
    suspend fun requestImageTargets(bitmap: Bitmap, sourceLanguageCode: String): VisionResponse? {
        val paddle = kits.paddle ?: return null
        return try {
            val detected = paddle.detectLines(bitmap) ?: return null
            val paragraphs = withContext(Dispatchers.Default) { detectorParagraphs(bitmap, detected.lines, sourceLanguageCode).first }
            paragraphs.forEach { it.languageCode = sourceLanguageCode }
            Timber.tag(TAG).i("image targets: ${detected.lines.size} lines -> ${paragraphs.size} paragraphs")
            VisionResponse.Success(Transaction(bitmap, detected, sourceLanguageCode, paragraphs, image = ImageTargets.Detected))
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
     * [kit] 이 검출한 결과를 [Transaction] 으로 만든다. 검출기가 줄을 주는 엔진이고 끝까지 읽을 필요가 없으면 줄 상자로 문단만 묶는다.
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
    ): Transaction {
        if (!readAll && sourceLanguageCode != "auto" && kit.linesFromDetector) {
            return withContext(Dispatchers.Default) {
                detectedToTransaction(bitmap, kit, urduLettersIfNeeded(detected, sourceLanguageCode), sourceLanguageCode)
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

        return Transaction(bitmap, text, detectedLanguageCode, analyzedParagraphs)
    }

    /**
     * 검출만 된 화면을 줄 상자로 문단에 묶는다. 하네스의 `detector-real` 갈래가 재는 길과 같다 — 줄마다 단어 하나(상자 = 줄 상자,
     * 폰트높이 = 상자 높이)를 세운 [Line] 을 검출기 기준값으로 문단에 묶고 쪼개기 후처리를 한다. 세로쓰기는 판정하지 않는다
     * (세로쓰기 문자는 ML Kit 이 다 읽는다).
     */
    private fun detectedToTransaction(bitmap: Bitmap, kit: VisionKit, detected: OcrText, sourceLanguageCode: String): Transaction {
        val (paragraphs, sources) = detectorParagraphs(bitmap, detected.lines, sourceLanguageCode)
        paragraphs.forEach { it.languageCode = sourceLanguageCode }
        Timber.tag(TAG).i("detected only: ${detected.lines.size} lines -> ${paragraphs.size} paragraphs")
        return Transaction(bitmap, detected, sourceLanguageCode, paragraphs, UnreadParagraphs(kit, sources))
    }

    /**
     * 검출기 줄을 줄 상자로 문단에 묶는다([detectedToTransaction] 참고). 문단마다 그 문단을 이루는 원 줄(문단 안 순서)을 함께 준다.
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

        val paragraphs = groupLinesIntoParagraphs(lines, writingDirection, params).flatMap { paragraph ->
            correctDetectAndSplitParagraphs(detectAndSplitParagraphs(paragraph, writingDirection), writingDirection)
        }

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
    suspend fun readParagraph(transaction: Transaction, paragraph: Paragraph, background: Boolean = false): Paragraph? {
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

    private fun clampToBitmap(box: Rect, bitmap: Bitmap) = Rect(
        box.left.coerceAtLeast(0),
        box.top.coerceAtLeast(0),
        box.right.coerceAtMost(bitmap.width),
        box.bottom.coerceAtMost(bitmap.height),
    )

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
            val detection = paddleDetection?.await() ?: return null
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
     * 주어진 텍스트의 언어를 ML Kit 으로 판정한다. 판정 불가 시 "und".
     * 자동 감지 번역에서 화면 전체가 아니라 실제 번역 대상 문장으로 감지할 때도 재사용한다.
     */
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

    suspend fun identifyLanguage(text: String): String = suspendCancellableCoroutine { continuation ->
        val languageIdentifier = LanguageIdentification.getClient()
        languageIdentifier.identifyLanguage(text)
            .addOnSuccessListener { languageCode ->
                continuation.resume(languageCode)
            }
            .addOnFailureListener { _ ->
                continuation.resume("und")
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

        /**
         * [groupLinesIntoParagraphs] 로 클러스터링 하는 경우 세로로 단락 구분이 되어 있는것을 감지하는 것이 어려우므로
         * 세로단락 구분을 [detectAndSplitParagraphs], [correctDetectAndSplitParagraphs] 으로 확인한다.
         */
        paragraphs = paragraphs.flatMap { paragraph ->
            val splitParagraphs = detectAndSplitParagraphs(paragraph, writingDirection)
            splitParagraphs.forEach {
                Timber.tag(TAG).d("detectAndSplitParagraphs ${it.boundingBox} ${it.representation} ")
            }
            val clusterParagraphs = correctDetectAndSplitParagraphs(splitParagraphs, writingDirection)
            clusterParagraphs.forEach {
                Timber.tag(TAG).d("correctDetectAndSplitParagraphs ${it.boundingBox} ${it.representation} ")
            }
            clusterParagraphs
        }

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
            groupLinesIntoParagraphs(mergeColumnPieces(verticalLines, writingDirection, verticalParams), writingDirection, verticalParams)
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
        var horizontalParagraphs: List<Paragraph> = groupLinesIntoParagraphs(lines, horizontalWritingDirection, horizontalParams)
        horizontalParagraphs = horizontalParagraphs.flatMap { paragraph ->
            val splitParagraphs = detectAndSplitParagraphs(paragraph, horizontalWritingDirection)
            splitParagraphs.forEach {
                Timber.tag(TAG).d("detectAndSplitParagraphs ${it.boundingBox} ${it.representation} ")
            }
            val clusterParagraphs = correctDetectAndSplitParagraphs(splitParagraphs, horizontalWritingDirection)
            clusterParagraphs.forEach {
                Timber.tag(TAG).d("correctDetectAndSplitParagraphs ${it.boundingBox} ${it.representation} ")
            }
            clusterParagraphs
        }

        verticalParagraphs.forEach { Timber.tag(TAG).i("verticalParagraphs : ${it.boundingBox} ${it.representation}") }
        horizontalParagraphs.forEach { Timber.tag(TAG).i("horizontalParagraphs : ${it.boundingBox} ${it.representation}") }

        verticalParagraphs.addAll(horizontalParagraphs)

        // 가로 경로와 같은 이유 — 문장 경계를 로케일 규칙으로 찾으려면 언어를 알아야 한다.
        verticalParagraphs.forEach { it.languageCode = sourceLanguageCode }

        return verticalParagraphs
    }

    /**
     * 엔진이 준 줄([OcrLine])을 조립기의 입력인 [Word] 로 바꾼다.
     *
     * 줄을 읽는 순서로 정렬해 단어를 펼친 뒤, 상자를 이미지 안으로 자르고, 글자 상자가 있는 단어만 남긴다. 예전에는
     * ML Kit 의 `Text.Line → Text.Element → Word` 두 함수였는데 늘 이어서 불렸다. 규칙은 그대로다.
     */
    internal fun ocrLinesToWords(bitmap: Bitmap, lines: List<OcrLine>, writingDirection: WritingDirection): List<Word> =
        ocrWordsToWords(bitmap, sortLinesToWords(lines, writingDirection), writingDirection)

    /** 줄을 읽는 순서로 정렬해 단어를 펼친다. 세로쓰기는 세로로 긴 줄만 남긴다. */
    internal fun sortLinesToWords(textLines: List<OcrLine>, writingDirection: WritingDirection): List<OcrWord> {
        return when (writingDirection) {
            WritingDirection.LTR -> {
                textLines
                    .filter { it.boundingBox.isValid() }
                    .sortedWith(
                        Comparator { line1, line2 ->
                            val topComparison = line1.boundingBox!!.top.compareTo(line2.boundingBox!!.top)
                            if (topComparison != 0) topComparison else line1.boundingBox!!.left.compareTo(line2.boundingBox!!.left)
                        }
                    )
                    .flatMap { line -> line.readWords }
            }

            WritingDirection.RTL -> {
                textLines
                    .filter { it.boundingBox.isValid() }
                    .sortedWith(
                        Comparator { line1, line2 ->
                            val topComparison = line1.boundingBox!!.top.compareTo(line2.boundingBox!!.top)
                            if (topComparison != 0) topComparison else line2.boundingBox!!.right.compareTo(line1.boundingBox!!.right)
                        }
                    )
                    .flatMap { line -> line.readWords }
            }

            WritingDirection.TTB_LTR -> {
                textLines
                    .filter { it.boundingBox.isValid() }
                    .filter { it.boundingBox!!.width() < it.boundingBox!!.height() }
                    .sortedWith(
                        Comparator { line1, line2 ->
                            val leftComparison = line1.boundingBox!!.left.compareTo(line2.boundingBox!!.left)
                            if (leftComparison != 0) leftComparison else line1.boundingBox!!.top.compareTo(line2.boundingBox!!.top)
                        }
                    )
                    .flatMap { line -> line.readWords }
            }

            WritingDirection.TTB_RTL -> {
                textLines
                    .filter { it.boundingBox.isValid() }
                    .filter { it.boundingBox!!.width() < it.boundingBox!!.height() }
                    .sortedWith(
                        Comparator { line1, line2 ->
                            val rightComparison = line2.boundingBox!!.right.compareTo(line1.boundingBox!!.right)
                            if (rightComparison != 0) rightComparison else line1.boundingBox!!.top.compareTo(line2.boundingBox!!.top)
                        }
                    )
                    .flatMap { line -> line.readWords }
            }
        }
    }

    /** 단어 상자를 이미지 안으로 자르고, 글자 상자가 하나라도 있는 단어만 [Word] 로 만든다. */
    internal fun ocrWordsToWords(bitmap: Bitmap, elements: List<OcrWord>, writingDirection: WritingDirection): List<Word> {
        val words = mutableListOf<Word>()
        val bitmapWidth = bitmap.width
        val bitmapHeight = bitmap.height

        for (element in elements) {
            element.boundingBox?.let { boundingBox ->
                // BoundingBox 보정 작업
                val left = if (boundingBox.left < 0) 0 else boundingBox.left
                val top = if (boundingBox.top < 0) 0 else boundingBox.top
                val right = if (boundingBox.right > bitmapWidth) bitmapWidth else boundingBox.right
                val bottom = if (boundingBox.bottom > bitmapHeight) bitmapHeight else boundingBox.bottom

                // 새로운 Rect 생성
                val correctedBoundingBox = Rect(left, top, right, bottom)

                // 보정된 boundingBox를 사용하여 너비와 높이를 확인
                if (correctedBoundingBox.width() > 0 && correctedBoundingBox.height() > 0) {
                    val chars = element.symbols
                        .filter { it.boundingBox.isValid() }
                        .map { Char(it.boundingBox!!, it.text, writingDirection) }

                    if (chars.isNotEmpty()) {
                        words.add(Word(correctedBoundingBox, element.text, writingDirection, chars))
                    }
                }
            }
        }
        return words
    }

    /**
     * [Word] 리스트를
     * [Line] 리스트로 변환한다.
     */
    internal fun groupWordsIntoLines(words: List<Word>, writingDirection: WritingDirection, params: AssemblyParams): List<Line> = with(params) {
        val lines = mutableListOf<Line>()
        val columnGaps = if (WORD_COLUMN_GAP_ROWS > 0 &&
            (writingDirection == WritingDirection.LTR || writingDirection == WritingDirection.RTL)
        ) ColumnGaps(words) else null

        VisionSingleLineText.sortedForReading(words, writingDirection)
            .forEach { word ->
                var addedToLine = false

                for (line in lines) {
                    // 판단하려고 하는 새로운 Word 와 가장 근접한 line 의 Word
                    val closestWord = line.words.minByOrNull { it.getWriteDirectionDistance(word) }!!

                    // word-closestWord 폰트 높이 평균
                    val averageFontHeight: Double = word.getAverageFontHeight(closestWord)

                    // closestWord-word 중심축 거리
                    val axisDistance = word.getAxisDistance(closestWord)

                    //  중심축 거리가 closestWord-word 폰트 높이 평균 보다 크면 같은 라인이 아님
                    if (axisDistance > averageFontHeight) break

                    // 읽기방향 word-closestWord 거리
                    val writeDirectionDistance: Double = word.getWriteDirectionDistance(closestWord).toDouble()

                    // (요소 간 거리 : 요소 평균 높이) 비율
                    //
                    // 기준을 행 안 최대 박스 높이로 바꿔도 보았는데 나아지지 않았다 —
                    // 쪼개진 행 수가 같은 지점에서 묶임·오염이 같은 프론티어에 있었다
                    // (2026-09-23 실측). 기준을 바꾸는 문제가 아니라 한계비가 좁았던 것이다.
                    val writeDirectionDistanceFontHeightRatio: Double =
                        writeDirectionDistance / averageFontHeight

                    /** 판단하려고 하는 새로운 Word 와 기존 Line 에서 새로운 Word 에 가장 근접한 Word 는 읽기방향 일정 거리 이상 떨어져 있지 않아야 한다. */
                    // [condition 0]
                    // 1.5 로 새로 허용된 틈(0.63 폰트높이 초과)이 표의 열 틈이면 잇지 않는다(3라운드 E2′).
                    val columnGap = columnGaps != null && writeDirectionDistanceFontHeightRatio > COLUMN_GAP_MIN_RATIO &&
                            columnGaps.isColumnGap(closestWord, word, WORD_COLUMN_GAP_ROWS)
                    if (!columnGap && writeDirectionDistanceFontHeightRatio <= WORD_WRITE_DIRECTION_DISTANCE_FONT_HEIGHT_RATIO_LIMIT) {
                        // 행방향 중심축 유사율
                        val axisSimilarityRatio = word.getAxisSimilarityRatio(closestWord)

                        // word-closestWord 폰트 높이 유사율
                        val fontHeightSimilarityRatio = word.getFontHeightSimilarityRatio(closestWord)

                        // 행방향 중심축 유사율 * word-closestWord 폰트 높이 유사율
                        val axisFontHeightSimilarityRatio = axisSimilarityRatio * fontHeightSimilarityRatio

                        /** 판단하려고 하는 새로운 Word 와 기존 Line 에서 새로운 Word 에 가장 근접한 Word 는 행방향으로 동일 선상에 위치하고, 폰트 높이가 유사해야 한다. */
                        // [condition 0-0]
                        if (axisFontHeightSimilarityRatio >= WORD_AXIS_FONT_HEIGHT_SIMILARITY_MINIMUM_RATIO) { // 0.85
                            Timber.tag(TAG).d(
                                "groupWordsIntoLines add 0-0 : "
                                        + "${writeDirectionDistance._cutDecimal()}, "
                                        + "${averageFontHeight._cutDecimal()}, "
                                        + "${writeDirectionDistanceFontHeightRatio._cutDecimal()}, "
                                        + "${axisSimilarityRatio._cutDecimal()}, "
                                        + "${fontHeightSimilarityRatio._cutDecimal()}, "
                                        + "${axisFontHeightSimilarityRatio._cutDecimal()}, "
                                        + "${line.representation}(${line.boundingBox}) + ${word.representation}(${word.boundingBox})"
                            )
                            line.addWord(word)
                            addedToLine = true
                            break
                        }
                        // [condition 0-1]
                        else {
                            Timber.tag(TAG).v(
                                "groupWordsIntoLines drop 0-1 : "
                                        + "${writeDirectionDistance._cutDecimal()}, "
                                        + "${averageFontHeight._cutDecimal()}, "
                                        + "${writeDirectionDistanceFontHeightRatio._cutDecimal()}, "
                                        + "${axisSimilarityRatio._cutDecimal()}, "
                                        + "${fontHeightSimilarityRatio._cutDecimal()}, "
                                        + "${axisFontHeightSimilarityRatio._cutDecimal()}, "
                                        + "${line.representation}(${line.boundingBox}) + ${word.representation}(${word.boundingBox})"
                            )
                        }
                    }
                    // [condition 1]
                    else {
                        Timber.tag(TAG).v(
                            "groupWordsIntoLines drop 1 : "
                                    + "${writeDirectionDistance._cutDecimal()}, "
                                    + "${averageFontHeight._cutDecimal()}, "
                                    + "${writeDirectionDistanceFontHeightRatio._cutDecimal()}, "
                                    + "${line.representation}(${line.boundingBox}) + ${word.representation}(${word.boundingBox})"
                        )
                    }
                }

                if (!addedToLine) {
                    lines.add(0, Line(mutableListOf(word), writingDirection))
                }
            }

        lines
    }

    /**
     * 분석된 Line 들을 Paragraph 로 클러스터링 한다.
     * 위에서 아래로, 왼쪽에서 오른쪽으로(LTR. RTL 은 반대) List<Line> 을 탐색하면서
     * 선행 Line 과 후행 Line 을 폰트높이, 라인간 거리, 배경색상, 폰트색상 등의 요소를 근거로 비교하고 클러스터링 한다.
     */
    /**
     * 화면의 대표 줄 간격. 줄 중심 사이 거리의 중앙값이다.
     *
     * 예전에는 행간을 그 줄의 박스 높이로 나눠 봤는데, 박스 높이는 그 줄에 어떤 글자가
     * 왔느냐에 좌우된다 — 키릴은 대부분의 줄에 디센더가 없어 30px 로 조이고 아랍어는
     * 위아래로 뻗어 44~53px 가 된다. 같은 레이아웃인데도 비율이 1.6 대 0.6 으로 갈려
     * 러시아어 문단이 통째로 잘렸다(2026-09-23 실측).
     * 줄 간격은 레이아웃이 정하는 양이라 문자와 무관하다.
     */
    internal fun referencePitch(lines: List<Line>, writingDirection: WritingDirection): Double {
        val isVertical = writingDirection == WritingDirection.TTB_LTR ||
                writingDirection == WritingDirection.TTB_RTL
        val centers = lines
            .map { if (isVertical) it.boundingBox.centerX() else it.boundingBox.centerY() }
            .sorted()
        if (centers.size < 2) return 0.0
        val pitches = centers.zipWithNext { a, b -> (b - a).toDouble() }.filter { it > 0 }.sorted()
        if (pitches.isEmpty()) return 0.0
        return pitches[pitches.size / 2]
    }

    /**
     * 제목 규칙(3라운드 E3′) — [LINE_HEADING_HEIGHT_RATIO] 참조. [paragraph] 가 한 행뿐이고 [line] 이 셋을 모두
     * 만족하면 참이다. 제목 후보는 그 행의 줄들을 합친 상자다.
     */
    private fun AssemblyParams.isHeadingBreak(
        paragraph: Paragraph,
        line: Line,
        writingDirection: WritingDirection,
        textLeft: Int,
        textRight: Int,
    ): Boolean {
        if (writingDirection != WritingDirection.LTR && writingDirection != WritingDirection.RTL) return false
        if (!paragraph.areAllInLine()) return false
        val title = paragraph.boundingBox
        val titleHeight = title.height()
        if (titleHeight <= 0) return false
        if (line.boundingBox.height() >= LINE_HEADING_HEIGHT_RATIO * titleHeight) return false
        val shortBy = if (writingDirection == WritingDirection.RTL) title.left - textLeft else textRight - title.right
        if (shortBy <= 2 * titleHeight) return false
        if (LINE_HEADING_WIDTH_RATIO > 0 && title.width() >= LINE_HEADING_WIDTH_RATIO * (textRight - textLeft)) return false
        return true
    }

    /**
     * 세로 분기에서 ML Kit 이 한 열을 여러 조각으로 끊어 준 것을 잇는다([VERTICAL_COLUMN_MERGE], 3라운드 E1′).
     *
     * 조각은 열 높이의 일부만 차지해 축 인식 채움비로 재면 문단 끝처럼 보이고, 쪼개기 후처리가 "나란한 줄" 로
     * 오인한다. 가로로 절반 넘게 겹치고 위아래 틈이 열 폭 이하인 이웃 조각을 한 줄로 잇는다. 이은 줄은 조각들의
     * 단어 객체를 그대로 담는다 — 하네스가 단어로 원 조각을 찾아 채점한다(분모가 설정과 무관해야 한다).
     */
    internal fun mergeColumnPieces(lines: List<Line>, writingDirection: WritingDirection, params: AssemblyParams): List<Line> {
        if (!params.VERTICAL_COLUMN_MERGE) return lines
        val out = mutableListOf<Line>()
        for (line in VisionSingleLineText.sortedForReading(lines, writingDirection)) {
            val last = out.lastOrNull()
            if (last != null) {
                val a = last.boundingBox
                val b = line.boundingBox
                val overlap = minOf(a.right, b.right) - maxOf(a.left, b.left)
                val narrower = minOf(a.width(), b.width())
                val gap = b.top - a.bottom
                if (narrower > 0 && overlap > narrower * 0.5 && gap <= maxOf(a.width(), b.width())) {
                    // 조각끼리 위아래로 조금 겹칠 수 있어, 이어 붙이기만 하면 줄 안 단어 순서가 어긋난다. 열 안은 위→아래.
                    val words = (last.words + line.words).sortedBy { it.boundingBox.top }
                    out[out.size - 1] = Line(words.toMutableList(), writingDirection)
                    continue
                }
            }
            out.add(line)
        }
        return out
    }

    /**
     * 표의 열 틈(3라운드 E2′, [WORD_COLUMN_GAP_ROWS]). 화면의 단어를 행으로 묶고(하네스 `EvalMetrics.bands` 와 같다 —
     * 세로 겹침이 작은 쪽 높이의 절반 초과), 행마다 그 행 글자 높이(단어 높이 중앙값)의 [COLUMN_GAP_MIN_RATIO] 배
     * 이상 벌어진 틈의 가로 구간을 모아 둔다. 보통 단어 사이는 그보다 좁아 열 틈이 되지 않는다.
     */
    private class ColumnGaps(words: List<Word>) {
        private val rowOf = java.util.IdentityHashMap<Word, Int>()
        private val gaps: List<List<IntRange>>

        init {
            val remaining = words.sortedBy { it.boundingBox.top }.toMutableList()
            val rows = mutableListOf<List<Word>>()
            while (remaining.isNotEmpty()) {
                val head = remaining.removeAt(0)
                val row = mutableListOf(head)
                val iterator = remaining.iterator()
                while (iterator.hasNext()) {
                    val other = iterator.next()
                    val span = minOf(head.boundingBox.bottom, other.boundingBox.bottom) -
                            maxOf(head.boundingBox.top, other.boundingBox.top)
                    val smaller = minOf(head.boundingBox.height(), other.boundingBox.height())
                    if (smaller > 0 && span.toDouble() / smaller > 0.5) {
                        row.add(other); iterator.remove()
                    }
                }
                rows.add(row)
            }
            rows.forEachIndexed { index, row -> row.forEach { rowOf[it] = index } }
            gaps = rows.map { row ->
                val heights = row.map { it.boundingBox.height() }.sorted()
                val rowHeight = heights[heights.size / 2]
                row.sortedBy { it.boundingBox.left }.zipWithNext().mapNotNull { (a, b) ->
                    val gap = b.boundingBox.left - a.boundingBox.right
                    if (rowHeight > 0 && gap >= COLUMN_GAP_MIN_RATIO * rowHeight) a.boundingBox.right..b.boundingBox.left
                    else null
                }
            }
        }

        /** [a]·[b] 사이 틈이 위아래 3행 중 [need] 개 이상의 행에서 같은 가로 구간에 벌어져 있나. */
        fun isColumnGap(a: Word, b: Word, need: Int): Boolean {
            val row = rowOf[b] ?: return false
            val low = minOf(a.boundingBox.right, b.boundingBox.right)
            val high = maxOf(a.boundingBox.left, b.boundingBox.left)
            if (high <= low) return false
            var matched = 0
            for (other in (row - 3)..(row + 3)) {
                if (other == row || other < 0 || other >= gaps.size) continue
                if (gaps[other].any { g ->
                        val overlap = minOf(high, g.last) - maxOf(low, g.first)
                        val narrower = minOf(high - low, g.last - g.first)
                        narrower > 0 && overlap > narrower * 0.5
                    }) matched++
            }
            return matched >= need
        }
    }

    /** 두 줄의 중심 사이 거리(줄바꿈 방향). */
    private fun pitchBetween(a: Line, b: Line, writingDirection: WritingDirection): Double {
        val isVertical = writingDirection == WritingDirection.TTB_LTR ||
                writingDirection == WritingDirection.TTB_RTL
        return if (isVertical) abs(a.boundingBox.centerX() - b.boundingBox.centerX()).toDouble()
        else abs(a.boundingBox.centerY() - b.boundingBox.centerY()).toDouble()
    }

    internal fun groupLinesIntoParagraphs(lines: List<Line>, writingDirection: WritingDirection, params: AssemblyParams): List<Paragraph> = with(params) {
        val paragraphs = mutableListOf<Paragraph>()
        val referencePitch = referencePitch(lines, writingDirection)
        val pitchLimit = referencePitch * LINE_PITCH_LIMIT
        // 화면 글의 가로 범위 — 제목 규칙(3라운드 E3′)이 "짧은 제목" 을 잴 때 쓴다.
        val textLeft = lines.minOfOrNull { it.boundingBox.left } ?: 0
        val textRight = lines.maxOfOrNull { it.boundingBox.right } ?: 0

        VisionSingleLineText.sortedForReading(lines, writingDirection)
            .forEach { line ->
                /** 판단하려고 하는 새로운 Line이 이미 분석되어 paragraphs 에 존재한다면 continue forEach loop */
                if (line in paragraphs.flatMap { it.lines }) return@forEach

                var addedToParagraph = false

                for (paragraph in paragraphs) {
                    // 텍스트 읽기 방향에서 일부 겹치는지의 여부
                    val isWriteDirectionOverlaps = line.isWriteDirectionOverlaps(paragraph)

                    // 줄바꿈 방향에서 일부 겹치는지의 여부
                    val isLineReturnDirectionOverlaps = line.isLineReturnDirectionOverlaps(paragraph)

                    /** 판단하려고 하는 새로운 라인과 기존 Paragraph 가 텍스트 읽기 방향과 줄바꿈 방향에서 일부 겹치면 동일 Paragraph 그룹으로 판단한다. */
                    if (isWriteDirectionOverlaps && isLineReturnDirectionOverlaps) {
                        Timber.tag(TAG).d(
                            "groupLinesIntoParagraphs add 0 : "
                                    + "${paragraph.boundingBox}, "
                                    + "${paragraph.representation}(${paragraph.height}), "
                                    + "${line.boundingBox}, "
                                    + "${line.representation}(${line.height})"
                        )

                        paragraph.lines.add(line)
                        addedToParagraph = true
                        break
                    }

                    // 판단하려고 하는 새로운 라인과 가장 근접한 paragraph 의 line (paragraph.lines 의 마지막 element)
                    val closestLine = paragraph.lines.lastOrNull() ?: continue // 없으면 continue

                    // 한 행뿐인 문단이 제목이고 이 줄이 그 아래 부제면 잇지 않는다(3라운드 E3′).
                    if (LINE_HEADING_HEIGHT_RATIO > 0 && !isLineReturnDirectionOverlaps &&
                        isHeadingBreak(paragraph, line, writingDirection, textLeft, textRight)
                    ) continue

                    // line 과 closestLine 의 행간
                    val lineSpacing = closestLine.getLineReturnDirectionDistance(line)

                    // 줄 간격이 크게 벌어지면 다른 문단으로 본다.
                    // 검출기가 줄을 주는 엔진은 레이아웃이 정하는 pitch 로, 단어에서 줄을
                    // 유도하는 ML Kit 경로는 예전처럼 박스 높이로 잰다(위 주석 참조).
                    if (pitchLimit > 0) {
                        if (pitchBetween(closestLine, line, writingDirection) > pitchLimit) continue
                    } else {
                        if (lineSpacing > closestLine.fontHeight * 1.6) continue
                    }

                    /**
                     * 문단의 마지막 줄이 단을 채우지 못했으면 그 문단은 거기서 끝난 것이다.
                     * 감싸인 글은 마지막 줄만 짧다. 제목과 목록 항목도 짧아 자연히 갈린다.
                     *
                     * 줄 간격보다 센 신호다 — 문단 사이가 거의 붙은 배치에서는 간격만으로는
                     * 구분할 정보가 없지만 이 신호는 남는다(2026-09-23 실측).
                     * 단 너비는 그 문단 줄들의 최대 너비로 본다. 문단 안에서는 대부분의 줄이
                     * 단을 채우므로 안정적이다.
                     */
                    val alongVertical = LINE_MEASURE_ALONG_WRITING_AXIS &&
                            (writingDirection == WritingDirection.TTB_RTL || writingDirection == WritingDirection.TTB_LTR)
                    fun extentOf(l: Line) = if (alongVertical) l.boundingBox.height() else l.boundingBox.width()
                    val columnWidth = paragraph.lines.maxOf { extentOf(it) }
                    if (LINE_FILL_MINIMUM_RATIO > 0 && columnWidth > 0 &&
                        extentOf(closestLine).toDouble() / columnWidth < LINE_FILL_MINIMUM_RATIO
                    ) continue

                    /**
                     * 줄 머리가 단 안쪽으로 들어가 있으면 새 문단의 첫 줄이다.
                     *
                     * 책 조판은 문단 사이를 빈 줄이 아니라 첫 줄 들여쓰기로 구분한다.
                     * 그런 쪽에서는 행간이 아무 정보도 주지 않아 문단이 통째로 붙었다
                     * (실측: 책 15면에서 묶임 90.9% 인데 오염이 전체 단어의 79%).
                     * 들여쓰기는 그 배치에서 유일하게 남는 신호다.
                     *
                     * 기준은 문단 줄들의 머리 중 가장 바깥이다. 문단 안의 이어지는 줄은
                     * 첫 줄보다 바깥에 있으므로 이 검사에 걸리지 않는다.
                     */
                    if (LINE_INDENT_LIMIT > 0) {
                        val isRtl = writingDirection == WritingDirection.RTL
                        // 세로쓰기(축 인식을 켰을 때)는 열의 머리가 위 끝이다.
                        val paragraphHead = when {
                            alongVertical -> paragraph.lines.minOf { it.boundingBox.top }
                            isRtl -> paragraph.lines.maxOf { it.boundingBox.right }
                            else -> paragraph.lines.minOf { it.boundingBox.left }
                        }
                        val lineHead = when {
                            alongVertical -> line.boundingBox.top
                            isRtl -> line.boundingBox.right
                            else -> line.boundingBox.left
                        }
                        val indent = if (isRtl && !alongVertical) paragraphHead - lineHead else lineHead - paragraphHead

                        /**
                         * 들여쓰기만으로 끊으면 웹이 깨진다 — 인용문·중첩목록·코드블록처럼
                         * 문단 시작이 아닌 들여쓰기가 흔하기 때문이다(실측: 웹 83면에서
                         * 온전한 문단 74.9% → 67.2%). 앞 줄이 단을 못 채웠다는 조건을
                         * 함께 요구하면 두 신호가 동의할 때만 끊는다. 책 조판에서는 문단
                         * 마지막 줄이 짧고 다음 줄이 들여쓰기되어 둘이 같이 성립한다.
                         */
                        val filled = if (columnWidth > 0)
                            extentOf(closestLine).toDouble() / columnWidth else 1.0
                        if (indent > line.fontHeight * LINE_INDENT_LIMIT &&
                            filled < LINE_INDENT_FILL_GUARD
                        ) continue
                    }

                    // line-closestLine 폰트높이 평균
                    val averageFontHeight: Double = line.getAverageFontHeight(closestLine)

                    /** 판단하려고 하는 새로운 라인과 기존 Paragraph 내 라인들의 평균 폰트높이가 FONT_HEIGHT_SIMILARITY_MINIMUM_RATIO 이상의 유사성을 가지고 있어야 한다. */
                    // line-closestLine 폰트높이 유사성
                    val fontHeightSimilarityRatio = line.getFontHeightSimilarityRatio(closestLine)

                    // [condition 0] 폰트높이 유사성 조건에 부합하는 경우
                    if (fontHeightSimilarityRatio >= LINE_FONT_HEIGHT_SIMILARITY_MINIMUM_RATIO) {
                        // [condition 0-0] 텍스트 읽기 방향으로 일부 겹치는 경우
                        if (isWriteDirectionOverlaps) {
                            /** 판단하려고 하는 새로운 라인과 Paragraph 는 텍스트 읽기 방향으로 LINE_WRITE_DIRECTION_OVERLAP_MINIMUM_RATIO 비율 이상 겹쳐야 한다. */
                            // 텍스트 읽기 방향으로 width 가 작은 것이 큰 것에 겹치는 비율
                            val writeDirectionOverlapRatio: Double = line.getWriteDirectionOverlapRatio(paragraph)

                            // [condition 0-0-0] 텍스트 읽기 방향 겹침조건 부합하는 경우
                            if (writeDirectionOverlapRatio >= LINE_WRITE_DIRECTION_OVERLAP_MINIMUM_RATIO) {
                                /**
                                 * closestLine 폰트높이의 유사성과 라인 행간 affinity 로 동일 Paragraph 를 판단한다.
                                 *
                                 * 색은 쓰지 않는다. 글자색을 줄에서 재는 일이 실제 화면에서 너무 자주
                                 * 어긋나, 같은 문단인데 색이 다르다고 갈라놓는 쪽이 압도적으로 많았다.
                                 * 색이 막아주던 잘못된 병합은 위의 줄 채움비가 대신 막는다.
                                 * 실제 웹 83면 표본에서 색을 빼고 채움비를 켜니 문단 오염이
                                 * 261 → 43 줄로 줄고 묶임은 1606 → 1668 줄로 늘었다(2026-09-23 실측).
                                 */
                                // 라인 affinity ({행간 : 요소 평균 높이} 비)
                                // 줄 간격이 화면 대표 간격에 가까울수록 1 에 가깝다.
                                // 박스 높이로 나누면 문자마다 기준이 달라진다 — 키릴은 박스가
                                // 조여 같은 레이아웃에서도 affinity 가 0.58 까지 떨어졌다.
                                val pitch = pitchBetween(closestLine, line, writingDirection)
                                val lineSpacingAffinity =
                                    if (pitchLimit > 0 && referencePitch > 0 && pitch > 0)
                                        min(1.0, referencePitch / pitch)
                                    else min(1.0, 1.0 / (lineSpacing.toDouble() / averageFontHeight))

                                // 폰트높이 유사성 * {행간 : 요소 평균 높이} 비 affinity
                                val fontHeightLineSpacingAffinity =
                                    fontHeightSimilarityRatio * lineSpacingAffinity

                                // [condition 0-0-0-0] 
                                if (fontHeightLineSpacingAffinity >= LINE_FONT_HEIGHT_SPACING_AFFINITY_LIMIT) {
                                    Timber.tag(TAG).d(
                                        "groupLinesIntoParagraphs add 0-0-0-0 : "
                                                + "${fontHeightSimilarityRatio._cutDecimal()}, "
                                                + "${lineSpacingAffinity._cutDecimal()}, "
                                                + "*${fontHeightLineSpacingAffinity._cutDecimal()}, "
                                                + "${closestLine.representation}(${closestLine.height}) + ${line.representation}(${line.height})"
                                    )
                                    paragraph.lines.add(line)
                                    addedToParagraph = true
                                    break
                                }
                                // [condition 0-0-0-1] 
                                else {
                                    Timber.tag(TAG).v(
                                        "groupLinesIntoParagraphs drop 0-0-0-1 : "
                                                + "${fontHeightSimilarityRatio._cutDecimal()}, "
                                                + "${lineSpacingAffinity._cutDecimal()}, "
                                                + "*${fontHeightLineSpacingAffinity._cutDecimal()}, "
                                                + "${closestLine.representation}(${closestLine.height}) + ${line.representation}(${line.height})"
                                    )
                                }
                            }
                            // [condition 0-0-1] 
                            else {
                                Timber.tag(TAG).v(
                                    "groupLinesIntoParagraphs drop 0-0-1 : "
                                            + "${fontHeightSimilarityRatio._cutDecimal()}, "
                                            + "${writeDirectionOverlapRatio._cutDecimal()}, "
                                            + "${closestLine.representation}(${closestLine.height}) + ${line.representation}(${line.height})"
                                )
                            }
                        }

                        // [condition 0-1] 줄바꿈 방향에서 일부 겹치는 경우
                        else if (isLineReturnDirectionOverlaps) {
                            /** 판단하려고 하는 새로운 라인과 기존 Paragraph 에서 새로운 라인에 가장 근접한 라인은 줄바꿈 방향으로 동일 선상에 위치해야 한다. */
                            // 라인 중심축 유사율
                            val axisSimilarityRatio = line.getAxisSimilarityRatio(closestLine)

                            // 라인 중심축 유사율 * line-closestLine 높이 유사율
                            val axisHeightSimilarityRatio = axisSimilarityRatio * fontHeightSimilarityRatio

                            // [condition 0-1-0]
                            if (axisHeightSimilarityRatio >= LINE_AXIS_HEIGHT_SIMILARITY_MINIMUM_RATIO) {
                                /** 새로운 라인과 기존 Paragraph 에서 새로운 라인에 가장 근접한 라인은 텍스트 읽기 방향 일정 거리 이상 떨어져 있지 않아야 한다. */
                                // 텍스트 읽기 방향 Line-Line 거리
                                val writeDirectionDistance: Double = line.getWriteDirectionDistance(closestLine).toDouble()

                                // (요소 간 거리 : 요소 평균 폰트높이) 비율
                                val writeDirectionDistanceFontHeightRatio: Double = writeDirectionDistance / averageFontHeight

                                // [condition 0-1-0-0]
                                if (writeDirectionDistanceFontHeightRatio <= LINE_WRITE_DIRECTION_DISTANCE_FONT_HEIGHT_RATIO_LIMIT) {
                                    Timber.tag(TAG).d(
                                        "groupLinesIntoParagraphs add 0-1-0-0 : "
                                                + "${fontHeightSimilarityRatio._cutDecimal()}, "
                                                + "${axisSimilarityRatio._cutDecimal()}, "
                                                + "${axisHeightSimilarityRatio._cutDecimal()}, "
                                                + "${writeDirectionDistance._cutDecimal()}, "
                                                + "${writeDirectionDistanceFontHeightRatio._cutDecimal()}, "
                                                + "${closestLine.representation}(${closestLine.height}) + ${line.representation}(${line.height}))"
                                    )

                                    if (writingDirection == WritingDirection.LTR) {
                                        if (closestLine.boundingBox.right < line.boundingBox.right) {
                                            paragraph.lines.add(line)
                                        } else {
                                            paragraph.lines.add(paragraph.lines.size - 1, line)
                                        }
                                    } else if (writingDirection == WritingDirection.TTB_RTL ||
                                        writingDirection == WritingDirection.TTB_LTR
                                    ) {
                                        // 세로쓰기에서 줄바꿈 방향으로 겹친다는 것은 ML Kit 이 한 열을 여러
                                        // 조각으로 끊었다는 뜻이다. 열 안의 순서는 위에서 아래다 — 가로쓰기처럼
                                        // 왼쪽 끝으로 자리를 정하면 조각 순서가 뒤섞인다(실측: ja131).
                                        if (closestLine.boundingBox.top < line.boundingBox.top) {
                                            paragraph.lines.add(line)
                                        } else {
                                            paragraph.lines.add(paragraph.lines.size - 1, line)
                                        }
                                    } else {
                                        if (line.boundingBox.left < closestLine.boundingBox.left) {
                                            paragraph.lines.add(line)
                                        } else {
                                            paragraph.lines.add(paragraph.lines.size - 1, line)
                                        }
                                    }
                                    // 복수의 Line 들이 하나의 행을 이루게 된다
                                    paragraph.hasParallelLines = true
                                    addedToParagraph = true
                                    break
                                }
                                // [condition 0-1-0-1]
                                else {
                                    Timber.tag(TAG).v(
                                        "groupLinesIntoParagraphs drop 0-1-0-1 : "
                                                + "${fontHeightSimilarityRatio._cutDecimal()}, "
                                                + "${axisSimilarityRatio._cutDecimal()}, "
                                                + "${axisHeightSimilarityRatio._cutDecimal()}, "
                                                + "${writeDirectionDistance._cutDecimal()}, "
                                                + "${writeDirectionDistanceFontHeightRatio}, "
                                                + "${LINE_WRITE_DIRECTION_DISTANCE_FONT_HEIGHT_RATIO_LIMIT}, "
                                                + "${(writeDirectionDistanceFontHeightRatio <= LINE_WRITE_DIRECTION_DISTANCE_FONT_HEIGHT_RATIO_LIMIT)}, "
                                                + "${closestLine.representation}(${closestLine.height}) + ${line.representation}(${line.height}))"
                                    )
                                }
                            }
                            // [condition 0-1-1]
                            else {
                                Timber.tag(TAG).v(
                                    "groupLinesIntoParagraphs drop 0-1-1 : "
                                            + "${fontHeightSimilarityRatio._cutDecimal()}, "
                                            + "${axisSimilarityRatio._cutDecimal()}, "
                                            + "${axisHeightSimilarityRatio._cutDecimal()}, "
                                            + "${closestLine.representation}(${closestLine.height}) + ${line.representation}(${line.height}))"
                                )
                            }
                        }
                        // [condition 0-2]
                        else {
                            Timber.tag(TAG).v(
                                "groupLinesIntoParagraphs drop 0-2 : "
                                        + "${fontHeightSimilarityRatio._cutDecimal()}, "
                                        + "${closestLine.representation}(${closestLine.height}) + ${line.representation}(${line.height}))"
                            )
                        }
                    }
                    // [condition 1]
                    else {
                        Timber.tag(TAG).v(
                            "groupLinesIntoParagraphs drop 1 : "
                                    + "${fontHeightSimilarityRatio._cutDecimal()}, "
                                    + "${closestLine.representation}(${closestLine.height}) + ${line.representation}(${line.height}))"
                        )
                    }
                }

                if (!addedToParagraph) {
                    paragraphs.add(0, Paragraph(mutableListOf(line), writingDirection))
                }
            }

        paragraphs
    }

    /**
     * groupLinesIntoParagraphs 에서 분석된 Paragraph 중
     *
     *        △ △ △ △ △ △ △ △  ○ ○ ○ ○ ○ ○ ○ ○
     *        △ △ △ △ △ △ △ △  ○ ○ ○ ○ ○ ○ ○ ○
     *        △ △ △ △ △ △ △ △  ○ ○ ○ ○ ○ ○ ○ ○
     *        △ △ △ △ △ △ △ △  ○ ○ ○ ○ ○ ○ ○ ○
     *
     * 이런 Paragraph 의 경우 △ 단락과 ○ 단락이 있으나, groupLinesIntoParagraphs 에서 분리해 내지 못한다.
     *
     * 복수의 Line 들이 하나의 행을 이루는 것이 있는 Paragraph 를 분석하여
     * 세로로 단락 구분이 가능한지 확인한다.
     * DBSCAN 기법을 이용하되, 요소 간 거리는 x축 기준으로 판단하여 Line 의 시작위치가 비슷한 것 끼리 클러스터링 한다.
     */
    internal fun detectAndSplitParagraphs(paragraph: Paragraph, writingDirection: WritingDirection): List<Paragraph> {
        if (!paragraph.hasParallelLines || paragraph.lines.size == 1) {
            return listOf(paragraph)
        }

        // lines 가 하나의 라인을 이루는 경우
        if (paragraph.areAllInLine()) {
            return listOf(paragraph)
        }

        Timber.tag(TAG).d("detectAndSplitParagraphs ${paragraph.representation}")

        val lines = paragraph.lines
        val clustersVisited = mutableSetOf<Line>()
        val clusters = mutableListOf<MutableList<Line>>()
        val distanceLimit: Double = paragraph.averageLineHeight()

        // 클러스터 확장 및 탐색
        // 각 Line을 기준으로 이웃하는 Line을 찾고, 이를 클러스터에 추가하며 재귀적으로 탐색한다
        fun expandCluster(line: Line, cluster: MutableList<Line>) {
            val neighbors =
                lines.filter {
                    if (it != line) {
                        Timber.tag(TAG).i(
                            "Split cluster "
                                    + "$distanceLimit, ${abs(line.startPosition - it.startPosition)}, ${line.boundingBox}, ${line.representation}, ${it.boundingBox}, ${it.representation}"
                        )
                    }
                    it != line && abs(line.startPosition - it.startPosition) <= distanceLimit
                }

            cluster.add(line)
            clustersVisited.add(line)
            neighbors.forEach {
                if (!clustersVisited.contains(it)) {
                    expandCluster(it, cluster)
                }
            }
        }

        // 클러스터 그룹화
        lines.forEach { line ->
            if (!clustersVisited.contains(line)) {
                val cluster = mutableListOf<Line>()
                expandCluster(line, cluster)
                clusters.add(cluster)
            }
        }

        // 묶음은 탐색 순서로 쌓여 줄 순서가 읽는 순서와 다르다. 가로쓰기는 뒤이은
        // correctDetectAndSplitParagraphs 가 다시 합치며 정렬하지만 세로 분기에는 그 단계가 없어,
        // 세로쓰기는 여기서 읽는 순서로 놓는다(실측: ja131 한 문단의 열 조각이 뒤섞였다).
        val vertical = writingDirection == WritingDirection.TTB_RTL || writingDirection == WritingDirection.TTB_LTR
        return clusters.map { cluster ->
            val ordered = if (vertical) VisionSingleLineText.sortedForReading(cluster, writingDirection) else cluster
            Paragraph(ordered.toMutableList(), writingDirection)
        }
    }

    /**
     * groupLinesIntoParagraphs 에서 분석된 Paragraph 중
     *
     *        ○ ○ ○ ○ ○ ○ ○ ○  ○ ○ ○ ○ ○ ○ ○ ○
     *            ○ ○ ○ ○ ○ ○ ○ ○ ○ ○ ○
     *                  ○ ○ ○ ○ ○ ○ ○ ○ ○ ○
     *
     * 이런 Paragraph 의 경우 detectAndSplitParagraphs 검증을 하게 되면
     *
     *        ○ ○ ○ ○ ○ ○ ○ ○  △ △ △ △ △ △ △ △
     *            ▲ ▲ ▲ ▲ ▲ ▲ ▲ ▲ ▲ ▲ ▲
     *                  ◇ ◇ ◇ ◇ ◇ ◇ ◇ ◇ ◇ ◇
     *
     * 와 같이 모두 분리된 Paragraph 로 인식 되므로
     * 이를 보정하여 하나의 Paragraph 로 클러스터링 한다.
     */
    internal fun correctDetectAndSplitParagraphs(paragraphs: List<Paragraph>, writingDirection: WritingDirection): List<Paragraph> {
        val clustersVisited = mutableSetOf<Paragraph>()
        val clusters = mutableListOf<MutableList<Paragraph>>()

        fun expandCluster(paragraph: Paragraph, cluster: MutableList<Paragraph>) {
            val neighbors = paragraphs.filter {
                if (it != paragraph) {
                    Timber.tag(TAG)
                        .i("Correct cluster ${paragraph.isWriteDirectionOverlaps(it)}, ${paragraph.boundingBox}, ${paragraph.representation}, ${it.boundingBox}, ${it.representation}")
                }
                it != paragraph && paragraph.isWriteDirectionOverlaps(it)
            }
            cluster.add(paragraph)
            clustersVisited.add(paragraph)
            neighbors.forEach {
                if (!clustersVisited.contains(it)) {
                    expandCluster(it, cluster)
                }
            }
        }

        paragraphs.forEach { paragraph ->
            if (!clustersVisited.contains(paragraph)) {
                val cluster = mutableListOf<Paragraph>()
                expandCluster(paragraph, cluster)
                clusters.add(cluster)
            }
        }

        fun mergeParagraphs(paragraphs: List<Paragraph>): Paragraph {
            val allLines = VisionSingleLineText.sortedForReading(paragraphs.flatMap { it.lines }, writingDirection)
                .toMutableList()
            return Paragraph(allLines, writingDirection)
        }

        return clusters.map { cluster -> mergeParagraphs(cluster) }
    }

    /**
     * OCR 원문이 가로쓰기인지 세로쓰기인지 확인한다.
     *
     * 가로로 긴 줄과 그렇지 않은 줄을 **글자 수로** 저울질한다. 예전에는 줄 개수로 다수결을 했는데,
     * 한두 글자짜리 줄은 상자가 폭보다 높아 세로 표로 잡힌다 — 축구 순위표처럼 "1", "38", "W" 칸이
     * 많은 가로 화면이 세로쓰기로 판정되어 화면 전체가 세로 분기로 갔다(2026-09-24 실측, BBC 순위표).
     * 글자 수로 세면 긴 세로 열과 긴 가로 문장이 판정을 정하고 짧은 칸은 거의 무게가 없다.
     * 표본 343면(세로 86)에서 오판 0, 세로 표의 비율이 세로 표본은 0.84 이상·가로 표본은 0.11 이하로
     * 벌어진다(줄 개수로는 0.61 과 0.56 이라 경계에 붙어 있었다).
     */
    internal fun detectVerticalWriting(text: OcrText): Boolean {
        var horizontalChars = 0
        var verticalChars = 0

        for (textBlock in text.blocks) {
            for (line in textBlock.lines) {
                line.boundingBox?.let {
                    val chars = line.text.count { c -> !c.isWhitespace() }
                    if (it.width() > it.height() && it.height() > 0) {
                        horizontalChars += chars
                    } else {
                        verticalChars += chars
                    }
                }
            }
        }
        Timber.tag(TAG).i("isVerticalWriting  $horizontalChars $verticalChars")
        return horizontalChars < verticalChars
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
private val OcrLine.readWords: List<OcrWord>
    get() = checkNotNull(words) { "검출만 되고 읽지 않은 줄이다" }

/** ML Kit 글이 이 언어로 감지되면 auto 가 PP-OCRv5 를 보지 않는다(§13.4 — 제 문자가 있어야 감지되는 비라틴 ML Kit 언어). */
private val MLKIT_FIRST = setOf("hi", "mr", "ne", "sa", "ko", "ja", "zh")

/**
 * auto 에서 바탕 후보를 고르는 점수. 줄마다 (신뢰도 − 0.5) × 공백 뺀 글자 수의 합 — 확신이 반도 안 되는 줄은 깎는다.
 * 줄 신뢰도의 단순 합은 쓰레기 줄을 더 읽은 후보를 이기게 하고, 평균은 일부만 읽은 후보를 이기게 한다(§11). 신뢰도가 없으면 0.
 */
internal fun autoConfidenceScore(ocr: OcrText): Double =
    ocr.lines.sumOf { line -> ((line.confidence ?: 0f) - 0.5) * line.text.count { !it.isWhitespace() } }
