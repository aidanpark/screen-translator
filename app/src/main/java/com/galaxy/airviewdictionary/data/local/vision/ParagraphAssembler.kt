package com.galaxy.airviewdictionary.data.local.vision

import android.graphics.Bitmap
import android.graphics.Rect
import com.galaxy.airviewdictionary.data.local.vision.model.Char
import com.galaxy.airviewdictionary.extensions._cutDecimal
import com.galaxy.airviewdictionary.extensions.isValid
import com.galaxy.airviewdictionary.data.local.vision.model.Line
import com.galaxy.airviewdictionary.data.local.vision.model.Paragraph
import com.galaxy.airviewdictionary.data.local.vision.model.VisionSingleLineText
import com.galaxy.airviewdictionary.data.local.vision.model.Word
import com.galaxy.airviewdictionary.data.local.vision.ocr.OcrLine
import com.galaxy.airviewdictionary.data.local.vision.ocr.OcrText
import com.galaxy.airviewdictionary.data.local.vision.ocr.OcrWord
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.min

/**
 * 단어 → 줄 → 문단 기하 조립기(`.docs/geometry-assembly-evaluation.md`). 엔진이 준 줄을 [Word] 로 펼치고, 단어를 줄로, 줄을 문단으로 묶고, 나란한 단을
 * 쪼개고 다시 합친다. 기준값은 [AssemblyParams] 로 받는 순수 계산이다 — 평가 하네스가 같은 함수를 그대로 부른다.
 * `VisionRepository` 에서 떼어 냈다(코드 정리 B5 — 1,300줄 저장소에 auto 엔진 고르기와 섞여 있었다). 로직은 옮기기만 했다.
 */
internal object ParagraphAssembler {

    private const val TAG = "ParagraphAssembler"

    /** 상자를 [bitmap] 안으로 자른다. 결과의 폭 · 높이가 0 이하일 수 있다 — 부르는 쪽이 거른다. */
    fun clampToBitmap(box: Rect, bitmap: Bitmap) = Rect(
        box.left.coerceAtLeast(0),
        box.top.coerceAtLeast(0),
        box.right.coerceAtMost(bitmap.width),
        box.bottom.coerceAtMost(bitmap.height),
    )

    /**
     * 엔진이 준 줄([OcrLine])을 조립기의 입력인 [Word] 로 바꾼다.
     *
     * 줄을 읽는 순서로 정렬해 단어를 펼친 뒤, 상자를 이미지 안으로 자르고, 글자 상자가 있는 단어만 남긴다. 예전에는
     * ML Kit 의 `Text.Line → Text.Element → Word` 두 함수였는데 늘 이어서 불렸다. 규칙은 그대로다.
     */
    internal fun ocrLinesToWords(bitmap: Bitmap, lines: List<OcrLine>, writingDirection: WritingDirection): List<Word> =
        ocrWordsToWords(bitmap, sortLinesToWords(lines, writingDirection), writingDirection)

    /**
     * 줄을 읽는 순서(위에서 아래, 같은 높이면 쓰기 방향)로 정렬해 단어를 펼친다. 가로쓰기만 온다 — 세로쓰기는 `textToVerticalParagraphs` 가
     * 열로 조립한다(코드 정리 C2 — 닿지 않던 세로쓰기 분기 둘을 지웠다).
     */
    internal fun sortLinesToWords(textLines: List<OcrLine>, writingDirection: WritingDirection): List<OcrWord> {
        val rtl = writingDirection == WritingDirection.RTL
        return textLines
            .filter { it.boundingBox.isValid() }
            .sortedWith(compareBy<OcrLine> { it.boundingBox!!.top }.thenBy { if (rtl) -it.boundingBox!!.right else it.boundingBox!!.left })
            .flatMap { line -> line.readWords }
    }

    /** 단어 상자를 이미지 안으로 자르고, 글자 상자가 하나라도 있는 단어만 [Word] 로 만든다. */
    internal fun ocrWordsToWords(bitmap: Bitmap, elements: List<OcrWord>, writingDirection: WritingDirection): List<Word> {
        val words = mutableListOf<Word>()

        for (element in elements) {
            element.boundingBox?.let { boundingBox ->
                // BoundingBox 보정 작업 — 이미지 안으로 자른다
                val correctedBoundingBox = clampToBitmap(boundingBox, bitmap)

                // 보정된 boundingBox를 사용하여 너비와 높이를 확인
                if (correctedBoundingBox.width() > 0 && correctedBoundingBox.height() > 0) {
                    val chars = element.symbols
                        .filter { it.boundingBox.isValid() }
                        .map { Char(it.boundingBox!!, it.text, writingDirection) }

                    if (chars.isNotEmpty()) {
                        words.add(Word(correctedBoundingBox, element.text, writingDirection, chars))
                    }
                }
            }
        }
        return words
    }

