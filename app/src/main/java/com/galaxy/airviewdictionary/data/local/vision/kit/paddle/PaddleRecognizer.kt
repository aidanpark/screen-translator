package com.galaxy.airviewdictionary.data.local.vision.kit.paddle

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Rect
import com.galaxy.airviewdictionary.data.local.vision.ocr.OcrLine
import com.galaxy.airviewdictionary.data.local.vision.ocr.OcrSymbol
import com.galaxy.airviewdictionary.data.local.vision.ocr.OcrWord
import timber.log.Timber
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * PP-OCRv5 모바일 인식기 하나(문자권 하나). 줄 상자를 원본 해상도에서 잘라 읽고 **단어와 글자 상자까지** 만든다.
 *
 * 인식기 출력은 `[1, T, C]` 소프트맥스이고 시간축 T 는 크롭 폭에 왼쪽 → 오른쪽으로 선형 대응한다. blank 가 아닌 예측의 시점을
 * x 로 되돌리면 글자 위치가 나오고, 공백 예측을 경계로 묶으면 단어가 된다(`.docs/vision-engine-design.md` §3.5). 같은 순전파를 다시
 * 쓰므로 추가 비용이 없다. 글자 신뢰도는 그 시점의 확률이다.
 *
 * 높이 48 로 맞춘 폭이 [LineChunks.MAX_WIDTH] 를 넘는 줄(가로 화면·태블릿의 작은 글씨)은 빈 틈에서 잘라 조각마다 읽고 보이는 순서대로 잇는다 —
 * 인식기 메모리가 폭의 제곱에 가깝게 늘기 때문이다(§22). 글자 상자는 조각의 자리만큼 옮긴다.
 *
 * [visualOrder] 인식기(아랍 문자)는 글을 **화면에 보이는 순서**(왼쪽부터)로 낸다. 읽는 순서로 되돌린다 — 라틴·숫자 덩어리는 안의 순서를
 * 지키고 괄호는 거울상을 되돌린다([VisualOrder]). `OcrText` 계약 2.
 */
