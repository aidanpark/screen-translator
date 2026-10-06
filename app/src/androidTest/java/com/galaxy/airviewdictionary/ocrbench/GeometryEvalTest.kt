package com.galaxy.airviewdictionary.ocrbench

import com.galaxy.airviewdictionary.data.local.vision.ParagraphAssembler
import androidx.test.platform.app.InstrumentationRegistry
import com.galaxy.airviewdictionary.data.local.vision.VisionRepository
import org.junit.Test
import java.io.File

/**
 * 기하 조립기 평가. 이것은 테스트가 아니라 **평가**다 — 단정이 없고 통과/실패가 없다.
 * "이 동작이 맞나" 가 아니라 "얼마나 좋나" 를 묻는다. 회귀 단정은
 * [GeometryRegressionTest] 에 있다.
 *
 * 절차와 함정은 `.docs/geometry-assembly-evaluation.md` 와 `ocr-eval` 스킬에 있다.
 * 표본 분류는 에셋의 `classes.tsv` 에서 읽는다.
 *
 * 돌리는 법:
 *
 *     ./gradlew :app:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=com.galaxy.airviewdictionary.ocrbench.GeometryEvalTest \
 *       -Pandroid.testInstrumentationRunnerArguments.sweep='기준;느슨=indent:0.5,guard:0.95'
 *
 * 인자(모두 생략 가능):
 *   sweep   설정 목록. 문법은 [EvalSetting]. 생략하면 출시값만 잰다
 *   groups  볼 갈래를 콤마로. 생략하면 1라운드 갈래(web, book-*, layout, vertical)뿐이다 —
 *           새 갈래는 **반드시 적어야 한다.** `tools/ocrsamples/run_eval.py` 가 이를 강제한다
 *   roles   tune | holdout. 생략하면 둘 다
 *   detail  1 이면 표본별 수치도 낸다(`ROW`, `OM` 줄)
 *
 * 출력 머리에 시험 이름, 인자, `SAMPLES` 줄(면 수와 이름)이 붙는다. `run_eval.py` 는 이 면 수를
 * `classes.tsv` 에서 기대한 수와 맞춰 본다 — 에셋이 없는 표본은 조용히 빠지기 때문이다.
 *
 * 결과는 파일로 낸다 — logcat 은 프로덕션 Timber 로그에 밀려 앞줄이 사라진다
 * (실측: 7줄 중 앞 2줄 유실). AGP 가 그 디렉터리를 호스트로 가져간다.
 */
class GeometryEvalTest {

    private val args get() = InstrumentationRegistry.getArguments()

    private val report: File by lazy {
        val dir = File(
            args.getString("additionalTestOutputDir")
                ?: InstrumentationRegistry.getInstrumentation().targetContext
                    .externalMediaDirs.first().path
        ).apply { mkdirs() }
        // JUnit 은 테스트 메서드마다 인스턴스를 새로 만든다. 매번 비우면 마지막 것만 남는다.
        File(dir, "eval.txt").apply { if (opened.compareAndSet(false, true)) writeText("") }
    }

    private fun log(message: String) {
        android.util.Log.i("GeometryEval", message)
        report.appendText(message + "\n")
    }

    /** 원자료만 보고도 무엇을 어떻게 잰 것인지 알 수 있게 머리에 적는다. */
    private fun header(test: String, samples: List<Sample>) {
        log("시험 $test")
        log("인자 " + listOf("sweep", "groups", "roles", "top", "detail")
            .mapNotNull { key -> args.getString(key)?.let { "$key=$it" } }.joinToString(" "))
        log("SAMPLES\t${samples.size}\t${samples.joinToString(",") { it.name }}")
    }