    /**
     * [Word] 리스트를
     * [Line] 리스트로 변환한다.
     */
    internal fun groupWordsIntoLines(words: List<Word>, writingDirection: WritingDirection, params: AssemblyParams): List<Line> = with(params) {
        val lines = mutableListOf<Line>()

        VisionSingleLineText.sortedForReading(words, writingDirection)
            .forEach { word ->
                var addedToLine = false

                for (line in lines) {
                    // 판단하려고 하는 새로운 Word 와 가장 근접한 line 의 Word
                    val closestWord = line.words.minByOrNull { it.getWriteDirectionDistance(word) }!!

                    // word-closestWord 폰트 높이 평균
                    val averageFontHeight: Double = word.getAverageFontHeight(closestWord)

                    // closestWord-word 중심축 거리
                    val axisDistance = word.getAxisDistance(closestWord)

                    //  중심축 거리가 closestWord-word 폰트 높이 평균 보다 크면 같은 라인이 아님
                    if (axisDistance > averageFontHeight) break

                    // 읽기방향 word-closestWord 거리
                    val writeDirectionDistance: Double = word.getWriteDirectionDistance(closestWord).toDouble()

                    // (요소 간 거리 : 요소 평균 높이) 비율
                    //
                    // 기준을 행 안 최대 박스 높이로 바꿔도 보았는데 나아지지 않았다 —
                    // 쪼개진 행 수가 같은 지점에서 묶임·오염이 같은 프론티어에 있었다
                    // (2026-09-23 실측). 기준을 바꾸는 문제가 아니라 한계비가 좁았던 것이다.
                    val writeDirectionDistanceFontHeightRatio: Double =
                        writeDirectionDistance / averageFontHeight

                    /** 판단하려고 하는 새로운 Word 와 기존 Line 에서 새로운 Word 에 가장 근접한 Word 는 읽기방향 일정 거리 이상 떨어져 있지 않아야 한다. */
                    // [condition 0]
                    if (writeDirectionDistanceFontHeightRatio <= WORD_WRITE_DIRECTION_DISTANCE_FONT_HEIGHT_RATIO_LIMIT) {
                        // 행방향 중심축 유사율
                        val axisSimilarityRatio = word.getAxisSimilarityRatio(closestWord)

                        // word-closestWord 폰트 높이 유사율
                        val fontHeightSimilarityRatio = word.getFontHeightSimilarityRatio(closestWord)

                        // 행방향 중심축 유사율 * word-closestWord 폰트 높이 유사율
                        val axisFontHeightSimilarityRatio = axisSimilarityRatio * fontHeightSimilarityRatio

                        /** 판단하려고 하는 새로운 Word 와 기존 Line 에서 새로운 Word 에 가장 근접한 Word 는 행방향으로 동일 선상에 위치하고, 폰트 높이가 유사해야 한다. */
                        // [condition 0-0]
                        if (axisFontHeightSimilarityRatio >= WORD_AXIS_FONT_HEIGHT_SIMILARITY_MINIMUM_RATIO) { // 0.85
                            Timber.tag(TAG).d(
                                "groupWordsIntoLines add 0-0 : "
                                        + "${writeDirectionDistance._cutDecimal()}, "
                                        + "${averageFontHeight._cutDecimal()}, "
                                        + "${writeDirectionDistanceFontHeightRatio._cutDecimal()}, "
                                        + "${axisSimilarityRatio._cutDecimal()}, "
                                        + "${fontHeightSimilarityRatio._cutDecimal()}, "
                                        + "${axisFontHeightSimilarityRatio._cutDecimal()}, "
                                        + "${line.representation}(${line.boundingBox}) + ${word.representation}(${word.boundingBox})"
                            )
                            line.addWord(word)
                            addedToLine = true
                            break
                        }
                        // [condition 0-1]
                        else {
                            Timber.tag(TAG).v(
                                "groupWordsIntoLines drop 0-1 : "
                                        + "${writeDirectionDistance._cutDecimal()}, "
                                        + "${averageFontHeight._cutDecimal()}, "
                                        + "${writeDirectionDistanceFontHeightRatio._cutDecimal()}, "
                                        + "${axisSimilarityRatio._cutDecimal()}, "
                                        + "${fontHeightSimilarityRatio._cutDecimal()}, "
                                        + "${axisFontHeightSimilarityRatio._cutDecimal()}, "
                                        + "${line.representation}(${line.boundingBox}) + ${word.representation}(${word.boundingBox})"
                            )
                        }
                    }
                    // [condition 1]
                    else {
                        Timber.tag(TAG).v(
                            "groupWordsIntoLines drop 1 : "
                                    + "${writeDirectionDistance._cutDecimal()}, "
                                    + "${averageFontHeight._cutDecimal()}, "
                                    + "${writeDirectionDistanceFontHeightRatio._cutDecimal()}, "
                                    + "${line.representation}(${line.boundingBox}) + ${word.representation}(${word.boundingBox})"
                        )
                    }
                }

                if (!addedToLine) {
                    lines.add(0, Line(mutableListOf(word), writingDirection))
                }
            }

        lines
    }

