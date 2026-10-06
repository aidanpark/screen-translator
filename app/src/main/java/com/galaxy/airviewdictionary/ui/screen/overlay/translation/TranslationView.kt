package com.galaxy.airviewdictionary.ui.screen.overlay.translation

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galaxy.airviewdictionary.data.local.preference.PreferenceRepository
import com.galaxy.airviewdictionary.R
import com.galaxy.airviewdictionary.core.OverlayService
import com.galaxy.airviewdictionary.data.local.screen.ScreenInfo
import com.galaxy.airviewdictionary.data.local.screen.ScreenInfoHolder
import com.galaxy.airviewdictionary.data.local.vision.WritingDirection
import com.galaxy.airviewdictionary.data.local.vision.model.VisionText
import com.galaxy.airviewdictionary.data.remote.translation.Language
import com.galaxy.airviewdictionary.data.remote.translation.detectedLanguageLabel
import com.galaxy.airviewdictionary.data.remote.translation.Transaction
import com.galaxy.airviewdictionary.extensions.toSpValue
import com.galaxy.airviewdictionary.ui.common.AutoResizeText
import com.galaxy.airviewdictionary.ui.screen.overlay.Event
import com.galaxy.airviewdictionary.ui.screen.overlay.OverlayView
import com.galaxy.airviewdictionary.ui.screen.overlay.targethandle.TargetHandleViewModel
import com.galaxy.airviewdictionary.ui.screen.overlay.visiontext.VisionTextView
import com.galaxy.airviewdictionary.ui.screen.reply.ReplyActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt


/**
 * 번역 뷰
 */
@Singleton
open class TranslationView : OverlayView() {

    companion object {
        val INSTANCE: TranslationView by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { TranslationView() }

        val liveStateFlow = MutableStateFlow(false)
    }

    private lateinit var targetHandleViewModel: TargetHandleViewModel

    override lateinit var layoutParams: WindowManager.LayoutParams

    override val composable: @Composable () -> Unit = @Composable {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current

        val translationState by targetHandleViewModel.translationFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = Pair<VisionText?, Transaction?>(null, null)
        )

