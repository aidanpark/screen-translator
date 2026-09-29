package com.galaxy.airviewdictionary.ocrbench

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Debug
import androidx.test.platform.app.InstrumentationRegistry
import com.galaxy.airviewdictionary.data.local.vision.VisionRepository
import com.galaxy.airviewdictionary.data.local.vision.kit.MlKitVisionKit
import com.galaxy.airviewdictionary.data.local.vision.kit.TextRecognizerType
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/**
 * ML Kit 인식기 하나하나의 값 — 성능 실험 P2·P4(`.docs/perf-experiment-plan.md`).
 * 한 프로세스에서 인식기를 TEXT → CHINESE → KOREAN → JAPANESE → DEVANAGARI 순으로 처음 올리며 첫 인식 시간(모델 적재 포함)과
 * 늘어난 메모리를 재고, 적재 뒤 인식 시간(중앙값)을 잰다. 끝으로 모두 닫은 뒤 메모리가 돌아오는지, auto 한 번이 얼마인지 본다.
 * 화면은 `cost_<언어>.png`(1080×2400)를 에셋에 잠깐 놓는다. 보고는 앱 외부 미디어의 `mlkit_cost.txt`.
 */
class MlKitCostTest {

    private fun pssMb(): Double = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss / 1024.0
    private fun nativeMb(): Double = Debug.getNativeHeapAllocatedSize() / 1048576.0
    private fun ms(t: Long) = (System.nanoTime() - t) / 1_000_000

    @Test
    fun measure() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val out = StringBuilder()
        fun log(line: String) { out.appendLine(line); android.util.Log.i("MlKitCost", line) }
        fun image(name: String): Bitmap = instrumentation.context.assets.open(name).use { BitmapFactory.decodeStream(it) }
        val screens = instrumentation.context.assets.list("")!!.filter { it.startsWith("cost_") && it.endsWith(".png") }.sorted()
        check(screens.isNotEmpty()) { "cost_<언어>.png 가 에셋에 없다" }
        val first = image(screens.first())

        System.gc(); Thread.sleep(500)
        log("시작\tPSS ${"%.0f".format(pssMb())}MB\t네이티브 ${"%.0f".format(nativeMb())}MB\t화면 ${screens.joinToString(",")}")

        // 인식기를 하나씩 올린다 — 첫 인식은 모델 적재를 포함한다
        val kits = TextRecognizerType.entries.map { MlKitVisionKit(it) }
        for (kit in kits) {
            val pss0 = pssMb(); val nat0 = nativeMb()
            var t = System.nanoTime(); kit.detect(first); val cold = ms(t)
            val warm = (1..5).map { t = System.nanoTime(); kit.detect(first); ms(t) }.sorted()[2]
            System.gc(); Thread.sleep(300)
            log("${kit.name}\t첫 인식 ${cold}ms\t이후 ${warm}ms\tPSS +${"%.0f".format(pssMb() - pss0)}MB\t네이티브 +${"%.0f".format(nativeMb() - nat0)}MB")
        }
        log("다섯 개 적재 뒤\tPSS ${"%.0f".format(pssMb())}MB\t네이티브 ${"%.0f".format(nativeMb())}MB")

        // 화면마다 인식기별 적재 뒤 시간 — auto 는 이것을 모두 직렬로 돈다
        for (name in screens) {
            val bmp = image(name)
            val each = kits.map { kit -> kit.name to (1..3).map { val t = System.nanoTime(); kit.detect(bmp); ms(t) }.sorted()[1] }
            log("$name\t" + each.joinToString("\t") { "${it.first} ${it.second}ms" } + "\t합 ${each.sumOf { it.second }}ms")
        }

        // auto 한 번(ML Kit 만 — 문맥 없는 VisionRepository 는 PP-OCRv5 를 쓰지 않는다)
        val repository = VisionRepository()
        for (name in screens) {
            val bmp = image(name)
            repository.read(bmp, "auto")
            val auto = (1..3).map { val t = System.nanoTime(); repository.read(bmp, "auto"); ms(t) }.sorted()[1]
            log("$name\tauto(ML Kit) ${auto}ms")
        }

        kits.forEach { it.closeRecognizer() }
        System.gc(); Thread.sleep(1000)
        log("모두 닫은 뒤\tPSS ${"%.0f".format(pssMb())}MB\t네이티브 ${"%.0f".format(nativeMb())}MB")

        val dir = instrumentation.targetContext.externalMediaDirs.first()
        File(dir, "mlkit_cost.txt").writeText(out.toString())
    }
}