    /**
     * 화면의 대표 줄 간격. 줄 중심 사이 거리의 중앙값이다.
     *
     * 예전에는 행간을 그 줄의 박스 높이로 나눠 봤는데, 박스 높이는 그 줄에 어떤 글자가
     * 왔느냐에 좌우된다 — 키릴은 대부분의 줄에 디센더가 없어 30px 로 조이고 아랍어는
     * 위아래로 뻗어 44~53px 가 된다. 같은 레이아웃인데도 비율이 1.6 대 0.6 으로 갈려
     * 러시아어 문단이 통째로 잘렸다(2026-09-23 실측).
     * 줄 간격은 레이아웃이 정하는 양이라 문자와 무관하다.
     */
    internal fun referencePitch(lines: List<Line>, writingDirection: WritingDirection): Double {
        val isVertical = writingDirection == WritingDirection.TTB_LTR ||
                writingDirection == WritingDirection.TTB_RTL
        val centers = lines
            .map { if (isVertical) it.boundingBox.centerX() else it.boundingBox.centerY() }
            .sorted()
        if (centers.size < 2) return 0.0
        val pitches = centers.zipWithNext { a, b -> (b - a).toDouble() }.filter { it > 0 }.sorted()
        if (pitches.isEmpty()) return 0.0
        return pitches[pitches.size / 2]
    }

    /** 두 줄의 중심 사이 거리(줄바꿈 방향). */
    private fun pitchBetween(a: Line, b: Line, writingDirection: WritingDirection): Double {
        val isVertical = writingDirection == WritingDirection.TTB_LTR ||
                writingDirection == WritingDirection.TTB_RTL
        return if (isVertical) abs(a.boundingBox.centerX() - b.boundingBox.centerX()).toDouble()
        else abs(a.boundingBox.centerY() - b.boundingBox.centerY()).toDouble()
    }