        translationState?.let { (visionText, translation) ->
            if (visionText != null && translation != null) {
                if (isAttachedToWindow()) {
                    val fontSizeSp = getRenderFontSizeSp(context, visionText.fontHeight).sp
                    TranslationBox(
                        translation,
                        fontSizeSp,
                        onPauseDismissRunning = { targetHandleViewModel.pauseDismissRunning() },
                        onResumeDismissRunning = { targetHandleViewModel.resumeDismissRunning() },
                        onRerunDismissRunning = { targetHandleViewModel.rerunDismissRunning() }
                    )
                }
            }
        } ?: clear()
    }

    override fun onServiceConnected(overlayService: OverlayService) {
        targetHandleViewModel = overlayService.getTargetHandleViewModel()
        super.onServiceConnected(overlayService)
    }

    override fun onOverlayServiceEvent(overlayService: OverlayService, event: Event) {
        when (event) {
            Event.ConfigurationChanged -> {
                clear()
            }

            else -> {}
        }
        super.onOverlayServiceEvent(overlayService, event)
    }

    open suspend fun cast(
        applicationContext: Context,
        translation: Transaction,
        visionText: VisionText
    ) {
        layoutParams = getTranslationLayout(
            applicationContext,
            translation,
            visionText
        )
        super.cast(applicationContext)
        liveStateFlow.value = true
    }

    override fun clear() {
        liveStateFlow.value = false
        drawnTranslation = null
        super.clear()
    }

    /**
     * 렌더링 데이타 저장.
     * 같은 데이타를 다시 렌더링 할 시 화면깜빡임을 없애기 위함.
     */
    private var drawnTranslation: Transaction? = null

    private val FONT_SIZE_MIN_SP: Float = 13f // 최소 폰트 사이즈
    private val FONT_SIZE_MAX_SP: Float = 24f // 최대 폰트 사이즈
    private val FONT_SIZE_RATIO: Float = 0.9f // visionTextFontHeight 대비 번역창 폰트 사이즈 비율
    private val CONTENT_WIDTH_RATIO: Float = 1.2f // VisionText 대비 콘텐트 너비 비율
    // 원문과 번역문은 서로 다른 언어이고 쓰기 방향도 다를 수 있어 각각 독립된 문단으로 그린다.
    // 그래서 구분자는 공백이 아니라 줄바꿈이다. 창 크기 측정과 복사도 같은 구분자를 쓴다.
    private val SOURCE_RESULT_SEPARATOR: String = "\n"

    // 텍스트 맨 앞에 특수문자처럼 인라인으로 들어가는 스피커 아이콘.
    private val SPEAKER_INLINE_ID: String = "speaker" // annotatedString inline content id
    private val SPEAKER_ICON_EM: Float = 1.1f // 아이콘 크기(폰트 대비 배율). 텍스트와 위화감 없도록 폰트 크기에 비례한다.
    private val SPEAKER_TEXT_GAP: String = " " // 아이콘과 원본텍스트 사이의 여백(폰트 크기에 비례하는 공백 한 칸)

    /**
     * 창 크기 측정용으로, 인라인 스피커 아이콘(≈ SPEAKER_ICON_EM em)이 차지하는 폭을 공백 문자로 환산해 돌려준다.
     * StaticLayout 은 inline content 를 렌더링하지 못하므로, 측정 텍스트 앞에 이 공백을 붙여
     * 실제 렌더(인라인 아이콘)와 창 너비/줄바꿈 계산이 일치하도록 한다.
     * 공백 한 칸의 폭은 폰트 크기에 비례하므로, 필요한 개수는 폰트 크기가 변해도 아이콘 폭을 일정하게 반영한다.
     */
    private fun getSpeakerSpace(context: Context, fontSizeSp: Float): String {
        val fontSizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            fontSizeSp,
            context.resources.displayMetrics
        )
        val spaceWidthPx = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSizePx
        }.measureText(" ").coerceAtLeast(1f)

        val iconWidthPx = fontSizePx * SPEAKER_ICON_EM
        val spaceCount = ceil(iconWidthPx / spaceWidthPx).toInt().coerceIn(1, 20)
        return " ".repeat(spaceCount)
    }

    /**
     * 이 언어로 쓴 문단의 기준 방향. 코드를 모르면 LTR.
     *
     * 문단마다 방향을 따로 주는 이유: 원문과 번역문을 한 문단에 이어 붙이면 양방향 알고리즘이
     * 문단 전체에 기준 방향 하나만 정한다. 아랍어 원문 뒤에 한국어 번역문이 오면 기준이 LTR 로
     * 잡혀, 아랍어 구간의 숫자·괄호·마침표가 반대편에 붙어 문장이 뒤집혀 보인다.
     */
    private fun paragraphDirectionOf(languageCode: String?): TextDirection =
        if (languageCode != null &&
            Language.writingDirection(languageCode, false) == WritingDirection.RTL
        ) TextDirection.Rtl else TextDirection.Ltr

    private fun getTranslationLayout(
        applicationContext: Context,
        translation: Transaction,
        visionText: VisionText
    ): WindowManager.LayoutParams {
        val screenInfo: ScreenInfo = ScreenInfoHolder.get()
        Timber.tag(TAG).d("translation [${translation}] ")
        Timber.tag(TAG).d("visionText [${visionText}] ")

        Timber.tag(TAG).d("translationTransaction.sourceText [${translation.sourceText}] ")
        Timber.tag(TAG).d("drawnTranslationTransaction?.sourceText [${drawnTranslation?.sourceText}] ")
        Timber.tag(TAG).d("translationTransaction.resolvedSourceLanguageCode [${translation.resolvedSourceLanguageCode}] ")
        Timber.tag(TAG).d("drawnTranslationTransaction?.resolvedSourceLanguageCode [${drawnTranslation?.resolvedSourceLanguageCode}] ")

        if (
            translation.sourceText != drawnTranslation?.sourceText
            || translation.resolvedSourceLanguageCode != drawnTranslation?.resolvedSourceLanguageCode
        ) {
            Timber.tag(TAG).e("+++++++++++ clear() !!!!!!!!!!!!!!!!!!")
            clear()
        }

        // 표시용 원문. 원문 언어가 auto 이고 언어가 확정된 경우에만 판정 언어 라벨이 앞에 붙는다(복사·TTS 에는 붙지 않는다).
        val sourceText = translation.detectedLanguageLabel + translation.sourceText

        // 폰트 사이즈
        val fontSizeSp = getRenderFontSizeSp(applicationContext, visionText.fontHeight)
        Timber.tag(TAG).d("+++++++++++ getTranslationLayout fontSizeSp [${fontSizeSp}]")

        // 측정용 문자열. 원문 문단에는 인라인 아이콘 폭(≈공백)과 아이콘~텍스트 갭이 앞에 붙는다.
        val sourceMeasureText =
            getSpeakerSpace(applicationContext, fontSizeSp) + SPEAKER_TEXT_GAP + sourceText
        val resultMeasureText = translation.resultText.orEmpty()

        // 두 문단이 세로로 쌓이므로 너비는 둘 중 넓은 쪽이다(합이 아니다).
        val textWidth = maxOf(
            measureTextWidth(applicationContext, sourceMeasureText, fontSizeSp),
            measureTextWidth(applicationContext, resultMeasureText, fontSizeSp),
        )
        Timber.tag(TAG).d("textWidth [${textWidth}]")

        // 스크린과 TranslationView 사이 최소 마진
        val screenViewMinMargin = applicationContext.resources.getDimensionPixelSize(R.dimen.translation_view_screen_min_margin)
        Timber.tag(TAG).d("screenViewMargin [${screenViewMinMargin}]")

        // TranslationView 의 shadow 두께
        val viewShadowPadding = applicationContext.resources.getDimensionPixelSize(R.dimen.translation_view_shadow_padding)
        Timber.tag(TAG).d("viewShadowPadding [${viewShadowPadding}]")

        // TranslationView 와 content 패딩
        val viewContentPadding = applicationContext.resources.getDimensionPixelSize(R.dimen.translation_view_content_padding)
        Timber.tag(TAG).d("viewContentPadding [${viewContentPadding}]")

        // TranslationView 아래 부분(엔진로고, 복사아이콘) 높이
        val bottomMenuHeight = applicationContext.resources.getDimensionPixelSize(R.dimen.translation_view_bottom_menu_height)
        Timber.tag(TAG).d("bottomMenuHeight [${bottomMenuHeight}]")

        // content 너비
        val contentWidth: Float =
            // 텍스트 너비가 화면너비를 넘지 않으면 텍스트를 한줄로 표현하도록 한다.
            if ((screenViewMinMargin + viewShadowPadding + viewContentPadding + textWidth + viewContentPadding + viewShadowPadding + screenViewMinMargin) < screenInfo.width) {
                textWidth
            } else {
                visionText.width * CONTENT_WIDTH_RATIO
            }
        Timber.tag(TAG).d("contentWidth [${contentWidth}]")

        // TranslationView 최소 너비
        val viewMinWidth = applicationContext.resources.getDimensionPixelSize(R.dimen.translation_view_min_width)

        // TranslationView 최대 너비
        val viewMaxWidth = screenInfo.width - screenViewMinMargin * 2

        // TranslationView 너비
        val viewWidth: Int = (viewShadowPadding + viewContentPadding + contentWidth + viewContentPadding + viewShadowPadding).roundToInt().coerceIn(viewMinWidth, viewMaxWidth)
        Timber.tag(TAG).d("viewWidth [${viewWidth}]")

        // content 높이. 두 문단을 각각 재서 더한다.
        //
        // 하나의 StaticLayout 에 "원문\n번역문" 으로 넣어 재면 안 된다. 실제 렌더는 문단마다
        // 별도의 Paragraph 라서 각자 ascent/descent 를 갖는데, 한 번에 재면 그 여백이 맨 위와
        // 맨 아래에만 붙어 렌더보다 낮게 나온다. 그러면 AutoResizeText 가 오버플로로 판단해
        // 폰트를 한 단계 줄여 그리고, 줄어든 만큼이 창 아래에 빈 줄처럼 남는다.
        val contentTextWidth = viewWidth - (viewShadowPadding * 2) - (viewContentPadding * 2)
        val contentHeight = calculateTextHeight(
            applicationContext,
            sourceMeasureText,
            contentTextWidth,
            fontSizeSp,
        ) + calculateTextHeight(
            applicationContext,
            resultMeasureText,
            contentTextWidth,
            fontSizeSp,
        )
        Timber.tag(TAG).d("contentHeight [${contentHeight}]")

        // TranslationView 최대 높이
        val viewMaxHeight = screenInfo.height - screenViewMinMargin * 2
        Timber.tag(TAG).d("viewMaxHeight [${viewMaxHeight}]")

        // TranslationView 높이
        val viewHeight: Int =
            // 텍스트를 한줄로 표현하는 경우
            if ((screenViewMinMargin + viewShadowPadding + viewContentPadding + textWidth + viewContentPadding + viewShadowPadding + screenViewMinMargin) < screenInfo.width) {
                viewShadowPadding + viewContentPadding + contentHeight + bottomMenuHeight + viewShadowPadding
            } else {
                min(
                    (viewShadowPadding + viewContentPadding + contentHeight + bottomMenuHeight + viewShadowPadding),
                    viewMaxHeight
                )
            }
        Timber.tag(TAG).d("viewHeight [${viewHeight}]")

        // 확장된 너비 반영된 포지션
        // 말풍선을 대상 텍스트의 가운데에 맞춘다.
        // visionText.start 를 쓰면 안 된다 — RTL 에서 그 값은 박스의 오른쪽 변이라
        // 말풍선이 글자 폭만큼 통째로 오른쪽으로 밀린다(LTR 공식은 우연히 가운데 정렬과 같다).
        val layoutPosX = (visionText.boundingBox.centerX() - viewWidth / 2).coerceIn(
            0,
            (screenInfo.width - viewWidth)
        )
        val layoutPosY =
            visionText.boundingBox.top - VisionTextView.paragraphFrameMargin - applicationContext.resources.getDimensionPixelSize(
                R.dimen.translation_view_vision_text_v_margin
            ) - viewHeight
        Timber.tag(TAG).d("layoutPosX [${layoutPosX}] layoutPosY [${layoutPosY}]")

        return WindowManager.LayoutParams(
            viewWidth,
            viewHeight,
            layoutPosX,
            layoutPosY,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
    }

    /**
     * 주어진 visionTextFontHeight를 사용해 원하는 텍스트 크기를 계산하고, 해당 크기를 SP 단위로 반환하는 함수.
     * - visionTextFontHeight 를 SP 단위로 변환합니다.
     * - 변환된 텍스트 높이는 FONT_SIZE_MIN_SP과 FONT_SIZE_MAX_SP 사이로 제한됩니다.
     * - 최종적으로, 소수점 둘째 자리까지 포맷된 값을 Float 타입으로 반환합니다.
     *
     * @param context Context - 리소스 접근을 위한 컨텍스트
     * @param visionTextFontHeight Double - 입력된 텍스트의 높이 값
     * @return Float - 변환된 SP 단위의 텍스트 크기
     */
    private fun getRenderFontSizeSp(context: Context, visionTextFontHeight: Double): Float {
        val fontHeight = (visionTextFontHeight * FONT_SIZE_RATIO).toFloat().toSpValue(context)
            .coerceIn(FONT_SIZE_MIN_SP, FONT_SIZE_MAX_SP)
        return (fontHeight * 100).roundToInt() / 100f
    }

    /**
     * 주어진 텍스트와 글꼴 크기를 바탕으로 텍스트의 너비를 측정하여 반환하는 함수.
     * - TextPaint를 생성하고, 글꼴 크기를 fontSizeSp로 설정합니다.
     * - measureText() 메서드를 사용하여 텍스트의 실제 너비를 계산하고, Float 타입으로 반환합니다.
     *
     * @param context Context - 리소스 접근을 위한 컨텍스트
     * @param text String - 너비를 측정할 텍스트
     * @param fontSizeSp Float - 텍스트의 글꼴 크기 (SP 단위)
     * @return Float - 텍스트의 너비 (픽셀 단위)
     */
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

    /**
     * 주어진 텍스트, 텍스트의 너비, 글꼴 크기를 바탕으로 텍스트가 차지하는 높이를 계산하여 반환하는 함수.
     * - StaticLayout을 사용해 텍스트의 레이아웃을 설정하며, 이를 통해 여러 줄에 걸쳐 텍스트의 전체 높이를 측정할 수 있습니다.
     * - 텍스트의 높이는 StaticLayout 객체의 height 속성을 통해 반환됩니다.
     *
     * @param context Context - 리소스 접근을 위한 컨텍스트
     * @param text String - 높이를 계산할 텍스트
     * @param width Int - 텍스트가 그려질 최대 너비 (픽셀 단위)
     * @param fontSizeSp Float - 텍스트의 글꼴 크기 (SP 단위)
     * @return Int - 텍스트의 전체 높이 (픽셀 단위)
     */
    private fun calculateTextHeight(
        context: Context,
        text: String,
        width: Int,
        fontSizeSp: Float
    ): Int {
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                fontSizeSp,
                context.resources.displayMetrics
            )
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.0f)
            .setIncludePad(true)
            .build()
