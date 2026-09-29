package com.galaxy.airviewdictionary.data.local.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import androidx.annotation.VisibleForTesting
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * AI 이미지 번역(`.docs/vision-engine-design.md` §25)에 보낼 화면 조각을 만든다.
 *
 * 포인터 모드는 **가로는 화면 폭 전체, 세로는 검출한 줄(단어 모드)이나 문단(문장·문단 모드)의 높이**만큼 자르고 포인터 자리에 표시를 그린다
 * (2026-09-28 사용자 결정). 가로를 다 주면 검출 상자가 줄 끝을 조금 놓쳐도 글이 잘리지 않고, 모델이 표시 기준으로 단어·문장·문단을 찾는다.
 * 영역 선택·고정 영역은 그 영역을 그대로 자르고 표시를 그리지 않는다.
 *
 * 긴 변이 [MAX_WIDTH] 를 넘으면 줄인다. 이미지 입력 토큰은 넓이에 비례하고(약 28×28 픽셀에 1 토큰), 폰 화면 글자는 줄여도 읽힌다.
 */
object ImageCrop {

    /** 보낼 조각의 긴 변 상한. 1440 폭 화면이면 0.71 배 — 44px 글자가 31px 이 된다. */
    const val MAX_WIDTH = 1024

    /** 줄 상자 위아래 여백(줄 높이 배수). 검출 상자가 위첨자·아래로 긴 글자를 조금 놓친다. */
    const val LINE_MARGIN = 0.35

    /**
     * 문장·문단 모드의 위아래 여백(줄 높이 배수). 문단 묶기가 한 문단을 둘로 가를 때가 있어(타밀어 문단의 짧은 끝줄, 2026-09-29) 이웃 줄을
     * 한 줄 반 더 보여 준다 — 어디까지가 그 문장·문단인지는 모델이 본다.
     */
    const val PARAGRAPH_MARGIN = 1.5

    private const val MIN_MARGIN_PX = 8

    /**
     * 포인터 표시 모양. [description] 은 프롬프트가 표시를 소개하는 말, [at] 은 "표시가 가리키는 자리" 를 부르는 말이다(`ImageTranslation`).
     * 색은 화면 내용과 헷갈리지 않도록 잘 쓰이지 않는 자홍색이다.
     */
    enum class PointerMarker(val description: String, val at: String) {
        /** 속 빈 원. 글자 위에 그려져 글자를 가린다 — 가려진 글자를 잘못 읽었다("bought" → "ght", "가로질러" → "카로질러"). */
        RING("magenta circle", "the circle"),

        /** 반투명 원판(지름 줄 높이의 0.9) — 글자가 비쳐 보인다. 쓰는 표시다. */
        HIGHLIGHT("translucent magenta highlight", "the highlight"),

        /** 포인터 줄 바로 아래에서 위를 가리키는 삼각형. 글자를 가리지 않지만 모델이 어느 낱말을 가리키는지 잘 못 짚었다. */
        CARET("small magenta triangle under the text, pointing up", "the tip of the triangle"),
    }

    /**
     * 쓰는 표시. 12개 표본의 단어 모드에서 낱말을 맞힌 수(2026-09-29, `ClaudeImageLiveTest`): Sonnet 5 는 반투명 9·원 8·삼각형 3,
     * Haiku 4.5 는 5·1·3. 기기 실측 시험이 모양을 바꿔 가며 잰다.
     */
    @VisibleForTesting
    var marker: PointerMarker = PointerMarker.HIGHLIGHT

    private const val MARKER_COLOR = 0xFFFF00FF.toInt()
    private const val HIGHLIGHT_COLOR = 0x55FF00FF

    /** 줄인 뒤 크기 기준의 원 반지름·선 굵기와 삼각형 높이. */
    private const val MARKER_RADIUS_PX = 16f
    private const val MARKER_STROKE_PX = 4f
    private const val CARET_HEIGHT_PX = 14f