    /**
     * 분석된 Line 들을 Paragraph 로 클러스터링 한다.
     * 위에서 아래로, 왼쪽에서 오른쪽으로(LTR. RTL 은 반대) List<Line> 을 탐색하면서
     * 선행 Line 과 후행 Line 을 폰트 높이, 줄 간격(행간 · pitch), 줄 채움비, 들여쓰기를 근거로 비교하고 클러스터링 한다([AssemblyParams]).
     */
    internal fun groupLinesIntoParagraphs(lines: List<Line>, writingDirection: WritingDirection, params: AssemblyParams): List<Paragraph> = with(params) {
        val paragraphs = mutableListOf<Paragraph>()
        val referencePitch = referencePitch(lines, writingDirection)
        val pitchLimit = referencePitch * LINE_PITCH_LIMIT
        VisionSingleLineText.sortedForReading(lines, writingDirection)
            .forEach { line ->
                /** 판단하려고 하는 새로운 Line이 이미 분석되어 paragraphs 에 존재한다면 continue forEach loop */
                if (line in paragraphs.flatMap { it.lines }) return@forEach

                var addedToParagraph = false

                for (paragraph in paragraphs) {
                    // 텍스트 읽기 방향에서 일부 겹치는지의 여부
                    val isWriteDirectionOverlaps = line.isWriteDirectionOverlaps(paragraph)

                    // 줄바꿈 방향에서 일부 겹치는지의 여부
                    val isLineReturnDirectionOverlaps = line.isLineReturnDirectionOverlaps(paragraph)

                    /** 판단하려고 하는 새로운 라인과 기존 Paragraph 가 텍스트 읽기 방향과 줄바꿈 방향에서 일부 겹치면 동일 Paragraph 그룹으로 판단한다. */
                    if (isWriteDirectionOverlaps && isLineReturnDirectionOverlaps) {
                        Timber.tag(TAG).d(
                            "groupLinesIntoParagraphs add 0 : "
                                    + "${paragraph.boundingBox}, "
                                    + "${paragraph.representation}(${paragraph.height}), "
                                    + "${line.boundingBox}, "
                                    + "${line.representation}(${line.height})"
                        )

                        paragraph.lines.add(line)
                        addedToParagraph = true
                        break
                    }

                    // 판단하려고 하는 새로운 라인과 가장 근접한 paragraph 의 line (paragraph.lines 의 마지막 element)
                    val closestLine = paragraph.lines.lastOrNull() ?: continue // 없으면 continue

                    // line 과 closestLine 의 행간
                    val lineSpacing = closestLine.getLineReturnDirectionDistance(line)

                    // 줄 간격이 크게 벌어지면 다른 문단으로 본다.
                    // 검출기가 줄을 주는 엔진은 레이아웃이 정하는 pitch 로, 단어에서 줄을
                    // 유도하는 ML Kit 경로는 예전처럼 박스 높이로 잰다(위 주석 참조).
                    if (pitchLimit > 0) {
                        if (pitchBetween(closestLine, line, writingDirection) > pitchLimit) continue
                    } else {
                        if (lineSpacing > closestLine.fontHeight * 1.6) continue
                    }

                    /**
                     * 문단의 마지막 줄이 단을 채우지 못했으면 그 문단은 거기서 끝난 것이다.
                     * 감싸인 글은 마지막 줄만 짧다. 제목과 목록 항목도 짧아 자연히 갈린다.
                     *
                     * 줄 간격보다 센 신호다 — 문단 사이가 거의 붙은 배치에서는 간격만으로는
                     * 구분할 정보가 없지만 이 신호는 남는다(2026-09-23 실측).
                     * 단 너비는 그 문단 줄들의 최대 너비로 본다. 문단 안에서는 대부분의 줄이
                     * 단을 채우므로 안정적이다.
                     */
                    val alongVertical = LINE_MEASURE_ALONG_WRITING_AXIS &&
                            (writingDirection == WritingDirection.TTB_RTL || writingDirection == WritingDirection.TTB_LTR)
                    fun extentOf(l: Line) = if (alongVertical) l.boundingBox.height() else l.boundingBox.width()
                    val columnWidth = paragraph.lines.maxOf { extentOf(it) }
                    if (LINE_FILL_MINIMUM_RATIO > 0 && columnWidth > 0 &&
                        extentOf(closestLine).toDouble() / columnWidth < LINE_FILL_MINIMUM_RATIO
                    ) continue

                    /**
                     * 줄 머리가 단 안쪽으로 들어가 있으면 새 문단의 첫 줄이다.
                     *
                     * 책 조판은 문단 사이를 빈 줄이 아니라 첫 줄 들여쓰기로 구분한다.
                     * 그런 쪽에서는 행간이 아무 정보도 주지 않아 문단이 통째로 붙었다
                     * (실측: 책 15면에서 묶임 90.9% 인데 오염이 전체 단어의 79%).
                     * 들여쓰기는 그 배치에서 유일하게 남는 신호다.
                     *
                     * 기준은 문단 줄들의 머리 중 가장 바깥이다. 문단 안의 이어지는 줄은
                     * 첫 줄보다 바깥에 있으므로 이 검사에 걸리지 않는다.
                     */
                    if (LINE_INDENT_LIMIT > 0) {
                        val isRtl = writingDirection == WritingDirection.RTL
                        // 세로쓰기(축 인식을 켰을 때)는 열의 머리가 위 끝이다.
                        val paragraphHead = when {
                            alongVertical -> paragraph.lines.minOf { it.boundingBox.top }
                            isRtl -> paragraph.lines.maxOf { it.boundingBox.right }
                            else -> paragraph.lines.minOf { it.boundingBox.left }
                        }
                        val lineHead = when {
                            alongVertical -> line.boundingBox.top
                            isRtl -> line.boundingBox.right
                            else -> line.boundingBox.left
                        }
                        val indent = if (isRtl && !alongVertical) paragraphHead - lineHead else lineHead - paragraphHead

                        /**
                         * 들여쓰기만으로 끊으면 웹이 깨진다 — 인용문·중첩목록·코드블록처럼
                         * 문단 시작이 아닌 들여쓰기가 흔하기 때문이다(실측: 웹 83면에서
                         * 온전한 문단 74.9% → 67.2%). 앞 줄이 단을 못 채웠다는 조건을
                         * 함께 요구하면 두 신호가 동의할 때만 끊는다. 책 조판에서는 문단
                         * 마지막 줄이 짧고 다음 줄이 들여쓰기되어 둘이 같이 성립한다.
                         */
                        val filled = if (columnWidth > 0)
                            extentOf(closestLine).toDouble() / columnWidth else 1.0
                        if (indent > line.fontHeight * LINE_INDENT_LIMIT &&
                            filled < LINE_INDENT_FILL_GUARD
                        ) continue
                    }

                    // line-closestLine 폰트높이 평균
                    val averageFontHeight: Double = line.getAverageFontHeight(closestLine)

                    /** 판단하려고 하는 새로운 라인과 기존 Paragraph 내 라인들의 평균 폰트높이가 FONT_HEIGHT_SIMILARITY_MINIMUM_RATIO 이상의 유사성을 가지고 있어야 한다. */
                    // line-closestLine 폰트높이 유사성
                    val fontHeightSimilarityRatio = line.getFontHeightSimilarityRatio(closestLine)

                    // [condition 0] 폰트높이 유사성 조건에 부합하는 경우
                    if (fontHeightSimilarityRatio >= LINE_FONT_HEIGHT_SIMILARITY_MINIMUM_RATIO) {
                        // [condition 0-0] 텍스트 읽기 방향으로 일부 겹치는 경우
                        if (isWriteDirectionOverlaps) {
                            /** 판단하려고 하는 새로운 라인과 Paragraph 는 텍스트 읽기 방향으로 LINE_WRITE_DIRECTION_OVERLAP_MINIMUM_RATIO 비율 이상 겹쳐야 한다. */
                            // 텍스트 읽기 방향으로 width 가 작은 것이 큰 것에 겹치는 비율
                            val writeDirectionOverlapRatio: Double = line.getWriteDirectionOverlapRatio(paragraph)

                            // [condition 0-0-0] 텍스트 읽기 방향 겹침조건 부합하는 경우
                            if (writeDirectionOverlapRatio >= LINE_WRITE_DIRECTION_OVERLAP_MINIMUM_RATIO) {
                                /**
                                 * closestLine 폰트높이의 유사성과 라인 행간 affinity 로 동일 Paragraph 를 판단한다.
                                 *
                                 * 색은 쓰지 않는다. 글자색을 줄에서 재는 일이 실제 화면에서 너무 자주
                                 * 어긋나, 같은 문단인데 색이 다르다고 갈라놓는 쪽이 압도적으로 많았다.
                                 * 색이 막아주던 잘못된 병합은 위의 줄 채움비가 대신 막는다.
                                 * 실제 웹 83면 표본에서 색을 빼고 채움비를 켜니 문단 오염이
                                 * 261 → 43 줄로 줄고 묶임은 1606 → 1668 줄로 늘었다(2026-09-23 실측).
                                 */
                                // 라인 affinity ({행간 : 요소 평균 높이} 비)
                                // 줄 간격이 화면 대표 간격에 가까울수록 1 에 가깝다.
                                // 박스 높이로 나누면 문자마다 기준이 달라진다 — 키릴은 박스가
                                // 조여 같은 레이아웃에서도 affinity 가 0.58 까지 떨어졌다.
                                val pitch = pitchBetween(closestLine, line, writingDirection)
                                val lineSpacingAffinity =
                                    if (pitchLimit > 0 && referencePitch > 0 && pitch > 0)
                                        min(1.0, referencePitch / pitch)
                                    else min(1.0, 1.0 / (lineSpacing.toDouble() / averageFontHeight))

                                // 폰트높이 유사성 * {행간 : 요소 평균 높이} 비 affinity
                                val fontHeightLineSpacingAffinity =
                                    fontHeightSimilarityRatio * lineSpacingAffinity

                                // [condition 0-0-0-0] 
                                if (fontHeightLineSpacingAffinity >= LINE_FONT_HEIGHT_SPACING_AFFINITY_LIMIT) {
                                    Timber.tag(TAG).d(
                                        "groupLinesIntoParagraphs add 0-0-0-0 : "
                                                + "${fontHeightSimilarityRatio._cutDecimal()}, "
                                                + "${lineSpacingAffinity._cutDecimal()}, "
                                                + "*${fontHeightLineSpacingAffinity._cutDecimal()}, "
                                                + "${closestLine.representation}(${closestLine.height}) + ${line.representation}(${line.height})"
                                    )
                                    paragraph.lines.add(line)
                                    addedToParagraph = true
                                    break
                                }
                                // [condition 0-0-0-1] 
                                else {
                                    Timber.tag(TAG).v(
                                        "groupLinesIntoParagraphs drop 0-0-0-1 : "
                                                + "${fontHeightSimilarityRatio._cutDecimal()}, "
                                                + "${lineSpacingAffinity._cutDecimal()}, "
                                                + "*${fontHeightLineSpacingAffinity._cutDecimal()}, "
                                                + "${closestLine.representation}(${closestLine.height}) + ${line.representation}(${line.height})"
                                    )
                                }
                            }
                            // [condition 0-0-1] 
                            else {
                                Timber.tag(TAG).v(
                                    "groupLinesIntoParagraphs drop 0-0-1 : "
                                            + "${fontHeightSimilarityRatio._cutDecimal()}, "
                                            + "${writeDirectionOverlapRatio._cutDecimal()}, "
                                            + "${closestLine.representation}(${closestLine.height}) + ${line.representation}(${line.height})"
                                )
                            }
                        }

                        // [condition 0-1] 줄바꿈 방향에서 일부 겹치는 경우
                        else if (isLineReturnDirectionOverlaps) {
                            /** 판단하려고 하는 새로운 라인과 기존 Paragraph 에서 새로운 라인에 가장 근접한 라인은 줄바꿈 방향으로 동일 선상에 위치해야 한다. */
                            // 라인 중심축 유사율
                            val axisSimilarityRatio = line.getAxisSimilarityRatio(closestLine)

                            // 라인 중심축 유사율 * line-closestLine 높이 유사율
                            val axisHeightSimilarityRatio = axisSimilarityRatio * fontHeightSimilarityRatio

                            // [condition 0-1-0]
                            if (axisHeightSimilarityRatio >= LINE_AXIS_HEIGHT_SIMILARITY_MINIMUM_RATIO) {
                                /** 새로운 라인과 기존 Paragraph 에서 새로운 라인에 가장 근접한 라인은 텍스트 읽기 방향 일정 거리 이상 떨어져 있지 않아야 한다. */
                                // 텍스트 읽기 방향 Line-Line 거리
                                val writeDirectionDistance: Double = line.getWriteDirectionDistance(closestLine).toDouble()

                                // (요소 간 거리 : 요소 평균 폰트높이) 비율
                                val writeDirectionDistanceFontHeightRatio: Double = writeDirectionDistance / averageFontHeight

                                // [condition 0-1-0-0]
                                if (writeDirectionDistanceFontHeightRatio <= LINE_WRITE_DIRECTION_DISTANCE_FONT_HEIGHT_RATIO_LIMIT) {
                                    Timber.tag(TAG).d(
                                        "groupLinesIntoParagraphs add 0-1-0-0 : "
                                                + "${fontHeightSimilarityRatio._cutDecimal()}, "
                                                + "${axisSimilarityRatio._cutDecimal()}, "
                                                + "${axisHeightSimilarityRatio._cutDecimal()}, "
                                                + "${writeDirectionDistance._cutDecimal()}, "
                                                + "${writeDirectionDistanceFontHeightRatio._cutDecimal()}, "
                                                + "${closestLine.representation}(${closestLine.height}) + ${line.representation}(${line.height}))"
                                    )

                                    if (writingDirection == WritingDirection.LTR) {
                                        if (closestLine.boundingBox.right < line.boundingBox.right) {
                                            paragraph.lines.add(line)
                                        } else {
                                            paragraph.lines.add(paragraph.lines.size - 1, line)
                                        }
                                    } else if (writingDirection == WritingDirection.TTB_RTL ||
                                        writingDirection == WritingDirection.TTB_LTR
                                    ) {
                                        // 세로쓰기에서 줄바꿈 방향으로 겹친다는 것은 ML Kit 이 한 열을 여러
                                        // 조각으로 끊었다는 뜻이다. 열 안의 순서는 위에서 아래다 — 가로쓰기처럼
                                        // 왼쪽 끝으로 자리를 정하면 조각 순서가 뒤섞인다(실측: ja131).
                                        if (closestLine.boundingBox.top < line.boundingBox.top) {
                                            paragraph.lines.add(line)
                                        } else {
                                            paragraph.lines.add(paragraph.lines.size - 1, line)
                                        }
                                    } else {
                                        if (line.boundingBox.left < closestLine.boundingBox.left) {
                                            paragraph.lines.add(line)
                                        } else {
                                            paragraph.lines.add(paragraph.lines.size - 1, line)
                                        }
                                    }
                                    // 복수의 Line 들이 하나의 행을 이루게 된다
                                    paragraph.hasParallelLines = true
                                    addedToParagraph = true
                                    break
                                }
                                // [condition 0-1-0-1]
                                else {
                                    Timber.tag(TAG).v(
                                        "groupLinesIntoParagraphs drop 0-1-0-1 : "
                                                + "${fontHeightSimilarityRatio._cutDecimal()}, "
                                                + "${axisSimilarityRatio._cutDecimal()}, "
                                                + "${axisHeightSimilarityRatio._cutDecimal()}, "
                                                + "${writeDirectionDistance._cutDecimal()}, "
                                                + "${writeDirectionDistanceFontHeightRatio}, "
                                                + "${LINE_WRITE_DIRECTION_DISTANCE_FONT_HEIGHT_RATIO_LIMIT}, "
                                                + "${(writeDirectionDistanceFontHeightRatio <= LINE_WRITE_DIRECTION_DISTANCE_FONT_HEIGHT_RATIO_LIMIT)}, "
                                                + "${closestLine.representation}(${closestLine.height}) + ${line.representation}(${line.height}))"
                                    )
                                }
                            }
                            // [condition 0-1-1]
                            else {
                                Timber.tag(TAG).v(
                                    "groupLinesIntoParagraphs drop 0-1-1 : "
                                            + "${fontHeightSimilarityRatio._cutDecimal()}, "
                                            + "${axisSimilarityRatio._cutDecimal()}, "
                                            + "${axisHeightSimilarityRatio._cutDecimal()}, "
                                            + "${closestLine.representation}(${closestLine.height}) + ${line.representation}(${line.height}))"
                                )
                            }
                        }
                        // [condition 0-2]
                        else {
                            Timber.tag(TAG).v(
                                "groupLinesIntoParagraphs drop 0-2 : "
                                        + "${fontHeightSimilarityRatio._cutDecimal()}, "
                                        + "${closestLine.representation}(${closestLine.height}) + ${line.representation}(${line.height}))"
                            )
                        }
                    }
                    // [condition 1]
                    else {
                        Timber.tag(TAG).v(
                            "groupLinesIntoParagraphs drop 1 : "
                                    + "${fontHeightSimilarityRatio._cutDecimal()}, "
                                    + "${closestLine.representation}(${closestLine.height}) + ${line.representation}(${line.height}))"
                        )
                    }
                }

                if (!addedToParagraph) {
                    paragraphs.add(0, Paragraph(mutableListOf(line), writingDirection))
                }
            }

        paragraphs
    }

