package com.galaxy.airviewdictionary.data.local.vision.ocr

import kotlin.math.abs
import kotlin.math.min

/**
 * 검출기가 찾은 줄을 읽는 순서로 이어 화면 글([OcrText.text])을 만든다(`.docs/vision-engine-design.md` §23).
 *
 * PP-OCRv5 검출기는 줄을 찾은 순서(윗 픽셀부터 훑는 순서)로 준다. 그 순서로 이으면 한 행이 여러 상자로 잡힌 아랍어 행이나 두 단
 * 배치에서 같은 행의 상자 순서가 뒤섞인다. 글 전체를 조립 없이 번역하는 영역 선택·고정 영역이 그 글을 그대로 보낸다.
 *
 * 규칙: 세로 중심이 가까운(두 줄 높이 중 작은 쪽의 절반 안) 줄을 한 행으로 묶고, 행은 위에서 아래로, 행 안은 그 행의 글이 주로
 * 오른쪽→왼쪽 문자면 오른쪽부터, 아니면 왼쪽부터 잇는다. 같은 행의 상자는 빈칸으로, 행은 줄바꿈으로 잇는다. 빈 글은 건너뛴다.
 *
 * 두 단 배치는 행 규칙만으로는 행마다 "왼쪽 단, 오른쪽 단" 으로 섞인다. 조립기가 줄을 문단으로 묶어 주면 [joinParagraphs] 가 문단 상자로
 * 단을 가른다(§24). 문단을 모르는 곳(auto 표본)은 [text] 로 행 규칙만 쓴다.
 *
 * ML Kit 은 제 덩어리 순서로 글을 주므로 쓰지 않는다 — 검출만 된 줄을 읽은 경우에만 쓴다.
 */
object ReadingOrder {

    /** 줄 하나의 상자와 글. 상자가 없는 줄은 [box] = null — 맨 뒤에 받은 순서대로 붙인다. */
    class Item(val box: Box?, val text: String)

