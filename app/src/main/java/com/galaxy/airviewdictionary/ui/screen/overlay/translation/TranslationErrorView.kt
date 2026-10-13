package com.galaxy.airviewdictionary.ui.screen.overlay.translation

import com.galaxy.airviewdictionary.data.local.preference.PreferenceRepository
import android.content.Context
import android.graphics.Paint
import android.graphics.PixelFormat
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galaxy.airviewdictionary.R
import com.galaxy.airviewdictionary.core.OverlayService
import com.galaxy.airviewdictionary.data.local.screen.ScreenInfo
import com.galaxy.airviewdictionary.data.local.screen.ScreenInfoHolder
import com.galaxy.airviewdictionary.data.local.vision.model.VisionText
import com.galaxy.airviewdictionary.data.remote.translation.Transaction
import com.galaxy.airviewdictionary.ui.screen.overlay.Event
import com.galaxy.airviewdictionary.ui.screen.overlay.OverlayView
import com.galaxy.airviewdictionary.ui.screen.overlay.visiontext.VisionTextView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 번역 실패 사유 안내 뷰.
 *
 * 위치는 번역창(TranslationView)이 뜨는 자리(지목한 텍스트 위)와 같지만,
 * 모양은 시스템 토스트처럼 단순한 필(진회색 배경 + 실패 엔진 로고 + 흰 안내문)이다.
 * 번역창의 translationFlow 파이프라인은 ACTION_UP 이후에는 컨텐츠를 발행하지
 * 않으므로(dismiss 전용), LLM 처럼 손을 뗀 뒤에 도착하는 실패는 이 뷰가
 * 자체 컨텐츠 플로우로 표시한다. pointerPositionedTranslationFlow 를 거치지
 * 않으므로 광고 게이트·사용량 카운트에는 실패가 섞이지 않는다.
 *
 * 오버레이 윈도우 해제 보장 (누수 시 화면에 창이 영구 잔존하므로 다층 방어):
 *  1) closeDelay 후 컨텐츠 null → 컴포저블의 ?: clear()
 *  2) 그 뒤에도 붙어 있으면 1초 후 강제 clear() — 세대 토큰으로 새 표시는 보호
 *  3) 새 캡처 시작 시 명시적 clear() (캡처 이미지에 안내 창이 찍히는 것도 방지)
 *  4) 서비스 Unbind·화면 회전 시 clear()
 * 추가로 창 자체가 FLAG_NOT_TOUCHABLE 이라 잔존하더라도 터치를 가로채지 않는다.
 */
class TranslationErrorView : OverlayView() {

    companion object {
        val INSTANCE: TranslationErrorView by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { TranslationErrorView() }
    }

    override lateinit var layoutParams: WindowManager.LayoutParams

    private val errorContentFlow = MutableStateFlow<Pair<VisionText, Transaction>?>(null)

    /** cast 마다 증가. 이전 cast 의 지연 해제 타이머가 새 표시를 지우지 못하게 한다. */
    private var castGeneration = 0

    // 토스트 스타일 상수
    private val FONT_SIZE_SP = 14f
    private val H_PADDING_DP = 18f
    private val V_PADDING_DP = 13f
    private val ICON_DP = 18f
    private val ICON_GAP_DP = 8f
    private val CORNER_DP = 24
    private val BACKGROUND_COLOR = Color(0xF2323232)
    private val TEXT_COLOR = Color(0xFFF5F5F5)

