package com.galaxy.airviewdictionary.data.local.vision.kit

import android.graphics.Bitmap
import androidx.lifecycle.Lifecycle
import com.galaxy.airviewdictionary.data.local.vision.ocr.OcrLine
import com.galaxy.airviewdictionary.data.local.vision.ocr.OcrText

/**
 * 화면 이미지를 읽어 엔진 중립 결과([OcrText])로 돌려주는 인식 엔진. 결과는 [OcrText] 의 계약을 지킨다.
 *
 * 어느 엔진으로 읽을지는 엔진이 아니라 [VisionKitSelector] 가 소스 언어로 정한다(`.docs/vision-engine-design.md` §3.6).
 */
interface VisionKit {

    /** 로그에 남길 이름. */
    val name: String

    /**
     * 검출기가 줄을 직접 주는가(PP-OCRv5). 그런 엔진의 화면은 줄 상자로 문단을 묶고 검출기 기준값으로 조립한다 — 줄이 이미 다 읽혔어도
     * 같다(auto 표본이 작은 화면의 줄을 모두 읽은 경우). ML Kit 은 단어를 주고 앱이 줄을 다시 유도한다.
     */
    val linesFromDetector: Boolean get() = false

    /**
     * [screen] 전체에서 글 줄을 찾는다. 읽지 않은 줄은 `words == null` 로 준다 — 검출이 싼 엔진은 줄 위치만 주고
     * 읽기는 [recognize] 로 미룬다. 검출과 읽기를 나눌 수 없는 엔진(ML Kit)은 여기서 다 읽는다.
     */
    suspend fun detect(screen: Bitmap): OcrText

    /** [lines] 가운데 읽지 않은 줄을 읽어 채운다. 이미 읽힌 줄은 그대로 두고, 순서와 개수를 바꾸지 않는다. */
    suspend fun recognize(screen: Bitmap, lines: List<OcrLine>): List<OcrLine>

    /** 엔진 자원을 [lifecycle] 에 묶는다. 수명이 끝나면 닫힌다. */
    fun addObserver(lifecycle: Lifecycle)
}
