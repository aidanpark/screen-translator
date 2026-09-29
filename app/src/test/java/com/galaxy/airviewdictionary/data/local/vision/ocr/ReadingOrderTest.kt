package com.galaxy.airviewdictionary.data.local.vision.ocr

import com.galaxy.airviewdictionary.data.local.vision.ocr.ReadingOrder.Box
import com.galaxy.airviewdictionary.data.local.vision.ocr.ReadingOrder.Item
import org.junit.Assert.assertEquals
import org.junit.Test

/** 검출기 줄을 읽는 순서로 잇기(`.docs/vision-engine-design.md` §23). 입력 순서는 검출기처럼 윗 픽셀 순으로 준다. */
class ReadingOrderTest {

    private fun item(left: Int, top: Int, right: Int, bottom: Int, text: String) = Item(Box(left, top, right, bottom), text)

    /** 아랍어 한 행이 상자 둘로 잡혔다. 왼쪽 상자가 1px 위라 먼저 온다 — 오른쪽 상자부터 읽어야 한다. */
    @Test
    fun rightToLeftRowReadsFromTheRight() {
        val detected = listOf(
            item(100, 10, 300, 40, "الثاني"),
            item(350, 11, 600, 41, "الجزء الأول"),
            item(80, 60, 600, 90, "السطر التالي"),
        )
        assertEquals("الجزء الأول الثاني\nالسطر التالي", ReadingOrder.join(detected))
    }

    /** 라틴 행은 왼쪽부터. 오른쪽 상자가 더 위에서 시작해도 같은 행이다. */
    @Test
    fun leftToRightRowReadsFromTheLeft() {
        val detected = listOf(
            item(500, 98, 900, 130, "right"),
            item(0, 100, 400, 130, "left"),
            item(0, 150, 400, 180, "next"),
        )
        assertEquals("left right\nnext", ReadingOrder.join(detected))
    }

    /** 두 단 배치 — 검출 순서가 행마다 좌우를 뒤바꿔도 행 안의 순서는 늘 같다. */
    @Test
    fun twoColumnRowsKeepAConsistentOrder() {
        val detected = listOf(
            item(0, 100, 400, 130, "L1"),
            item(500, 104, 900, 134, "R1"),
            item(500, 146, 900, 176, "R2"),
            item(0, 150, 400, 180, "L2"),
        )
        assertEquals("L1 R1\nL2 R2", ReadingOrder.join(detected))
    }

    /** 행은 위에서 아래로 — 받은 순서와 무관하다. */
    @Test
    fun rowsGoTopToBottom() {
        val detected = listOf(
            item(0, 300, 400, 330, "third"),
            item(0, 100, 400, 130, "first"),
            item(0, 200, 400, 230, "second"),
        )
        assertEquals("first\nsecond\nthird", ReadingOrder.join(detected))
    }

    /** 세로 중심이 줄 높이의 절반 넘게 벌어지면 다른 행이다. */
    @Test
    fun linesMoreThanHalfALineApartAreDifferentRows() {
        val detected = listOf(
            item(500, 100, 900, 130, "upper"),
            item(0, 120, 400, 150, "lower"),
        )
        assertEquals("upper\nlower", ReadingOrder.join(detected))
    }

    /** 빈 글은 건너뛰고, 상자가 없는 줄은 맨 뒤에 받은 순서대로. */
    @Test
    fun blankLinesAreSkippedAndUnboxedLinesGoLast() {
        val detected = listOf(
            Item(null, "loose"),
            item(0, 100, 400, 130, "  "),
            item(0, 150, 400, 180, "body"),
        )
        assertEquals("body\nloose", ReadingOrder.join(detected))
    }

    /** 행의 방향은 그 행 글자의 과반으로 — 숫자·기호는 세지 않는다. */
    @Test
    fun rowDirectionFollowsTheMajorityOfLetters() {
        val arabicWithNumber = listOf(item(0, 10, 100, 40, "2026"), item(150, 10, 400, 40, "سنة"))
        assertEquals("سنة 2026", ReadingOrder.join(arabicWithNumber))
        val latinWithArabicWord = listOf(item(0, 10, 300, 40, "Welcome to"), item(350, 10, 450, 40, "دبي"))
        assertEquals("Welcome to دبي", ReadingOrder.join(latinWithArabicWord))
    }

    // --- 문단으로 묶인 줄(§24) ---

    /** 한 단의 줄을 한 문단으로. 행 높이 30, 행 사이 20. */
    private fun column(left: Int, right: Int, top: Int, vararg texts: String) =
        texts.mapIndexed { i, text -> item(left, top + i * 50, right, top + i * 50 + 30, text) }

    /** 두 단의 행이 가지런해도(행 사이마다 가로 틈이 있어도) 단이 먼저 갈린다. */
    @Test
    fun alignedTwoColumnsReadColumnByColumn() {
        val left = column(0, 400, 100, "L1", "L2", "L3")
        val right = column(500, 900, 100, "R1", "R2", "R3")
        assertEquals("L1\nL2\nL3\nR1\nR2\nR3", ReadingOrder.joinParagraphs(listOf(right, left), rightToLeft = false))
    }

