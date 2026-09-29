package com.galaxy.airviewdictionary.ocrbench

import com.galaxy.airviewdictionary.data.local.vision.AssemblyParams

/**
 * 조립 상수 한 벌. 지정하지 않은 값은 언어별 기본값(= 출시값)을 쓴다.
 *
 * 계측 인자로 받는다. 예전에는 설정 목록이 테스트 코드에 박혀 있어 실험마다 코드를
 * 고쳤는데(한 세션에 열다섯 번), 그러면 실험이 무거워지고 "무엇을 어떻게 돌리는가" 를
 * 문서나 스킬에 적을 수가 없다.
 *
 * 문법: 설정은 `;` 로 나누고, 하나는 `라벨=키:값,키:값` 이다. 라벨은 생략할 수 있다.
 *
 *     sweep='기준;느슨=indent:0.5,guard:0.95;끔=indent:0'
 *
 * 키는 [KEYS] 를 보라. 키 앞에 `v.` 를 붙이면 **세로 부분에만** 준다(`v.fill:0.8`). 세로 분기는
 * 한 화면의 세로 부분과 가로 부분을 따로 조립하므로, 접두사 없이 주면 세로 페이지의 가로
 * 부분까지 바뀐다. 가로 표본에서는 `v.` 키가 아무 일도 하지 않는다.
 */
class EvalSetting(val label: String, val values: Map<String, Double>) {

    /**
     * 문단 묶기 뒤의 후처리(`detectAndSplitParagraphs`, `correctDetectAndSplitParagraphs`)를
     * 돌릴지. 프로덕션은 늘 돌린다. `post:0` 으로 끄면 두 단계가 숫자를 얼마나 바꾸는지를
     * 같은 실행 안에서 따로 볼 수 있다.
     */
    val postProcess: Boolean get() = (values["post"] ?: 1.0) != 0.0

    /**
     * 이 설정의 기준값 한 벌 — 출시값을 채우고 이 설정의 값으로 덮는다.
     *
     * [vertical] 을 따로 받는 이유 — 프로덕션 세로 분기는 한 화면 안에서 세로 부분에는
     * `AssemblyParams.reference(true, …)`, 가로 부분에는 `(false, …)` 를 따로 쓴다. 그래서
     * 한 표본을 조립하는 데 두 벌이 필요할 수 있다.
     */
    internal fun paramsFor(sample: Sample, vertical: Boolean = sample.isVertical): AssemblyParams {
        // 검출기 경로는 기준값이 다르다 — 줄 박스가 글자에 붙어 채움비가 또렷하고, 줄 간격을
        // 박스 높이가 아니라 화면 대표 pitch 로 잰다.
        var params = AssemblyParams.reference(vertical, sample.lang, sample.linesFromDetector)
        for ((rawKey, value) in values) {
            if (rawKey.startsWith(VERTICAL_ONLY) && !vertical) continue
            params = params.withKey(rawKey.removePrefix(VERTICAL_ONLY), value)
        }
        return params
    }

    private fun AssemblyParams.withKey(key: String, value: Double): AssemblyParams = when (key) {
        "post" -> this // 상수가 아니라 조립 절차의 선택이다. [postProcess] 가 읽는다
        "indent" -> copy(LINE_INDENT_LIMIT = value)
        "guard" -> copy(LINE_INDENT_FILL_GUARD = value)
        "fill" -> copy(LINE_FILL_MINIMUM_RATIO = value)
        "affinity" -> copy(LINE_FONT_HEIGHT_SPACING_AFFINITY_LIMIT = value)
        "lineHeight" -> copy(LINE_FONT_HEIGHT_SIMILARITY_MINIMUM_RATIO = value)
        "overlap" -> copy(LINE_WRITE_DIRECTION_OVERLAP_MINIMUM_RATIO = value)
        "pitch" -> copy(LINE_PITCH_LIMIT = value)
        "wordGap" -> copy(WORD_WRITE_DIRECTION_DISTANCE_FONT_HEIGHT_RATIO_LIMIT = value)
        "wordAxis" -> copy(WORD_AXIS_FONT_HEIGHT_SIMILARITY_MINIMUM_RATIO = value)
        "axis" -> copy(LINE_MEASURE_ALONG_WRITING_AXIS = value != 0.0)
        "head" -> copy(LINE_HEADING_HEIGHT_RATIO = value)
        "hwidth" -> copy(LINE_HEADING_WIDTH_RATIO = value)
        "vmerge" -> copy(VERTICAL_COLUMN_MERGE = value != 0.0)
        "vpost" -> copy(VERTICAL_SPLIT = value != 0.0)
        "colgap" -> copy(WORD_COLUMN_GAP_ROWS = value.toInt())
        else -> throw IllegalArgumentException("모르는 키 '$key'. 쓸 수 있는 것: $KEYS")
    }

    companion object {
        val KEYS = listOf(
            "indent", "guard", "fill", "affinity", "lineHeight", "overlap", "pitch",
            "wordGap", "wordAxis", "axis", "head", "hwidth", "vmerge", "vpost", "colgap", "post",
        )

        /** 이 접두사가 붙은 키는 세로 부분에만 적용한다. */
        const val VERTICAL_ONLY = "v."

        /** 아무 값도 바꾸지 않는 설정 — 즉 출시값. */
        fun shipped(label: String = "출시값") = EvalSetting(label, emptyMap())

        fun parse(spec: String?): List<EvalSetting> {
            if (spec.isNullOrBlank()) return listOf(shipped())
            return spec.split(";").filter { it.isNotBlank() }.map { part ->
                val label: String
                val body: String
                val eq = part.indexOf('=')
                if (eq < 0) {
                    label = part.trim(); body = ""
                } else {
                    label = part.substring(0, eq).trim(); body = part.substring(eq + 1)
                }
                val values = body.split(",").filter { it.isNotBlank() }.associate {
                    val kv = it.split(":")
                    require(kv.size == 2) { "설정을 읽을 수 없다: '$it' (키:값 이어야 한다)" }
                    kv[0].trim() to (kv[1].trim().toDoubleOrNull()
                        ?: throw IllegalArgumentException("숫자가 아니다: '${kv[1]}'"))
                }
                for (key in values.keys) require(key.removePrefix(VERTICAL_ONLY) in KEYS) {
                    "모르는 키 '$key'. 쓸 수 있는 것: $KEYS (앞에 v. 를 붙이면 세로 부분에만)"
                }
                EvalSetting(if (label.isEmpty()) body else label, values)
            }
        }
    }
}
