package com.galaxy.airviewdictionary.ui.screen.overlay.menubar


import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.view.isVisible
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRightAlt
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galaxy.airviewdictionary.R
import com.galaxy.airviewdictionary.core.OverlayService
import com.galaxy.airviewdictionary.data.local.screen.ScreenInfo
import com.galaxy.airviewdictionary.data.local.screen.ScreenInfoHolder
import com.galaxy.airviewdictionary.data.local.vision.TextDetectMode
import com.galaxy.airviewdictionary.data.remote.translation.Language
import com.galaxy.airviewdictionary.data.remote.translation.TranslationKitType
import com.galaxy.airviewdictionary.data.remote.translation.claude.ClaudeKit
import com.galaxy.airviewdictionary.data.remote.translation.deepl.DeepLKit
import com.galaxy.airviewdictionary.data.remote.translation.gemini.GeminiKit
import com.galaxy.airviewdictionary.data.remote.translation.openai.OpenAiKit
import com.galaxy.airviewdictionary.ui.screen.ads.AdGateActivity
import com.galaxy.airviewdictionary.ui.screen.main.SettingsActivity
import com.galaxy.airviewdictionary.ui.screen.overlay.Event
import com.galaxy.airviewdictionary.ui.screen.overlay.OverlayView
import com.galaxy.airviewdictionary.ui.screen.overlay.fixedarea.FixedAreaView
import com.galaxy.airviewdictionary.ui.screen.overlay.settings.HelpTextDetectModeView
import com.galaxy.airviewdictionary.ui.screen.overlay.settings.HelpTranslationKitView
import com.galaxy.airviewdictionary.ui.screen.overlay.settings.SliderDialogView
import com.galaxy.airviewdictionary.ui.screen.overlay.targethandle.CaptureStatus
import com.galaxy.airviewdictionary.ui.screen.overlay.targethandle.TargetHandleViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Singleton

/**
 * 메뉴 뷰
 */
@Singleton
class MenuBarView private constructor() : OverlayView() {

    companion object {
        val INSTANCE: MenuBarView by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { MenuBarView() }

        /**
         * 메뉴 조작중 임을 표시하는 변수
         * TargetHandleView 에서 참조하여 도킹 타이머 초기화에 사용한다.
         */
        val operatingStateFlow = MutableStateFlow(false)
    }

    private lateinit var viewModel: MenuBarViewModel

    private lateinit var targetHandleViewModel: TargetHandleViewModel

    override lateinit var layoutParams: WindowManager.LayoutParams

    private var debounceSetOperatingStateJob: Job? = null

    private fun toggleOperatingState() {
        operatingStateFlow.value = true
        debounceSetOperatingStateJob?.cancel()
        debounceSetOperatingStateJob = launchInAVDCoroutineScope {
            delay(400)
            operatingStateFlow.value = false
        }
    }