//        return (layout.height * 1.2).toInt()
        return layout.height
    }

    /**
     * 창을 실제로 그려진 텍스트 높이에 맞춘다.
     *
     * 창 크기는 표시 전에 StaticLayout 으로 미리 재는데, 그 줄바꿈 판정이 Compose 와 다르다.
     * 아랍어처럼 폴백 폰트로 셰이핑되는 글에서 한 줄씩 어긋나고(18줄 대 19줄 — 2026-09-22 실측),
     * 그러면 AutoResizeText 가 폰트를 줄여 맞추느라 줄어든 만큼이 번역문 아래 빈 공간으로 남는다.
     * 두 엔진의 줄바꿈을 맞추는 것은 폰트 폴백에 기대는 일이라, 그린 뒤에 창을 줄이는 쪽이 안전하다.
     *
     * 말풍선은 대상 위에 붙으므로 줄인 만큼 아래로 내려 아랫변을 제자리에 둔다.
     * 한 번만 줄인다 — 줄인 창에서 다시 재면 또 줄어들어 되먹임이 생긴다.
     */
    private var heightAdjustedFor: Transaction? = null

    private fun shrinkToRenderedHeight(context: Context, renderedHeightPx: Int) {
        val translation = drawnTranslation ?: return
        if (heightAdjustedFor === translation) return
        if (!isAttachedToWindow()) return

        val shadow = context.resources.getDimensionPixelSize(R.dimen.translation_view_shadow_padding)
        val padding = context.resources.getDimensionPixelSize(R.dimen.translation_view_content_padding)
        val bottomMenu = context.resources.getDimensionPixelSize(R.dimen.translation_view_bottom_menu_height)
        val wanted = shadow + padding + renderedHeightPx + bottomMenu + shadow
        val slack = layoutParams.height - wanted
        // 늘리지는 않는다. 미리 잰 값보다 더 필요했다면 이미 폰트를 줄여 맞춘 상태다.
        if (slack <= MIN_HEIGHT_ADJUST_PX) return

        heightAdjustedFor = translation
        layoutParams.height = wanted
        layoutParams.y += slack
        updateLayout(context)
    }

    /** 이보다 작은 차이는 무시한다. 반올림 때문에 창이 미세하게 흔들리는 것을 막는다. */
    private val MIN_HEIGHT_ADJUST_PX = 8

    @Composable
    fun TranslationBox(
        translation: Transaction,
        fontSize: TextUnit = 60.sp,
        enableAutoResize: Boolean = true,
        onPauseDismissRunning: () -> Unit,
        onResumeDismissRunning: () -> Unit,
        onRerunDismissRunning: () -> Unit,
    ) {
        drawnTranslation = translation

        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val coroutineScope = rememberCoroutineScope()

        // 다크 모드 여부
        val isDarkMode = isSystemInDarkTheme()

        // 배경 및 텍스트/아이콘 색상
        val backgroundColor = if (isDarkMode) Color.Black else Color.White
        val sourceTextColor = colorResource(if (isDarkMode) R.color.selected_text_color_dark else R.color.selected_text_color)
        val resultTextColor = if (isDarkMode) Color(0xFFFDFDFD) else Color(0xFF454545)
        val borderColor = if (isDarkMode) Color(0xFF6A6A6A) else Color(0xFFD6D6D6)
        val roundedCornerShape = RoundedCornerShape(16.dp)

        var readyToDisplay by remember { mutableStateOf(!enableAutoResize) }

        val shadowPadding = dimensionResource(R.dimen.translation_view_shadow_padding)
        val viewContentPadding = dimensionResource(R.dimen.translation_view_content_padding)
        val bottomMenuHeight = dimensionResource(R.dimen.translation_view_bottom_menu_height)

        val sourceLanguageCode by targetHandleViewModel.preferenceRepository.sourceLanguageCodeFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = "auto"
        )
        // 원문 문단은 확정된 원문 언어의 방향을 따른다. 설정이 auto 면 설정값에는 "auto" 밖에
        // 없으므로, 모델이나 엔진이 판정한 언어가 유일한 단서다.
        val sourceDirection =
            paragraphDirectionOf(translation.resolvedSourceLanguageCode ?: sourceLanguageCode)
        val resultDirection = paragraphDirectionOf(translation.targetLanguageCode)
        val sourceIsRtl = sourceDirection == TextDirection.Rtl

        // 표시용 원문. 라벨은 화면에만 붙이고 클립보드에는 넣지 않는다.
        val sourceText = translation.detectedLanguageLabel + translation.sourceText

        // 원문과 번역문을 각각 하나의 문단으로 만든다. 문단마다 기준 방향이 따로 정해지므로
        // 아랍어 원문은 오른쪽에서, 한국어 번역문은 왼쪽에서 시작한다.
        // 정렬은 AutoResizeText 의 TextAlign.Start 가 각 문단의 방향에 맞춰 해석한다.
        val annotatedText = buildAnnotatedString {
            withStyle(ParagraphStyle(textDirection = sourceDirection)) {
                // 스피커 아이콘을 원문 문단 맨 앞에 특수문자처럼 인라인으로 삽입한다.
                // 문단이 RTL 이면 "맨 앞"이 오른쪽이라 아이콘도 함께 넘어간다.
                appendInlineContent(SPEAKER_INLINE_ID, "🔊")
                append(SPEAKER_TEXT_GAP)
                withStyle(
                    style = SpanStyle(
                        color = sourceTextColor,
                        fontWeight = FontWeight.Bold
                    )
                ) {
                    append(sourceText)
                }
            }
            withStyle(ParagraphStyle(textDirection = resultDirection)) {
                withStyle(style = SpanStyle(color = resultTextColor)) {
                    append(translation.resultText.orEmpty())
                }
            }
        }

        // Window transparency
        val translationTransparency by targetHandleViewModel.preferenceRepository.translationTransparencyFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = PreferenceRepository.DEFAULT_TRANSPARENCY
        )

        val automaticTranslationPlayback by targetHandleViewModel.preferenceRepository.automaticTranslationPlaybackFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = false
        )

        if (automaticTranslationPlayback) {
            targetHandleViewModel.playTTS(translation)
        }

        // 인라인 스피커 아이콘: 폰트 크기(em) 기준으로 크기가 정해져 텍스트와 위화감 없이 특수문자처럼 렌더링된다.
        // 탭하면 TTS 재생. Placeholder 크기가 em 단위라 폰트가 커지면 아이콘도 함께 커진다.
        val inlineContent = mapOf(
            SPEAKER_INLINE_ID to InlineTextContent(
                Placeholder(
                    width = SPEAKER_ICON_EM.em,
                    height = SPEAKER_ICON_EM.em,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.Center
                )
            ) {
                // AutoMirrored 아이콘은 레이아웃 방향을 따라 좌우가 뒤집힌다.
                // 원문이 RTL 이면 스피커도 반대쪽을 향해야 문단 방향과 어긋나지 않는다.
                CompositionLocalProvider(
                    LocalLayoutDirection provides
                            if (sourceIsRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.VolumeUp,
                    contentDescription = "Listen to translation",
                    tint = sourceTextColor,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            // 아이콘 글리프를 자르지 않도록 unbounded(원형) 리플. 색상은 원문 텍스트 색을 따른다.
                            indication = ripple(bounded = false, color = sourceTextColor)
                        ) {
                            Timber.tag("TranslationView").d("onClick 스피커 아이콘 (inline)")
                            targetHandleViewModel.playTTS(translation)
                        }
                )
                }
            }
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(translationTransparency),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(shadowPadding),
            ) {
                Box(
                    modifier = Modifier
                        .shadow(shadowPadding / 2, shape = roundedCornerShape, clip = true)
                        .background(
                            color = backgroundColor,
                            shape = roundedCornerShape
                        )
                        .border(
                            width = 0.4.dp,
                            color = borderColor,
                            shape = roundedCornerShape
                        )
                        .fillMaxSize()
                        .alpha(if (readyToDisplay) 1.0f else 0f)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    // 터치가 시작되면 dismiss running pause
                                    onPauseDismissRunning()
                                    tryAwaitRelease()
                                    // 터치가 끝난 후 dismiss running resume
                                    onResumeDismissRunning()
                                }
                            )
                        }
                ) {
                    ConstraintLayout(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        val (textBox, image) = createRefs()

                        // 부모의 하단에 위치
                        Row(
                            modifier = Modifier
                                .constrainAs(image) {
                                    bottom.linkTo(parent.bottom)
                                    start.linkTo(parent.start)
                                }
                                .wrapContentHeight()
//                                .background(Color(0x33000000))
                                .fillMaxWidth()
                                .padding(start = viewContentPadding),
                            verticalAlignment = Alignment.CenterVertically, // 수직 가운데 정렬
                            horizontalArrangement = Arrangement.End // 수평 방향 끝에 위치
                        ) {
                            // 번역 kit 이미지
                            Image(
                                painter = painterResource(id = translation.translationKitType!!.logoResourceId),
                                contentDescription = "Translated by ${translation.translationKitType}",
                                modifier = Modifier
                                    .sizeIn(
//                                        maxWidth = 40.dp,
                                        maxHeight = 16.dp,
                                    ),
                                contentScale = ContentScale.Fit,
                            )

                            // OpenAI/Gemini 는 로고에 이름이 없으므로 실제 사용한 모델명을 텍스트로 함께 표시
                            translation.modelName?.let { modelName ->
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = modelName,
                                    color = Color(0xFF747278),
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                )
                            }

                            Spacer(modifier = Modifier.weight(1f)) // 로고 이미지와 IconButton 사이에 공간을 추가

                            // Copy
                            IconButton(
                                onClick = {
                                    onRerunDismissRunning()
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText(
                                        "Translated Text",
                                        translation.sourceText + SOURCE_RESULT_SEPARATOR + translation.resultText
                                    )
                                    clipboard.setPrimaryClip(clip)
                                },
                                modifier = Modifier
                                    .size(bottomMenuHeight)
                                    .padding(1.dp) // 버튼 크기
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy content",
                                    tint = Color(0xFF747278),
                                    modifier = Modifier
                                        .size(18.dp)
                                        .padding(2.dp) // 아이콘 크기
                                )
                            }

                            // Reply
                            IconButton(
                                onClick = {
                                    coroutineScope.launch {
                                        delay(300)
                                        ReplyActivity.start(
                                            context,
                                            translation.resultText,
                                            translation.resolvedSourceLanguageCode,
                                            translation.targetLanguageCode
                                        )
                                        clear()
                                    }
                                },
                                modifier = Modifier
                                    .size(bottomMenuHeight)
                                    .padding(1.dp) // 버튼 크기
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_reply),
                                    contentDescription = "Reply",
                                    modifier = Modifier
                                        .size(24.dp)
                                        .padding(2.dp), // 아이콘 크기
                                    tint = Color(0xFF747278),
                                )
                            }
                        }

                        // 부모의 상단에 고정
                        Box(
                            modifier = Modifier
                                .constrainAs(textBox) {
                                    top.linkTo(parent.top)
                                    bottom.linkTo(image.top)
                                    start.linkTo(parent.start)
                                    end.linkTo(parent.end)
                                    height = Dimension.fillToConstraints
                                }
//                                .background(Color(0x3312df87))
                                .fillMaxWidth()
                                .padding(
                                    start = viewContentPadding,
                                    top = viewContentPadding,
                                    end = viewContentPadding
                                )
                        ) {
                            AutoResizeText(
                                text = annotatedText,
                                maxFontSize = fontSize,
                                enableAutoResize = enableAutoResize,
                                inlineContent = inlineContent,
                                onReadyToDisplay = { renderedHeightPx ->
                                    readyToDisplay = true
                                    shrinkToRenderedHeight(context, renderedHeightPx)
                                },
                                modifier = Modifier.align(Alignment.TopStart)
                            )
                        }
                    }
                }
            }
        }
    }

}
