internal class PaddleRecognizer(
    private val sessions: PaddleSessions,
    private val model: String,
    private val dictionary: String,
    private val visualOrder: Boolean,
) {
    /** CTC 어휘: 0 번은 blank, 사전 글자, 마지막에 공백(PaddleOCR `use_space_char`). */
    private val vocab: List<String>? by lazy {
        sessions.text(dictionary)?.split("\n")?.dropLastWhile { it.isEmpty() }?.let { listOf("") + it + " " }
    }

    fun isReady(): Boolean = sessions.has(model) && sessions.has(dictionary)

    /** 세션을 만들다 실패해 이 프로세스에서 쓸 수 없는가. ONNX Runtime 을 건드리지 않는다. */
    fun broken(): Boolean = sessions.broken(model)

    /** 세션과 어휘를 갖춰 둔다(이미 있으면 그대로). 갖출 수 없으면 false. */
    fun prepare(): Boolean = sessions.session(model, THREADS) != null && vocab != null

    /**
     * 방출 하나. [left]·[right] 는 그 시점과 다음 시점의 화면 x 다. [synthetic] 은 넓은 줄의 조각 사이에 넣은 공백 — 인식기가 낸 것이 아니라
     * 신뢰도가 없다.
     */
    private class Emission(val text: String, val left: Int, val right: Int, val prob: Float, val synthetic: Boolean = false)

    /** [options] 를 추론에 넘긴다 — 취소되면 도는 추론을 멈추는 데 쓴다([terminable]). */
    fun read(screen: Bitmap, box: Rect, options: OrtSession.RunOptions? = null): OcrLine {
        val session = sessions.session(model, THREADS) ?: throw IllegalStateException("$model 세션이 없다")
        val vocab = vocab ?: throw IllegalStateException("$dictionary 가 아직 없다")
        val started = System.nanoTime()
        val crop = Bitmap.createBitmap(screen, box.left, box.top, box.width(), box.height())
        val w = max(16, (HEIGHT.toFloat() * box.width() / box.height()).roundToInt())
        val input = Bitmap.createScaledBitmap(crop, w, HEIGHT, true)
        val dark = crop.isDark()
        val pixels = IntArray(w * HEIGHT).also { input.getPixels(it, 0, w, 0, 0, w, HEIGHT) }
        if (input !== crop && input !== screen) input.recycle()
        // createBitmap 은 불변 화면에서 화면 전체와 같은 사각형을 자르면 화면 자체를 돌려준다 — 부르는 쪽의 화면을 지우면 안 된다
        if (crop !== screen) crop.recycle()
        // 폭이 상한을 넘는 줄은 빈 틈에서 잘라 조각마다 읽는다(§22)
        val chunks = if (w <= LineChunks.MAX_WIDTH) listOf(LineChunks.Chunk(0, w, false))
        else LineChunks.plan(columnInk(pixels, w, HEIGHT, dark))

        var preparing = System.nanoTime() - started
        var inferring = 0L
        val emissions = mutableListOf<Emission>()
        fun recognize(chunk: LineChunks.Chunk) {
            val width = chunk.end - chunk.start
            val t0 = System.nanoTime()
            val tensor = bgrTensor(pixels, w, chunk.start, width, HEIGHT, dark) { _, v -> (v - 0.5f) / 0.5f }
            val t1 = System.nanoTime()
            preparing += t1 - t0
            OnnxTensor.createTensor(sessions.env, tensor, longArrayOf(1, 3, HEIGHT.toLong(), width.toLong())).use { x ->
                val inputs = mapOf(session.inputNames.first() to x)
                (if (options != null) session.run(inputs, options) else session.run(inputs)).use { out ->
                    inferring += System.nanoTime() - t1
                    val result = out[0] as OnnxTensor
                    val shape = result.info.shape // [1, T, C]
                    val steps = shape[1].toInt()
                    val classes = shape[2].toInt()
                    val buffer = result.floatBuffer
                    val probs = FloatArray(buffer.remaining()).also { buffer.get(it) }
                    // 시점 t → 입력 열 start + width·t/steps → 화면 x. 한 조각이면 left + 상자 폭·t/steps 와 같다
                    val span = max(1, steps).toLong()
                    fun x(t: Int) = box.left + (box.width().toLong() * (chunk.start * span + width.toLong() * t) / (w * span)).toInt()
                    val first = emissions.size
                    var previous = 0
                    for (t in 0 until steps) {
                        var best = 0
                        var bestProb = -1f
                        for (c in 0 until classes) {
                            val p = probs[t * classes + c]
                            if (p > bestProb) { bestProb = p; best = c }
                        }
                        // 탐욕 CTC: blank 가 아니고 앞과 같은 글자가 아닐 때만 낸다
                        if (best != 0 && best != previous && best < vocab.size) emissions.add(Emission(vocab[best], x(t), x(t + 1), bestProb))
                        previous = best
                    }
                    // 낱말 사이 틈에서 잘랐으면 조각 사이에 공백을 넣는다 — 양쪽 조각 모두 가장자리 공백은 내지 않는다
                    if (chunk.spaceBefore && first > 0 && emissions.size > first && emissions[first - 1].text != " " && emissions[first].text != " ") {
                        emissions.add(first, Emission(" ", x(0), x(0), 0f, synthetic = true))
                    }
                }
            }
        }
        var waiting = 0L
        if (chunks.size == 1) {
            recognize(chunks[0])
        } else {
            val queued = System.nanoTime()
            synchronized(WIDE_LINES) {
                waiting = System.nanoTime() - queued
                chunks.forEach { recognize(it) }
            }
        }
        Timber.tag("PaddleRecognizer").d(
            "${box.width()}x${box.height()}→${w}x$HEIGHT${if (chunks.size > 1) " 조각 ${chunks.size} 대기 ${waiting / 1_000_000}ms" else ""} " +
                "준비 ${preparing / 1_000_000}ms 추론 ${inferring / 1_000_000}ms " +
                "복호 ${(System.nanoTime() - started - waiting - preparing - inferring) / 1_000_000}ms"
        )
        return toLine(box, emissions)
    }

    /**
     * 방출로 글자·단어 상자를 세운다. 글자 폭은 다음 방출까지다.
     *
     * 줄의 첫 글자와 마지막 글자는 줄 상자 끝까지 늘린다. CTC 는 글자 안쪽의 한 시점에서 방출하므로 첫 방출은 글자 왼끝보다, 마지막 방출의 다음
     * 시점은 글자 오른끝보다 안쪽이다(훑기 캡처 1,699줄: 잉크 끝과의 차이 중앙값 줄 높이의 0.15~0.27, 90% 0.5). 그대로 두면 단어 모드에서 줄 끝
     * 단어의 바깥쪽을 가리켜도 단어가 잡히지 않는다.
     */
    private fun toLine(box: Rect, emissions: List<Emission>): OcrLine {
        val first = emissions.indexOfFirst { it.text != " " }
        val last = emissions.indexOfLast { it.text != " " }
        val symbols = emissions.mapIndexed { i, e ->
            val left = if (i == first) box.left else e.left
            val end = when {
                i == last -> box.right
                i + 1 < emissions.size -> emissions[i + 1].left
                else -> e.right
            }
            OcrSymbol(Rect(left, box.top, max(end, left + 1), box.bottom), e.text, if (e.synthetic) null else e.prob)
        }
        // 읽는 순서는 줄 전체로 정한다 — 단어만 떼어 보면 아랍어 문맥이 없어 숫자 순서를 달리 판정한다([VisualOrder]).
        // 거울상을 되돌린 괄호는 글만 바꾼 글자가 된다(상자는 그대로)
        val placed = if (visualOrder) VisualOrder.place(symbols.map { it.text }) else symbols.indices.map { VisualOrder.Placed(it, symbols[it].text) }
        val rank = IntArray(symbols.size).also { r -> placed.forEachIndexed { k, p -> r[p.index] = k } }
        val logical = symbols.toMutableList().also { l -> placed.forEach { p -> if (p.text != symbols[p.index].text) l[p.index] = symbols[p.index].copy(text = p.text) } }

        // 보이는 순서의 공백 경계로 단어를 가른다. 단어 안의 글자는 줄의 읽는 순서를 따른다
        val words = mutableListOf<OcrWord>()
        var current = mutableListOf<Int>()
        fun flush() {
            if (current.isEmpty()) return
            val ordered = current.sortedBy { rank[it] }.map { logical[it] }
            val wordBox = Rect(symbols[current.first()].boundingBox!!.left, box.top, symbols[current.last()].boundingBox!!.right, box.bottom)
            words.add(OcrWord(wordBox, ordered.joinToString("") { it.text }, ordered))
            current = mutableListOf()
        }
        for (i in symbols.indices) if (symbols[i].text == " ") flush() else current.add(i)
        flush()

        val lineSymbols = placed.map { logical[it.index] }
        val read = emissions.filterNot { it.synthetic }
        val confidence = if (read.isEmpty()) 0f else read.map { it.prob }.average().toFloat()
        return OcrLine(box, lineSymbols.joinToString("") { it.text }.trim(), confidence, words)
    }

    companion object {
        private const val HEIGHT = 48
        private const val THREADS = 2

        /**
         * 폭 상한을 넘는 줄은 모든 인식기를 통틀어 한 번에 하나만 읽는다(§22). 문단 읽기는 줄 넷을 동시에 읽는데(`PaddleOcrVisionKit`), 넓은 줄이
         * 여럿이어도 동시에 도는 순전파는 모두 상한 폭 이하이고 그중 넓은 줄의 조각은 하나뿐이다.
         */
        private val WIDE_LINES = Any()
    }
}
