package com.galaxy.airviewdictionary.ocrbench

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.BitmapFactory
import com.galaxy.airviewdictionary.data.local.vision.kit.paddle.ScriptGate
import java.nio.FloatBuffer
import androidx.test.platform.app.InstrumentationRegistry
import com.galaxy.airviewdictionary.data.local.vision.VisionRepository
import com.galaxy.airviewdictionary.data.local.vision.kit.paddle.PaddleKits
import com.galaxy.airviewdictionary.data.local.vision.kit.paddle.PaddleModelFiles
import com.galaxy.airviewdictionary.data.local.vision.model.VisionResponse
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * 성능 P7(`.docs/perf-experiment-plan.md` §7) — 기기의 관문 판정이 파이썬 평가(`tools/scriptid/gate_parity.py`)와 같은지, 판별에 얼마나 걸리는지.
 * 화면은 앱 내부 `files/gate_png/real_<이름>.png`(폰 화면 비율로 자른 새 holdout 80면, `run-as` 로 복사한다).
 * 결과: 외부 미디어 `gate_device.json` — 이름마다 관문 판정(gate), auto 판정 전체의 관문 결과(e2e), 검출 · 판별 시간(ms).
 */
class ScriptGateDeviceTest {

    private val appContext get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun dumpGate() = runBlocking {
        val dir = File(appContext.filesDir, "gate_png")
        val paddle = PaddleKits(PaddleModelFiles(appContext))
        val repository = VisionRepository(appContext)
        val out = JSONObject()
        val files = dir.listFiles { f -> f.name.startsWith("real_") && f.name.endsWith(".png") }!!.sortedBy { it.name }
        // 세션 적재를 시간에서 빼려고 한 번 먼저 돌린다
        BitmapFactory.decodeFile(files.first().path)?.let { warm -> paddle.autoDetect(warm)?.let { paddle.scriptGate(warm, it) } }
        for (file in files) {
            val name = file.name.removePrefix("real_").removeSuffix(".png")
            val screen = BitmapFactory.decodeFile(file.path) ?: error("읽지 못했다: $file")
            val t0 = System.nanoTime()
            val detected = paddle.autoDetect(screen)
            val t1 = System.nanoTime()
            val verdict = detected?.let { paddle.scriptGate(screen, it) }
            val t2 = System.nanoTime()
            val e2e = repository.detect(screen, "auto").unsupportedScript
            out.put(name, JSONObject()
                .put("gate", verdict?.script ?: JSONObject.NULL)
                .put("e2e", e2e?.script ?: JSONObject.NULL)
                .put("detectMs", (t1 - t0) / 1_000_000)
                .put("gateMs", (t2 - t1) / 1_000_000))
            screen.recycle()
        }
        File(appContext.externalMediaDirs.first(), "gate_device.json").writeText(out.toString(1))
    }

    /**
     * 판별 시간을 전처리와 추론으로 나누고, 추론 스레드 수와 XNNPACK 가속을 견준다(같은 화면 20면, 화면마다 넓은 줄 12개).
     * 결과: 외부 미디어 `gate_bench.txt`.
     */
    @Test
    fun benchmark() = runBlocking {
        val dir = File(appContext.filesDir, "gate_png")
        val paddle = PaddleKits(PaddleModelFiles(appContext))
        val files = dir.listFiles { f -> f.name.startsWith("real_") && f.name.endsWith(".png") }!!.sortedBy { it.name }.take(20)
        val inputs = ArrayList<Pair<Int, FloatBuffer>>()
        val prep = ArrayList<Long>()
        for (file in files) {
            val screen = BitmapFactory.decodeFile(file.path) ?: continue
            val detected = paddle.autoDetect(screen) ?: continue
            val boxes = detected.lines.mapNotNull { l -> l.boundingBox?.let { intArrayOf(it.left, it.top, it.right, it.bottom) } }
            val lines = ScriptGate.pickLines(boxes, screen.width)
            val t0 = System.nanoTime()
            val batch = FloatBuffer.allocate(lines.size * ScriptGate.H * ScriptGate.W)
            for (box in lines) {
                val c = ScriptGate.cropRect(box, screen.width, screen.height)
                val cw = c[2] - c[0]; val ch = c[3] - c[1]
                val crop = IntArray(cw * ch)
                screen.getPixels(crop, 0, cw, c[0], c[1], cw, ch)
                batch.put(ScriptGate.cropInput(crop, cw, ch))
            }
            batch.rewind()
            prep += (System.nanoTime() - t0) / 1_000_000
            inputs += lines.size to batch
            screen.recycle()
        }
        val model = appContext.assets.open("paddle/${ScriptGate.MODEL}").use { it.readBytes() }
        val env = OrtEnvironment.getEnvironment()
        val report = StringBuilder("전처리 중앙값 ${prep.sorted()[prep.size / 2]}ms (화면 ${prep.size})\n")
        for (xnn in listOf(false, true)) for (threads in listOf(1, 2, 4)) {
            val session = OrtSession.SessionOptions().use { o ->
                o.setIntraOpNumThreads(threads)
                if (xnn) o.addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
                env.createSession(model, o)
            }
            fun run(n: Int, buf: FloatBuffer) {
                buf.rewind()
                OnnxTensor.createTensor(env, buf, longArrayOf(n.toLong(), 1, ScriptGate.H.toLong(), ScriptGate.W.toLong())).use { t ->
                    session.run(mapOf("x" to t)).close()
                }
            }
            inputs.first().let { (n, b) -> repeat(3) { run(n, b) } }
            val times = inputs.map { (n, b) -> val t = System.nanoTime(); run(n, b); (System.nanoTime() - t) / 1_000_000 }.sorted()
            report.append("추론 xnnpack=$xnn threads=$threads: 중앙값 ${times[times.size / 2]}ms 최대 ${times.last()}ms\n")
            session.close()
        }
        File(appContext.externalMediaDirs.first(), "gate_bench.txt").writeText(report.toString())
    }

