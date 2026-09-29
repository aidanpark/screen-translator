package com.galaxy.airviewdictionary.data.local.vision.kit.paddle

import android.graphics.Bitmap
import androidx.lifecycle.Lifecycle
import com.galaxy.airviewdictionary.data.local.vision.kit.VisionKit
import com.galaxy.airviewdictionary.data.local.vision.ocr.OcrBlock
import com.galaxy.airviewdictionary.data.local.vision.ocr.OcrLine
import com.galaxy.airviewdictionary.data.local.vision.ocr.OcrText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * PP-OCRv5(ONNX Runtime) 인식 엔진 하나 — 검출기는 문자권 공용, 인식기는 문자권마다.
 *
 * ML Kit 과 달리 검출과 인식을 나눈다(`.docs/vision-engine-design.md` §3.4). [detect] 는 줄 상자만 찾아 읽지 않은 줄(`words == null`)로
 * 주고, [recognize] 가 필요한 줄만 읽는다. 덩어리가 없는 엔진이라 줄마다 덩어리 하나로 준다(`OcrText` 계약 3).
 */
class PaddleOcrVisionKit internal constructor(
    override val name: String,
    private val detector: PaddleDetector,
    private val recognizer: PaddleRecognizer,
) : VisionKit {

    override val linesFromDetector: Boolean get() = true

    @Volatile
    private var ready = false

    /**
     * 모델이 다 있고 쓸 수 있는가. 팩을 아직 받는 중이거나, ONNX Runtime·세션을 만들다 실패했으면 false — 고르기 정책이 ML Kit 으로 떨어뜨린다(§22).
     * 파일은 한 번 갖춰지면 다시 묻지 않는다. ONNX Runtime 을 건드리지 않는다.
     */
    fun isReady(): Boolean {
        if (detector.broken() || recognizer.broken()) return false
        return ready || (detector.isReady() && recognizer.isReady()).also { ready = it }
    }

    override suspend fun detect(screen: Bitmap): OcrText = withContext(Dispatchers.Default) {
        // 검출한 줄은 이 엔진이 읽는다 — 인식기 세션까지 먼저 만든다. 못 만들면 검출 전에 실패해, 부르는 쪽이 이 화면을 ML Kit 으로 다시 검출한다
        check(detector.prepare() && recognizer.prepare()) { "$name 세션을 만들 수 없다" }
        val boxes = detector.detect(screen)
        OcrText("", boxes.map { box -> OcrBlock(box, listOf(OcrLine(box, "", null, null))) })
    }

    /**
     * 줄 넷을 동시에 읽는다(세션 스레드 2) — 한 줄씩(스레드 4)보다 문단 한 개가 1.85배 빠르다(실측 §12). 순서는 그대로다.
     * 취소되면 아직 시작하지 않은 줄은 건너뛰고 도는 줄의 추론은 멈춘다([terminable]).
     */
    override suspend fun recognize(screen: Bitmap, lines: List<OcrLine>): List<OcrLine> = withContext(readers) {
        coroutineScope {
            lines.map { line ->
                async {
                    // 포인터가 다음 문단으로 가면 읽기가 취소된다 — 줄마다 확인한다
                    ensureActive()
                    val box = line.boundingBox
                    when {
                        line.words != null -> line
                        box == null || box.isEmpty -> line.copy(words = emptyList())
                        else -> terminable { options -> recognizer.read(screen, box, options) }
                    }
                }
            }.awaitAll()
        }
    }

    /** 세션은 앱 수명 동안 둔다. 묶을 자원이 없다. */
    override fun addObserver(lifecycle: Lifecycle) = Unit

    private companion object {
        @OptIn(ExperimentalCoroutinesApi::class)
        val readers = Dispatchers.Default.limitedParallelism(4)
    }
}
