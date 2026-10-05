package com.galaxy.airviewdictionary.ui.screen.overlay.fixedarea


import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.SignalWifiStatusbarConnectedNoInternet4
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galaxy.airviewdictionary.R
import com.galaxy.airviewdictionary.core.OverlayService
import com.galaxy.airviewdictionary.data.local.capture.CapturePreventedException
import com.galaxy.airviewdictionary.data.local.capture.ImageCrop
import com.galaxy.airviewdictionary.data.local.capture.CaptureResponse
import com.galaxy.airviewdictionary.data.local.capture.NoMediaProjectionTokenException
import com.galaxy.airviewdictionary.data.local.preference.PreferenceRepository
import com.galaxy.airviewdictionary.data.local.screen.ScreenInfo
import com.galaxy.airviewdictionary.data.local.screen.ScreenInfoHolder
import com.galaxy.airviewdictionary.data.local.ads.AdGateState
import com.galaxy.airviewdictionary.data.local.vision.TextDetectMode
import com.galaxy.airviewdictionary.data.local.vision.model.Transaction
import com.galaxy.airviewdictionary.data.local.vision.model.VisionResponse
import com.galaxy.airviewdictionary.data.remote.translation.ImageTranslation
import com.galaxy.airviewdictionary.data.remote.translation.NoTextInImageException
import com.galaxy.airviewdictionary.data.remote.translation.TranslationErrorMessages
import com.galaxy.airviewdictionary.data.remote.translation.UnsupportedScriptException
import com.galaxy.airviewdictionary.data.remote.translation.TranslationKitType
import com.galaxy.airviewdictionary.data.remote.translation.TranslationResponse
import com.galaxy.airviewdictionary.extensions.isNetworkAvailable
import com.galaxy.airviewdictionary.extensions.setFromPoints
import com.galaxy.airviewdictionary.extensions.vibrate
import com.galaxy.airviewdictionary.ui.screen.main.SettingsActivity
import com.galaxy.airviewdictionary.ui.screen.overlay.Event
import com.galaxy.airviewdictionary.ui.screen.overlay.OverlayView
import com.galaxy.airviewdictionary.ui.screen.overlay.dialog.DialogView
import com.galaxy.airviewdictionary.ui.screen.overlay.selection.createOverlaidBitmap
import com.galaxy.airviewdictionary.ui.screen.overlay.targethandle.TargetHandleView
import com.galaxy.airviewdictionary.ui.screen.overlay.targethandle.TargetHandleViewModel
import com.galaxy.airviewdictionary.ui.screen.overlay.targethandle.TranslationSourceLanguage
import com.galaxy.airviewdictionary.ui.screen.permissions.ScreenCapturePermissionRequesterActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Singleton


/**
 * 고정 선택 영역 뷰
 */
@Singleton
open class FixedAreaView : OverlayView() {

    enum class State {
        Idle, // 화면 진입 상태
        Handling, // TargetHandleView 를 조작하고 영역을 지정하기 까지의 상태
        Translating, // 영역을 지정한 뒤 번역시작 버튼을 누른 상태
        TranslatingHandling, // Translating 상태에서 영역을 터치하여 잠시 메뉴를 보여지게 하는 상태
    }

    companion object {
        val INSTANCE: FixedAreaView by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { FixedAreaView() }

        val fixedAreaViewStateFlow = MutableStateFlow(State.Idle)

        val translationFlow = MutableStateFlow("")

        private const val SELECTION_AREA_RATIO_LIMIT: Int = 50

        private const val BACKGROUND_ALPHA: Float = 0.28f

        private const val BACKGROUND_FADE_OUT_ALPHA: Float = 0.0f

        private const val BACKGROUND_FADE_OUT_DURATION: Int = 8000
    }

    private lateinit var targetHandleViewModel: TargetHandleViewModel

    override lateinit var layoutParams: WindowManager.LayoutParams

    private lateinit var layoutTopCenter: Point

    private var fixedAreaViewStateFlowJob: Job? = null

    private var translateJob: Job? = null

    private var fixedAreaViewBackgroundAlphaJob: Job? = null