    /** 오른쪽→왼쪽 글은 오른쪽 단부터. */
    @Test
    fun rightToLeftColumnsStartOnTheRight() {
        val left = column(0, 400, 100, "ب١", "ب٢", "ب٣")
        val right = column(500, 900, 100, "أ١", "أ٢", "أ٣")
        assertEquals("أ١\nأ٢\nأ٣\nب١\nب٢\nب٣", ReadingOrder.joinParagraphs(listOf(left, right), rightToLeft = true))
    }

    /** 두 단 위를 가로지르는 제목이 먼저, 단 안에서는 문단이 위에서 아래로 — 두 단의 문단 경계가 같은 높이(y 250~300)에 와도. */
    @Test
    fun headingThenColumnsThenParagraphsDownEachColumn() {
        val heading = listOf(item(0, 0, 900, 40, "Heading"))
        val l1 = column(0, 400, 100, "L1a", "L1b", "L1c")
        val l2 = column(0, 400, 300, "L2a", "L2b", "L2c")
        val r1 = column(500, 900, 100, "R1a", "R1b", "R1c")
        val r2 = column(500, 900, 300, "R2a", "R2b", "R2c")
        assertEquals(
            "Heading\nL1a\nL1b\nL1c\nL2a\nL2b\nL2c\nR1a\nR1b\nR1c\nR2a\nR2b\nR2c",
            ReadingOrder.joinParagraphs(listOf(r2, l2, heading, r1, l1), rightToLeft = false),
        )
    }

    /**
     * 조립기가 정보 상자의 이름 칸 넷, 값 칸 넷을 각각 한 문단으로 묶어 준다(행 간격이 고르다 — bg705). 짧은 행이라 흘러내리는 문단이 아니다 —
     * 행마다 나눠 행 단위로 읽는다.
     */
    @Test
    fun groupedInfoboxCellsReadRowByRow() {
        val names = listOf(item(70, 100, 250, 150, "Length"), item(70, 220, 230, 270, "Width"), item(70, 340, 300, 390, "Depth"), item(70, 460, 200, 510, "Area"))
        val values = listOf(item(740, 100, 920, 150, "1166 km"), item(740, 220, 900, 270, "624 km"), item(740, 340, 1250, 390, "2245 m (1253 m)"), item(740, 460, 1000, 510, "436 402 km²"))
        assertEquals(
            "Length 1166 km\nWidth 624 km\nDepth 2245 m (1253 m)\nArea 436 402 km²",
            ReadingOrder.joinParagraphs(listOf(values, names), rightToLeft = false),
        )
    }

    /** 표 — 칸마다 한 줄짜리 문단이면 행 사이의 가로 틈이 먼저 갈라 행 단위로 읽는다. 같은 행의 칸은 빈칸으로 잇는다. */
    @Test
    fun singleLineCellsReadRowByRow() {
        val cells = listOf(
            listOf(item(500, 150, 900, 180, "value 2")),
            listOf(item(0, 100, 400, 130, "name 1")),
            listOf(item(0, 150, 400, 180, "name 2")),
            listOf(item(500, 100, 900, 130, "value 1")),
        )
        assertEquals("name 1 value 1\nname 2 value 2", ReadingOrder.joinParagraphs(cells, rightToLeft = false))
    }

    /** 문단 안은 행 규칙 그대로 — 한 행이 상자 둘로 잡힌 아랍어는 오른쪽 상자부터. 상자가 없는 문단은 맨 뒤. */
    @Test
    fun linesInsideAParagraphFollowTheRowRule() {
        val paragraph = listOf(
            item(100, 10, 300, 40, "الثاني"),
            item(350, 11, 600, 41, "الجزء الأول"),
            item(80, 60, 600, 90, "السطر التالي"),
        )
        assertEquals(
            "الجزء الأول الثاني\nالسطر التالي\nloose",
            ReadingOrder.joinParagraphs(listOf(listOf(Item(null, "loose")), paragraph), rightToLeft = true),
        )
    }

    /** 정보 상자 — 이름 칸은 한 줄, 값 칸은 두 줄이어도 행 단위로 읽는다(이름 단에는 여러 행짜리 문단이 없다). */
    @Test
    fun infoboxReadsRowByRowEvenWithAMultiLineValue() {
        val cells = listOf(
            column(0, 300, 100, "Born"),
            column(400, 900, 100, "12 March 1950", "Moscow"),
            column(0, 300, 220, "Died"),
            column(400, 900, 220, "2020"),
        )
        assertEquals("Born 12 March 1950\nMoscow\nDied 2020", ReadingOrder.joinParagraphs(cells, rightToLeft = false))
    }

    /** 상자가 겹쳐 더 가를 수 없으면 위에서 아래로. */
    @Test
    fun overlappingParagraphsFallBackToTopDown() {
        val a = listOf(item(0, 0, 600, 100, "A"))
        val b = listOf(item(300, 80, 900, 180, "B"))
        assertEquals("A\nB", ReadingOrder.joinParagraphs(listOf(b, a), rightToLeft = false))
    }
}
