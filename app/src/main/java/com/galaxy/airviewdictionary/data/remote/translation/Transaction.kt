package com.galaxy.airviewdictionary.data.remote.translation

/**
 * 한 번의 번역 결과. 화면·TTS·답장·애널리틱스가 공통으로 읽는 값이다.
 *
 * 채우는 책임이 둘로 나뉜다:
 *
 * - **킷**은 자기가 아는 것만 채운다 — [sourceText], [resolvedSourceLanguageCode](판정했을 때만),
 *   [resultText], [modelName], [translationKitType].
 *   [targetId] 와 [requestedSourceLanguageCode] 는 킷이 모르는 값이라 건드리지 않는다.
 * - **파이프라인**(TargetHandleViewModel)이 그 결과를 받아 나머지를 채우고 빈 값을 메운다.
 *   확정은 거기 한 곳에서만 일어난다.
 *
 * 이 분담이 필요한 이유: 킷마다 아는 것이 다르다. 원문 언어를 판정해 돌려주는 킷도 있고
 * 아닌 킷도 있다. 그 차이를 파이프라인에서 한 번 흡수하고, 그 아래로는 출처를 묻지 않는 값만 내려보낸다.
 */
data class Transaction(
    /**
     * 이 번역이 어느 대상에 대한 것인지.
     * [com.galaxy.airviewdictionary.data.local.vision.model.TranslationTarget.id] 와 같다.
     * 번역창 파이프라인은 이 값으로 짝을 맞춘다. 킷은 채우지 않는다.
     */
    val targetId: Long? = null,
    /**
     * 사용자가 설정에서 고른 원문 언어. "auto" 일 수 있다.
     * 표시 정책(판정 언어를 보여줄지)과 로깅에만 쓴다. 킷은 채우지 않는다.
     */
    val requestedSourceLanguageCode: String? = null,
    /**
     * 확정된 원문 언어. "auto" 가 들어오지 않으며, 끝내 못 정했으면 null.
     *
     * 쓰기 방향·TTS 목소리·답장 언어·애널리틱스는 모두 이 값만 본다.
     * 예전에는 설정값(auto 포함)과 감지값이 별도 필드로 공존해서, 소비처마다
     * 둘 중 무엇을 볼지 제각기 정하다가 조용히 어긋났다.
     */
    val resolvedSourceLanguageCode: String? = null,
    val targetLanguageCode: String? = null,
    /** 확정된 원문. 킷이 돌려준 원문이 있으면 그것, 없으면 OCR 이 읽은 값이다. */
    val sourceText: String? = null,
    val translationKitType: TranslationKitType? = null,
    val resultText: String? = null,
    /** LLM 기반 엔진에서 실제 사용한 모델명. 그 외 엔진은 null. */
    val modelName: String? = null,
    /**
     * AI 이미지 번역(§25)의 단어·문장 모드에서 모델이 함께 읽은 대상 줄·문단 전체 글. 하이라이트를 좁히는 데만 쓴다([ImageTargetNarrowing]).
     * 그 밖에는 null.
     */
    val imageContext: String? = null,
) {
    /**
     * 사용자가 자동 감지를 골랐고 실제로 언어가 확정된 경우에만 true.
     * 번역창이 "[아랍어]" 같은 판정 결과를 함께 보여줄지 정하는 기준이다.
     */
    val showsDetectedLanguage: Boolean
        get() = requestedSourceLanguageCode.equals("auto", ignoreCase = true) &&
                !resolvedSourceLanguageCode.isNullOrBlank()

    override fun toString(): String {
        return "Translation(" +
                "targetId=$targetId, " +
                "requestedSourceLanguageCode=$requestedSourceLanguageCode, " +
                "resolvedSourceLanguageCode=$resolvedSourceLanguageCode, " +
                "targetLanguageCode=$targetLanguageCode, " +
                "sourceText=$sourceText, " +
                "translationKitType=$translationKitType, " +
                "resultText=$resultText, " +
                "modelName=$modelName, " +
                ")"
    }
}

/**
 * 번역창에 원문과 함께 보여줄 판정 언어 라벨. 자동 감지로 언어가 확정됐을 때만 붙는다.
 *
 * 복사·TTS 에는 쓰지 않는다 — 원문 자체가 아니라 화면 표시용 부가 정보이기 때문이다.
 * (예전에는 킷이 이 라벨을 sourceText 에 문자열로 붙여 보내서, TTS 가 "[아랍어]" 까지 읽고
 *  복사에도 라벨이 딸려갔다)
 */
val Transaction.detectedLanguageLabel: String
    get() = if (showsDetectedLanguage) "[${Language(resolvedSourceLanguageCode!!).displayName}] " else ""