    @Test
    fun evaluate() {
        val settings = EvalSetting.parse(args.getString("sweep"))
        val groups = args.getString("groups")?.split(",")?.map { it.trim() }?.toSet()
            ?: setOf("web", "book-justified", "book-ragged", "layout", "vertical")
        val roles = args.getString("roles")?.split(",")?.map { it.trim() }?.toSet()
        val detail = args.getString("detail") == "1"

        val repository = VisionRepository()
        val samples = EvalSamples.load(
            InstrumentationRegistry.getInstrumentation().context, groups, roles
        )
        header("evaluate", samples)
        log("표본 ${samples.size}면, 설정 ${settings.size}개")
        log("갈래: " + samples.groupingBy { it.group }.eachCount())

        // 갈래 × 역할 × 설정 별로 모은다. 역할을 나누는 것이 이 평가의 핵심 규율이다 —
        // 상수를 고른 표본으로 검증하면 과적합을 못 본다.
        val totals = linkedMapOf<Triple<String, String, String>, Score>()
        for (sample in samples) {
            for (setting in settings) {
                val score = EvalMetrics.score(EvalMetrics.run(repository, sample, setting), sample)
                totals.getOrPut(Triple(sample.group, sample.role, setting.label)) { Score() } += score
                // 칸 순서는 뒤에 덧붙이기만 한다 — 원자료를 읽는 sheet.py 가 칸 위치로 읽는다.
                if (detail) log(
                    "ROW\t${setting.label}\t${sample.name}\t${sample.group}\t${sample.role}" +
                            "\t${score.grouped}\t${score.expected}\t${score.dirty}" +
                            "\t${score.clean}\t${score.blocks}\t${score.sibling}" +
                            "\t${sample.direction.name.lowercase()}\t${sample.input.name.lowercase()}" +
                            "\t${score.orderedTotal - score.ordered}\t${score.orderedTotal}"
                )
            }
        }

        log("")
        log("갈래".padEnd(16) + "역할".padEnd(9) + "설정".padEnd(20) +
                "온전한 문단".padEnd(17) + "오염".padEnd(7) + "묶임".padEnd(20) + "순서 위반")
        for ((key, score) in totals) {
            val (group, role, label) = key
            log(
                group.padEnd(16) + role.padEnd(9) + label.padEnd(22) +
                        "${score.clean}/${score.blocks} (${(score.cleanRatio * 100).toInt()}%)".padEnd(17) +
                        "${score.dirty}".padEnd(7) +
                        "${score.grouped}/${score.expected} (${(score.groupedRatio * 100).toInt()}%)".padEnd(20) +
                        "${score.orderedTotal - score.ordered}/${score.orderedTotal}"
            )
        }

        // 쓰기 방향별 순서 위반. RTL·세로쓰기 순회 분기를 검증하는 것이 이 지표의 목적이다.
        log("")
        val byDirection = linkedMapOf<Pair<String, String>, Score>()
        for (sample in samples) {
            for (setting in settings) {
                val score = EvalMetrics.score(EvalMetrics.run(repository, sample, setting), sample)
                byDirection.getOrPut(sample.direction.name to setting.label) { Score() } += score
            }
        }
        log("방향".padEnd(10) + "설정".padEnd(22) + "순서 위반")
        for ((key, score) in byDirection) {
            log(key.first.padEnd(10) + key.second.padEnd(22) +
                    "${score.orderedTotal - score.ordered}/${score.orderedTotal}")
        }

        // 설정별 총합. 갈래를 섞으면 큰 갈래에 가려지므로 참고용으로만 본다.
        log("")
        for (setting in settings) {
            val sum = Score()
            for ((key, score) in totals) if (key.third == setting.label) sum += score
            log("합계 ${setting.label.padEnd(22)} $sum")
        }
    }

