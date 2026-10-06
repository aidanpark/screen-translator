package com.galaxy.airviewdictionary.data.local.vision

import com.galaxy.airviewdictionary.data.local.vision.kit.VisionKitSelector

/**
 * auto 에서 ML Kit 라틴 인식기 하나로 끝내도 되는가 — 성능 실험 P4-2 로 채택한 규칙(`.docs/perf-experiment-plan.md` §5).
 * 라틴 화면이 확실할 때만 멈추고, 아니면 나머지 인식기와 PP-OCRv5 로 지금처럼 고른다.
 *
 * 비라틴 인식기로는 멈추지 않는다 — 중국어·한국어 인식기는 모르는 문자를 제 문자처럼 지어내고 언어 감지도 그렇게 본다(§4.1).
 * 라틴 인식기는 모르는 문자를 버리거나 기호 섞인 쓰레기로 읽으므로, 아래 세 신호로 가려낸다.
 *
 * 순수 계산만 둔다(단위 시험). 상자는 (left, top, right, bottom).
 */
internal object AutoLatinStop {

    /** 끄면 auto 가 예전처럼 인식기 다섯을 모두 돌린다. 기기 시험에서 앞뒤를 견줄 때만 끈다. */
    @Volatile
    var enabled = true

    /** 라틴 글자 수(공백 뺌)가 이보다 적으면 멈추지 않는다. */
    const val MIN_CHARS = 20

    /** 줄 언어·쓰레기 없는 줄의 글자 비율 문턱(α). */
    const val MIN_LINE_SHARE = 0.8

    /** PP-OCRv5 검출 상자 넓이 중 라틴 줄 상자가 덮은 비율 문턱(k). */
    const val MIN_COVERAGE = 0.6

    /** 라틴 인식기가 남의 문자를 읽을 때 내는 기호. 이것이 든 줄은 깨끗하지 않다. */
    private val GARBAGE = "|{}[]\\<>~^_`#@*=+".toSet()

    /** 덮은 비율을 셀 때 쓰는 격자 한 칸(화소). */
    private const val CELL = 4

    /**
     * 줄 언어가 라틴 인식기의 언어인가 — und 가 아니고, 지정했을 때 ML Kit 전용 인식기(중국어·한국어·일본어·데바나가리)로 가는 언어가 아니다.
     * 평가 때(`stop2.py` 의 kit_for)처럼 PP-OCRv5 문자권 언어(ru·ar 등)도 여기서는 라틴 쪽으로 센다 — 그런 화면은 PP 관문이 막는다.
     */
    fun isLatinReaderLanguage(code: String): Boolean {
        if (code == "und") return false
        // VisionKitSelector.candidatesFor 와 같은 판정(평가 스크립트도 그대로 흉내 냈다) — hi-Latn 같은 로마자 표기는 라틴으로 센다
        // 데바나가리 표는 인식기 고르기와 같은 것을 쓴다(코드 정리 A5 — 따로 둔 4개짜리 표가 9개짜리 표와 갈라져 있었다. 더 있는 5개는 예전
        // 엔진의 저장값이라 ML Kit 언어 감지가 내놓지 않는다)
        // 한중일은 접두사로 본다(ja-Latn 도 비라틴) — 평가 때의 판정 그대로다(코드 정리 B4 는 표만 같이 쓰고 판정은 바꾸지 않았다)
        return !(VisionKitSelector.MLKIT_SCRIPT_LANGUAGES.any { code.startsWith(it) } || code in VisionKitSelector.DEVANAGARI_LANGUAGES)
    }

    fun chars(text: String): Int = text.count { !it.isWhitespace() }

    /** 라틴 글만으로 볼 수 있는 신호(글자 수·쓰레기 기호)가 서는가. 서지 않으면 PP-OCRv5 검출을 기다릴 것 없이 나머지 인식기로 간다. */
    fun passesTextChecks(lineTexts: List<String>): Boolean {
        val n = lineTexts.map(::chars)
        val total = n.sum()
        if (total < MIN_CHARS) return false
        val clean = lineTexts.indices.sumOf { i -> if (lineTexts[i].any { it in GARBAGE }) 0 else n[i] }
        return clean.toDouble() / total >= MIN_LINE_SHARE
    }

    /** PP-OCRv5 검출 대비 덮은 비율이 문턱 이상인가. */
    fun passesCoverage(lineBoxes: List<IntArray?>, detectorBoxes: List<IntArray>, width: Int, height: Int): Boolean =
        coverage(lineBoxes.filterNotNull(), detectorBoxes, width, height) >= MIN_COVERAGE

    /** 줄마다 감지한 언어로 본 라틴 줄의 글자 비율이 문턱 이상인가. */
    fun passesLineLanguages(lineTexts: List<String>, lineLanguages: List<String>): Boolean {
        val n = lineTexts.map(::chars)
        val total = n.sum()
        if (total == 0) return false
        val latin = lineTexts.indices.sumOf { i -> if (isLatinReaderLanguage(lineLanguages[i])) n[i] else 0 }
        return latin.toDouble() / total >= MIN_LINE_SHARE
    }

    /** PP-OCRv5 검출 상자 합집합 넓이 중 라틴 줄 상자 합집합이 덮은 비율(격자 [CELL] 화소). 검출 상자가 없으면 0. */
    fun coverage(latinBoxes: List<IntArray>, detectorBoxes: List<IntArray>, width: Int, height: Int): Double {
        if (detectorBoxes.isEmpty()) return 0.0
        val w = width / CELL + 1
        val h = height / CELL + 1
        val det = BooleanArray(w * h)
        val lat = BooleanArray(w * h)
        fun paint(grid: BooleanArray, b: IntArray) {
            val l = (b[0].coerceAtLeast(0) / CELL).coerceAtMost(w - 1)
            val t = (b[1].coerceAtLeast(0) / CELL).coerceAtMost(h - 1)
            val r = (b[2].coerceAtLeast(0) / CELL).coerceAtMost(w - 1)
            val bo = (b[3].coerceAtLeast(0) / CELL).coerceAtMost(h - 1)
            for (y in t..bo) for (x in l..r) grid[y * w + x] = true
        }
        detectorBoxes.forEach { paint(det, it) }
        latinBoxes.forEach { paint(lat, it) }
        var area = 0
        var covered = 0
        for (i in det.indices) if (det[i]) {
            area++
            if (lat[i]) covered++
        }
        return if (area == 0) 0.0 else covered.toDouble() / area
    }
}
