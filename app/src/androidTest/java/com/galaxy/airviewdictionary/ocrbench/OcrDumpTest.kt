package com.galaxy.airviewdictionary.ocrbench

import com.galaxy.airviewdictionary.data.local.vision.ParagraphAssembler
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.galaxy.airviewdictionary.data.local.vision.VisionRepository
import com.galaxy.airviewdictionary.data.local.vision.ocr.OcrLine
import com.galaxy.airviewdictionary.data.local.vision.ocr.OcrText
import com.galaxy.airviewdictionary.data.local.vision.toLine
import com.galaxy.airviewdictionary.data.local.vision.model.Word
import com.galaxy.airviewdictionary.data.remote.translation.Language
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * 표본 PNG 를 한 번만 OCR 해서, **프로덕션이 기하 단계에 넣는 입력**을 JSON 으로 떨어뜨린다.
 *
 * 조립기는 비트맵을 보지 않으므로 튜닝에 PNG 가 필요 없다. 박스만 남기면 조립 결과가 원본과
 * 같고, 설정 비교가 완전히 결정적이 된다.
 *
 * 두 해석을 **모두** 저장한다. 세로쓰기 여부는 프로덕션이 OCR 결과로 판정하는데(가로로 긴 줄
 * 수와 아닌 줄 수를 비교), 페이지의 정답과 다를 수 있다. 그래서 어느 쪽으로 판정되든 재현할 수
 * 있게 둘 다 남기고, 프로덕션 판정도 함께 기록한다 — 판정 정확도를 전 표본에서 잴 수 있다.
 *
 *  - `w_<이름>.json` 가로 경로 입력: 단어 배열. `textToParagraphs` 가 만드는 것과 같다.
 *    LTR 과 RTL 은 단어 **집합**이 같고(순서만 다르고 조립기가 어차피 다시 정렬한다) 그래서
 *    하나로 충분하다.
 *  - `v_<이름>.json` 세로 경로 입력: `textToVerticalParagraphs` 가 만드는 것과 같다. 세로로 긴
 *    ML Kit 줄은 `toLine()` 으로 **줄 구조째** 넘어가고(단어로 흩으면 세로 경로를 재현할 수
 *    없다), 가로로 긴 줄은 가로 경로 단어가 된다. 프로덕션 판정(`detectedVertical`)도 여기 있다.
 *
 * 결과는 AGP 가 `app/build/outputs/connected_android_test_additional_output` 밑으로 가져다
 * 놓는다. 앱의 외부 디렉터리는 테스트 뒤 APK 와 함께 지워지므로 그 자리에 쓴다.
 */
class OcrDumpTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().context
    private val appContext get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun dumpWordsForAllSamples() {
        val repository = VisionRepository()
        val given = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val outDir = File(given ?: appContext.externalMediaDirs.first().path, "worddump")
            .apply { mkdirs() }
        val assets = context.assets.list("")!!.toList()
        val names = assets.filter { it.startsWith("real_") && it.endsWith(".png") }
            .map { it.removePrefix("real_").removeSuffix(".png") }
            .sorted()

        var done = 0
        for (name in names) {
            val code = name.trimEnd('0', '1', '2', '3', '4', '5', '6', '7', '8', '9')
            if (code !in MlKitOcr.latin && code !in MlKitOcr.nonLatin) {
                log("$name 건너뜀 — '$code' 는 ML Kit 인식기가 없는 문자다"); continue
            }
            val bytes = context.assets.open("real_$name.png").use { it.readBytes() }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val text = try {
                runBlocking { repository.read(bitmap, code) }
            } catch (e: Exception) {
                log("$name OCR 실패 ${e.message}"); bitmap.recycle(); continue
            }

            val horizontal = horizontalInput(repository, bitmap, text.lines, code)
            File(outDir, "w_$name.json").writeText(wordsJson(horizontal).toString())

            val vertical = verticalInput(repository, bitmap, text, code)
            File(outDir, "v_$name.json").writeText(vertical.toString())

            bitmap.recycle()
            done++
            log("$name 단어 ${horizontal.size}개, 세로판정 ${vertical.getBoolean("detectedVertical")}," +
                    " 세로줄 ${vertical.getJSONArray("verticalLines").length()}개")
        }
        log("덤프 완료 $done/${names.size} → ${outDir.absolutePath}")
    }

    /** `textToParagraphs` 가 groupWordsIntoLines 에 넣는 단어. */
    private fun horizontalInput(
        repository: VisionRepository,
        bitmap: Bitmap,
        lines: List<OcrLine>,
        code: String,
    ): List<Word> {
        val direction = Language.writingDirection(code, false)
        return ParagraphAssembler.ocrLinesToWords(bitmap, lines, direction)
    }

    /**
     * `textToVerticalParagraphs` 가 기하 단계에 넣는 입력.
     *
     * 프로덕션은 한 화면의 줄을 종횡비로 둘로 나눈다 — 세로로 긴 줄은 `toLine()` 으로 바로
     * Line 이 되어 세로 경로로, 나머지는 단어로 흩어져 가로 경로로 간다. 그 분할을 여기서
     * 그대로 한다.
     */
    private fun verticalInput(
        repository: VisionRepository,
        bitmap: Bitmap,
        text: OcrText,
        code: String,
    ): JSONObject {
        val ttb = Language.writingDirection(code, true)
        val textLines = text.blocks
            .flatMap { block -> block.lines.filter { it.boundingBox != null } }
            .sortedWith { a, b ->
                val right = b.boundingBox!!.right.compareTo(a.boundingBox!!.right)
                if (right != 0) right else a.boundingBox!!.top.compareTo(b.boundingBox!!.top)
            }
        val tall = textLines.filter { it.boundingBox!!.height() > it.boundingBox!!.width() }
        val wide = textLines.filter { it.boundingBox!!.height() <= it.boundingBox!!.width() }

        val verticalLines = JSONArray()
        for (line in tall) {
            // 기호 박스가 없는 요소가 있으면 프로덕션도 여기서 멈춘다(`_toWord` 의 `!!`).
            // 표본 하나 때문에 덤프 전체가 죽지 않게 그 줄만 건너뛰고 센다.
            val built = try {
                line.toLine(ttb)
            } catch (e: NullPointerException) {
                log("  세로줄 하나 건너뜀 — 기호 박스 없음"); continue
            }
            verticalLines.put(JSONObject().put("words", wordsJson(built.words)))
        }
        val horizontalWords = horizontalInput(repository, bitmap, wide, code)

        // 세로쓰기 판정(`detectVerticalWriting`)이 보는 입력 그대로 — ML Kit 줄마다 상자와 글자.
        // 판정 규칙을 바꿀 때 전 표본에서 기기 없이 재 볼 수 있게 남긴다.
        val mlkitLines = JSONArray()
        for (block in text.blocks) for (line in block.lines) {
            val box = line.boundingBox ?: continue
            mlkitLines.put(JSONObject().put("l", box.left).put("t", box.top).put("r", box.right)
                .put("b", box.bottom).put("text", line.text))
        }

        return JSONObject()
            .put("detectedVertical", ParagraphAssembler.detectVerticalWriting(text))
            .put("mlkitLines", mlkitLines)
            .put("direction", ttb.name)
            .put("width", bitmap.width)
            .put("height", bitmap.height)
            .put("verticalLines", verticalLines)
            .put("horizontalWords", wordsJson(horizontalWords))
    }

    private fun wordsJson(words: List<Word>) = JSONArray().apply {
        for (word in words) put(
            JSONObject()
                .put("l", word.boundingBox.left)
                .put("t", word.boundingBox.top)
                .put("r", word.boundingBox.right)
                .put("b", word.boundingBox.bottom)
                .put("text", word.representation)
        )
    }

    private fun log(message: String) = android.util.Log.i("OcrDump", message)
}