    /**
     * 나란히 놓인 별개 라벨을 하나로 붙이는 정도. 네이티브 앱 화면은 물음이 다르다 —
     * 긴 문단이 드물고 짧은 라벨이 촘촘하므로 "몇 줄을 모았나" 가 잘 맞지 않는다.
     *
     * **섞인 칸**은 표 칸(TD/TH) 가운데 다른 칸의 단어와 같은 Line 에 든 칸의 수다. 분모는
     * 단어가 하나라도 든 칸 수로, 정답이 정하므로 조립 설정과 무관하다. Line 을 분모로 두면
     * 칸을 여러 Line 으로 쪼개는 설정일수록 비율이 낮아져 비교가 기운다(함정 3). 캡션·본문과
     * 섞인 것은 세지 않는다 — 물음은 칸끼리 붙느냐다.
     */
    @Test
    fun overMerge() {
        val settings = EvalSetting.parse(args.getString("sweep"))
        val groups = args.getString("groups")?.split(",")?.map { it.trim() }?.toSet() ?: setOf("native")
        val roles = args.getString("roles")?.split(",")?.map { it.trim() }?.toSet()
        val detail = args.getString("detail") == "1"
        val repository = VisionRepository()
        val samples = EvalSamples.load(InstrumentationRegistry.getInstrumentation().context, groups, roles)
        header("overMerge", samples)
        log("과병합 — 표본 ${samples.size}면 ${samples.map { it.name }}")
        val totals = linkedMapOf<String, IntArray>() // 섞인 Line, Line, 섞인 문단, 문단, 섞인 칸, 칸, 갈라진 블록
        for (setting in settings) {
            val t = totals.getOrPut(setting.label) { IntArray(7) }
            for (sample in samples) {
                val run = EvalMetrics.run(repository, sample, setting)
                val cellIds = sample.tags.filterValues { it == "TD" || it == "TH" }.keys
                val c = IntArray(7)
                val mixedCells = mutableSetOf<Int>()
                for (paragraph in run.paragraphs) {
                    for (line in paragraph.lines) {
                        val ids = line.words
                            .mapNotNull { run.unitBlock[it]?.takeIf { id -> id >= 0 } }.toSet()
                        if (ids.isEmpty()) continue
                        c[1]++; if (ids.size > 1) c[0]++
                        val cells = ids.filter { it in cellIds }
                        if (cells.size > 1) mixedCells.addAll(cells)
                    }
                }
                for (units in run.paragraphUnits) {
                    val ids = units.mapNotNull { run.unitBlock[it]?.takeIf { id -> id >= 0 } }.toSet()
                    if (ids.isEmpty()) continue
                    c[3]++; if (ids.size > 1) c[2]++
                }
                c[4] = mixedCells.size
                c[5] = run.unitBlock.values.filter { it in cellIds }.toSet().size
                // 두 문단 이상으로 갈라진 정답 블록 — 섞인 문단 수가 못 보는 과분할(3라운드 E3′ 보호 조건).
                val paragraphsOf = mutableMapOf<Int, MutableSet<Int>>()
                run.paragraphUnits.forEachIndexed { index, units ->
                    for (unit in units) {
                        val id = run.unitBlock[unit] ?: -1
                        if (id >= 0) paragraphsOf.getOrPut(id) { mutableSetOf() }.add(index)
                    }
                }
                c[6] = paragraphsOf.values.count { it.size > 1 }
                for (i in 0 until 7) t[i] += c[i]
                if (detail) log(
                    "OM\t${setting.label}\t${sample.name}\t${sample.group}\t${sample.role}" +
                            "\t${c[0]}\t${c[1]}\t${c[2]}\t${c[3]}\t${c[4]}\t${c[5]}\t${c[6]}"
                )
            }
        }
        log("설정".padEnd(22) + "섞인 Line".padEnd(12) + "섞인 문단".padEnd(12) +
                "전체 Line/문단".padEnd(16) + "섞인 칸".padEnd(20) + "갈라진 블록")
        for ((label, t) in totals) {
            val cellRatio = if (t[5] == 0) "-" else "${t[4]}/${t[5]} (${"%.1f".format(t[4] * 100.0 / t[5])}%)"
            log(label.padEnd(22) + "${t[0]}".padEnd(12) + "${t[2]}".padEnd(12) +
                    "${t[1]} / ${t[3]}".padEnd(16) + cellRatio.padEnd(20) + "${t[6]}")
        }
    }