    /**
     * 조건 4 — 대표 화면(`files/autopick_png/real_cost_*.png`, 1080×2400)에서 auto 판정(`detect`) 한 번의 시간을 관문을 켠 채와 끈 채로 잰다.
     * 발열 · 순서의 치우침을 빼려고 한 프로세스에서 끔 · 켬을 번갈아 10쌍(화면마다 예열 한 번), 각각 중앙값. 결과: 외부 미디어 `gate_time.txt`.
     */
    @Test
    fun measureGateTime() = runBlocking {
        val repository = VisionRepository(appContext)
        val dir = File(appContext.filesDir, "autopick_png")
        val out = StringBuilder()
        for (file in dir.listFiles { f -> f.name.startsWith("real_cost_") }!!.sortedBy { it.name }) {
            val image = BitmapFactory.decodeFile(file.path) ?: continue
            // 관문은 auto 판정 단계(detect)에 든다 — 관문이 개입한 화면은 글을 읽지 않으므로 read 가 아니라 detect 를 잰다
            ScriptGate.enabled = true
            repository.detect(image, "auto")
            val off = ArrayList<Long>(); val on = ArrayList<Long>()
            repeat(20) { i ->
                ScriptGate.enabled = i % 2 == 1
                val t = System.nanoTime(); repository.detect(image, "auto")
                (if (ScriptGate.enabled) on else off) += (System.nanoTime() - t) / 1_000_000
            }
            ScriptGate.enabled = true
            val gated = repository.detect(image, "auto").unsupportedScript?.script
            off.sort(); on.sort()
            out.appendLine("${file.name.removePrefix("real_").removeSuffix(".png")}\toff ${(off[4] + off[5]) / 2}ms\ton ${(on[4] + on[5]) / 2}ms${gated?.let { " (관문: $it)" } ?: ""}")
            image.recycle()
        }
        File(appContext.externalMediaDirs.first(), "gate_time.txt").writeText(out.toString())
    }
    /** 판별 시간이 검출 직후의 CPU 다툼 때문인지 — 검출 바로 뒤(A), 100ms 쉰 뒤(B), 그 바로 다음 한 번 더(C). 결과: `gate_split.txt`. */
    @Test
    fun splitGateTime() = runBlocking {
        val dir = File(appContext.filesDir, "gate_png")
        val paddle = PaddleKits(PaddleModelFiles(appContext))
        val files = dir.listFiles { f -> f.name.startsWith("real_") && f.name.endsWith(".png") }!!.sortedBy { it.name }.take(30)
        BitmapFactory.decodeFile(files.first().path)?.let { warm -> paddle.autoDetect(warm)?.let { paddle.scriptGate(warm, it) } }
        val a = ArrayList<Long>(); val b = ArrayList<Long>(); val c = ArrayList<Long>()
        fun ms(t: Long) = (System.nanoTime() - t) / 1_000_000
        for (file in files) {
            val screen = BitmapFactory.decodeFile(file.path) ?: continue
            val detected = paddle.autoDetect(screen) ?: continue
            var t = System.nanoTime(); paddle.scriptGate(screen, detected); a += ms(t)
            Thread.sleep(100)
            t = System.nanoTime(); paddle.scriptGate(screen, detected); b += ms(t)
            t = System.nanoTime(); paddle.scriptGate(screen, detected); c += ms(t)
            screen.recycle()
        }
        fun med(x: List<Long>) = x.sorted()[x.size / 2]
        File(appContext.externalMediaDirs.first(), "gate_split.txt").writeText("A ${med(a)}ms  B ${med(b)}ms  C ${med(c)}ms (화면 ${a.size})\n")
    }
}