    override val composable: @Composable () -> Unit = @Composable {
        val errorState by errorContentFlow.collectAsStateWithLifecycle()

        errorState?.let { (_, translation) ->
            if (isAttachedToWindow()) {
                val shape = RoundedCornerShape(CORNER_DP.dp)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(dimensionResource(R.dimen.translation_view_shadow_padding)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .shadow((CORNER_DP / 6).dp, shape = shape, clip = true)
                            .background(color = BACKGROUND_COLOR, shape = shape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = H_PADDING_DP.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            translation.translationKitType?.let { kitType ->
                                // 워드마크(logoResourceId)는 가로로 길어 측정 예산(18dp 정사각)을
                                // 초과하므로 정사각 CI 아이콘을 고정 크기로 쓴다.
                                Image(
                                    painter = painterResource(id = kitType.ciResourceId),
                                    contentDescription = null,
                                    modifier = Modifier.size(ICON_DP.dp),
                                    contentScale = ContentScale.Fit,
                                )
                                Spacer(modifier = Modifier.width(ICON_GAP_DP.dp))
                            }
                            Text(
                                text = translation.resultText.orEmpty(),
                                color = TEXT_COLOR,
                                fontSize = FONT_SIZE_SP.sp,
                            )
                        }
                    }
                }
            }
        } ?: clear()
    }

    /**
     * 주의: 호출부는 TargetHandleViewModel 의 직렬화된 collect 본문 한 곳뿐이어야 한다.
     * OverlayView.cast() 는 코루틴 스코프를 취소 없이 교체하므로, attach 전의 재-cast 가
     * 가능한 토폴로지가 되면 이전 타이머가 고아로 살아남는다. (아래 세대 토큰이
     * 표시 오동작은 막지만, 호출부 직렬성이 이 클래스의 전제다)
     */
    suspend fun cast(
        applicationContext: Context,
        translation: Transaction,
        visionText: VisionText,
        closeDelayMillis: Long
    ) {
        // 이전 에러 창이 남아 있으면 낡은 위치·크기로 재사용되지 않도록 항상 재부착한다.
        if (isAttachedToWindow()) {
            clear()
        }
        layoutParams = getErrorLayout(applicationContext, translation, visionText)
        errorContentFlow.value = Pair(visionText, translation)
        super.cast(applicationContext)

        val generation = ++castGeneration
        // 무제한이면 타이머를 두지 않는다 — 새 제스처가 시작되면 requestCapture() 가 이 창을 닫는다.
        if (PreferenceRepository.isTranslationCloseDelayInfinite(closeDelayMillis)) return
        launchInOverlayViewCoroutineScope {
            delay(closeDelayMillis)
            // 내용 동등성(CAS)이 아니라 세대로 판단한다 — 같은 단어·같은 실패의 재시도가
            // 구조적으로 동일한 컨텐츠를 만들어도 이전 타이머가 새 표시를 지우지 못하게.
            if (castGeneration == generation) {
                errorContentFlow.value = null // → 컴포저블이 clear()
            }
            delay(1000)
            if (castGeneration == generation && isAttachedToWindow()) {
                clear()
            }
        }
    }

    override fun clear() {
        errorContentFlow.value = null
        super.clear()
    }

    override fun onOverlayServiceEvent(overlayService: OverlayService, event: Event) {
        when (event) {
            Event.ConfigurationChanged -> clear() // 회전 시 위치가 무효화되므로 해제
            else -> {}
        }
        super.onOverlayServiceEvent(overlayService, event)
    }

    /**
     * 토스트 필의 윈도우 레이아웃.
     * 크기는 안내문 텍스트에 맞추고, 위치는 번역창과 동일하게
     * 지목한 텍스트(visionText) 위에 가로 중앙 정렬로 잡는다.
     */
    private fun getErrorLayout(
        applicationContext: Context,
        translation: Transaction,
        visionText: VisionText
    ): WindowManager.LayoutParams {
        val screenInfo: ScreenInfo = ScreenInfoHolder.get()
        val text = translation.resultText.orEmpty()

        fun dpToPx(dp: Float): Int = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp, applicationContext.resources.displayMetrics
        ).roundToInt()

        val shadowPadding = applicationContext.resources.getDimensionPixelSize(R.dimen.translation_view_shadow_padding)
        val screenViewMinMargin = applicationContext.resources.getDimensionPixelSize(R.dimen.translation_view_screen_min_margin)
        val hPadding = dpToPx(H_PADDING_DP)
        val vPadding = dpToPx(V_PADDING_DP)
        val iconPx = dpToPx(ICON_DP)
        val iconSpace = iconPx + dpToPx(ICON_GAP_DP)

        // 내림 오차로 창이 텍스트보다 좁아져 의도치 않은 줄바꿈이 생기지 않도록 올림
        val textWidth = ceil(measureTextWidth(applicationContext, text, FONT_SIZE_SP)).toInt()

        val viewMaxWidth = screenInfo.width - screenViewMinMargin * 2
        val viewWidth = (shadowPadding * 2 + hPadding * 2 + iconSpace + textWidth)
            .coerceAtMost(viewMaxWidth)

        val textAreaWidth = viewWidth - shadowPadding * 2 - hPadding * 2 - iconSpace
        val textLayout = buildTextLayout(applicationContext, text, textAreaWidth, FONT_SIZE_SP)
        // 측정(StaticLayout)과 실제 렌더(Compose Text)의 폰트 메트릭 차이를 흡수할 여유분.
        // 줄이 많을수록 오차가 누적될 수 있어 줄 수에 비례해 더 준다.
        val heightSlack = dpToPx(2f) + (textLayout.lineCount - 1) * dpToPx(1f)
        val viewHeight = shadowPadding * 2 + vPadding * 2 + max(textLayout.height, iconPx) + heightSlack

        // 안내창도 대상 텍스트의 가운데에 맞춘다. visionText.start 는 RTL 에서 오른쪽 변이라
        // 그대로 쓰면 글자 폭만큼 오른쪽으로 밀린다.
        val layoutPosX = (visionText.boundingBox.centerX() - viewWidth / 2)
            .coerceIn(0, (screenInfo.width - viewWidth))
        val layoutPosY = (
                visionText.boundingBox.top -
                        VisionTextView.paragraphFrameMargin -
                        applicationContext.resources.getDimensionPixelSize(R.dimen.translation_view_vision_text_v_margin) -
                        viewHeight
                ).coerceAtLeast(0)

        return WindowManager.LayoutParams(
            viewWidth,
            viewHeight,
            layoutPosX,
            layoutPosY,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    // 토스트처럼 아래 화면 조작을 가로채지 않는다 (잔존 시 안전장치이기도 함)
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
    }

    private fun measureTextWidth(context: Context, text: String, fontSizeSp: Float): Float {
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                fontSizeSp,
                context.resources.displayMetrics
            )
        }
        return textPaint.measureText(text)
    }

    private fun buildTextLayout(
        context: Context,
        text: String,
        width: Int,
        fontSizeSp: Float
    ): StaticLayout {
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                fontSizeSp,
                context.resources.displayMetrics
            )
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.0f)
            .setIncludePad(true)
            .build()
    }
}
