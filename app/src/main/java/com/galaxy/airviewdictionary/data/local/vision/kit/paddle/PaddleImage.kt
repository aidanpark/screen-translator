package com.galaxy.airviewdictionary.data.local.vision.kit.paddle

import android.graphics.Bitmap
import java.nio.FloatBuffer

/**
 * 비트맵을 PP-OCR 입력 텐서(NCHW, BGR)로 편다. 채널 순서는 학습 때와 같게 B, G, R 이다(PaddleOCR 은 OpenCV 로 읽는다).
 * [invert] 면 밝기를 뒤집는다 — 다크 모드 캡처는 뒤집어야 제대로 읽힌다(실측 러시아어 87.8% → 99.5%).
 */
internal inline fun Bitmap.toBgrTensor(invert: Boolean, normalize: (channel: Int, value: Float) -> Float): FloatBuffer {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    return bgrTensor(pixels, width, 0, width, height, invert, normalize)
}

/** 한 줄에 [stride] 픽셀인 [pixels] 의 열 [x0, x0 + [width]) 만 텐서로 편다 — 넓은 줄을 조각으로 읽을 때(§22). */
internal inline fun bgrTensor(
    pixels: IntArray, stride: Int, x0: Int, width: Int, height: Int,
    invert: Boolean, normalize: (channel: Int, value: Float) -> Float,
): FloatBuffer {
    val buffer = FloatBuffer.allocate(3 * width * height)
    for (c in 0 until 3) {
        val shift = c * 8 // B, G, R
        for (y in 0 until height) {
            val row = y * stride + x0
            for (x in row until row + width) {
                var v = ((pixels[x] shr shift) and 0xFF) / 255f
                if (invert) v = 1f - v
                buffer.put(normalize(c, v))
            }
        }
    }
    buffer.rewind()
    return buffer
}

/**
 * 픽셀(ARGB)의 밝기 0 ~ 255(Rec.601 가중치). 문자 판별기(`ScriptGate`)는 학습 때와 같아야 해 Pillow 의 회색조 공식을 따로 쓴다.
 */
private fun luminance(p: Int): Int = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000

/** 열마다 가장 진한 픽셀의 진하기(바탕 0 ~ 글자 255). [dark] 바탕이면 밝은 것이 글자다. 넓은 줄의 자를 자리를 찾는 데 쓴다([LineChunks]). */
internal fun columnInk(pixels: IntArray, width: Int, height: Int, dark: Boolean): IntArray = IntArray(width) { x ->
    var most = 0
    for (y in 0 until height) {
        val p = pixels[y * width + x]
        val luminance = luminance(p)
        val ink = if (dark) luminance else 255 - luminance
        if (ink > most) most = ink
    }
    most
}

/**
 * 어두운 바탕인가. 글자는 화면의 적은 부분이라 평균 밝기는 바탕이 정한다. 8픽셀 간격으로 표본을 뜬다.
 *
 * 영역 선택·고정 영역은 영역 밖을 완전한 검정으로 칠해 넘긴다. 그 검정까지 세면 흰 바탕 영역이 어둡다고 판정돼 뒤집히고,
 * 검출이 단어 조각으로 부서진다(`.docs/vision-engine-design.md` §17). 그래서 완전한 검정이 아닌 표본이 차지하는 사각형 안만 본다 —
 * 순검정 바탕의 다크 모드는 그 사각형(글자 범위) 안도 대부분 검정이라 여전히 어둡다.
 */
internal fun Bitmap.isDark(): Boolean {
    val step = 8
    var left = Int.MAX_VALUE
    var top = Int.MAX_VALUE
    var right = -1
    var bottom = -1
    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            if (getPixel(x, y) and 0xFFFFFF != 0) {
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
            x += step
        }
        y += step
    }
    if (right < 0) return false
    var sum = 0L
    var count = 0
    y = top
    while (y <= bottom) {
        var x = left
        while (x <= right) {
            val p = getPixel(x, y)
            sum += luminance(p)
            count++
            x += step
        }
        y += step
    }
    return sum / count < 128
}
