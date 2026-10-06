package com.galaxy.airviewdictionary.ocrbench

import com.galaxy.airviewdictionary.data.local.vision.ParagraphAssembler
import android.graphics.Rect
import com.galaxy.airviewdictionary.data.local.vision.AssemblyParams
import com.galaxy.airviewdictionary.data.local.vision.VisionRepository
import com.galaxy.airviewdictionary.data.local.vision.WritingDirection
import com.galaxy.airviewdictionary.data.local.vision.model.Line
import com.galaxy.airviewdictionary.data.local.vision.model.Paragraph

/**
 * 조립 품질 지표.
 *
 * 재는 것은 OCR 정확도가 아니라 조립이다 — 글자를 틀리게 읽어도 벌점이 없다. 인식기를
 * 갈아끼워도 조립기는 그대로 쓰이므로 조립만 떼어내 잰다.
 *
 * **분모는 조립 설정과 무관해야 한다.** 줄 단위로 세면 줄 조립을 고칠수록 Line 수가 줄어
 * 기대값도 같이 줄고, 그러면 설정끼리 비교가 성립하지 않는다(실측: 같은 표본에서 기대값이
 * 2126 에서 1735 까지 움직였다). 그래서 **인식기가 준 것**을 단위로 센다 — ML Kit 경로는
 * 단어, 검출기 경로는 줄이다.
 */
class Score(
    var grouped: Int = 0,
    var expected: Int = 0,
    var dirty: Int = 0,
    /** 빠짐도 섞임도 없이 한 문단으로 잡힌 정답 블록 수. 사용자 체감에 가장 가깝다. */
    var clean: Int = 0,
    var blocks: Int = 0,
    /** 오염 중 인접 형제 목록 항목끼리 붙은 것. 제품상 덜 나쁘므로 따로 센다. */
    var sibling: Int = 0,
    /** 읽기 순서가 기하와 맞는 문단 수. */
    var ordered: Int = 0,
    var orderedTotal: Int = 0,
) {
    val cleanRatio get() = if (blocks == 0) 0.0 else clean.toDouble() / blocks
    val groupedRatio get() = if (expected == 0) 0.0 else grouped.toDouble() / expected
    val orderRatio get() = if (orderedTotal == 0) 1.0 else ordered.toDouble() / orderedTotal

    operator fun plusAssign(other: Score) {
        grouped += other.grouped; expected += other.expected; dirty += other.dirty
        clean += other.clean; blocks += other.blocks; sibling += other.sibling
        ordered += other.ordered; orderedTotal += other.orderedTotal
    }

    override fun toString() =
        "온전 $clean/$blocks (${(cleanRatio * 100).toInt()}%) 묶임 $grouped/$expected 오염 $dirty" +
                " 순서 $ordered/$orderedTotal"
}

object EvalMetrics {

    /**
     * 같은 행(세로쓰기에서는 같은 열)에 드는 것끼리 묶는다.
     *
     * 겹침을 재는 축이 방향에 따라 다르다. 가로쓰기의 행은 세로로 겹치고, 세로쓰기의 열은
     * 가로로 겹친다. 이걸 틀리면 분모를 잘못 세어 측정 자체가 무의미해진다.
     */
    private fun overlapRatio(a: Rect, b: Rect, vertical: Boolean): Double {
        val aLow = if (vertical) a.left else a.top
        val aHigh = if (vertical) a.right else a.bottom
        val bLow = if (vertical) b.left else b.top
        val bHigh = if (vertical) b.right else b.bottom
        val span = minOf(aHigh, bHigh) - maxOf(aLow, bLow)
        val smaller = minOf(aHigh - aLow, bHigh - bLow)
        return if (smaller <= 0) 0.0 else span.toDouble() / smaller
    }

    fun <T> bands(items: List<T>, vertical: Boolean, box: (T) -> Rect): List<List<T>> {
        val remaining = items.sortedBy { if (vertical) -box(it).right else box(it).top }.toMutableList()
        val out = mutableListOf<List<T>>()
        while (remaining.isNotEmpty()) {
            val head = remaining.removeAt(0)
            val band = mutableListOf(head)
            val iterator = remaining.iterator()
            while (iterator.hasNext()) {
                val other = iterator.next()
                if (overlapRatio(box(head), box(other), vertical) > 0.5) {
                    band.add(other); iterator.remove()
                }
            }
            out.add(band)
        }
        return out
    }

