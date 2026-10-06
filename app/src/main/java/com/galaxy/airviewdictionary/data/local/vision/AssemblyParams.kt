package com.galaxy.airviewdictionary.data.local.vision

import com.galaxy.airviewdictionary.data.remote.translation.Language

/**
 * 조립 기준값 한 벌. 조립 함수([ParagraphAssembler.groupWordsIntoLines] 등)가 인자로 받는다.
 *
 * 예전에는 [VisionRepository](싱글턴)의 필드였다. 요청마다 `setReferenceConstantValue` 로 채우고 조립했는데, 요청이 겹치면(앞 제스처의
 * auto 인식이 새 제스처와 함께 돌 때 등) 한 요청이 다른 요청의 값으로 조립했다. 이제 요청마다 [reference] 로 만든 값을 조립 끝까지 들고 간다.
 * 평가 하네스는 [copy] 로 값을 바꿔 쓴다.
 *
 * 이름은 옛 필드 이름 그대로다 — 조립 함수가 `with(params)` 로 읽어 본문이 바뀌지 않고, 측정 기록(`.docs/`)의 이름과도 맞는다.
 */
@Suppress("PropertyName")
internal data class AssemblyParams(
    /**
     * Word 행 중심축 유사판단 + 높이 유사판단 최소 유사율.
     * (1에 가까울 수록 유사하다)
     */
    val WORD_AXIS_FONT_HEIGHT_SIMILARITY_MINIMUM_RATIO: Double,

    /**
     * Word 동일 Line 판단 {요소 간 거리 : 요소 폰트높이 평균} 비율 한계비.
     * (0에 가까울 수록 가깝다)
     */
    val WORD_WRITE_DIRECTION_DISTANCE_FONT_HEIGHT_RATIO_LIMIT: Double,

    /**
     * Line 폰트높이 유사판단 최소 유사율.
     * (1에 가까울 수록 유사하다)
     */
    val LINE_FONT_HEIGHT_SIMILARITY_MINIMUM_RATIO: Double,

    /**
     * Line 동일 Paragraph 판단 텍스트 읽기 방향 최소 겹침 비율.
     */
    val LINE_WRITE_DIRECTION_OVERLAP_MINIMUM_RATIO: Double,

    /**
     * Line 폰트높이 유사성 x {행간 : 요소 폰트높이 평균} 비 affinity 한계비.
     */
    val LINE_FONT_HEIGHT_SPACING_AFFINITY_LIMIT: Double,

    /**
     * Line 행 중심축 유사판단 + 폰트높이 유사판단 최소 유사율.
     * (1에 가까울 수록 유사하다)
     */
    val LINE_AXIS_HEIGHT_SIMILARITY_MINIMUM_RATIO: Double,

    /**
     * Line 동일 Line 판단 {요소 간 거리 : 요소 폰트높이 평균} 비율 한계비.
     * (0에 가까울 수록 가깝다)
     */
    val LINE_WRITE_DIRECTION_DISTANCE_FONT_HEIGHT_RATIO_LIMIT: Double,

    /**
     * 같은 문단으로 볼 줄 간격의 한계비. 화면 대표 줄 간격(중앙값) 대비로 잰다.
     * 실측(4개 언어): 문단 안은 0.90~1.06, 문단 경계는 1.49~3.10 으로 갈린다.
     */
    val LINE_PITCH_LIMIT: Double,

    /**
     * 문단이 이어진다고 볼 최소 단 채움 비율.
     * 감싸인 글에서 문단의 마지막 줄은 단을 다 채우지 못한다 — 그게 문단이 끝났다는 신호다.
     * 실측(11문자 x 3배치): 문단 안은 0.98(하위 10% 도 0.89), 경계는 0.41.
     * 0.80 으로 자르면 경계 129/154 를 잡고 문단 안 오탐은 510 건 중 0 이다.
     */
    val LINE_FILL_MINIMUM_RATIO: Double,

    /**
     * 새 문단의 첫 줄로 보는 들여쓰기 크기. 폰트높이 배수이며 0 이면 쓰지 않는다.
     */
    val LINE_INDENT_LIMIT: Double,

    /**
     * 들여쓰기로 문단을 끊을 때 앞 줄이 단을 이만큼도 못 채웠어야 한다는 조건.
     * 1.0 이면 앞 줄 조건을 보지 않는다(들여쓰기 단독 판정).
     */
    val LINE_INDENT_FILL_GUARD: Double,

    /**
     * 채움비와 들여쓰기를 쓰기 방향의 축으로 잴지. 끄면 늘 가로 축(폭, 왼쪽 끝)으로 잰다.
     *
     * 세로쓰기에서는 열의 폭이 글자 두께라 채움비가 늘 ~1.0 이고 들여쓰기도 발동하지 않는다 —
     * 세로 문단이 통째로 붙는 원인으로 보인다. 켜면 세로쓰기에서 열의 높이와 위 끝으로 잰다.
     * 가로쓰기에서는 켜도 꺼도 같다.
     */
    val LINE_MEASURE_ALONG_WRITING_AXIS: Boolean = false,

    /** 세로 분기에서 쪼개기 후처리([ParagraphAssembler.detectAndSplitParagraphs])를 돌릴지(3라운드 E1′). */
    val VERTICAL_SPLIT: Boolean = true,
) {
    companion object {
        /**
         * 조립 기준값(출시값)을 정한다.
         *
         * [linesFromDetector] 가 줄의 출처를 가른다. 이 값에 따라 기준이 달라지는 이유:
         *
         * - ML Kit 은 단어 요소를 주고 앱이 줄을 다시 유도한다. 그리고 ML Kit 이 지원하는
         *   문자(라틴·한중일·한글·데바나가리)는 줄마다 글자 높이가 안정적이라, 행간을 박스
         *   높이로 재는 기존 기준이 잘 맞는다.
         * - 검출기가 줄을 직접 주는 엔진(PP-OCRv5)은 아랍어·키릴·태국어를 맡는데, 이 문자들은
         *   줄에 어떤 글자가 오느냐로 박스 높이가 30~53px 까지 요동친다. 거기서는 박스 높이가
         *   기준이 될 수 없고, 레이아웃이 정하는 줄 간격(pitch)과 줄 채움을 봐야 한다.
         *
         * 두 기준을 하나로 합치려 했더니 서로를 깎았다 — ML Kit 경로에서 영어와 한국어가
         * 11%p 나빠졌다(2026-09-23 실측). 엔진과 문자권이 일치하므로 나누는 편이 옳다.
         *
         * todo 가로읽기 non-spacing 언어([Language.isNonSpacingLanguage])
         *      non-spacing 언어 의 경우 LINE_HORIZONTAL_DISTANCE_HEIGHT_RATIO_LIMIT 등의 조정이 필요하다.
         *      1. 중국어 LTR, non-spacing 중국어는 일반적으로 띄어쓰기를 사용하지 않습니다. 문자들이 연속적으로 쓰여지며, 구분은 주로 문장부호에 의존합니다.
         *      2. 일본어 LTR, non-spacing 일본어는 일반적으로 띄어쓰기를 사용하지 않습니다. 하지만 교육 자료나 어린이 책에서는 때때로 단어와 문법 요소를 구분하기 위해 띄어쓰기를 사용하기도 합니다.
         *      3. 태국어 LTR, non-spacing 태국어 문자는 연속적으로 쓰여지며, 문장의 끝을 나타내는 특정 기호를 사용합니다.
         * todo 일본어 후리가나 처리(가로·세로)
         */
        fun reference(
            isVerticalWriting: Boolean,
            @Suppress("UNUSED_PARAMETER") sourceLanguageCode: String, // 언어별 조정(위 todo)의 자리 — 아직 쓰지 않는다
            linesFromDetector: Boolean = false,
        ): AssemblyParams {
            val base = AssemblyParams(
                // 한 시각적 행이 Line 여러 개로 쪼개지는 것을 막는 두 값이다. 예전 값(0.85, 0.63)
                // 에서는 실제 웹 83면의 행 3298 개 중 871 개가 쪼개졌다 — 구텐베르크 책면에서는
                // 한 행이 Line 10 개로 갈라져 문단 묶기가 손쓸 수 없는 상태였다. 간격 한계를
                // 올리는 것이 결정적이었고(쪼개진 행 871 → 447), 그 결과 단어 묶임이
                // 83.0% → 91.5% 로 올랐다(2026-09-23 실측).
                WORD_AXIS_FONT_HEIGHT_SIMILARITY_MINIMUM_RATIO = 0.40,
                WORD_WRITE_DIRECTION_DISTANCE_FONT_HEIGHT_RATIO_LIMIT = 1.5,
                LINE_FONT_HEIGHT_SIMILARITY_MINIMUM_RATIO = 0.68,
                LINE_WRITE_DIRECTION_OVERLAP_MINIMUM_RATIO = 0.84,
                LINE_FONT_HEIGHT_SPACING_AFFINITY_LIMIT = 0.70,
                LINE_AXIS_HEIGHT_SIMILARITY_MINIMUM_RATIO = 0.87,
                LINE_WRITE_DIRECTION_DISTANCE_FONT_HEIGHT_RATIO_LIMIT = 0.91,
                // 0 이면 그 판정을 쓰지 않는다. ML Kit 경로는 박스 높이 기준으로 간다.
                LINE_PITCH_LIMIT = if (linesFromDetector) 1.10 else 0.0,
                // 검출기가 준 줄은 박스가 글자에 딱 붙어 채움비가 또렷하다. 단어에서 줄을
                // 유도하는 ML Kit 경로는 줄 끝 단어가 잘려 조금 느슨하게 본다. 줄 조립을
                // 고친 뒤(위 WORD 상수) 이 값을 0.40 에서 0.60 으로 올릴 수 있었다 — 줄이
                // 온전해지자 채움비가 실제 단 너비를 뜻하게 됐기 때문이다.
                LINE_FILL_MINIMUM_RATIO = if (linesFromDetector) 0.80 else 0.60,
                // 책 조판은 문단 사이를 빈 줄이 아니라 첫 줄 들여쓰기로 구분하므로 행간 신호가
                // 아무 정보도 주지 않는다. 들여쓰기만으로 끊으면 웹이 깨지니(인용문·중첩목록)
                // 앞 줄이 단을 못 채웠다는 조건을 함께 요구한다 — 두 신호가 동의할 때만 끊는다.
                // 실측(책·문학 35면을 조판으로 갈라서): 양쪽 정렬에서 온전한 문단이 튜닝 8면
                // 23% → 62%, 선택에 쓰지 않은 5면 45% → 74%(그 5면의 문단 오염은 0). ragged
                // 산문 22면에서도 44% → 55% 로 듣는다. 웹 83면은 네 블록을 내주고, 표·다단·
                // 코드·용어집·FAQ 16면은 온전한 문단이 같고 오염이 241 → 210 으로 준다.
                LINE_INDENT_LIMIT = 0.50,
                LINE_INDENT_FILL_GUARD = 0.95,
            )
            if (!isVerticalWriting) return base
            // 세로쓰기는 채움비·들여쓰기를 열 방향으로 재고(열 높이·위 끝), 채움 문턱을 0.80 으로 두며, 쪼개기
            // 후처리를 돌리지 않는다. 세로쓰기에는 문단 사이 간격이 없어 채움비가 유일한 경계 신호인데, 가로 축으로
            // 재면 열의 폭(글자 두께)이라 늘 ~1.0 이어서 문단이 통째로 붙었다. 쪼개기는 세로에서 "나란한 두 단" 이
            // 아니라 "한 열의 조각" 에 반응해 발동했다. 3라운드 E1′ holdout(세로 28면, 채점 108): 온전한 문단
            // 36 → 64%, 오염 528 → 73, 순서 위반 0 → 0, 가로 340면 변화 0(.docs/results/round-3, 2026-09-24).
            // 4라운드에서 새 표본으로 재현을 확인하고 출시하였다(커밋 b4e5173f).
            return base.copy(
                LINE_MEASURE_ALONG_WRITING_AXIS = true,
                LINE_FILL_MINIMUM_RATIO = 0.80,
                VERTICAL_SPLIT = false,
            )
        }
    }
}
