package com.galaxy.airviewdictionary.data.local.vision.kit.paddle

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * ONNX Runtime 세션. 모델마다 처음 쓸 때 한 번 만들어 앱 수명 동안 둔다 — 적재에 수백 ms 가 든다.
 * 검출기 세션은 문자권 인식기들이 함께 쓴다. `OrtSession.run` 은 여러 스레드에서 불러도 된다.
 *
 * **ONNX Runtime 은 첫 세션을 만들 때까지 건드리지 않는다**(`.docs/vision-engine-design.md` §22). 환경을 만들 때 네이티브 라이브러리가 올라가는데,
 * 그 실패는 `UnsatisfiedLinkError`·`ExceptionInInitializerError`(Error)로 온다. 끄기 스위치(§19)가 꺼져 있으면 만들지 않고, 환경이나 세션을
 * 만들다 무엇이든 던지면 그 모델(환경이면 전부)을 이 프로세스 동안 쓰지 않는다 — [has] 가 false 가 되어 ML Kit 으로 떨어진다.
 */
internal class PaddleSessions(private val files: PaddleModelFiles) {

    @Volatile
    private var environment: OrtEnvironment? = null

    /** 환경을 만들다 실패했다. 이 프로세스에서는 다시 시도하지 않는다. */
    @Volatile
    private var unavailable = false

    /** 파일은 있는데 세션을 만들다 실패한 모델. 매번 모델을 다시 읽어 다시 실패하지 않도록 기억한다. */
    private val failed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val sessions = ConcurrentHashMap<String, OrtSession>()

    /** 세션을 받은 뒤에만 부른다 — 그때는 환경이 있다. */
    val env: OrtEnvironment get() = checkNotNull(environment) { "ONNX Runtime 환경이 아직 없다" }

    /** [model] 을 이 프로세스에서 쓸 수 없는가 — 환경이나 그 모델의 세션을 만들다 실패했다. ONNX Runtime 을 건드리지 않는다. */
    fun broken(model: String): Boolean = unavailable || model in failed

    /** [model] 을 쓸 수 있는가 — 실패한 적이 없고 파일이 있다. ONNX Runtime 을 건드리지 않는다. */
    fun has(model: String): Boolean = !broken(model) && (sessions.containsKey(model) || files.has(model))

    /**
     * 모델이 아직 없으면(팩을 받는 중), 스위치가 꺼져 있으면, 환경이나 세션을 만들 수 없으면 null. [keepArena] 는 CPU 아레나를 둘지 —
     * 큰 인식 모델은 끄지만(§20) 작은 문자 판별기는 켜 둔다(끄면 S26 에서 판별이 20ms 가량 느렸다, 성능 P7). [threads] 는 한 번의 추론이 쓰는 스레드 수다 —
     * 검출은 한 장을 4스레드로, 인식은 줄 여럿을 동시에 2스레드씩 돌리는 편이 빠르다(실측 §12). [spinning] 이 false 면 추론 스레드가 일이 끝난 뒤
     * 기다리며 CPU 를 돌지 않는다. [key] 는 세션을 기억하는 이름이다 — 같은 모델을 다른 설정으로 견주는 기기 시험만 바꾼다.
     */
    fun session(model: String, threads: Int, keepArena: Boolean = arena, spinning: Boolean = true, key: String = model): OrtSession? {
        sessions[key]?.let { return it }
        synchronized(this) {
            sessions[key]?.let { return it }
            if (broken(model)) return null
            val bytes = files.read(model) ?: return null
            val env = environment() ?: return null
            return try {
                OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(threads)
                    if (!spinning) options.addConfigEntry("session.intra_op.allow_spinning", "0")
                    // CPU 아레나는 추론 중 쓴 작업 메모리를 세션마다 최대치로 붙잡아 둔다 — 세션 넷이면 ~500MB 가 늘 차 있다(§20).
                    if (!keepArena) {
                        options.setCPUArenaAllocator(false)
                        options.setMemoryPatternOptimization(false)
                    }
                    env.createSession(bytes, options)
                }.also { sessions[key] = it }
            } catch (t: Throwable) {
                failed += model
                Timber.tag(TAG).e(t, "PP-OCRv5 세션을 만들지 못했다: $model — 이 프로세스에서는 이 모델을 쓰지 않는다")
                null
            }
        }
    }

    /** 환경을 처음 부를 때 만든다. 스위치가 꺼져 있으면 만들지 않는다(다음에 켜지면 만든다). */
    private fun environment(): OrtEnvironment? {
        environment?.let { return it }
        if (unavailable || !PaddleSwitch.enabled) return null
        return try {
            OrtEnvironment.getEnvironment().also { environment = it }
        } catch (t: Throwable) {
            unavailable = true
            Timber.tag(TAG).e(t, "ONNX Runtime 을 올리지 못했다 — 이 프로세스에서는 PP-OCRv5 를 쓰지 않는다")
            null
        }
    }

    fun text(name: String): String? = files.read(name)?.toString(Charsets.UTF_8)

    internal companion object {
        private const val TAG = "PaddleSessions"

        /** CPU 아레나를 쓰는가(§20). 새 세션부터 적용된다 — 기기 비교 시험이 바꾼다. */
        @Volatile
        var arena = false
    }
}