    /**
     * 서로 다른 정답 블록을 섞은 문단을 줄 단위로 찍는다(E3 — 앱 화면 과병합의 모양 보기).
     *
     * `#residuals` 로는 안 보인다 — 그것은 채점 블록(두 줄 이상)만 보여 주는데 앱 라벨은 대개
     * 한 줄이다. 줄마다 좌표, 담긴 정답 블록과 태그, 글자열을 낸다.
     */
    @Test
    fun mixedParagraphs() {
        val setting = EvalSetting.parse(args.getString("sweep")).first()
        val groups = args.getString("groups")?.split(",")?.map { it.trim() }?.toSet() ?: setOf("native")
        val roles = args.getString("roles")?.split(",")?.map { it.trim() }?.toSet()
        val repository = VisionRepository()
        val samples = EvalSamples.load(InstrumentationRegistry.getInstrumentation().context, groups, roles)
        header("mixedParagraphs", samples)
        var count = 0
        for (sample in samples) {
            val run = EvalMetrics.run(repository, sample, setting)
            run.paragraphs.forEachIndexed { index, paragraph ->
                val ids = run.paragraphUnits[index]
                    .mapNotNull { run.unitBlock[it]?.takeIf { id -> id >= 0 } }.toSet()
                if (ids.size < 2) return@forEachIndexed
                count++
                log("[${sample.name}] 문단 $index — 블록 " +
                        ids.sorted().joinToString(" ") { "$it(${sample.tags[it] ?: "?"})" })
                for (line in paragraph.lines) {
                    val b = line.boundingBox
                    val lineIds = (line.words.mapNotNull { run.unitBlock[it] } +
                            listOfNotNull(run.unitBlock[line])).filter { it >= 0 }.toSet()
                    log("    %5d,%5d %4dx%-4d 블록%-10s %s".format(
                        b.left, b.top, b.width(), b.height(), lineIds.sorted().toString(),
                        line.representation.take(40)))
                }
            }
        }
        log("")
        log("섞인 문단 $count 개 (${setting.label})")
    }

    /**
     * 한 시각적 행이 Line 여러 개로 쪼개진 정도. 문단 묶기를 아무리 손봐도 줄 조립이
     * 한 행을 여러 Line 으로 내놓으면 고칠 수 없다. 이웃 사이 간격도 함께 낸다 —
     * 한 행에 진짜로 별개 요소가 나란히 놓인 경우는 쪼개진 것이 아니기 때문이다.
     */
    @Test
    fun splitRows() {
        val settings = EvalSetting.parse(args.getString("sweep"))
        val groups = args.getString("groups")?.split(",")?.map { it.trim() }?.toSet()
        val repository = VisionRepository()
        val roles = args.getString("roles")?.split(",")?.map { it.trim() }?.toSet()
        val samples = EvalSamples.load(InstrumentationRegistry.getInstrumentation().context, groups, roles)
        header("splitRows", samples)
        for (setting in settings) {
            var bands = 0; var split = 0
            val gaps = mutableListOf<Double>()
            for (sample in samples) {
                if (sample.input != InputUnit.WORDS) continue // 앱이 줄을 유도하는 경로에만 해당한다
                val lines = ParagraphAssembler.groupWordsIntoLines(sample.buildWords(), sample.direction, setting.paramsFor(sample))
                for (band in EvalMetrics.bands(lines, sample.isVertical) { it.boundingBox }) {
                    bands++
                    if (band.size < 2) continue
                    split++
                    val sorted = band.sortedBy {
                        if (sample.isVertical) -it.boundingBox.top else it.boundingBox.left
                    }
                    for (i in 0 until sorted.size - 1) {
                        val a = sorted[i].boundingBox
                        val b = sorted[i + 1].boundingBox
                        val gap = if (sample.isVertical) b.top - a.bottom else b.left - a.right
                        val height = (sorted[i].fontHeight + sorted[i + 1].fontHeight) / 2
                        if (height > 0) gaps.add(gap / height)
                    }
                }
            }
            log("${setting.label}: 행 $bands 개 중 쪼개진 행 $split, 이웃 쌍 ${gaps.size}")
            for (limit in listOf(0.5, 1.0, 2.0, 4.0)) {
                log("    간격 ${limit}폰트높이 이하: ${gaps.count { it <= limit }} 쌍")
            }
        }
    }

