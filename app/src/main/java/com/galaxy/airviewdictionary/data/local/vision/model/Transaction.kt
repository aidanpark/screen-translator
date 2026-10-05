package com.galaxy.airviewdictionary.data.local.vision.model

import android.graphics.Bitmap
import android.graphics.Rect
import com.galaxy.airviewdictionary.data.local.vision.UnreadParagraphs
import com.galaxy.airviewdictionary.data.local.vision.WritingDirection
import com.galaxy.airviewdictionary.data.local.vision.ocr.OcrText

data class Transaction(
    val bitmap: Bitmap,
    /** 엔진이 준 인식 결과 날것. 선택 모드와 고정 영역이 화면 전체 글·덩어리 상자를 쓴다. */
    val ocr: OcrText,
    val detectedLanguageCode: String,
    /** 화면의 문단. 검출만 된 화면이면 줄 상자로 묶은 것이라 글이 없다 — `VisionRepository.readParagraph` 로 읽는다. */
    val paragraphs: List<Paragraph>,
    /** 검출만 된 화면이면 있다. 다 읽힌 화면(ML Kit)은 null. */
    val unread: UnreadParagraphs? = null,
    /** AI 이미지 번역으로 보낼 화면이면 번역 대상을 찾는 방법(`.docs/vision-engine-design.md` §25). 글을 번역하면 null. */
    val image: ImageTargets? = null,
    /**
     * auto 의 지원되지 않는 문자권 관문(성능 P7)이 개입한 화면이면 그 판정 — 화면은 검출 줄로 묶은 이미지 대상([image] = Detected)이다.
     * Claude 이미지 번역을 쓸 수 있으면 그 길로 보내고, 아니면 번역 대신 "읽을 수 없는 문자" 안내를 띄운다.
     */
    val unsupportedScript: com.galaxy.airviewdictionary.data.local.vision.kit.paddle.ScriptGate.Verdict? = null,
) {

    /** 글이 있는 문단. 검출만 된 화면이면 지금까지 읽은 것만, 화면의 문단 순서대로. */
    fun readParagraphs(): List<Paragraph> =
        unread?.let { unread -> paragraphs.mapNotNull { unread.cached(it) } } ?: paragraphs

    fun mostFrequentWritingDirection(): WritingDirection? {
        return paragraphs.groupingBy { it.writingDirection } // 각 writingDirection별 그룹화
            .eachCount() // 각 그룹의 개수 계산
            .maxByOrNull { it.value } // 개수가 가장 많은 항목 선택
            ?.key // 해당 writingDirection 반환
    }

    override fun toString(): String {
        return "Vision(" +
                "bitmap=$bitmap, " +
                "ocr=${ocr.blocks.size} blocks, " +
                "detectedLanguageCode=$detectedLanguageCode, " +
                "result=$paragraphs, " +
                "unread=${unread != null}, " +
                "image=$image, " +
                ")"
    }
}
/** AI 이미지 번역(§25)의 대상 찾기. 글을 읽지 않으므로 단어 위치는 모른다 — 번역할 단어·문장·문단은 모델이 표시를 보고 고른다. */
sealed interface ImageTargets {

    /** PP-OCRv5 검출기 줄 — 단어 모드는 포인터 아래 줄, 문장·문단 모드는 그 문단. [Transaction.paragraphs] 는 줄 상자를 묶은 것이다(글 없음). */
    data object Detected : ImageTargets

    /** 모델 팩(PP-OCRv5)이 아직 없다 — 포인터 위아래 고정 높이의 띠. */
    data object PointerBand : ImageTargets

    /** 영역 선택의 영역 전체. */
    data class Area(val rect: Rect) : ImageTargets
}