    override val composable: @Composable () -> Unit = @Composable {
        val localView = LocalView.current
        val context = LocalContext.current
        val resources = context.resources
        val lifecycleOwner = LocalLifecycleOwner.current
        val composeScope = rememberCoroutineScope()

        val isDarkMode = isSystemInDarkTheme()
        val fixedAreaViewColor = colorResource(if (isDarkMode) R.color.fixed_area_color else R.color.fixed_area_color_dark)
        val fixedAreaViewBackgroundAlpha = remember { Animatable(BACKGROUND_ALPHA) }

        val stoppedDistancePx = with(resources.displayMetrics) {
            resources.getDimensionPixelSize(R.dimen.targethandle_view_pointer_stopped_distance)
        }
        val selectionMinWidthPx = with(resources.displayMetrics) {
            resources.getDimensionPixelSize(R.dimen.area_selection_min_width)
        }
        val selectionMinHeightPx = with(resources.displayMetrics) {
            resources.getDimensionPixelSize(R.dimen.area_selection_min_height)
        }

        val textDetectMode by targetHandleViewModel.preferenceRepository.textDetectModeFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = TextDetectMode.FIXED_AREA
        )

        if (textDetectMode != TextDetectMode.FIXED_AREA) {
            clear()
        }

        val dragHandleHaptic by targetHandleViewModel.preferenceRepository.dragHandleHapticFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = false
        )

        fun haptic() {
            if (dragHandleHaptic) {
                context.vibrate()
            }
        }

        val selectionStarted = remember { mutableStateOf(true) }
        val selectedCompleted = remember { mutableStateOf(false) }
        val selectionAreaRatio = remember { mutableIntStateOf(0) }
        val selectedArea = remember { mutableStateOf(Rect()) }

        val pointerStoppedPosition: Point? by targetHandleViewModel.pointerStoppedPositionFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = null
        )

        val fixedAreaViewState: State by fixedAreaViewStateFlow.collectAsStateWithLifecycle()

        // SettingsActivity live 상태 flow
        val settingsActivityLiveState by SettingsActivity.liveStateFlow.collectAsStateWithLifecycle()

        // 영역선택 시작
        fun startSelection() {
            FixedAreaTranslationView.INSTANCE.clear()
            fixedAreaViewStateFlowJob?.cancel()
            fixedAreaViewStateFlow.value = State.Idle
            translateJob?.cancel()
            fixedAreaViewBackgroundAlphaJob?.cancel()
            composeScope.launch {
                fixedAreaViewBackgroundAlpha.snapTo(BACKGROUND_ALPHA)
            }
            selectionStarted.value = true
            targetHandleViewModel.areaSelectingStateFlow.value = true
            translationFlow.value = ""
            haptic()
        }

        // 영역선택 리셋
        fun resetSelection(currentPosition: Point) {
            targetHandleViewModel.cancelCapture()

            selectedCompleted.value = false // 영역선택 취소
            targetHandleViewModel.areaSelectingStateFlow.value = false
            translationFlow.value = ""
            selectionStarted.value = false // 영역선택 시작 취소

            layoutParams.x = currentPosition.x
            layoutParams.y = currentPosition.y
            layoutParams.width = layoutParams.x + 1
            layoutParams.height = currentPosition.y + 1
            updateLayout(context)

            view?.post {
                layoutTopCenter = currentPosition // 현재 포인터 지점으로 layoutTopLeft 를 갱신
            }
        }

        // 영역선택 완료
        fun completeSelection(_selectedArea: Rect) {
            selectedCompleted.value = true
            selectedArea.value = _selectedArea
            targetHandleViewModel.areaSelectingStateFlow.value = false
            if (selectionAreaRatio.intValue in 5..SELECTION_AREA_RATIO_LIMIT) {
                haptic()
            } else if (selectionAreaRatio.intValue > SELECTION_AREA_RATIO_LIMIT) {
                clear()
            }
        }

        LaunchedEffect(pointerStoppedPosition) {

            if (pointerStoppedPosition == null) {
                return@LaunchedEffect
            }

            // 영역선택 시작되지 않은 경우
            if (!selectionStarted.value) {
                startSelection()
                return@LaunchedEffect
            }

            // Selected Area
            val r: Rect = Rect().apply {
                setFromPoints(layoutTopCenter, pointerStoppedPosition!!)
            }
            // Selected Area 가 최소 너비/높이를 만족하는지 확인
            if ((r.width() > selectionMinWidthPx && r.height() > selectionMinHeightPx)
                || (r.width() > selectionMinHeightPx && r.height() > selectionMinWidthPx)
            ) {
                // 영역선택 완료
                completeSelection(r)
            }
        }

        val pointerPosition by targetHandleViewModel.pointerPositionFlow.collectAsStateWithLifecycle()
        val _pointerPosition = remember { mutableStateOf<Point?>(null) }

        pointerPosition?.let { currentPosition ->
            // 포인터를 layoutTopLeft 위나 왼쪽으로 이동한 경우
            if (currentPosition.y + stoppedDistancePx / 2 < layoutTopCenter.y
                || currentPosition.x + stoppedDistancePx / 2 < layoutTopCenter.x
            ) {
                resetSelection(currentPosition) // 선택작업 리셋
            }
            // 포인터를 layoutTopLeft 아래 & 오른쪽으로 이동한 경우
            else {
                // 영역선택이 시작된 경우
                if (selectionStarted.value) {
                    if (_pointerPosition.value != null) {
                        // 이전 포인트 보다 layoutTopLeft 위나 왼쪽으로 포인터를 이동하는 경우
                        if (currentPosition.y + stoppedDistancePx / 2 < _pointerPosition.value!!.y
                            || currentPosition.x + stoppedDistancePx / 2 < _pointerPosition.value!!.x
                        ) {
                            resetSelection(currentPosition) // 선택작업 리셋
                        }
                    }
                }
                // 영역선택이 시작되지 않은 경우
                else {
                    layoutTopCenter = currentPosition // 현재 포인터 지점으로 layoutTopLeft 를 갱신
                }
            }

            _pointerPosition.value = currentPosition

            // layoutTopLeft 부터 현재 포인터 위치 까지의 영역 사각형
            val r: Rect = Rect().apply {
                setFromPoints(layoutTopCenter, currentPosition)
            }

            layoutParams.x = r.left
            layoutParams.y = r.top
            layoutParams.width = r.width()
            layoutParams.height = r.height()

            val area = r.width() * r.height()
            val screenInfo: ScreenInfo = ScreenInfoHolder.get()
            val percentage = (area.toDouble() / screenInfo.safeArea.toDouble()) * 100
            Timber.tag(TAG).d("screenInfo $screenInfo    area $area  percentage $percentage")
            selectionAreaRatio.intValue = percentage.toInt()
            updateLayout(context)
        }

        fun fixedAreaVisible() {
            if (fixedAreaViewStateFlow.value == State.Translating || fixedAreaViewStateFlow.value == State.TranslatingHandling) {
                fixedAreaViewStateFlowJob?.cancel()
                // Box 를 클릭 하면 MenuBarView 와 TargetHandleView 가 보여 지도록 함.
                fixedAreaViewStateFlow.value = State.TranslatingHandling
                // 3초 후 다시 State.Translating 으로
                fixedAreaViewStateFlowJob = launchInOverlayViewCoroutineScope {
                    delay(BACKGROUND_FADE_OUT_DURATION.toLong())
                    fixedAreaViewStateFlow.value = State.Translating
                }

                fixedAreaViewBackgroundAlphaJob?.cancel()
                fixedAreaViewBackgroundAlphaJob = composeScope.launch {
                    fixedAreaViewBackgroundAlpha.snapTo(BACKGROUND_ALPHA)
                    fixedAreaViewBackgroundAlpha.animateTo(
                        targetValue = BACKGROUND_FADE_OUT_ALPHA,
                        animationSpec = tween(durationMillis = BACKGROUND_FADE_OUT_DURATION)
                    )
                }
            }
        }

        LaunchedEffect(settingsActivityLiveState) {
            if (settingsActivityLiveState) {
                fixedAreaVisible()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(fixedAreaViewColor.copy(alpha = fixedAreaViewBackgroundAlpha.value))
                .clickable(
                    indication = null, // 클릭 효과 제거
                    interactionSource = remember { MutableInteractionSource() } // 상태 추적 제거
                ) {
                    fixedAreaVisible()
                },
            contentAlignment = Alignment.Center,
        ) {
            if (fixedAreaViewState == State.Idle || fixedAreaViewState == State.Handling) {
                if (selectionAreaRatio.intValue <= SELECTION_AREA_RATIO_LIMIT) { // 화면 면적의 50% 이하
                    IconButton(
                        onClick = {
                            if (context.isNetworkAvailable()) {
                                fun start() {
                                    startFixedAreaTranslate(context, selectedArea.value)
                                    launchInOverlayViewCoroutineScope {
                                        TargetHandleView.INSTANCE.cast(context, true)
                                        FixedAreaTranslationView.INSTANCE.cast(context, selectedArea.value)
                                    }

                                    fixedAreaViewBackgroundAlphaJob?.cancel()
                                    fixedAreaViewBackgroundAlphaJob = composeScope.launch {
                                        fixedAreaViewBackgroundAlpha.animateTo(
                                            targetValue = BACKGROUND_FADE_OUT_ALPHA,
                                            animationSpec = tween(durationMillis = BACKGROUND_FADE_OUT_DURATION)
                                        )
                                    }
                                }

                                launchInOverlayViewCoroutineScope {
                                    DialogView.INSTANCE.cast(
                                        applicationContext = context,
                                        icon = Icons.Default.BatteryAlert,
                                        dialogTitle = context.getString(R.string.message_translate_fixedarea_warn),
                                        dialogText = context.getString(R.string.message_translate_fixedarea_warn_detail),
                                        onConfirm = {
                                            launchInOverlayViewCoroutineScope {
                                                start()
                                            }
                                        }
                                    )
                                }
                            } else {
                                launchInOverlayViewCoroutineScope {
                                    DialogView.INSTANCE.cast(
                                        applicationContext = context,
                                        icon = Icons.Default.SignalWifiStatusbarConnectedNoInternet4,
                                        dialogTitle = context.getString(R.string.message_network_unavailable),
                                        dialogText = context.getString(R.string.message_network_unavailable_detail),
                                        onConfirm = {
                                            clear()
                                        }
                                    )
                                }
                            }
                        },
                        modifier = Modifier
                            .size(96.dp)
                            .padding(1.dp) // 버튼 크기
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PlayCircleOutline,
                            contentDescription = "start translation",
                            tint = fixedAreaViewColor,
                            modifier = Modifier
                                .size(52.dp)
                                .padding(2.dp) // 아이콘 크기
                        )
                    }
                }
            }
        }
    }

    override fun onServiceConnected(overlayService: OverlayService) {
        targetHandleViewModel = overlayService.getTargetHandleViewModel()
        super.onServiceConnected(overlayService)
    }

    open suspend fun cast(
        applicationContext: Context,
        startPosition: Point,
    ) {
        this.layoutTopCenter = startPosition
        layoutParams = WindowManager.LayoutParams(
            1,
            1,
            startPosition.x,
            startPosition.y,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        super.cast(applicationContext)
        // 바인딩 실패로 cast 가 중단되면 onServiceConnected 가 불리지 않아
        // targetHandleViewModel 이 미초기화 상태다(2.6.5 회귀).
        if (!isRunning.get()) return
        targetHandleViewModel.areaSelectingStateFlow.value = true
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

    override fun clear() {
        FixedAreaTranslationView.INSTANCE.clear()
        fixedAreaViewBackgroundAlphaJob?.cancel()
        fixedAreaViewBackgroundAlphaJob = null
        fixedAreaViewStateFlowJob?.cancel()
        fixedAreaViewStateFlowJob = null
        fixedAreaViewStateFlow.value = State.Idle
        translateJob?.cancel()
        translateJob = null
        detectedString = null
        targetHandleViewModel.areaSelectingStateFlow.value = false
        super.clear()
    }

    private fun startFixedAreaTranslate(context: Context, selectedArea: Rect) {
        translateJob?.cancel()
        // 새로 시작하면 첫 인식 결과는 언제나 처리한다 — 직전 글과 같아도 번역하고(해제 후 같은 글 위에 다시 잡으면
        // 결과창이 빈 채로 남던 문제), 빈 글이면 이전 세션의 자막을 지운다.
        detectedString = null
        fixedAreaViewStateFlowJob?.cancel()
        fixedAreaViewStateFlow.value = State.Translating
        translateJob = launchInOverlayViewCoroutineScope {
            while (fixedAreaViewStateFlow.value == State.Translating || fixedAreaViewStateFlow.value == State.TranslatingHandling) {
                // 0.1초 간격
                delay(100)
                // 광고 사용권이 없으면(스킵/미시청) 광고 게이트를 열고 종료 — 포인터 모드와 동일한 규칙
                if (!AdGateState.isUsable()) {
                    targetHandleViewModel.showAdGate()
                    clear()
                }
                // 화면 캡처 + OCR 요청
                else {
                    requestVision(context, selectedArea)
                }
            }
        }
    }

    /** 직전에 번역한 인식 글. null 이면 이번 세션에서 아직 아무것도 처리하지 않았다. */
    private var detectedString: String? = null

    private suspend fun requestVision(context: Context, selectedArea: Rect) {
        // 캡처 이미지
        val captureResponse: CaptureResponse = targetHandleViewModel.captureRepository.request()
        Timber.tag(TAG).d("captureResponse $captureResponse")
        if (captureResponse !is CaptureResponse.Success) {
            Timber.tag(TAG).d("CaptureResponse.Error ${(captureResponse as CaptureResponse.Error).t}")
            if (captureResponse.t is NoMediaProjectionTokenException) {
                // 화면 캡처 권한을 요청
                val intent = Intent(context, ScreenCapturePermissionRequesterActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                clear()
            } else if (captureResponse.t is CapturePreventedException) {
                // 캡처 방지 알림
                // captureResponse.t.checkerBitmap 처리
            }
            return
        }

        // 영역선택 이미지
        val selectedAreaBitmap = createOverlaidBitmap(captureResponse.bitmap, selectedArea)

        // Test 캡처 이미지 확인

        val sourceLanguageCode: String = targetHandleViewModel.preferenceRepository.sourceLanguageCodeFlow.first()
        val translationKitType: TranslationKitType = targetHandleViewModel.preferenceRepository.translationKitTypeFlow.first()
        if (targetHandleViewModel.translationRepository.usesImageTranslation(translationKitType, sourceLanguageCode)) {
            requestImageTranslate(context, captureResponse.bitmap, selectedAreaBitmap, selectedArea, sourceLanguageCode)
            return
        }
        val visionResponse: VisionResponse = targetHandleViewModel.visionRepository.request(
            bitmap = selectedAreaBitmap,
            // 읽을 엔진이 없는 언어인데 이미지 번역을 못 하면(원격 스위치를 껐다) auto 로 읽는다
            sourceLanguageCode = if (ImageTranslation.isImageOnlyLanguage(sourceLanguageCode)) "auto" else sourceLanguageCode,
            // 영역 안의 글 전체가 필요하다 — 검출만 하고 멈추지 않는다.
            readAll = true,
            // 쉬지 않고 캡처한다 — 관문이 개입해도 넣은 ML Kit 인식을 기다려 작업이 쌓이지 않게 한다
            waitOnGate = true,
        )

        if (visionResponse !is VisionResponse.Success) {
            return
        }

        // auto 의 지원되지 않는 문자권 관문(성능 P7)이 개입했다 — 읽지 못했으니 영역 이미지의 지문으로 바뀜을 보고, Claude 이미지 번역을
        // 쓸 수 있으면 영역을 보내고 아니면 "읽을 수 없는 문자" 안내를 띄운다
        visionResponse.result.unsupportedScript?.let { verdict ->
            val fingerprint = try {
                targetHandleViewModel.visionRepository.imageFingerprint(selectedAreaBitmap).replace("\n", " ")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "image fingerprint 실패")
                return
            }
            val previous = detectedString
            if (previous != null && (previous == fingerprint || ImageTranslation.sameFingerprint(previous, fingerprint))) return
            detectedString = fingerprint
            if (targetHandleViewModel.translationRepository.claudeImageReady(translationKitType)) {
                translateAreaImage(context, captureResponse.bitmap, selectedArea, sourceLanguageCode)
            } else {
                translationFlow.value = "⚠ " + TranslationErrorMessages.resolve(context, UnsupportedScriptException(verdict.language))
            }
            return
        }

        val visionResponseString = visionResponse.result.ocr.text.replace("\n", " ")
        if (detectedString == visionResponseString) {
            return
        }

        detectedString = visionResponseString
        Timber.tag(TAG).d("[detectedString] $detectedString")
        requestTranslate(context, visionResponse.result, visionResponseString)
    }

    /**
     * AI 이미지 번역(§25) — 글이 바뀌었을 때만 영역 이미지를 보낸다(주기마다 보내면 비용이 폭증한다). 바뀜은 읽을 수 있는 화면(auto)이면
     * 지금처럼 OCR 글로, 읽을 엔진이 없는 언어면 PP-OCRv5 지문으로 본다 — 지문은 뜻 없는 글이라 조금 달라도 같은 글로 본다.
     */
    private suspend fun requestImageTranslate(
        context: Context,
        screen: Bitmap,
        selectedAreaBitmap: Bitmap,
        selectedArea: Rect,
        sourceLanguageCode: String,
    ) {
        val imageOnlyLanguage = ImageTranslation.isImageOnlyLanguage(sourceLanguageCode)
        val fingerprint = try {
            if (imageOnlyLanguage) {
                targetHandleViewModel.visionRepository.imageFingerprint(selectedAreaBitmap)
            } else {
                val visionResponse = targetHandleViewModel.visionRepository.request(selectedAreaBitmap, sourceLanguageCode, readAll = true)
                if (visionResponse !is VisionResponse.Success) return
                visionResponse.result.ocr.text
            }.replace("\n", " ")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "image fingerprint 실패")
            return
        }
        val previous = detectedString
        if (previous != null && (previous == fingerprint || imageOnlyLanguage && ImageTranslation.sameFingerprint(previous, fingerprint))) return
        detectedString = fingerprint
        if (fingerprint.isBlank()) {
            translationFlow.value = ""
            return
        }
        translateAreaImage(context, screen, selectedArea, sourceLanguageCode)
    }

    /** 영역 이미지를 Claude 에 보내 번역한다(§25). 바뀜은 부르는 쪽이 이미 보았다. */
    private suspend fun translateAreaImage(context: Context, screen: Bitmap, selectedArea: Rect, sourceLanguageCode: String) {
        val sourceLanguagePref: String = targetHandleViewModel.preferenceRepository.sourceLanguageCodeFlow.first()
        val targetLanguageCode: String = targetHandleViewModel.preferenceRepository.targetLanguageCodeFlow.first()
        val crop = withContext(Dispatchers.Default) { ImageCrop.area(screen, selectedArea) } ?: return
        val response = try {
            targetHandleViewModel.translationRepository.requestImage(sourceLanguageCode, targetLanguageCode, crop, TextDetectMode.FIXED_AREA)
        } finally {
            crop.recycle()
        }
        when (response) {
            is TranslationResponse.Success -> {
                val transaction = com.galaxy.airviewdictionary.data.remote.translation.Transaction(
                    requestedSourceLanguageCode = sourceLanguagePref,
                    resolvedSourceLanguageCode = TranslationSourceLanguage.resolved(response.result.resolvedSourceLanguageCode, sourceLanguageCode),
                    targetLanguageCode = response.result.targetLanguageCode,
                    sourceText = response.result.sourceText,
                    translationKitType = response.result.translationKitType,
                    resultText = response.result.resultText,
                    modelName = response.result.modelName,
                )
                translationFlow.value = response.result.resultText ?: ""
                transaction.resolvedSourceLanguageCode?.let {
                    targetHandleViewModel.preferenceRepository.update(PreferenceRepository.LAST_USED_SOURCE_LANGUAGE_CODE, it)
                }
                targetHandleViewModel.increaseTrialCount()
                targetHandleViewModel.analyticsRepository.translationReport(
                    transaction = transaction,
                    textDetectMode = TextDetectMode.FIXED_AREA,
                )
            }

            is TranslationResponse.Error -> {
                Timber.tag(TAG).d("Response Error ${response.t}")
                // 영역에 글이 없다는 답이면 결과창을 비운다
                translationFlow.value =
                    if (response.t is NoTextInImageException) "" else "⚠ " + TranslationErrorMessages.resolve(context, response.t)
            }
        }
    }

    private suspend fun requestTranslate(context: Context, visionResult: Transaction, sourceText: String) {
        val translationKitType: TranslationKitType = targetHandleViewModel.preferenceRepository.translationKitTypeFlow.first()
        // 영역 글 전체로 식별한 언어(auto 면 ML Kit 식별값, 아니면 고른 언어). 확정 원문 언어의 대체값이다.
        val sourceLanguageCode: String = visionResult.detectedLanguageCode
        val sourceLanguagePref: String = targetHandleViewModel.preferenceRepository.sourceLanguageCodeFlow.first()
        val targetLanguageCode: String = targetHandleViewModel.preferenceRepository.targetLanguageCodeFlow.first()
        // 엔진에 넘기는 값 — 포인터 모드와 같은 규칙이다(auto 면 AI 엔진이 판정, "und" 는 auto 로. §23)
        val kitSourceLanguageCode = TranslationSourceLanguage.forKit(translationKitType, sourceLanguagePref, sourceLanguageCode)

        if (sourceText.trim().isEmpty()) {
            translationFlow.value = ""
        } else {
            // TTS 목소리 목록 등이 참조하는 "마지막 번역의 실제 소스 언어"를 기록
            sourceLanguageCode.takeIf { it.isNotBlank() && it != "auto" && it != "und" }?.let {
                targetHandleViewModel.preferenceRepository.update(PreferenceRepository.LAST_USED_SOURCE_LANGUAGE_CODE, it)
            }
            targetHandleViewModel.translationRepository.request(
                translationKitType = translationKitType,
                sourceLanguageCode = kitSourceLanguageCode,
                targetLanguageCode = targetLanguageCode,
                sourceText = sourceText,
            ).also {
                when (it) {
                    is TranslationResponse.Success -> {
                        val transaction = com.galaxy.airviewdictionary.data.remote.translation.Transaction(
                            // 포인터 모드와 같이 설정값("auto" 포함)을 기록한다.
                            requestedSourceLanguageCode = sourceLanguagePref,
                            // 고정영역은 OCR 텍스트를 번역한다.
                            // 킷이 판정하지 못했으면 화면 전체 OCR 이 판정한 언어가 곧 원문 언어다.
                            // 정규화(소문자, "auto"/"und" 는 판정 못 한 것)는 포인터 모드의 확정과 같은 함수로 한다.
                            resolvedSourceLanguageCode = TranslationSourceLanguage.resolved(it.result.resolvedSourceLanguageCode, sourceLanguageCode),
                            targetLanguageCode = it.result.targetLanguageCode,
                            sourceText = sourceText,
                            translationKitType = it.result.translationKitType,
                            resultText = it.result.resultText,
                            modelName = it.result.modelName,
                        )
                        Timber.tag(TAG).d("===== $translationKitType ${it.result.resultText}")
                        translationFlow.value = it.result.resultText ?: ""
                        targetHandleViewModel.increaseTrialCount()
                        // 인식 텍스트가 바뀌어 새로 번역했을 때만 온다(폴링마다가 아님).
                        targetHandleViewModel.analyticsRepository.translationReport(
                            transaction = transaction,
                            textDetectMode = TextDetectMode.FIXED_AREA,
                        )
                    }

                    is TranslationResponse.Error -> {
                        Timber.tag(TAG).d("Response Error ${it.t}")
                        // 실패 원인을 고정 영역 결과창에 표시한다 (무소음 실패 방지)
                        translationFlow.value =
                            "⚠ " + TranslationErrorMessages.resolve(context, it.t)
                    }
                }
            }
        }
    }
}

















