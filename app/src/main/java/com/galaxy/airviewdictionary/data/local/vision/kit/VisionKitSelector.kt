package com.galaxy.airviewdictionary.data.local.vision.kit

import android.content.Context
import androidx.lifecycle.Lifecycle
import com.galaxy.airviewdictionary.data.local.vision.kit.paddle.PaddleKits
import com.galaxy.airviewdictionary.data.local.vision.kit.paddle.PaddleModelFiles

/**
 * 소스 언어로 인식 엔진 후보를 고르는 한 곳(`.docs/vision-engine-design.md` §3.6).
 *
 * 후보가 여럿이면(auto) 부르는 쪽이 모두 읽혀 하나를 고른다(§11). auto 는 [paddle] 의 표본 후보도 함께 본다(§13). 새 엔진은 여기에
 * 언어를 이어 붙인다.
 */
class VisionKitSelector(context: Context? = null) {

    private val latin = MlKitVisionKit(TextRecognizerType.TEXT)
    private val chinese = MlKitVisionKit(TextRecognizerType.CHINESE)
    private val korean = MlKitVisionKit(TextRecognizerType.KOREAN)
    private val japanese = MlKitVisionKit(TextRecognizerType.JAPANESE)
    private val devanagari = MlKitVisionKit(TextRecognizerType.DEVANAGARI)

    /**
     * ML Kit 이 읽지 못하는 문자권(아랍·동슬라브 키릴·태국)의 PP-OCRv5 엔진. 모델 팩이 아직 없으면 그 언어도 지금처럼 라틴으로
     * 떨어진다. 문맥이 없으면(조립기 시험) 쓰지 않는다.
     */
    val paddle: PaddleKits? = context?.let { PaddleKits(PaddleModelFiles(it)) }

    /** auto 의 후보. 신뢰도 합이 같으면 앞의 것이 이기므로 순서를 바꾸지 않는다. */
    private val all: List<VisionKit> = listOf(latin, chinese, korean, japanese, devanagari)

    fun candidatesFor(sourceLanguageCode: String): List<VisionKit> = when {
        sourceLanguageCode == "auto" -> all
        sourceLanguageCode.startsWith("zh") -> listOf(chinese)
        sourceLanguageCode.startsWith("ko") -> listOf(korean)
        sourceLanguageCode.startsWith("ja") -> listOf(japanese)
        sourceLanguageCode in DEVANAGARI_LANGUAGES -> listOf(devanagari)
        else -> listOf(paddle?.kitFor(sourceLanguageCode) ?: latin)
    }

    fun addObserver(lifecycle: Lifecycle) {
        all.forEach { it.addObserver(lifecycle) }
    }

    companion object {
        /**
         * [sourceLanguageCode] 의 문자를 읽을 엔진이 있는가 — 라틴 문자이거나, ML Kit 전용 인식기나 PP-OCRv5 가 맡는 문자.
         * 모델 팩 준비·끄기 스위치와 무관한 고정 판정이다. 없는 언어는 원문 언어로 고를 수 없다(`.docs/vision-engine-design.md` §21).
         *
         * 저장값은 예전 버전이 쓴 코드일 수 있다 — 대소문자를 가리지 않고, 문자 표기가 붙은 코드(`sr-Cyrl`)는 통째로도 본다(§23).
         */
        fun hasReaderFor(sourceLanguageCode: String): Boolean {
            val code = sourceLanguageCode.trim().lowercase().replace('_', '-')
            if (NO_READER_CODES.any { code == it || code.startsWith("$it-") }) return false
            val base = code.substringBefore('-')
            return base !in NON_LATIN_SCRIPT || base in MLKIT_SCRIPT_LANGUAGES || base in DEVANAGARI_LANGUAGES || base in PaddleKits.LANGUAGES
        }

        /** ML Kit 전용 인식기(중국어 · 한국어 · 일본어)가 맡는 언어. 라틴 멈추기(`AutoLatinStop`)와 auto 의 "ML Kit 먼저" 판정도 이 표를 쓴다. */
        internal val MLKIT_SCRIPT_LANGUAGES = setOf("zh", "ko", "ja")

        /** ML Kit 데바나가리 인식기가 읽는 언어. [candidatesFor] 도, auto 의 라틴 멈추기(`AutoLatinStop`)도 이 표를 쓴다. */
        internal val DEVANAGARI_LANGUAGES = setOf(
            "mr", // मराठी 마라티어 (Marathi)
            "sa", // संस्कृत 산스크리트어 (Sanskrit)
            "hi", // हिंदी 힌디어 (Hindi)
            "ne", // नेपाली 네팔어 (Nepali)
            // 예전 엔진(Azure 등)의 언어 목록에 있던 데바나가리 언어 — 저장값으로 남아 있을 수 있다
            "mai", // मैथिली 마이틸리어 (Maithili)
            "bho", // भोजपुरी 보즈푸리어 (Bhojpuri)
            "brx", // बड़ो 보도어 (Bodo)
            "doi", // डोगरी 도그리어 (Dogri)
            "gom", // कोंकणी 콘칸어 (Konkani)
        )

        /**
         * 언어는 라틴 문자로도 쓰지만 이 문자 표기는 읽을 엔진이 없는 코드. 세르비아어(`sr`)는 라틴 화면을 ML Kit 이 읽으므로
         * 고를 수 있고, 키릴 표기를 콕 집은 `sr-Cyrl` 만 막는다.
         */
        private val NO_READER_CODES = setOf("sr-cyrl")

        /**
         * 라틴 문자로 쓰지 않는 소스 언어(번역 엔진들이 받는 언어 중, 그리고 예전 버전이 저장했을 수 있는 코드). 세르비아어는 키릴과
         * 라틴을 함께 쓰고 라틴 화면은 ML Kit 이 읽으므로 넣지 않았다.
         */
        private val NON_LATIN_SCRIPT = setOf(
            "am", "ar", "be", "bg", "bn", "ckb", "el", "fa", "gu", "he", "hi", "hy", "ja", "ka", "kk", "km", "kn", "ko",
            "ky", "lo", "mk", "ml", "mn", "mr", "my", "ne", "or", "pa", "ps", "ru", "sd", "si", "ta", "te", "tg",
            "th", "ti", "tt", "ug", "uk", "ur", "yi", "zh",
            // 예전 코드·예전 엔진 목록에만 있던 언어 — 읽을 엔진이 없다
            "prs", // 다리어 (아랍 문자, PP-OCRv5 아랍 표에 없다)
            "ks",  // 카슈미르어 (아랍 문자)
            "yue", // 광둥어 (한자, 중국어 인식기로 가지 않는다)
            "lzh", // 고전 중국어 (한자, 위와 같다)
            "dv",  // 디베히어 (타나 문자)
            "bo",  // 티베트어
            "as",  // 아삼어 (벵골 문자)
            "mni", // 마니푸르어 (벵골·메이테이 문자)
            "iu",  // 이누크티투트어 (음절 문자)
            "ba",  // 바시키르어 (키릴, PP-OCRv5 동슬라브 표에 없다)
            "iw",  // 히브리어의 옛 코드
            "ji",  // 이디시어의 옛 코드
            "mnc", // 만주어 (만주 문자)
        )
    }
}
