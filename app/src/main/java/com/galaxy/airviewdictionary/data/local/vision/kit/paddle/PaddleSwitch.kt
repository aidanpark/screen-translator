package com.galaxy.airviewdictionary.data.local.vision.kit.paddle

import com.galaxy.airviewdictionary.data.remote.firebase.RemoteConfigRepository

/**
 * PP-OCRv5 끄기 스위치(`.docs/vision-engine-design.md` §19). 앱 업데이트 없이 Remote Config 로 되돌리기 위한 것이다.
 *
 * 값을 아직 못 받았으면(기본값도 설정 전이면 `VALUE_SOURCE_STATIC`) 켜진 것으로 본다 — 앱 시작 직후나 시험에서 꺼지면 안 된다.
 */
internal object PaddleSwitch {

    /** PP-OCRv5 를 쓰는가. 끄면 지정 언어·auto 모두 ML Kit 으로 간다. */
    val enabled: Boolean get() = flag(RemoteConfigRepository.PADDLE_OCR_ENABLED)

    /** auto 에서 PP-OCRv5 표본을 돌리는가. */
    val autoEnabled: Boolean get() = enabled && flag(RemoteConfigRepository.PADDLE_OCR_AUTO_ENABLED)

    private fun flag(key: String): Boolean = RemoteConfigRepository.switchOn(key)
}