    /**
     * 한 번의 조립과 채점. 단위(단어/줄)는 표본이 정한다.
     *
     * 조립할 때마다 단위 객체를 새로 만든다 — 같은 객체를 설정 여럿에 돌려쓰면 앞 설정의
     * 상태가 남는다.
     */
    class Run(
        val paragraphs: List<Paragraph>,
        /** 단위 객체 → 정답 블록 id. 소속 없음은 -1. */
        val unitBlock: java.util.IdentityHashMap<Any, Int>,
        /** 문단 → 그 문단이 담은 단위들(조립된 순서대로). */
        val paragraphUnits: List<List<Any>>,
        /** 문단 → 그 문단의 쓰기 방향. 세로 분기는 한 화면에 세로 문단과 가로 문단이 섞인다. */
        val paragraphDirections: List<WritingDirection>,
        val scoredBlocks: Set<Int>,
        val blockUnitCount: Map<Int, Int>,
        val totalUnits: Int,
    )

    /**
     * 표본 하나를 프로덕션과 똑같이 조립한다. 기준값도 여기서 만든다 — 세로 분기는 한 화면
     * 안에서 세로 부분과 가로 부분에 기준값을 따로 쓰므로 호출부에서 한 벌을 넘겨서는
     * 재현되지 않는다.
     */
    fun run(
        repository: VisionRepository,
        sample: Sample,
        setting: EvalSetting = EvalSetting.shipped(),
    ): Run {
        val post = setting.postProcess
        val paragraphs = mutableListOf<Paragraph>()
        val directions = mutableListOf<WritingDirection>()
        val units = mutableListOf<Any>()
        /** 문단마다 단위가 줄인지(아니면 단어인지). */
        val lineUnits = mutableListOf<Boolean>()
        /** 줄 단위일 때 단어 → 그 단어가 속한 원래 줄(조각). 이은 줄을 원 조각으로 되돌려 채점한다. */
        val pieceOf = java.util.IdentityHashMap<com.galaxy.airviewdictionary.data.local.vision.model.Word, Line>()

        fun addHorizontal(words: List<com.galaxy.airviewdictionary.data.local.vision.model.Word>,
                          direction: WritingDirection, params: AssemblyParams) {
            val lines = ParagraphAssembler.groupWordsIntoLines(words, direction, params)
            var grouped = ParagraphAssembler.groupLinesIntoParagraphs(lines, direction, params)
            if (post) grouped = grouped.flatMap {
                ParagraphAssembler.correctDetectAndSplitParagraphs(
                    ParagraphAssembler.detectAndSplitParagraphs(it, direction), direction
                )
            }
            units.addAll(words)
            for (p in grouped) { paragraphs.add(p); directions.add(direction); lineUnits.add(false) }
        }

        fun addLines(lines: List<Line>, direction: WritingDirection, vertical: Boolean, params: AssemblyParams) {
            for (piece in lines) for (word in piece.words) pieceOf[word] = piece
            var grouped = ParagraphAssembler.groupLinesIntoParagraphs(lines, direction, params)
            // 세로 분기는 쪼개기만 하고 다시 합치지 않는다. 가로 경로는 쪼갠 뒤 다시 합친다.
            if (post) grouped = grouped.flatMap {
                if (vertical && !params.VERTICAL_SPLIT) return@flatMap listOf(it)
                val split = ParagraphAssembler.detectAndSplitParagraphs(it, direction)
                if (vertical) split else ParagraphAssembler.correctDetectAndSplitParagraphs(split, direction)
            }
            units.addAll(lines)
            for (p in grouped) { paragraphs.add(p); directions.add(direction); lineUnits.add(true) }
        }

        when (sample.input) {
            InputUnit.WORDS -> addHorizontal(sample.buildWords(), sample.direction, setting.paramsFor(sample))
            // 검출기가 준 줄을 그대로 신뢰한다 — 단어→줄 단계를 지나가지 않는다.
            InputUnit.LINES -> addLines(sample.buildLines(), sample.direction, sample.isVertical, setting.paramsFor(sample))
            InputUnit.VSPLIT -> {
                // textToVerticalParagraphs 와 같다. 세로로 긴 줄은 세로 경로로 먼저,
                // 나머지 단어는 가로 경로로. 기준값도 부분마다 따로 채운다.
                addLines(sample.buildVerticalLines(), sample.direction, vertical = true, setting.paramsFor(sample, vertical = true))
                addHorizontal(sample.buildWords(), sample.horizontalDirection, setting.paramsFor(sample, vertical = false))
            }
        }

        val unitBlock = java.util.IdentityHashMap<Any, Int>()
        for (unit in units) {
            val box = boxOf(unit)
            unitBlock[unit] = sample.blocks.firstOrNull {
                it.second.contains(box.centerX(), box.centerY())
            }?.first ?: -1
        }
        val byBlock = mutableMapOf<Int, MutableList<Any>>()
        for (unit in units) {
            val id = unitBlock[unit] ?: -1
            if (id >= 0) byBlock.getOrPut(id) { mutableListOf() }.add(unit)
        }
        // 한 줄짜리 블록은 세지 않는다 — 묶을 게 없어 공짜 점수가 된다. 줄 수는 입력 박스의
        // 겹침으로 세므로 이것도 조립 설정과 무관하다.
        val scored = mutableSetOf<Int>()
        val counts = mutableMapOf<Int, Int>()
        var total = 0
        for ((id, list) in byBlock) {
            counts[id] = list.size
            // 단위가 줄이면 줄 수가 곧 행 수다. 단어면 가로 행으로 묶어 센다 — 세로 분기에서도
            // 단어는 가로 부분에서만 나오므로 축은 늘 가로다.
            val rows = if (list.all { it is Line }) list.size
            else bands(list, vertical = false) { boxOf(it) }.size
            if (rows >= 2) { scored.add(id); total += list.size }
        }

        val paragraphUnits = paragraphs.mapIndexed { i, paragraph ->
            if (!lineUnits[i]) return@mapIndexed paragraph.lines.flatMap { it.words }
            // 줄마다 원 조각으로 펼친다(같은 조각은 한 번만). 잇지 않은 줄은 제 단어가 저를 가리킨다.
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Line, Boolean>())
            paragraph.lines.flatMap { line ->
                val pieces = line.words.mapNotNull { pieceOf[it] }.filter { seen.add(it) }
                if (pieces.isEmpty() && seen.add(line)) listOf(line) else pieces
            }
        }
        return Run(paragraphs, unitBlock, paragraphUnits, directions, scored, counts, total)
    }

    /*
     * 문단 묶기 뒤의 후처리(`detectAndSplitParagraphs`, `correctDetectAndSplitParagraphs`)도
     * 프로덕션과 똑같이 돈다(위 run 안). 예전 하네스는 문단 묶기에서 멈췄고, 그래서 그때의 순서
     * 위반 숫자는 정렬 전 단계를 잰 인공물이었다(2026-09-24).
     */

    private fun boxOf(unit: Any): Rect = when (unit) {
        is Line -> unit.boundingBox
        is com.galaxy.airviewdictionary.data.local.vision.model.Word -> unit.boundingBox
        else -> throw IllegalArgumentException("모르는 단위 $unit")
    }

    /**
     * 나란한 `<li>` 두 개가 한 문단으로 붙는 것은 문단 둘이 붙는 것과 성질이 다르다 —
     * 같은 목록의 이웃 항목이라 함께 번역해도 내용이 어긋나지 않는다. `<p>` 두 개가 붙는
     * 것은 실제 오류이므로 목록성 태그에만 적용한다.
     *
     * 실측으로 이 허수는 전체 오염의 4.4% 뿐이었다. 지표를 바꾸지 않고 따로 세기만 한다.
     */
    private fun isSiblingItem(tags: Map<Int, String>, a: Int, b: Int): Boolean {
        val tag = tags[a] ?: return false
        if (tag != tags[b] || tag !in setOf("LI", "DT", "DD")) return false
        return kotlin.math.abs(a - b) <= 1
    }

    fun score(run: Run, sample: Sample): Score {
        val s = Score(expected = run.totalUnits, blocks = run.scoredBlocks.size)
        val perParagraph = run.paragraphUnits.map { units ->
            val counts = mutableMapOf<Int, Int>()
            for (unit in units) {
                val id = run.unitBlock[unit] ?: -1
                if (id >= 0) counts[id] = (counts[id] ?: 0) + 1
            }
            counts
        }
        for (id in run.scoredBlocks) {
            var bestHit = 0
            var bestDirty = 0
            var bestSibling = 0
            for (counts in perParagraph) {
                val hit = counts[id] ?: 0
                if (hit <= bestHit) continue
                bestHit = hit; bestDirty = 0; bestSibling = 0
                for ((other, n) in counts) {
                    if (other == id) continue
                    if (isSiblingItem(sample.tags, id, other)) bestSibling += n else bestDirty += n
                }
            }
            s.grouped += bestHit
            s.dirty += bestDirty
            s.sibling += bestSibling
            if (bestDirty == 0 && bestSibling == 0 && bestHit == run.blockUnitCount[id]) s.clean++
        }
        val order = orderScore(run, sample)
        s.ordered = order.first
        s.orderedTotal = order.second
        return s
    }

    /**
     * 읽기 순서가 기하와 맞는지 본다.
     *
     * 집합 소속만 재면 순서를 못 본다 — 한 문단 안에서 줄이 뒤바뀌어도 집합은 같으니
     * 만점이 된다. 그런데 사용자는 순서대로 읽고, 문장 분리도 그 순서로 이어 붙인 텍스트에서
     * 한다. 순서가 틀리면 번역문이 뒤죽박죽인데 집합 지표는 모른다.
     *
     * 올바른 순서는 **기하가 정한다** — 텍스트 정답이 필요 없다. 가로쓰기는 줄이 위에서
     * 아래로, 세로쓰기는 열이 오른쪽에서 왼쪽으로. 줄 안의 단어는 LTR 이면 왼쪽부터,
     * RTL 이면 오른쪽부터다.
     *
     * 이것이 RTL·세로쓰기 순회 분기를 검증하는 지표다. 집합 지표로는 통과해도 뒤집혀
     * 있을 수 있다.
     */
    /** 줄 순서, 또는 줄 안 단어 순서가 어긋난 문단의 번호. `#orderViolations` 진단이 쓴다. */
    fun misorderedParagraphs(run: Run): List<Int> = run.paragraphs.indices.filter { index ->
        val paragraph = run.paragraphs[index]
        val direction = run.paragraphDirections[index]
        (paragraph.lines.size >= 2 && !isSorted(paragraph.lines.map { it.boundingBox }, direction, lineOrder = true)) ||
                paragraph.lines.any { line ->
                    line.words.size >= 2 && !isSorted(line.words.map { it.boundingBox }, direction, lineOrder = false)
                }
    }

    private fun orderScore(run: Run, sample: Sample): Pair<Int, Int> {
        var ok = 0
        var total = 0
        run.paragraphs.forEachIndexed { index, paragraph ->
            val direction = run.paragraphDirections[index]
            if (paragraph.lines.size >= 2) {
                total++
                if (isSorted(paragraph.lines.map { it.boundingBox }, direction, lineOrder = true)) ok++
            }
            // 검출기 줄은 단어 하나짜리라 줄 안 순서가 없다.
            if (sample.input == InputUnit.LINES) return@forEachIndexed
            for (line in paragraph.lines) {
                if (line.words.size < 2) continue
                total++
                if (isSorted(line.words.map { it.boundingBox }, direction, lineOrder = false)) ok++
            }
        }
        return ok to total
    }

    /** 순서가 방향에 맞게 단조로운가. 같은 행 안에서의 미세한 흔들림은 눈감아 준다. */
    private fun isSorted(boxes: List<Rect>, direction: WritingDirection, lineOrder: Boolean): Boolean {
        if (lineOrder && (direction == WritingDirection.TTB_RTL || direction == WritingDirection.TTB_LTR)) {
            return isColumnOrder(boxes, rightToLeft = direction == WritingDirection.TTB_RTL)
        }
        val keys = boxes.map { box ->
            when {
                lineOrder && direction == WritingDirection.TTB_RTL -> -box.right
                lineOrder && direction == WritingDirection.TTB_LTR -> box.left
                lineOrder -> box.top
                direction == WritingDirection.RTL -> -box.left
                direction == WritingDirection.TTB_RTL || direction == WritingDirection.TTB_LTR -> box.top
                else -> box.left
            }
        }
        // 엄격한 증가를 요구하지 않는다. 앞 것보다 뚜렷이 뒤로 갔을 때만 어긋난 것으로 본다.
        val slack = if (lineOrder) 0 else 2
        for (i in 0 until keys.size - 1) if (keys[i + 1] < keys[i] - slack) return false
        return true
    }

    /**
     * 세로쓰기의 줄(열) 순서.
     *
     * ML Kit 은 한 열을 여러 조각으로 끊어 줄 수 있다. 가로로 겹치는 조각은 같은 열이고 같은 열
     * 안에서는 위에서 아래로 가야 하며, 다른 열로 넘어가면 읽는 방향(TTB_RTL 은 왼쪽)으로 가야 한다.
     * 예전에는 오른쪽 끝만 비교했는데, 그것은 조립기의 옛 정렬과 같은 기준이라 한 열의 조각이
     * 아래 → 위로 이어져도 통과시켰고, 위 → 아래로 바로 놓으면 오른쪽 끝 1px 차이로 위반이라 했다
     * (2026-09-24 에 알게 됐다).
     */
    private fun isColumnOrder(boxes: List<Rect>, rightToLeft: Boolean): Boolean {
        for (i in 0 until boxes.size - 1) {
            val a = boxes[i]
            val b = boxes[i + 1]
            val overlap = minOf(a.right, b.right) - maxOf(a.left, b.left)
            val narrower = minOf(a.width(), b.width())
            if (narrower > 0 && overlap > narrower * 0.5) {
                if (b.top < a.top) return false
            } else if (rightToLeft) {
                if (b.right > a.right) return false
            } else {
                if (b.left < a.left) return false
            }
        }
        return true
    }

    fun assemble(repository: VisionRepository, sample: Sample) = run(repository, sample).paragraphs
}