    /**
     * 가로 경로의 쪼개기 후처리 — 문단마다 나란한 단을 쪼갠 뒤([detectAndSplitParagraphs]) 잘못 쪼갠 것을 다시 합친다([correctDetectAndSplitParagraphs]).
     * [groupLinesIntoParagraphs] 는 세로로 나뉜 단락을 가르기 어렵다. 세 경로(ML Kit 가로 · 세로 화면의 가로 부분 · 검출기)가 같이 쓴다(코드 정리 B5).
     */
    fun splitColumns(paragraphs: List<Paragraph>, writingDirection: WritingDirection): List<Paragraph> = paragraphs.flatMap { paragraph ->
        val split = detectAndSplitParagraphs(paragraph, writingDirection)
        split.forEach { Timber.tag(TAG).d("detectAndSplitParagraphs ${it.boundingBox} ${it.representation} ") }
        correctDetectAndSplitParagraphs(split, writingDirection)
            .onEach { Timber.tag(TAG).d("correctDetectAndSplitParagraphs ${it.boundingBox} ${it.representation} ") }
    }

    /**
     * groupLinesIntoParagraphs 에서 분석된 Paragraph 중
     *
     *        △ △ △ △ △ △ △ △  ○ ○ ○ ○ ○ ○ ○ ○
     *        △ △ △ △ △ △ △ △  ○ ○ ○ ○ ○ ○ ○ ○
     *        △ △ △ △ △ △ △ △  ○ ○ ○ ○ ○ ○ ○ ○
     *        △ △ △ △ △ △ △ △  ○ ○ ○ ○ ○ ○ ○ ○
     *
     * 이런 Paragraph 의 경우 △ 단락과 ○ 단락이 있으나, groupLinesIntoParagraphs 에서 분리해 내지 못한다.
     *
     * 복수의 Line 들이 하나의 행을 이루는 것이 있는 Paragraph 를 분석하여
     * 세로로 단락 구분이 가능한지 확인한다.
     * DBSCAN 기법을 이용하되, 요소 간 거리는 x축 기준으로 판단하여 Line 의 시작위치가 비슷한 것 끼리 클러스터링 한다.
     */
    internal fun detectAndSplitParagraphs(paragraph: Paragraph, writingDirection: WritingDirection): List<Paragraph> {
        if (!paragraph.hasParallelLines || paragraph.lines.size == 1) {
            return listOf(paragraph)
        }

        // lines 가 하나의 라인을 이루는 경우
        if (paragraph.areAllInLine()) {
            return listOf(paragraph)
        }

        Timber.tag(TAG).d("detectAndSplitParagraphs ${paragraph.representation}")

        val lines = paragraph.lines
        val clustersVisited = mutableSetOf<Line>()
        val clusters = mutableListOf<MutableList<Line>>()
        val distanceLimit: Double = paragraph.averageLineHeight()

        // 클러스터 확장 및 탐색
        // 각 Line을 기준으로 이웃하는 Line을 찾고, 이를 클러스터에 추가하며 재귀적으로 탐색한다
        fun expandCluster(line: Line, cluster: MutableList<Line>) {
            val neighbors =
                lines.filter {
                    if (it != line) {
                        Timber.tag(TAG).i(
                            "Split cluster "
                                    + "$distanceLimit, ${abs(line.startPosition - it.startPosition)}, ${line.boundingBox}, ${line.representation}, ${it.boundingBox}, ${it.representation}"
                        )
                    }
                    it != line && abs(line.startPosition - it.startPosition) <= distanceLimit
                }

            cluster.add(line)
            clustersVisited.add(line)
            neighbors.forEach {
                if (!clustersVisited.contains(it)) {
                    expandCluster(it, cluster)
                }
            }
        }

        // 클러스터 그룹화
        lines.forEach { line ->
            if (!clustersVisited.contains(line)) {
                val cluster = mutableListOf<Line>()
                expandCluster(line, cluster)
                clusters.add(cluster)
            }
        }

        // 묶음은 탐색 순서로 쌓여 줄 순서가 읽는 순서와 다르다. 가로쓰기는 뒤이은
        // correctDetectAndSplitParagraphs 가 다시 합치며 정렬하지만 세로 분기에는 그 단계가 없어,
        // 세로쓰기는 여기서 읽는 순서로 놓는다(실측: ja131 한 문단의 열 조각이 뒤섞였다).
        val vertical = writingDirection == WritingDirection.TTB_RTL || writingDirection == WritingDirection.TTB_LTR
        return clusters.map { cluster ->
            val ordered = if (vertical) VisionSingleLineText.sortedForReading(cluster, writingDirection) else cluster
            Paragraph(ordered.toMutableList(), writingDirection)
        }
    }