    /**
     * [screen] 의 세로 [top]..[bottom](줄·문단 상자)을 화면 폭 전체로 잘라 [pointer] 자리에 표시를 그린다. 위아래 여백은 [lineHeight] 의
     * [marginRatio] 배다. 잘라낼 것이 없으면 null.
     */
    fun strip(screen: Bitmap, top: Int, bottom: Int, lineHeight: Int, pointer: Point?, marginRatio: Double = LINE_MARGIN): Bitmap? {
        if (screen.isRecycled || screen.width <= 0 || screen.height <= 0) return null
        val margin = max(MIN_MARGIN_PX, (lineHeight * marginRatio).roundToInt())
        val crop = Rect(0, top - margin, screen.width, bottom + margin)
        // 삼각형은 포인터 줄 바로 아래에 그린다 — 대상이 한 줄이면 그 줄 아래, 문단이면 포인터에서 반 줄 아래. 그 자리까지 자른다
        val caretTip = if (marker == PointerMarker.CARET && pointer != null) {
            if (bottom - top <= lineHeight * 3 / 2) bottom else pointer.y + lineHeight / 2
        } else null
        // 포인터가 상자 밖이면(검출이 빗나간 경우) 포인터 위아래 한 줄 높이까지 넣는다
        if (pointer != null && !crop.contains(0, pointer.y)) {
            val reach = max(MIN_MARGIN_PX, lineHeight)
            crop.union(Rect(0, pointer.y - reach, screen.width, pointer.y + reach))
        }
        return cut(screen, crop, pointer, caretTip, lineHeight)
    }

    /** [screen] 에서 [area] 를 그대로 자른다(영역 선택·고정 영역). 표시를 그리지 않는다. */
    fun area(screen: Bitmap, area: Rect): Bitmap? {
        if (screen.isRecycled || screen.width <= 0 || screen.height <= 0) return null
        return cut(screen, Rect(area), null, null, 0)
    }

    private fun cut(screen: Bitmap, rect: Rect, pointer: Point?, caretTip: Int?, lineHeight: Int): Bitmap? {
        if (!rect.intersect(0, 0, screen.width, screen.height) || rect.width() <= 0 || rect.height() <= 0) return null
        // 삼각형 자리(줄인 뒤 CARET_HEIGHT_PX, 줄이기 전에는 최대 그 1.5 배)까지 아래로 늘린다
        if (caretTip != null) rect.bottom = minOf(screen.height, max(rect.bottom, caretTip + (CARET_HEIGHT_PX * 1.5f).roundToInt() + 2))
        // createBitmap·createScaledBitmap 은 크기가 같으면 원본을 그대로 돌려줄 수 있다 — 화면 캡처에 그리거나, 부르는 쪽이 지우게 두면 안 된다
        val cropped = Bitmap.createBitmap(screen, rect.left, rect.top, rect.width(), rect.height())
        val scale = minOf(1f, MAX_WIDTH.toFloat() / max(rect.width(), rect.height()))
        var result = if (scale < 1f) {
            Bitmap.createScaledBitmap(cropped, (rect.width() * scale).roundToInt().coerceAtLeast(1), (rect.height() * scale).roundToInt().coerceAtLeast(1), true)
        } else cropped
        if (cropped !== screen && cropped !== result) cropped.recycle()
        if (result === screen || pointer != null && !result.isMutable) {
            val copy = result.copy(Bitmap.Config.ARGB_8888, true)
            if (result !== screen) result.recycle()
            result = copy
        }
        if (pointer != null) {
            // 줄인 뒤에 그린다 — 먼저 그리면 표시도 함께 줄어 흐려진다
            val canvas = Canvas(result)
            val x = (pointer.x - rect.left) * scale
            val y = (pointer.y - rect.top) * scale
            when (marker) {
                PointerMarker.RING -> canvas.drawCircle(
                    x, y, MARKER_RADIUS_PX,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MARKER_COLOR; style = Paint.Style.STROKE; strokeWidth = MARKER_STROKE_PX },
                )
                PointerMarker.HIGHLIGHT -> canvas.drawCircle(
                    x, y, max(MARKER_RADIUS_PX, lineHeight * scale * 0.45f),
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = HIGHLIGHT_COLOR; style = Paint.Style.FILL },
                )
                PointerMarker.CARET -> {
                    val tip = ((caretTip ?: pointer.y) - rect.top) * scale + 1
                    val half = CARET_HEIGHT_PX * 0.6f
                    val path = Path().apply {
                        moveTo(x, tip); lineTo(x - half, tip + CARET_HEIGHT_PX); lineTo(x + half, tip + CARET_HEIGHT_PX); close()
                    }
                    canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MARKER_COLOR; style = Paint.Style.FILL })
                }
            }
        }
        return result
    }
}