    override val composable: @Composable () -> Unit = @Composable {
        if (isAttachedToWindow()) {
            val context = LocalContext.current
            val lifecycleOwner = LocalLifecycleOwner.current
            val coroutineScope = rememberCoroutineScope()
            val configuration = LocalConfiguration.current

            val menuBarDragState = remember { mutableStateOf(MenuBarDragStates.Idle) }

            // screen capture 진행 상태의 flow
            val captureStatus by targetHandleViewModel.captureStatusFlow.collectAsStateWithLifecycle()

            // target handle 모션 이벤트 flow
            val targetHandleMotionEventState by targetHandleViewModel.motionEventFlow.collectAsStateWithLifecycle()

            // SettingsActivity live 상태 flow
            val settingsActivityLiveState by SettingsActivity.liveStateFlow.collectAsStateWithLifecycle()

            // 광고 게이트·광고 화면이 오버레이를 가리는 동안에는 메뉴바를 숨긴다
            val adGateCovering by AdGateActivity.coveringFlow.collectAsStateWithLifecycle()

            // Drag handle dock state
            val dragHandleDockState by targetHandleViewModel.dockStateFlow.collectAsStateWithLifecycle()

            // 텍스트 탐지 모드
            val textDetectMode by viewModel.preferenceRepository.textDetectModeFlow.collectAsStateWithLifecycle(
                lifecycle = lifecycleOwner.lifecycle,
                initialValue = TextDetectMode.WORD
            )

            // FixedAreaView 번역 상태 flow
            val fixedAreaViewState by FixedAreaView.fixedAreaViewStateFlow.collectAsStateWithLifecycle()

            LaunchedEffect(
                menuBarDragState.value,
                captureStatus,
                targetHandleMotionEventState,
                settingsActivityLiveState,
                adGateCovering,
                dragHandleDockState,
                textDetectMode,
                fixedAreaViewState
            ) {

                view?.let {
                    val menuVisible = when {
                        adGateCovering -> false // 광고 게이트/광고 표시 중에는 숨김
                        settingsActivityLiveState -> true
                        captureStatus != CaptureStatus.Requested
                                && targetHandleMotionEventState != MotionEvent.ACTION_MOVE
                                && (!dragHandleDockState || menuBarDragState.value == MenuBarDragStates.Handling)
                                && !(textDetectMode == TextDetectMode.FIXED_AREA && fixedAreaViewState == FixedAreaView.State.Translating) -> true

                        else -> false
                    }

                    Timber.tag(TAG).d(
                        "captureStatus $captureStatus " +
                                "\ntargetHandle ${targetHandleMotionEventState != MotionEvent.ACTION_MOVE} " +
                                "\ndragHandleDock ${!dragHandleDockState} " +
                                "\nmenuBarDrag ${menuBarDragState.value != MenuBarDragStates.Handling} " +
                                "\nmenuVisible $menuVisible " +
                                "\ntextDetectMode $textDetectMode " +
                                "\nfixedAreaViewMenuVisibilityControl $fixedAreaViewState" +
                                "\n!(textDetectMode == TextDetectMode.FIXED_AREA && fixedAreaViewState == FixedAreaView.State.Translating) ${!(textDetectMode == TextDetectMode.FIXED_AREA && fixedAreaViewState == FixedAreaView.State.Translating)}"
                    )

                    if (menuVisible) {
                        if (it.visibility != View.VISIBLE) {
                            it.visibility = View.VISIBLE
                            it.alpha = 0f
                            it.animate()
                                .alpha(1f)
                                .setStartDelay(200)
                                .setDuration(150)
                                .start()
                        }
                    } else {
                        if (it.isVisible) {
                            it.isVisible = false
                        }
                    }
                }
            }

            // Menu bar Visibility
            val menuBarVisibility by viewModel.preferenceRepository.menuBarVisibilityFlow.collectAsStateWithLifecycle(
                lifecycle = lifecycleOwner.lifecycle,
                initialValue = true
            )

            // Menu bar transparency
            val menuBarTransparency by viewModel.preferenceRepository.menuBarTransparencyFlow.collectAsStateWithLifecycle(
                lifecycle = lifecycleOwner.lifecycle,
                initialValue = 1.0f
            )

            val menuBarConfig by viewModel.preferenceRepository.menuBarConfigFlow.collectAsStateWithLifecycle(
                lifecycle = lifecycleOwner.lifecycle,
                initialValue = MenuConfig.WHOLE
            )

            val sliderDialogLiveState by SliderDialogView.liveStateFlow.collectAsStateWithLifecycle()

            val alpha = when {
                !settingsActivityLiveState -> menuBarTransparency
                !sliderDialogLiveState -> 1.0f
                menuBarVisibility -> menuBarTransparency
                else -> 0.0f
            }

            val menuConfig = when {
                settingsActivityLiveState || captureStatus == CaptureStatus.PermissionRequested -> MenuConfig.WHOLE
                else -> menuBarConfig
            }

            val textDetectModeHelpAlpha = remember { Animatable(0f) }
            var textDetectModeHelpAlphaAnimation: Job? by remember { mutableStateOf(null) }

            val translationKitTypeAlpha = remember { Animatable(0f) }
            var translationKitTypeAlphaAnimation: Job? by remember { mutableStateOf(null) }

            val localeLanguage: String = configuration.locales[0].language

            // source language
            val sourceLanguageCode by viewModel.preferenceRepository.sourceLanguageCodeFlow.collectAsStateWithLifecycle(
                lifecycle = lifecycleOwner.lifecycle,
                initialValue = "auto"
            )
            val sourceLanguage = viewModel.translationRepository.getSupportedSourceLanguage(sourceLanguageCode)
            Timber.tag(TAG).d("sourceLanguageCode $sourceLanguageCode sourceLanguage $sourceLanguage  localeLanguage $localeLanguage")

            // target language
            val targetLanguageCode by viewModel.preferenceRepository.targetLanguageCodeFlow.collectAsStateWithLifecycle(
                lifecycle = lifecycleOwner.lifecycle,
                initialValue = localeLanguage
            )
            val targetLanguage = viewModel.translationRepository.getSupportedTargetLanguage(targetLanguageCode)

            // translationKit Type
            val translationKitType by viewModel.preferenceRepository.translationKitTypeFlow.collectAsStateWithLifecycle(
                lifecycle = lifecycleOwner.lifecycle,
                initialValue = TranslationKitType.GOOGLE
            )

            val menuBarViewSettlePosition by SettingsActivity.menuBarViewSettlePositionFlow.collectAsStateWithLifecycle()
            menuBarViewSettlePosition?.let { newPosition ->
                Timber.tag(TAG).d("newPosition $newPosition settingsActivityLiveState $settingsActivityLiveState")
                if (settingsActivityLiveState) {
                    updateLayout(context, newPosition.x, newPosition.y)
                }
            }

            if (!menuBarVisibility && !settingsActivityLiveState) {
                clear()
            }

            val isDarkMode = isSystemInDarkTheme()
            val contentColor = if (isDarkMode) Color(0xFF898989) else Color(0xFF676767)

            Box {
                MenuBar(
                    menuConfig = menuConfig,
                    menuAlpha = alpha,
                    textDetectMode = textDetectMode,
                    sourceLanguageCode = sourceLanguageCode,
                    sourceLanguage = sourceLanguage,
                    targetLanguageCode = targetLanguageCode,
                    targetLanguage = targetLanguage,
                    translationKitType = translationKitType,
                    updateSwapLanguage = { sourceLanguage, targetLanguage ->
                        viewModel.updateSwapLanguage(sourceLanguage, targetLanguage)
                        toggleOperatingState()
                    },
                    onDragStart = { _ ->
                        if (!settingsActivityLiveState) {
                            menuBarDragState.value = MenuBarDragStates.Handling
                        }
                        operatingStateFlow.value = true
                    },
                    onDragEnd = {
                        menuBarDragState.value = MenuBarDragStates.Idle
                        operatingStateFlow.value = false
                    },
                    onDragCancel = {
                        menuBarDragState.value = MenuBarDragStates.Idle
                    },
                    onDrag = { _: PointerInputChange, dragAmount: Offset ->
                        if (!settingsActivityLiveState) {
                            view?.let {
                                val screenInfo: ScreenInfo = ScreenInfoHolder.get()
                                // 메뉴바가 화면보다 크거나(작은 화면·회전 직후 stale 값) 화면 정보가
                                // 아직 0 인 순간에는 coerceIn 범위가 뒤집혀 크래시하므로 하한을 0 으로 막는다.
                                val xBound = (screenInfo.width / 2 - it.width / 2).coerceAtLeast(0)
                                val yMax = (screenInfo.height - it.height).coerceAtLeast(0)
                                layoutParams.x = (layoutParams.x + dragAmount.x.toInt()).coerceIn(-xBound, xBound)
                                layoutParams.y = (layoutParams.y + dragAmount.y.toInt()).coerceIn(0, yMax)
                                updateLayout(context)
                            }
                            menuBarDragState.value = MenuBarDragStates.Handling
                        }
                    },
                    onClickTextDetectMode = {},
                    updateTextDetectMode = { textDetectMode ->
                        viewModel.updateTextDetectMode(textDetectMode)
                        textDetectModeHelpAlphaAnimation?.cancel()
                        textDetectModeHelpAlphaAnimation = coroutineScope.launch {
                            textDetectModeHelpAlpha.snapTo(1f)
                            delay(1500)
                            textDetectModeHelpAlpha.animateTo(
                                targetValue = 0f,
                                animationSpec = tween(durationMillis = 400)
                            )
                        }
                        toggleOperatingState()
                    },
                    launchLanguageListView = { isSourceLanguage ->
                        viewModel.launchLanguageListView(isSourceLanguage)
                        toggleOperatingState()
                    },
                    isSwappable = { sourceLanguageCode: String, targetLanguageCode: String, kitType: TranslationKitType ->
                        viewModel.isLanguageSwappable(sourceLanguageCode, targetLanguageCode, kitType)
                    },
                    onClickTranslationKitType = {},
                    updateTranslationKitType = { kitType ->
                        viewModel.updateTranslationKitType(kitType)
                        translationKitTypeAlphaAnimation?.cancel()
                        translationKitTypeAlphaAnimation = coroutineScope.launch {
                            translationKitTypeAlpha.snapTo(1f)
                            delay(1500)
                            translationKitTypeAlpha.animateTo(
                                targetValue = 0f,
                                animationSpec = tween(durationMillis = 400)
                            )
                        }
                        toggleOperatingState()
                    },
                    settingsButtonVisible = !settingsActivityLiveState,
                )

                if (settingsActivityLiveState) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .offset(x = 33.dp)
                            .align(Alignment.TopStart)
                            .alpha(textDetectModeHelpAlpha.value),
                    ) {
                        IconButton(
                            enabled = textDetectModeHelpAlpha.value == 1.0f,
                            onClick = {
                                if (!HelpTextDetectModeView.INSTANCE.isRunning.get()) {
                                    coroutineScope.launch {
                                        HelpTextDetectModeView.INSTANCE.cast(
                                            applicationContext = context,
                                            initialDetectMode = textDetectMode,
                                        )
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                                contentDescription = "help TextDetectMode",
                                modifier = Modifier.size(18.dp),
                                tint = contentColor
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .align(Alignment.TopEnd)
                            .alpha(translationKitTypeAlpha.value),
                    ) {
                        IconButton(
                            enabled = translationKitTypeAlpha.value == 1.0f,
                            onClick = {
                                if (!HelpTranslationKitView.INSTANCE.isRunning.get()) {
                                    coroutineScope.launch {
                                        HelpTranslationKitView.INSTANCE.cast(
                                            applicationContext = context,
                                            initialTranslationKitType = translationKitType,
                                        )
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                                contentDescription = "help TranslationKit",
                                modifier = Modifier.size(18.dp),
                                tint = contentColor
                            )
                        }
                    }
                }
            }
        }
    }

    override suspend fun cast(applicationContext: Context) {
        val screenInfo: ScreenInfo = ScreenInfoHolder.get()
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            0,
            (screenInfo.height / 5),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER
        }
        super.cast(applicationContext)
    }

    suspend fun castAtStartPosition(applicationContext: Context) {
        val screenInfo: ScreenInfo = ScreenInfoHolder.get()
        layoutParams.x = 0
        layoutParams.y = (screenInfo.height / 5)
        super.cast(applicationContext)
    }

    override fun onServiceConnected(overlayService: OverlayService) {
        viewModel = overlayService.getMenuBarViewModel()
        targetHandleViewModel = overlayService.getTargetHandleViewModel()
        super.onServiceConnected(overlayService)
    }

    override fun onOverlayServiceEvent(overlayService: OverlayService, event: Event) {
        when (event) {
            Event.ConfigurationChanged -> {
                onConfigurationChanged(overlayService.applicationContext)
            }

            else -> {}
        }
        super.onOverlayServiceEvent(overlayService, event)
    }

    /**
     * 화면 회전 되었을 시 위치 재설정
     */
    private fun onConfigurationChanged(context: Context) {
        Timber.tag(TAG).d("#### onConfigurationChanged() ####")
        val screenInfo: ScreenInfo = ScreenInfoHolder.get()
        val viewWidth: Int = view?.width ?: 0
        val viewHeight: Int = view?.height ?: 0
        if (viewWidth == 0 || viewHeight == 0) {
            return
        }
        Timber.tag(TAG).d("viewWidth $viewWidth viewHeight $viewHeight")

        // 화면 회전 전의 위치에 비례하게 위치 재설정
        layoutParams.x = when (layoutParams.x + viewWidth) {
            viewWidth -> 0
            screenInfo.height -> screenInfo.width - viewWidth
            screenInfo.width -> screenInfo.height - viewWidth
            else -> (layoutParams.x + viewWidth / 2) * screenInfo.width / screenInfo.height - viewWidth / 2
        }

        if (layoutParams.x < 0) {
            layoutParams.x = 0
        } else if ((layoutParams.x + viewWidth) > screenInfo.width) {
            layoutParams.x = screenInfo.width - viewWidth
        }

        layoutParams.y = when (layoutParams.y + viewHeight) {
            viewHeight -> 0
            screenInfo.height -> screenInfo.width - viewHeight
            screenInfo.width -> screenInfo.height - viewHeight
            else -> (layoutParams.y + viewHeight / 2) * screenInfo.height / screenInfo.width - viewHeight / 2
        }

        if (layoutParams.y < 0) {
            layoutParams.y = 0
        } else if ((layoutParams.y + viewHeight) > screenInfo.height) {
            layoutParams.y = screenInfo.height - viewHeight
        }

        updateLayout(context)
    }
}

@Composable
fun DynamicRowOrColumn(
    isVerticalConfig: Boolean,
    content: @Composable () -> Unit
) {
    if (isVerticalConfig) {
        Column(
            modifier = Modifier.padding(horizontal = 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy((-9).dp)
        ) {
            content()
        }
    } else {
        Row(
            modifier = Modifier.padding(horizontal = 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy((-9).dp)
        ) {
            content()
        }
    }
}

@Composable
fun MenuBar(
    menuConfig: MenuConfig = MenuConfig.WHOLE,
    scaleFactor: Float = 1.0f,
    menuAlpha: Float = 1.0f,
    shadowPadding: Dp = 3.dp,
    borderWidth: Dp = 0.4.dp,
    textDetectMode: TextDetectMode,
    sourceLanguageCode: String,
    sourceLanguage: Language,
    targetLanguageCode: String,
    targetLanguage: Language,
    translationKitType: TranslationKitType,
    updateSwapLanguage: ((currentSourceLanguage: Language, currentTargetLanguage: Language) -> Unit)? = null,
    onDragStart: ((overSlopOffset: Offset) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
    onDragCancel: (() -> Unit)? = null,
    onDrag: ((change: PointerInputChange, dragAmount: Offset) -> Unit)? = null,
    onClickTextDetectMode: (() -> Unit)? = null,
    updateTextDetectMode: ((textDetectMode: TextDetectMode) -> Unit)? = null,
    launchLanguageListView: ((isSourceLanguage: Boolean) -> Unit)? = null,
    isSwappable: (sourceLanguageCode: String, targetLanguageCode: String, kitType: TranslationKitType) -> Boolean,
    onClickTranslationKitType: (() -> Unit)? = null,
    updateTranslationKitType: ((kitType: TranslationKitType) -> Unit)? = null,
    settingsButtonVisible: Boolean = false,
    modifier: Modifier = Modifier
) {
    val isDarkMode = isSystemInDarkTheme()
    val layoutDirection = LocalLayoutDirection.current
    val isRtl = layoutDirection == LayoutDirection.Rtl

    val isVerticalConfig =
        menuConfig == MenuConfig.V_DETECT_MODE
                || menuConfig == MenuConfig.V_DETECT_MODE_TRANSLATION_KIT
                || menuConfig == MenuConfig.V_LANGUAGE
                || menuConfig == MenuConfig.V_LANGUAGE_TRANSLATION_KIT
                || menuConfig == MenuConfig.V_DETECT_MODE_LANGUAGE
                || menuConfig == MenuConfig.V_WHOLE

    val isShortConfig = isVerticalConfig
            || menuConfig == MenuConfig.WHOLE_SHORT
            || menuConfig == MenuConfig.LANGUAGE_SHORT_TRANSLATION_KIT
            || menuConfig == MenuConfig.DETECT_MODE_LANGUAGE_SHORT
            || menuConfig == MenuConfig.LANGUAGE_SHORT

    // 배경 및 텍스트/아이콘 색상
    val backgroundColor = if (isDarkMode) Color(0xFF1F1F1F) else Color(0xFFFEFEFE)
    val contentColor = if (isDarkMode) Color(0xFFFDFDFD) else Color(0xFF454545)
    val borderColor = if (isDarkMode) Color(0xFF6A6A6A) else Color(0xFFD6D6D6)
    val roundedCornerShape = RoundedCornerShape(32.dp)
    val languageViewWidth = if (isShortConfig) 46.dp else 82.dp
    val languageTextSize = if (isShortConfig) 13.sp else 15.sp
    val animDuration = 100
    val enterTransition: EnterTransition = if (isVerticalConfig) expandVertically(animationSpec = tween(animDuration)) else expandHorizontally(animationSpec = tween(animDuration))
    val exitTransition: ExitTransition = if (isVerticalConfig) shrinkVertically(animationSpec = tween(animDuration)) else shrinkHorizontally(animationSpec = tween(animDuration))

    // Source 언어와 Target 언어 교환 설정 애니메이션
    val isAnimatingSwapLanguage = remember { mutableStateOf(false) }
    val languageAnimationScope = rememberCoroutineScope()
    val languageTranslationProgress = remember { Animatable(2f) } // 0: 원래 위치, 1: 중간, 2: 돌아옴

    val languageTextAlpha = remember {
        derivedStateOf {
            if (languageTranslationProgress.value < 1f) 1f - languageTranslationProgress.value else languageTranslationProgress.value - 1f
        }
    }
    val sourceTextOffset = remember {
        derivedStateOf {
            if (languageTranslationProgress.value < 1f) languageTranslationProgress.value * 100f else 200f - languageTranslationProgress.value * 100f
        }
    }
    val targetTextOffset = remember {
        derivedStateOf {
            if (languageTranslationProgress.value < 1f) -languageTranslationProgress.value * 100f else languageTranslationProgress.value * 100f - 200f
        }
    }

    // Source 언어와 Target 언어 교환 설정 애니메이션 중간지점 이벤트
    LaunchedEffect(languageTranslationProgress.value, languageTextAlpha.value) {
        if (languageTranslationProgress.value == 1f && languageTextAlpha.value == 0f) {
            Timber.tag("MenuBar").d("Text has faded out at the center")
            updateSwapLanguage?.let { it(sourceLanguage, targetLanguage) }
        }
    }

    fun startLanguageAnimation() {
        languageAnimationScope.launch {
            isAnimatingSwapLanguage.value = true
            languageTranslationProgress.animateTo(1f, animationSpec = tween(230)) // 중앙으로 이동
            languageTranslationProgress.animateTo(2f, animationSpec = tween(230)) // 돌아옴
            isAnimatingSwapLanguage.value = false
        }
    }

    Box(
        modifier = modifier
            .wrapContentSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { overSlopOffset: Offset ->
                        onDragStart?.let { it(overSlopOffset) }
                    },
                    onDragEnd = {
                        onDragEnd?.let { it() }
                    },
                    onDragCancel = {
                        onDragCancel?.let { it() }
                    },
                    onDrag = { change: PointerInputChange, dragAmount: Offset ->
                        onDrag?.let { it(change, dragAmount) }
                    },
                )
            }
            .alpha(menuAlpha)
            .graphicsLayer(
                scaleX = scaleFactor,
                scaleY = scaleFactor,
                transformOrigin = TransformOrigin(if (isRtl) 1f else 0f, 0.0f)
            )
    ) {
        Box(
            modifier = Modifier
                .wrapContentSize()
                .padding(shadowPadding)
        ) {
            Surface(
                modifier = Modifier
                    .wrapContentSize()
                    .border(
                        width = borderWidth,
                        color = borderColor,
                        shape = roundedCornerShape
                    )
                    .shadow(
                        elevation = shadowPadding,
                        spotColor = Color.Black,
                        ambientColor = Color.Black,
                        shape = roundedCornerShape
                    ),
                shape = roundedCornerShape,
                color = backgroundColor,
            ) {
                DynamicRowOrColumn(
                    isVerticalConfig = isVerticalConfig,
                ) {
                    // Detect mode 설정
                    AnimatedVisibility(
                        visible = menuConfig == MenuConfig.WHOLE
                                || menuConfig == MenuConfig.DETECT_MODE_LANGUAGE
                                || menuConfig == MenuConfig.WHOLE_SHORT
                                || menuConfig == MenuConfig.DETECT_MODE_LANGUAGE_SHORT
                                || menuConfig == MenuConfig.DETECT_MODE_TRANSLATION_KIT
                                || menuConfig == MenuConfig.DETECT_MODE
                                || menuConfig == MenuConfig.V_DETECT_MODE
                                || menuConfig == MenuConfig.V_DETECT_MODE_TRANSLATION_KIT
                                || menuConfig == MenuConfig.V_DETECT_MODE_LANGUAGE
                                || menuConfig == MenuConfig.V_WHOLE,
                        enter = enterTransition,
                        exit = exitTransition,
                        content = {
                            TextDetectModeIconButton(
                                contentColor = contentColor,
                                textDetectMode = textDetectMode,
                                enabled = !isAnimatingSwapLanguage.value && updateTextDetectMode != null,
                                onClick = onClickTextDetectMode,
                                updateTextDetectMode = { textDetectMode ->
                                    updateTextDetectMode?.let { it(textDetectMode) }
                                },
                            )
                        }
                    )

                    // Source 언어 설정
                    AnimatedVisibility(
                        visible = menuConfig == MenuConfig.WHOLE
                                || menuConfig == MenuConfig.LANGUAGE_TRANSLATION_KIT
                                || menuConfig == MenuConfig.DETECT_MODE_LANGUAGE
                                || menuConfig == MenuConfig.LANGUAGE
                                || menuConfig == MenuConfig.WHOLE_SHORT
                                || menuConfig == MenuConfig.LANGUAGE_SHORT_TRANSLATION_KIT
                                || menuConfig == MenuConfig.DETECT_MODE_LANGUAGE_SHORT
                                || menuConfig == MenuConfig.LANGUAGE_SHORT
                                || menuConfig == MenuConfig.V_LANGUAGE
                                || menuConfig == MenuConfig.V_LANGUAGE_TRANSLATION_KIT
                                || menuConfig == MenuConfig.V_DETECT_MODE_LANGUAGE
                                || menuConfig == MenuConfig.V_WHOLE,
                        enter = enterTransition,
                        exit = exitTransition,
                        content = {
                            Box(modifier = Modifier.width(languageViewWidth)) {
                                TextButton(
                                    onClick = { launchLanguageListView?.let { it(true) } },
                                    enabled = !isAnimatingSwapLanguage.value && launchLanguageListView != null,
                                    modifier = Modifier
                                        .graphicsLayer {
                                            alpha = languageTextAlpha.value
                                            translationX = if (isVerticalConfig) 1f else sourceTextOffset.value
                                            translationY = if (isVerticalConfig) sourceTextOffset.value else 1f
                                        }
                                        .align(Alignment.CenterEnd),
                                    colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
                                ) {
                                    Text(
                                        text = if (isShortConfig) Language(sourceLanguageCode).displayShortName else Language(sourceLanguageCode).displayName,
                                        color = contentColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = languageTextSize),
                                        modifier = Modifier.widthIn(max = languageViewWidth)
                                    )
                                }
                            }
                        }
                    )

                    // Source 언어와 Target 언어 교환 설정
                    AnimatedVisibility(
                        visible = menuConfig == MenuConfig.WHOLE
                                || menuConfig == MenuConfig.LANGUAGE_TRANSLATION_KIT
                                || menuConfig == MenuConfig.DETECT_MODE_LANGUAGE
                                || menuConfig == MenuConfig.LANGUAGE
                                || menuConfig == MenuConfig.WHOLE_SHORT
                                || menuConfig == MenuConfig.LANGUAGE_SHORT_TRANSLATION_KIT
                                || menuConfig == MenuConfig.DETECT_MODE_LANGUAGE_SHORT
                                || menuConfig == MenuConfig.LANGUAGE_SHORT
                                || menuConfig == MenuConfig.V_LANGUAGE
                                || menuConfig == MenuConfig.V_LANGUAGE_TRANSLATION_KIT
                                || menuConfig == MenuConfig.V_DETECT_MODE_LANGUAGE
                                || menuConfig == MenuConfig.V_WHOLE,
                        enter = enterTransition,
                        exit = exitTransition,
                        content = {
                            SwapLanguagesIconButton(
                                contentColor = contentColor,
                                enabled = !isAnimatingSwapLanguage.value && launchLanguageListView != null,
                                swappable = isSwappable(sourceLanguageCode, targetLanguageCode, translationKitType),
                                isVerticalConfig = isVerticalConfig,
                                onClick = { startLanguageAnimation() },
                            )
                        }
                    )

                    // Target 언어 설정
                    AnimatedVisibility(
                        visible = menuConfig == MenuConfig.WHOLE
                                || menuConfig == MenuConfig.LANGUAGE_TRANSLATION_KIT
                                || menuConfig == MenuConfig.DETECT_MODE_LANGUAGE
                                || menuConfig == MenuConfig.LANGUAGE
                                || menuConfig == MenuConfig.WHOLE_SHORT
                                || menuConfig == MenuConfig.LANGUAGE_SHORT_TRANSLATION_KIT
                                || menuConfig == MenuConfig.DETECT_MODE_LANGUAGE_SHORT
                                || menuConfig == MenuConfig.LANGUAGE_SHORT
                                || menuConfig == MenuConfig.V_LANGUAGE
                                || menuConfig == MenuConfig.V_LANGUAGE_TRANSLATION_KIT
                                || menuConfig == MenuConfig.V_DETECT_MODE_LANGUAGE
                                || menuConfig == MenuConfig.V_WHOLE,
                        enter = enterTransition,
                        exit = exitTransition,
                        content = {
                            Box(modifier = Modifier.width(languageViewWidth)) {
                                TextButton(
                                    onClick = { launchLanguageListView?.let { it(false) } },
                                    enabled = !isAnimatingSwapLanguage.value && launchLanguageListView != null,
                                    modifier = Modifier
                                        .graphicsLayer {
                                            alpha = languageTextAlpha.value
                                            translationX = if (isVerticalConfig) 1f else targetTextOffset.value
                                            translationY = if (isVerticalConfig) targetTextOffset.value else 1f
                                        },
                                    colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
                                ) {
                                    Text(
                                        text = if (isShortConfig) Language(targetLanguageCode).displayShortName else Language(targetLanguageCode).displayName,
                                        color = contentColor,
                                        maxLines = 1,
                                        overflow = if (isShortConfig) TextOverflow.Visible else TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = languageTextSize),
                                        modifier = Modifier.widthIn(max = languageViewWidth)
                                    )
                                }
                            }
                        }
                    )

                    // TranslationKit
                    AnimatedVisibility(
                        visible = menuConfig == MenuConfig.WHOLE
                                || menuConfig == MenuConfig.LANGUAGE_TRANSLATION_KIT
                                || menuConfig == MenuConfig.WHOLE_SHORT
                                || menuConfig == MenuConfig.LANGUAGE_SHORT_TRANSLATION_KIT
                                || menuConfig == MenuConfig.DETECT_MODE_TRANSLATION_KIT
                                || menuConfig == MenuConfig.TRANSLATION_KIT
                                || menuConfig == MenuConfig.V_DETECT_MODE_TRANSLATION_KIT
                                || menuConfig == MenuConfig.V_LANGUAGE_TRANSLATION_KIT
                                || menuConfig == MenuConfig.V_WHOLE,
                        enter = enterTransition,
                        exit = exitTransition,
                        content = {
                            TranslationKitIconButton(
                                translationKitType = translationKitType,
                                sourceLanguage = sourceLanguage,
                                targetLanguage = targetLanguage,
                                enabled = !isAnimatingSwapLanguage.value && updateTranslationKitType != null,
                                onClick = onClickTranslationKitType,
                                updateTranslationKitType = { kitType ->
                                    updateTranslationKitType?.let { it(kitType) }
                                },
                            )
                        }
                    )

                    // 설정 실행
//                    if (settingsButtonVisible) {
//                        IconButton(
//                            modifier = Modifier.size(23.dp),
//                            onClick = { SettingsActivity.start(context) }
//                        ) {
//                            Icon(
//                                imageVector = Icons.Default.MoreVert,
//                                contentDescription = "Settings",
//                                tint = contentColor
//                            )
//                        }
//                    }
                }
            }
        }
    }
}

@Composable
fun TextDetectModeIconButton(
    contentColor: Color,
    textDetectMode: TextDetectMode,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    updateTextDetectMode: (textDetectMode: TextDetectMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentTextDetectMode = remember { mutableStateOf(textDetectMode) }

    LaunchedEffect(textDetectMode) {
        currentTextDetectMode.value = textDetectMode
    }

    // 애니메이션 상태 관리
    val transition = updateTransition(targetState = currentTextDetectMode.value, label = "TextDetectModeTransition")

    // 애니메이션 지속 시간
    val animationDuration = 400

    // 현재 누적된 회전 각도를 기억
    var rotationBase by remember { mutableFloatStateOf(0f) }
    val rotationEven = rotationBase % 2 == 0f

    // 회전 각도 애니메이션
    val rotationZ = animateFloatAsState(
        label = "TextDetectModeIconButtonRotationZ",
        targetValue = rotationBase * 180,
        animationSpec = tween(durationMillis = animationDuration),
    )

    // 페이드 효과
    val alphaWord by transition.animateFloat(
        transitionSpec = { tween(durationMillis = animationDuration) },
        label = "AlphaWord"
    ) { targetState ->
        if (targetState == TextDetectMode.WORD) 1f else 0f
    }

    val alphaSentence by transition.animateFloat(
        transitionSpec = { tween(durationMillis = animationDuration) },
        label = "alphaSentence"
    ) { targetState ->
        if (targetState == TextDetectMode.SENTENCE) 1f else 0f
    }

    val alphaParagraph by transition.animateFloat(
        transitionSpec = { tween(durationMillis = animationDuration) },
        label = "alphaParagraph"
    ) { targetState ->
        if (targetState == TextDetectMode.PARAGRAPH) 1f else 0f
    }

    val alphaSelect by transition.animateFloat(
        transitionSpec = { tween(durationMillis = animationDuration) },
        label = "alphaSelect"
    ) { targetState ->
        if (targetState == TextDetectMode.SELECT) 1f else 0f
    }

    val alphaFixedArea by transition.animateFloat(
        transitionSpec = { tween(durationMillis = animationDuration) },
        label = "alphaArea"
    ) { targetState ->
        if (targetState == TextDetectMode.FIXED_AREA) 1f else 0f
    }

    // 애니메이션 종료 감지
    val animationFinished = remember(rotationBase) {
        derivedStateOf {
            rotationBase > 0 && rotationZ.value >= rotationBase * 180
        }
    }

    LaunchedEffect(animationFinished.value) {
        if (animationFinished.value) {
            updateTextDetectMode(currentTextDetectMode.value)
        }
    }

    // 클릭 시 상태 전환
    IconButton(
        modifier = modifier.size(48.dp),
        onClick = {
            currentTextDetectMode.value = when (currentTextDetectMode.value) {
                TextDetectMode.WORD -> TextDetectMode.SENTENCE
                TextDetectMode.SENTENCE -> TextDetectMode.PARAGRAPH
                TextDetectMode.PARAGRAPH -> TextDetectMode.SELECT
                TextDetectMode.SELECT -> TextDetectMode.FIXED_AREA
                TextDetectMode.FIXED_AREA -> TextDetectMode.WORD
            }
            rotationBase++ // 회전 각도를 누적
            onClick?.let { it() }
        },
        enabled = enabled
    ) {
        // WORD 아이콘
        Icon(
            painter = painterResource(
                id = if ((currentTextDetectMode.value == TextDetectMode.WORD) == rotationEven) {
                    R.drawable.ic_detect_mode_word
                } else {
                    R.drawable.ic_detect_mode_word_odd
                }
            ),
            contentDescription = "Detect mode: WORD",
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer(
                    rotationZ = rotationZ.value,
                    alpha = alphaWord
                ),
            tint = contentColor
        )

        // SENTENCE 아이콘
        Icon(
            painter = painterResource(
                id = if ((currentTextDetectMode.value == TextDetectMode.SENTENCE) == rotationEven) {
                    R.drawable.ic_detect_mode_sentence
                } else {
                    R.drawable.ic_detect_mode_sentence_odd
                }
            ),
            contentDescription = "Detect mode: SENTENCE",
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer(
                    rotationZ = rotationZ.value,
                    alpha = alphaSentence
                ),
            tint = contentColor
        )

        // PARAGRAPH 아이콘
        Icon(
            painter = painterResource(id = R.drawable.ic_detect_mode_paragraph),
            contentDescription = "Detect mode: PARAGRAPH",
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer(
                    rotationZ = rotationZ.value,
                    alpha = alphaParagraph
                ),
            tint = contentColor
        )

        // SELECT 아이콘
        Icon(
            painter = painterResource(id = R.drawable.ic_detect_mode_select),
            contentDescription = "Detect mode: SELECT",
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer(
                    rotationZ = rotationZ.value,
                    alpha = alphaSelect
                ),
            tint = contentColor
        )

        // FIXED_AREA 아이콘
        Icon(
            painter = painterResource(
                id = if ((currentTextDetectMode.value == TextDetectMode.FIXED_AREA) == rotationEven) {
                    R.drawable.ic_detect_mode_fixedarea
                } else {
                    R.drawable.ic_detect_mode_area_odd
                }
            ),
            contentDescription = "Detect mode: FIXED_AREA",
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer(
                    rotationZ = rotationZ.value,
                    alpha = alphaFixedArea
                ),
            tint = contentColor
        )
    }
}

@Composable
fun SwapLanguagesIconButton(
    contentColor: Color,
    enabled: Boolean,
    swappable: Boolean,
    isVerticalConfig: Boolean,
    onClick: () -> Unit,
) {
    val totalFrames = 18 // 총 프레임 수
    val totalAnimationDuration = 320L // 전체 애니메이션 지속 시간 (ms)
    val frameDelay = totalAnimationDuration / totalFrames // 프레임 간 딜레이 계산

    var isForward by remember { mutableStateOf(false) } // 애니메이션 방향 (순방향/역방향)
    var currentFrame by remember { mutableStateOf(0) } // 현재 표시 중인 프레임
    var isAnimating by remember { mutableStateOf(false) } // 애니메이션 진행 상태

    LaunchedEffect(isAnimating) {
        if (isAnimating) {
            val frameSequence = if (isForward) (0 until totalFrames) else (totalFrames - 1 downTo 0)
            for (frame in frameSequence) {
                currentFrame = frame
                delay(frameDelay) // 프레임 간 딜레이 적용
            }
            isAnimating = false // 애니메이션 종료
        }
    }

    IconButton(
        modifier = Modifier.size(48.dp),
        onClick = {
            if (enabled && !isAnimating) {
                isForward = !isForward // 방향 전환
                isAnimating = true // 애니메이션 시작
                onClick()
            }
        },
        enabled = enabled && swappable
    ) {
        if (swappable) {
            val painter: Painter = painterResource(
                id = when (currentFrame) {
                    1 -> if (isVerticalConfig) R.drawable.v_swap_1 else R.drawable.swap_1
                    2 -> if (isVerticalConfig) R.drawable.v_swap_2 else R.drawable.swap_2
                    3 -> if (isVerticalConfig) R.drawable.v_swap_3 else R.drawable.swap_3
                    4 -> if (isVerticalConfig) R.drawable.v_swap_4 else R.drawable.swap_4
                    5 -> if (isVerticalConfig) R.drawable.v_swap_5 else R.drawable.swap_5
                    6 -> if (isVerticalConfig) R.drawable.v_swap_6 else R.drawable.swap_6
                    7 -> if (isVerticalConfig) R.drawable.v_swap_7 else R.drawable.swap_7
                    8 -> if (isVerticalConfig) R.drawable.v_swap_8 else R.drawable.swap_8
                    9 -> if (isVerticalConfig) R.drawable.v_swap_9 else R.drawable.swap_9
                    10 -> if (isVerticalConfig) R.drawable.v_swap_10 else R.drawable.swap_10
                    11 -> if (isVerticalConfig) R.drawable.v_swap_11 else R.drawable.swap_11
                    12 -> if (isVerticalConfig) R.drawable.v_swap_12 else R.drawable.swap_12
                    13 -> if (isVerticalConfig) R.drawable.v_swap_13 else R.drawable.swap_13
                    14 -> if (isVerticalConfig) R.drawable.v_swap_14 else R.drawable.swap_14
                    15 -> if (isVerticalConfig) R.drawable.v_swap_15 else R.drawable.swap_15
                    16 -> if (isVerticalConfig) R.drawable.v_swap_16 else R.drawable.swap_16
                    17 -> if (isVerticalConfig) R.drawable.v_swap_17 else R.drawable.swap_17
                    else -> if (isVerticalConfig) R.drawable.v_swap_0 else R.drawable.swap_0
                }
            )

            Image(
                painter = painter,
                contentDescription = "Swap Animation",
                modifier = Modifier.size(24.dp),
                colorFilter = ColorFilter.tint(contentColor) // 전달받은 contentColor 적용
            )
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowRightAlt,
                contentDescription = "ArrowRightAlt",
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer {
                        rotationZ = if (isVerticalConfig) 90f else 0f  // 시계방향 90도 회전
                    },
                tint = contentColor
            )
        }
    }
}

@Composable
fun TranslationKitIconButton(
    translationKitType: TranslationKitType,
    sourceLanguage: Language? = null,
    targetLanguage: Language? = null,
    enabled: Boolean = true,
    isDarkMode: Boolean? = null,
    colored: Boolean = false,
    onClick: (() -> Unit)? = null,
    updateTranslationKitType: (kitType: TranslationKitType) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDarkMode = isDarkMode ?: isSystemInDarkTheme()
    val context = LocalContext.current
    val currentKitType = remember { mutableStateOf(translationKitType) }

    // 키 기반 엔진(DeepL / OpenAI / Gemini)은 사용자 API 키가 저장된 경우에만 전환 대상에 노출한다
    val deepLActivated by DeepLKit.keyActivatedStateFlow.collectAsStateWithLifecycle()
    val openAIActivated by OpenAiKit.keyActivatedStateFlow.collectAsStateWithLifecycle()
    val geminiActivated by GeminiKit.keyActivatedStateFlow.collectAsStateWithLifecycle()
    val claudeActivated by ClaudeKit.keyActivatedStateFlow.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        DeepLKit.refreshAvailability(context)
        OpenAiKit.refreshAvailability(context)
        GeminiKit.refreshAvailability(context)
        ClaudeKit.refreshAvailability(context)
    }

    LaunchedEffect(translationKitType) {
        currentKitType.value = translationKitType
    }

    // 애니메이션 상태 관리
    val transition = updateTransition(targetState = currentKitType.value, label = "TranslationKitITransition")

    // 애니메이션 지속 시간
    val animationDuration = 400

    // 현재 누적된 회전 각도를 기억
    var rotationBase by remember { mutableFloatStateOf(0f) }
    val rotationEven = rotationBase % 2 == 0f

    // 회전 각도 애니메이션
    val rotationZ = animateFloatAsState(
        label = "TranslationKitIconButtonRotationZ",
        targetValue = rotationBase * 180,
        animationSpec = tween(durationMillis = animationDuration),
    )

    // 엔진별 페이드 효과는 아래 IconButton 안에서 엔진 목록을 순회하며 만든다 (N-provider 일반화)

    // 애니메이션 종료 감지
    val animationFinished = remember(rotationBase) {
        derivedStateOf {
            rotationBase > 0 && rotationZ.value >= rotationBase * 180
        }
    }

    LaunchedEffect(animationFinished.value) {
        if (animationFinished.value) {
            updateTranslationKitType(currentKitType.value)
        }
    }

    var nextKitType by remember { mutableStateOf<TranslationKitType?>(null) }

    LaunchedEffect(sourceLanguage, targetLanguage, translationKitType, deepLActivated, openAIActivated, geminiActivated, claudeActivated) {
        // 키 기반 엔진은 활성화된 경우에만 후보에 포함한다 (GOOGLE 은 항상 사용 가능)
        val activated: (TranslationKitType) -> Boolean = { kit ->
            when (kit) {
                TranslationKitType.GOOGLE -> true
                TranslationKitType.DEEPL -> deepLActivated
                TranslationKitType.OPENAI -> openAIActivated
                TranslationKitType.GEMINI -> geminiActivated
                TranslationKitType.CLAUDE -> claudeActivated
            }
        }
        // 언어쌍을 아직 모르면 활성 엔진 전체, 알면 두 언어가 공통으로 지원하는 엔진으로 후보를 좁힌다
        val candidates: List<TranslationKitType> = if (sourceLanguage == null || targetLanguage == null) {
            TranslationKitType.entries.filter { activated(it) }
        } else {
            sourceLanguage.supportKitTypes.toSet()
                .intersect(targetLanguage.supportKitTypes.toSet())
                .filter { activated(it) }
        }.sortedBy { it.ordinal } // enum 선언 순서: GOOGLE, DEEPL, OPENAI, GEMINI, CLAUDE

        nextKitType = if (candidates.size > 1) {
            val index = candidates.indexOf(translationKitType)
            if (index != -1) candidates[(index + 1) % candidates.size] else candidates.firstOrNull()
        } else {
            null
        }
    }

    fun getImageResourceId(selected: Boolean): Int {
        return if (selected) {
            if (colored) currentKitType.value.ciResourceId else if (isDarkMode) currentKitType.value.ciGrayDarkResourceId else currentKitType.value.ciGrayResourceId
        } else {
            if (colored) currentKitType.value.ciOddResourceId else if (isDarkMode) currentKitType.value.ciGrayOddDarkResourceId else currentKitType.value.ciGrayOddResourceId
        }
    }

    IconButton(
        modifier = modifier.size(48.dp),
        onClick = {
            nextKitType?.let {
                currentKitType.value = it
                rotationBase++ // 회전 각도를 누적
            }
            onClick?.let { it() }
        },
        enabled = enabled && (nextKitType != null && nextKitType != translationKitType)
    ) {
        // 엔진별 아이콘 레이어를 쌓고, 현재 선택된 엔진만 alpha 로 보이게 한다 (N-provider 일반화)
        TranslationKitType.entries.forEach { kit ->
            val alpha by transition.animateFloat(
                transitionSpec = { tween(durationMillis = animationDuration) },
                label = "Alpha_${kit.name}"
            ) { targetState ->
                if (targetState == kit) 1f else 0f
            }
            Image(
                painter = painterResource(
                    id = getImageResourceId((currentKitType.value == kit) == rotationEven)
                ),
                contentDescription = "KitType: ${currentKitType.value}",
                modifier = Modifier
                    // 크기는 표시 중인 아이콘(currentKitType)에 맞춘다. 레이어의 kit 로 잡으면
                    // 전환 중 사라지는 레이어가 잠깐 큰 크기로 그려져 "컸다가 작아지는" 현상이 생긴다.
                    // OpenAI 로고는 캔버스를 꽉 채워 같은 dp 에서 더 커 보이므로 살짝 줄인다.
                    .size(if (currentKitType.value == TranslationKitType.OPENAI) 23.dp else 27.dp)
                    .graphicsLayer(
                        rotationZ = rotationZ.value,
                        alpha = alpha
                    ),
            )
        }
    }
}





















