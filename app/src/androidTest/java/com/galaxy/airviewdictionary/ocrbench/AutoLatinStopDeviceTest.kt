package com.galaxy.airviewdictionary.ocrbench

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Debug
import androidx.test.platform.app.InstrumentationRegistry
import com.galaxy.airviewdictionary.data.local.vision.AutoLatinStop
import com.galaxy.airviewdictionary.data.local.vision.VisionRepository
import com.galaxy.airviewdictionary.ui.screen.overlay.selection.createOverlaidBitmap
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * 성능 P4-2 의 판정 3(`.docs/perf-experiment-plan.md` §5) — 앱에 넣은 라틴 멈춤이 오프라인 계산(`stop2.py`)과 같은지, 그리고 얼마나 빨라졌나.
 * 화면은 앱 외부 미디어의 `autopick_png/real_<이름>.png`(AutoPickDumpTest 와 같은 셋 — 전체·잘라내기 둘, 같은 난수).
 */
class AutoLatinStopDeviceTest {

    private val appContext get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val base get() = appContext.externalMediaDirs.first()

    /**
     * 화면 PNG 폴더. Android 16 에서는 adb 가 앱 외부 미디어에 올린 파일을 앱이 읽지 못한다 — 그때는 `run-as` 로 앱 내부 `files/autopick_png/` 에
     * 복사해 둔다(디버그 빌드). 거기가 있으면 먼저 쓴다.
     */
    private val pngDir get() = File(appContext.filesDir, "autopick_png").takeIf { it.isDirectory } ?: File(base, "autopick_png")

    private fun names(prefix: String): List<String> = pngDir.list()!!.filter { it.startsWith(prefix) && it.endsWith(".png") }
        .map { it.removePrefix("real_").removeSuffix(".png") }.sorted()

    private fun screen(name: String): Bitmap = BitmapFactory.decodeFile(File(pngDir, "real_$name.png").path)
        ?: error("real_$name.png 를 읽지 못했다 — ${pngDir.path}")

    /** AutoPickDumpTest.cropRect 와 같다 — 덤프와 같은 영역이 나와야 오프라인 계산과 견줄 수 있다. */
    private fun cropRect(screen: Bitmap, random: Random): Rect {
        val w = (screen.width * random.nextDouble(0.40, 0.95)).toInt()
        val h = (screen.height * random.nextDouble(0.12, 0.35)).toInt()
        val left = random.nextInt(0, screen.width - w + 1)
        val top = random.nextInt(200, maxOf(201, screen.height - h - 150))
        return Rect(left, top, left + w, top + h)
    }

    /** 표본마다 앱의 auto 판정(고른 엔진·라틴 멈춤·PP 승리)을 `autopick_stopcheck/` 에 떨어뜨린다. */
    @Test
    fun dumpDecisions() = runBlocking {
        AutoLatinStop.enabled = true
        val repository = VisionRepository(appContext)
        val out = File(base, "autopick_stopcheck").apply { mkdirs() }
        for (name in names("real_")) {
            if (name.startsWith("cost_") || File(out, "$name.json").exists()) continue
            val screen = screen(name)
            val random = Random(name.hashCode())
            val variants = JSONArray()
            for (kind in listOf("full", "crop1", "crop2")) {
                val rect = if (kind == "full") null else cropRect(screen, random)
                val image = rect?.let { createOverlaidBitmap(screen, it) } ?: screen
                val v = JSONObject().put("kind", kind)
                try {
                    val d = repository.detect(image, "auto")
                    v.put("kit", d.kit.name).put("latinStopped", d.latinStopped).put("paddleWon", d.paddleWon)
                } catch (e: Exception) {
                    v.put("error", e.message ?: e.javaClass.simpleName)
                }
                variants.put(v)
                if (image !== screen) image.recycle()
            }
            File(out, "$name.json").writeText(JSONObject().put("name", name).put("variants", variants).toString())
            screen.recycle()
        }
    }

    /** 대표 화면(`real_cost_*.png`, 1080×2400)에서 auto 한 번(검출·고르기 + 남은 줄 읽기)의 시간을 예전 흐름과 새 흐름으로 잰다. */
    @Test
    fun measureAutoTime() = runBlocking {
        val repository = VisionRepository(appContext)
        val out = StringBuilder()
        fun log(line: String) { out.appendLine(line); android.util.Log.i("AutoLatinStop", line) }
        fun pssMb() = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss / 1024
        // -e flows off 또는 on 으로 한 흐름만 새 프로세스에서 돌리면 끝의 PSS 가 그 흐름의 메모리다(예전 흐름은 인식기 다섯을 모두 올린다)
        val flows = when (InstrumentationRegistry.getArguments().getString("flows")) {
            "off" -> listOf(false)
            "on" -> listOf(true)
            else -> listOf(false, true)
        }
        for (name in names("real_cost_")) {
            val image = screen(name)
            val row = StringBuilder(name)
            for (enabled in flows) {
                AutoLatinStop.enabled = enabled
                repository.read(image, "auto") // 적재·예열
                val times = (1..5).map { val t = System.nanoTime(); repository.read(image, "auto"); (System.nanoTime() - t) / 1_000_000 }.sorted()
                val d = repository.detect(image, "auto")
                row.append("\t${if (enabled) "새" else "예전"} ${times[2]}ms(${d.kit.name}${if (d.latinStopped) ", 라틴 멈춤" else ""})")
            }
            log(row.toString())
            image.recycle()
        }
        AutoLatinStop.enabled = true
        System.gc(); Thread.sleep(500)
        log("흐름 ${flows.joinToString { if (it) "새" else "예전" }} 뒤 PSS ${pssMb()}MB")
        File(base, "autolatin_time_${InstrumentationRegistry.getArguments().getString("flows") ?: "both"}.txt").writeText(out.toString())
    }
}