    /**
     * groupLinesIntoParagraphs 에서 분석된 Paragraph 중
     *
     *        ○ ○ ○ ○ ○ ○ ○ ○  ○ ○ ○ ○ ○ ○ ○ ○
     *            ○ ○ ○ ○ ○ ○ ○ ○ ○ ○ ○
     *                  ○ ○ ○ ○ ○ ○ ○ ○ ○ ○
     *
     * 이런 Paragraph 의 경우 detectAndSplitParagraphs 검증을 하게 되면
     *
     *        ○ ○ ○ ○ ○ ○ ○ ○  △ △ △ △ △ △ △ △
     *            ▲ ▲ ▲ ▲ ▲ ▲ ▲ ▲ ▲ ▲ ▲
     *                  ◇ ◇ ◇ ◇ ◇ ◇ ◇ ◇ ◇ ◇
     *
     * 와 같이 모두 분리된 Paragraph 로 인식 되므로
     * 이를 보정하여 하나의 Paragraph 로 클러스터링 한다.
     */
    internal fun correctDetectAndSplitParagraphs(paragraphs: List<Paragraph>, writingDirection: WritingDirection): List<Paragraph> {
        val clustersVisited = mutableSetOf<Paragraph>()
        val clusters = mutableListOf<MutableList<Paragraph>>()

        fun expandCluster(paragraph: Paragraph, cluster: MutableList<Paragraph>) {
            val neighbors = paragraphs.filter {
                if (it != paragraph) {
                    Timber.tag(TAG)
                        .i("Correct cluster ${paragraph.isWriteDirectionOverlaps(it)}, ${paragraph.boundingBox}, ${paragraph.representation}, ${it.boundingBox}, ${it.representation}")
                }
                it != paragraph && paragraph.isWriteDirectionOverlaps(it)
            }
            cluster.add(paragraph)
            clustersVisited.add(paragraph)
            neighbors.forEach {
                if (!clustersVisited.contains(it)) {
                    expandCluster(it, cluster)
                }
            }
        }

        paragraphs.forEach { paragraph ->
            if (!clustersVisited.contains(paragraph)) {
                val cluster = mutableListOf<Paragraph>()
                expandCluster(paragraph, cluster)
                clusters.add(cluster)
            }
        }

        fun mergeParagraphs(paragraphs: List<Paragraph>): Paragraph {
            val allLines = VisionSingleLineText.sortedForReading(paragraphs.flatMap { it.lines }, writingDirection)
                .toMutableList()
            return Paragraph(allLines, writingDirection)
        }

        return clusters.map { cluster -> mergeParagraphs(cluster) }
    }

