package com.galaxy.airviewdictionary.data.local.vision

import com.galaxy.airviewdictionary.data.local.vision.kit.VisionKit
import com.galaxy.airviewdictionary.data.local.vision.model.Paragraph
import com.galaxy.airviewdictionary.data.local.vision.ocr.OcrLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections
import java.util.IdentityHashMap

/**
 * 검출만 된 화면의 문단들. 문단마다 원 줄(검출 상자)을 기억하고, 읽은 문단을 캡처 단위로 캐시한다 — 드래그로 되돌아와도
 * 다시 읽지 않는다(`.docs/vision-engine-design.md` §10). 다 읽힌 화면(ML Kit)에는 없다.
 *
 * 문단은 객체 그 자체로 가린다. `Paragraph` 는 data class 라 내용이 같으면 같다고 보기 때문이다.
 */
class UnreadParagraphs internal constructor(
    /** 이 화면을 검출한 엔진. 읽기도 이 엔진이 한다. */
    internal val kit: VisionKit,
    /** 검출 문단 → 그 문단을 이루는 원 줄(문단 안 순서). */
    private val sources: IdentityHashMap<Paragraph, List<OcrLine>>,
) {
    private val mutex = Mutex()

    /** 검출 문단 → 읽은 문단. 읽었는데 남은 단어가 없으면 null 로 넣는다. */
    private val read: MutableMap<Paragraph, Paragraph?> = Collections.synchronizedMap(IdentityHashMap())

    /** 이미 읽은 문단. 아직 안 읽었거나 읽을 것이 없었으면 null. */
    fun cached(paragraph: Paragraph): Paragraph? = read[paragraph]

    /** 이 화면의 검출 문단인데 아직 읽지 않았나. 읽는 데 시간이 걸리니 기다리는 표시를 할지 가른다. */
    fun needsReading(paragraph: Paragraph): Boolean = sources.containsKey(paragraph) && !read.containsKey(paragraph)

    /** 지금 도는 뒤 읽기(이웃 문단)와 그 문단. 앞 읽기가 오면 양보시킨다. */
    private var background: Pair<Paragraph, Job>? = null

    /**
     * [paragraph] 를 읽은 문단을 돌려준다. 처음이면 [readLines] 로 읽고 캐시한다. 이 화면의 검출 문단이 아니면 그대로 돌려준다.
     * 한 번에 한 문단만 읽는다 — 엔진은 동시에 둘을 돌려도 빨라지지 않는다. 이미 읽은 문단은 잠금을 기다리지 않는다.
     *
     * [background] 는 번역 문맥으로 쓸 이웃 문단 읽기다(`.docs/vision-engine-design.md` §18). 포인터가 가리킨 문단 읽기(앞 읽기)가 오면
     * 다른 문단을 읽던 뒤 읽기는 멈추고 null 을 돌려준다 — 캐시하지 않으니 다음에 다시 읽는다. 같은 문단이면 멈추지 않고 그 결과를 함께 기다린다.
     * 예전에는 이웃 문단 둘을 다 읽을 때까지(문단당 1~1.7초) 새로 가리킨 문단이 잠금 앞에서 기다렸다.
     */
    internal suspend fun getOrRead(
        paragraph: Paragraph,
        background: Boolean = false,
        readLines: suspend (List<OcrLine>) -> Paragraph?,
    ): Paragraph? {
        val lines = sources[paragraph] ?: return paragraph
        if (read.containsKey(paragraph)) return read[paragraph]
        if (!background) {
            synchronized(this) { this.background?.takeIf { it.first !== paragraph }?.second }?.cancel()
            return readUnderLock(paragraph, lines, readLines)
        }
        return try {
            coroutineScope {
                val job = async { readUnderLock(paragraph, lines, readLines) }
                synchronized(this@UnreadParagraphs) { this@UnreadParagraphs.background = paragraph to job }
                try {
                    job.await()
                } finally {
                    synchronized(this@UnreadParagraphs) {
                        if (this@UnreadParagraphs.background?.second === job) this@UnreadParagraphs.background = null
                    }
                }
            }
        } catch (e: CancellationException) {
            // 부른 쪽이 취소됐으면 그대로 던지고, 앞 읽기에 양보한 것이면 읽지 못한 것으로 돌려준다
            currentCoroutineContext().ensureActive()
            null
        }
    }

    private suspend fun readUnderLock(
        paragraph: Paragraph,
        lines: List<OcrLine>,
        readLines: suspend (List<OcrLine>) -> Paragraph?,
    ): Paragraph? = mutex.withLock {
        if (read.containsKey(paragraph)) read[paragraph]
        else readLines(lines).also { read[paragraph] = it }
    }
}