    /**
     * 출시값에서 남은 오차가 어디에 몰려 있는지 본다. 실패한 블록만 줄 단위로 찍는다.
     *
     * 오늘까지 가장 값어치 있었던 진단이다 — 집계 비율만 보면 안 보이던 결함 부류를
     * 이것으로 찾았다(한 시각적 행이 Line 열 개로 쪼개져 있었다). 값을 더 쓸기 전에
     * 잔차의 모양을 본다.
     */
    @Test
    fun residuals() {
        val setting = EvalSetting.parse(args.getString("sweep")).first()
        val groups = args.getString("groups")?.split(",")?.map { it.trim() }?.toSet()
        val top = args.getString("top")?.toIntOrNull() ?: 6
        val repository = VisionRepository()
        val roles = args.getString("roles")?.split(",")?.map { it.trim() }?.toSet()
        val samples = EvalSamples.load(InstrumentationRegistry.getInstrumentation().context, groups, roles)
        header("residuals", samples)

        val ranked = mutableListOf<Triple<String, Int, Int>>()
        val detail = mutableMapOf<String, List<String>>()
        for (sample in samples) {
            val run = EvalMetrics.run(repository, sample, setting)
            var miss = 0; var dirty = 0
            val rows = mutableListOf<String>()
            for (id in run.scoredBlocks) {
                val own = run.paragraphUnits.flatten().filter { run.unitBlock[it] == id }
                if (own.isEmpty()) continue
                var bestHit = 0; var bestDirty = 0; var bestIndex = -1
                run.paragraphUnits.forEachIndexed { index, units ->
                    val hit = units.count { run.unitBlock[it] == id }
                    if (hit > bestHit) {
                        bestHit = hit; bestIndex = index
                        bestDirty = units.count {
                            val b = run.unitBlock[it] ?: -1
                            b >= 0 && b != id
                        }
                    }
                }
                miss += own.size - bestHit
                dirty += bestDirty
                if (bestHit == own.size && bestDirty == 0) continue
                rows.add("    블록$id 단위 ${own.size}개 → 최대 $bestHit 오염 $bestDirty")
                val paragraph = run.paragraphs.getOrNull(bestIndex)
                paragraph?.lines?.take(12)?.forEach { line ->
                    val b = line.boundingBox
                    rows.add("        %5d,%5d %4dx%-4d %s".format(
                        b.left, b.top, b.width(), b.height(), line.representation.take(30)))
                }
            }
            if (miss > 0 || dirty > 0) {
                ranked.add(Triple(sample.name, miss, dirty))
                detail[sample.name] = rows
            }
        }

        log("잔차가 몰린 표본 (미묶임 + 오염 내림차순)")
        for ((name, miss, dirty) in ranked.sortedByDescending { it.second + it.third }) {
            log("  ${name.padEnd(8)}미묶임 ${miss.toString().padEnd(6)}오염 $dirty")
        }
        log("합계 미묶임 ${ranked.sumOf { it.second }} 오염 ${ranked.sumOf { it.third }}" +
                " / 잔차 있는 표본 ${ranked.size}면")
        log("")
        for ((name, _, _) in ranked.sortedByDescending { it.second + it.third }.take(top)) {
            log("  [$name]")
            detail[name]?.forEach { log(it) }
        }
    }

