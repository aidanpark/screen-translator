package com.galaxy.airviewdictionary.ocrbench

import androidx.test.platform.app.InstrumentationRegistry
import com.galaxy.airviewdictionary.data.local.vision.VisionRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * [AutoPickDumpTest] 덤프의 후보 글마다 프로덕션 언어 감지(`VisionRepository.identifyLanguage`)를 돌려 붙인다
 * (`.docs/vision-engine-design.md` §11 R4). 입력은 앱 외부 미디어의 `autopick_in/`, 출력은 `autopick_lang/`.
 */
class AutoPickLangIdTest {

    @Test
    fun identifyCandidateLanguages() = runBlocking {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val base = appContext.externalMediaDirs.first()
        val input = File(base, "autopick_in")
        val output = File(base, "autopick_lang").apply { mkdirs() }
        val repository = VisionRepository()
        for (file in input.listFiles()!!.filter { it.name.endsWith(".json") }.sortedBy { it.name }) {
            val dump = JSONObject(file.readText())
            val variants = JSONArray()
            val vs = dump.getJSONArray("variants")
            for (i in 0 until vs.length()) {
                val langs = JSONObject()
                val cands = vs.getJSONObject(i).getJSONArray("candidates")
                for (j in 0 until cands.length()) {
                    val c = cands.getJSONObject(j)
                    val text = c.optString("text", "")
                    langs.put(c.getString("kit"), if (text.isBlank()) "und" else repository.identifyLanguage(text))
                }
                variants.put(JSONObject().put("kind", vs.getJSONObject(i).getString("kind")).put("lang", langs))
            }
            File(output, file.name).writeText(JSONObject().put("name", dump.getString("name")).put("variants", variants).toString())
        }
    }

    /**
     * 같은 덤프의 후보 **줄마다** 프로덕션 언어 감지를 붙인다 — 순차 멈춤의 라틴 멈춤 조건(`.docs/perf-experiment-plan.md` §4 A).
     * 출력은 `autopick_linelang/`: 후보마다 줄 순서대로 언어 코드 목록.
     */
    @Test
    fun identifyLineLanguages() = runBlocking {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val base = appContext.externalMediaDirs.first()
        val input = File(base, "autopick_in")
        val output = File(base, "autopick_linelang").apply { mkdirs() }
        val repository = VisionRepository()
        for (file in input.listFiles()!!.filter { it.name.endsWith(".json") }.sortedBy { it.name }) {
            val dump = JSONObject(file.readText())
            val variants = JSONArray()
            val vs = dump.getJSONArray("variants")
            for (i in 0 until vs.length()) {
                val langs = JSONObject()
                val cands = vs.getJSONObject(i).getJSONArray("candidates")
                for (j in 0 until cands.length()) {
                    val c = cands.getJSONObject(j)
                    val lines = c.optJSONArray("lines") ?: JSONArray()
                    val perLine = JSONArray()
                    for (k in 0 until lines.length()) {
                        val t = lines.getJSONObject(k).optString("t", "")
                        perLine.put(if (t.isBlank()) "und" else repository.identifyLanguage(t))
                    }
                    langs.put(c.getString("kit"), perLine)
                }
                variants.put(JSONObject().put("kind", vs.getJSONObject(i).getString("kind")).put("lines", langs))
            }
            File(output, file.name).writeText(JSONObject().put("name", dump.getString("name")).put("variants", variants).toString())
        }
    }
}