    /** 화면 좌표 상자. `android.graphics.Rect` 에 기대지 않아 JVM 시험으로 잴 수 있다. */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val centerY: Double get() = (top + bottom) / 2.0
        val height: Int get() = bottom - top
    }

    /** [lines] 의 글을 읽는 순서로 잇는다. */
    fun text(lines: List<OcrLine>): String = join(
        lines.map { line -> Item(line.boundingBox?.let { Box(it.left, it.top, it.right, it.bottom) }, line.text) }
    )

    /** [items] 를 행으로 묶어 읽는 순서로 잇는다. */
    fun join(items: List<Item>): String = rows(items).joinToString("\n") { row ->
        row.joinToString(" ") { it.text.trim() }
    }

    /** 문단마다의 줄([paragraphs])을 읽는 순서로 잇는다. [joinParagraphs] 참고. */
    fun text(paragraphs: List<List<OcrLine>>, rightToLeft: Boolean): String = joinParagraphs(
        paragraphs.map { lines -> lines.map { line -> Item(line.boundingBox?.let { Box(it.left, it.top, it.right, it.bottom) }, line.text) } },
        rightToLeft,
    )

    /**
     * 문단으로 묶인 줄들을 읽는 순서로 잇는다. 흘러내리는 문단(본문·카드 설명처럼 [FLOW_ROWS] 행 이상이고 마지막을 뺀 행이 모두 행 높이의
     * [FLOW_WIDTH] 배 이상 넓은 것)은 한 덩이로, 나머지 문단은 행마다 따로 놓고 그 덩이들의 상자를 자르는 순서로 잇는다(XY-cut):
     *  1. 어느 덩이도 걸치지 않는 세로 틈으로 단을 가를 수 있고 가른 단마다 흘러내리는 문단이 있으면 단부터 가른다. 단은 [rightToLeft] 면 오른쪽부터
     *  2. 아니면 어느 덩이도 걸치지 않는 가로 틈에서 위아래 띠로 가른다 — 두 단 위의 제목, 표·정보 상자의 행. 다만 이웃한 띠를 합쳐 1 이
     *     되면 합친다: 두 단의 문단 경계가 우연히 같은 높이에 오면 거기에 가로 틈이 생기는데, 그 틈에서 자르면 단이 띠마다 섞인다
     *  3. 가로 틈이 없으면 세로 틈으로 가른다(한 줄짜리 칸이 나란한 행)
     *  4. 가른 조각마다 되풀이하고, 더 가를 수 없으면(상자가 겹친다) 위에서 아래로, 같으면 읽는 방향으로
     *
     * 흘러내리지 않는 문단을 행으로 나누는 까닭: 조립기는 정보 상자의 이름 칸 넷, 값 칸 넷을 각각 한 문단으로 묶는다(행 간격이 고르다).
     * 그것을 덩이째 두면 이름을 다 읽고 값을 읽는다. 행으로 나누면 행 사이의 가로 틈이 표를 행 단위로 가른다(§24).
     *
     * 흘러내리는 문단 안은 [join] 의 행 규칙이다. 덩이 사이는 줄바꿈으로 잇되, 같은 행에 나란히 놓인 한 행짜리 덩이(표의 이름과 값)끼리는
     * 행 규칙처럼 빈칸으로 잇는다. 상자가 없는 문단은 맨 뒤에 받은 순서대로 붙인다.
     */
    fun joinParagraphs(paragraphs: List<List<Item>>, rightToLeft: Boolean): String {
        val blocks = mutableListOf<Block>()
        val unboxed = mutableListOf<List<Item>>()
        for (paragraph in paragraphs) {
            val present = paragraph.filter { it.text.isNotBlank() }
            if (present.isEmpty()) continue
            val boxed = present.filter { it.box != null }
            if (boxed.size < present.size) unboxed.add(present - boxed.toSet())
            if (boxed.isEmpty()) continue
            val rows = rows(boxed)
            if (flowing(rows)) blocks.add(Block(boxed, rows.size)) else rows.forEach { blocks.add(Block(it, 1)) }
        }
        val text = StringBuilder()
        var previous: Block? = null
        for (block in order(blocks, rightToLeft)) {
            if (previous != null) {
                val sameRow = previous.rowCount == 1 && block.rowCount == 1 && sameRow(previous.items, block.box)
                text.append(if (sameRow) " " else "\n")
            }
            text.append(join(block.items))
            previous = block
        }
        for (items in unboxed) {
            if (text.isNotEmpty()) text.append("\n")
            text.append(join(items))
        }
        return text.toString()
    }

    /** 흘러내리는 문단으로 볼 최소 행 수와, 마지막을 뺀 행의 최소 폭(행 높이 배수). 훑기 238면에서 골랐다(§24). */
    private const val FLOW_ROWS = 3
    private const val FLOW_WIDTH = 6

    private fun flowing(rows: List<List<Item>>): Boolean {
        if (rows.size < FLOW_ROWS) return false
        return rows.dropLast(1).all { row ->
            val width = row.maxOf { it.box!!.right } - row.minOf { it.box!!.left }
            val height = row.map { it.box!!.height }.sorted()[row.size / 2]
            width >= FLOW_WIDTH * height
        }
    }

    /** 순서를 정할 덩이 — 흘러내리는 문단 하나, 또는 그렇지 않은 문단의 한 행. */
    private class Block(val items: List<Item>, val rowCount: Int) {
        val box: Box = items.mapNotNull { it.box }.let { boxes ->
            Box(boxes.minOf { it.left }, boxes.minOf { it.top }, boxes.maxOf { it.right }, boxes.maxOf { it.bottom })
        }
    }

    private fun order(blocks: List<Block>, rightToLeft: Boolean): List<Block> {
        if (blocks.size <= 1) return blocks
        val columns = columnsOf(blocks)
        fun byColumn() = (if (rightToLeft) columns.asReversed() else columns).flatMap { order(it, rightToLeft) }
        if (flows(columns)) return byColumn()
        val bands = cut(blocks, { it.box.top }, { it.box.bottom })
        if (bands.size > 1) {
            val merged = mutableListOf<List<Block>>()
            for (band in bands) {
                val last = merged.lastOrNull()
                if (last != null && flows(columnsOf(last + band))) merged[merged.lastIndex] = last + band else merged.add(band)
            }
            if (merged.size > 1) return merged.flatMap { order(it, rightToLeft) }
        }
        if (columns.size > 1) return byColumn()
        return blocks.sortedWith(compareBy<Block> { it.box.top }.thenBy { if (rightToLeft) -it.box.right else it.box.left })
    }

    private fun columnsOf(blocks: List<Block>) = cut(blocks, { it.box.left }, { it.box.right })

    /** 단이 둘 이상이고 단마다 흘러내리는 문단이 있다 — 본문이 단을 따라 흘러내린다. */
    private fun flows(columns: List<List<Block>>) = columns.size > 1 && columns.all { column -> column.any { it.rowCount >= 2 } }

    /** [start, end) 구간이 겹치는 것끼리 묶는다. 앞(작은 값)의 무리부터. */
    private fun cut(blocks: List<Block>, start: (Block) -> Int, end: (Block) -> Int): List<List<Block>> {
        val groups = mutableListOf<MutableList<Block>>()
        var reach = Int.MIN_VALUE
        for (block in blocks.sortedBy(start)) {
            if (groups.isEmpty() || start(block) >= reach) groups.add(mutableListOf(block)) else groups.last().add(block)
            reach = maxOf(reach, end(block))
        }
        return groups
    }

    /** 읽는 순서의 행들. 행마다 읽는 순서의 줄, 빈 글은 뺀다. */
    fun rows(items: List<Item>): List<List<Item>> {
        val present = items.filter { it.text.isNotBlank() }
        val boxed = present.filter { it.box != null }.sortedWith(compareBy({ it.box!!.centerY }, { it.box!!.left }))

        val rows = mutableListOf<MutableList<Item>>()
        for (item in boxed) {
            val box = item.box!!
            val row = rows.lastOrNull()
            if (row != null && sameRow(row, box)) row.add(item) else rows.add(mutableListOf(item))
        }

        val ordered: List<List<Item>> = rows.map { row ->
            if (isRightToLeft(row)) row.sortedByDescending { it.box!!.right } else row.sortedBy { it.box!!.left }
        }
        val unboxed = present.filter { it.box == null }
        return if (unboxed.isEmpty()) ordered else ordered + unboxed.map { listOf(it) }
    }

    /** [box] 의 세로 중심이 [row] 의 평균 세로 중심에서 (줄 높이·행 평균 높이 중 작은 쪽의) 절반 안인가. */
    private fun sameRow(row: List<Item>, box: Box): Boolean {
        val rowCenter = row.sumOf { it.box!!.centerY } / row.size
        val rowHeight = row.sumOf { it.box!!.height }.toDouble() / row.size
        val tolerance = min(box.height.toDouble(), rowHeight) / 2
        return abs(box.centerY - rowCenter) <= tolerance
    }

    /** 행의 글자 중 오른쪽→왼쪽 문자(아랍·히브리 등)가 절반을 넘는가. */
    private fun isRightToLeft(row: List<Item>): Boolean {
        var rtl = 0
        var letters = 0
        for (item in row) for (c in item.text) {
            if (!c.isLetter()) continue
            letters++
            val direction = Character.getDirectionality(c)
            if (direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT || direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) rtl++
        }
        return letters > 0 && rtl * 2 > letters
    }
}