    /**
     * 줄 순서가 어긋난 문단을 줄 단위로 찍는다. 집계의 "순서 위반" 이 어디서 오는지 볼 때 쓴다.
     * 줄마다 좌표와 글자열을 조립된 순서대로 낸다.
     */
    @Test
    fun orderViolations() {
        val setting = EvalSetting.parse(args.getString("sweep")).first()
        val groups = args.getString("groups")?.split(",")?.map { it.trim() }?.toSet()
        val roles = args.getString("roles")?.split(",")?.map { it.trim() }?.toSet()
        val repository = VisionRepository()
        val samples = EvalSamples.load(InstrumentationRegistry.getInstrumentation().context, groups, roles)
        header("orderViolations", samples)
        var count = 0
        for (sample in samples) {
            val run = EvalMetrics.run(repository, sample, setting)
            for (index in EvalMetrics.misorderedParagraphs(run)) {
                count++
                log("[${sample.name}] 문단 $index ${run.paragraphDirections[index]}")
                for (line in run.paragraphs[index].lines) {
                    val b = line.boundingBox
                    log("    l=%5d r=%5d t=%5d b=%5d %s".format(
                        b.left, b.right, b.top, b.bottom, line.representation.take(24)))
                }
            }
        }
        log("")
        log("순서가 어긋난 문단 $count 개 (${setting.label})")
    }

    /**
     * 문단 후처리가 실제로 발동하는지 센다.
     *
     * `detectAndSplitParagraphs` 는 코드 주석에 "(검증되지 않음)" 이라고 적힌 단계다. 가로
     * 경로에서는 쪼갠 뒤 `correctDetectAndSplitParagraphs` 가 다시 합치므로, 최종 성적이 같다고
     * 해서 쪼개기가 안 일어났다는 뜻은 아니다. 세로 분기는 다시 합치지 않으므로 이 구분이
     * 중요하다 — 쪼개기가 발동하면 그대로 최종 출력에 남는다.
     */
    @Test
    fun postProcessActivity() {
        val groups = args.getString("groups")?.split(",")?.map { it.trim() }?.toSet()
        val repository = VisionRepository()
        val roles = args.getString("roles")?.split(",")?.map { it.trim() }?.toSet()
        val samples = EvalSamples.load(InstrumentationRegistry.getInstrumentation().context, groups, roles)
        header("postProcessActivity", samples)
        val byGroup = linkedMapOf<String, IntArray>() // 문단, split 발동, split 조각, correct 뒤 조각
        val fired = mutableListOf<String>()
        val noPost = EvalSetting("후처리없음", mapOf("post" to 0.0))
        for (sample in samples) {
            val run = EvalMetrics.run(repository, sample, noPost)
            val c = byGroup.getOrPut(sample.group) { IntArray(4) }
            run.paragraphs.forEachIndexed { index, paragraph ->
                val direction = run.paragraphDirections[index]
                val vertical = direction == com.galaxy.airviewdictionary.data.local.vision.WritingDirection.TTB_RTL ||
                        direction == com.galaxy.airviewdictionary.data.local.vision.WritingDirection.TTB_LTR
                c[0]++
                val split = ParagraphAssembler.detectAndSplitParagraphs(paragraph, direction)
                if (split.size > 1) {
                    c[1]++; c[2] += split.size
                    // 세로 문단은 다시 합치지 않는다 — 쪼개진 그대로 최종 출력에 남는다.
                    val after = if (vertical) split
                    else ParagraphAssembler.correctDetectAndSplitParagraphs(split, direction)
                    c[3] += after.size
                    fired.add("${sample.name} ${if (vertical) "세로" else "가로"} " +
                            "${paragraph.lines.size}줄 → ${split.size}조각 → ${after.size}")
                }
            }
        }
        log("갈래".padEnd(16) + "문단".padEnd(8) + "split 발동".padEnd(12) + "조각".padEnd(8) + "correct 뒤")
        for ((group, c) in byGroup) {
            log(group.padEnd(16) + "${c[0]}".padEnd(8) + "${c[1]}".padEnd(12) + "${c[2]}".padEnd(8) + "${c[3]}")
        }
        log("")
        log("발동한 문단 ${fired.size}개" + if (fired.isEmpty()) " — 이 표본들로는 쪼개기 단계가 검증되지 않는다" else "")
        fired.take(20).forEach { log("  $it") }
    }

    private companion object {
        val opened = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}
