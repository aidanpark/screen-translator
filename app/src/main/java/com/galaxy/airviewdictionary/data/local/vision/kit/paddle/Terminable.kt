package com.galaxy.airviewdictionary.data.local.vision.kit.paddle

import ai.onnxruntime.OrtSession
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [run] 을 이 스레드에서 돌리되, 코루틴이 취소되면 도는 추론을 멈춘다(ONNX Runtime `RunOptions.setTerminate`). [run] 은 받은
 * `RunOptions` 를 `OrtSession.run` 에 넘겨야 한다.
 *
 * 추론은 네이티브 호출이라 취소 확인(`ensureActive`)이 닿지 않는다. 없으면 취소된 문단 읽기도 이미 시작한 줄(동시에 넷)을 끝까지 읽고, 그동안
 * 다음 문단 읽기가 기다린다(`UnreadParagraphs` 는 한 번에 한 문단만 읽는다). 취소 가능한 continuation 은 만들 때 부모 Job 에 걸리므로, 이
 * 스레드가 막혀 도는 동안에도 취소하는 쪽 스레드에서 곧바로 멈춤 신호가 간다. 멈춘 추론은 예외로 끝나고 부르는 쪽에는 취소로 보인다.
 */
internal suspend fun <T> terminable(run: (OrtSession.RunOptions) -> T): T {
    val options = OrtSession.RunOptions()
    // 멈춤 신호와 닫기가 엇갈리지 않게 — 닫힌 RunOptions 에 신호를 보내면 예외가 취소 처리기에서 난다
    val lock = Any()
    var open = true
    try {
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                synchronized(lock) { if (open) runCatching { options.setTerminate(true) } }
            }
            continuation.resumeWith(runCatching { run(options) })
        }
    } finally {
        synchronized(lock) {
            open = false
            options.close()
        }
    }
}