    /**
     * OCR 원문이 가로쓰기인지 세로쓰기인지 확인한다.
     *
     * 가로로 긴 줄과 그렇지 않은 줄을 **글자 수로** 저울질한다. 예전에는 줄 개수로 다수결을 했는데,
     * 한두 글자짜리 줄은 상자가 폭보다 높아 세로 표로 잡힌다 — 축구 순위표처럼 "1", "38", "W" 칸이
     * 많은 가로 화면이 세로쓰기로 판정되어 화면 전체가 세로 분기로 갔다(2026-09-24 실측, BBC 순위표).
     * 글자 수로 세면 긴 세로 열과 긴 가로 문장이 판정을 정하고 짧은 칸은 거의 무게가 없다.
     * 표본 343면(세로 86)에서 오판 0, 세로 표의 비율이 세로 표본은 0.84 이상·가로 표본은 0.11 이하로
     * 벌어진다(줄 개수로는 0.61 과 0.56 이라 경계에 붙어 있었다).
     */
    internal fun detectVerticalWriting(text: OcrText): Boolean {
        var horizontalChars = 0
        var verticalChars = 0

        for (textBlock in text.blocks) {
            for (line in textBlock.lines) {
                line.boundingBox?.let {
                    val chars = line.text.count { c -> !c.isWhitespace() }
                    if (it.width() > it.height() && it.height() > 0) {
                        horizontalChars += chars
                    } else {
                        verticalChars += chars
                    }
                }
            }
        }
        Timber.tag(TAG).i("isVerticalWriting  $horizontalChars $verticalChars")
        return horizontalChars < verticalChars
    }
}
