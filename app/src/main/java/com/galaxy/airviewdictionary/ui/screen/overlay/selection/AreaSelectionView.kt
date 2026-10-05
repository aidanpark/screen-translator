package com.galaxy.airviewdictionary.ui.screen.overlay.selection


import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.graphics.createBitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galaxy.airviewdictionary.R
import com.galaxy.airviewdictionary.core.OverlayService
import com.galaxy.airviewdictionary.data.local.capture.CapturePreventedException
import com.galaxy.airviewdictionary.data.local.capture.NoMediaProjectionTokenException
import com.galaxy.airviewdictionary.data.local.vision.TextDetectMode
import com.galaxy.airviewdictionary.data.local.vision.WritingDirection
import com.galaxy.airviewdictionary.extensions.setFromPoints
import com.galaxy.airviewdictionary.extensions.vibrate
import com.galaxy.airviewdictionary.data.remote.translation.Language
import com.galaxy.airviewdictionary.data.local.capture.CaptureResponse
import com.galaxy.airviewdictionary.data.local.vision.model.ImageTargets
import com.galaxy.airviewdictionary.data.local.vision.model.Transaction
import com.galaxy.airviewdictionary.data.local.vision.model.VisionResponse
import com.galaxy.airviewdictionary.data.local.vision.ocr.OcrText
import com.galaxy.airviewdictionary.data.remote.translation.ImageTranslation
import com.galaxy.airviewdictionary.data.remote.translation.TranslationKitType
import com.galaxy.airviewdictionary.ui.screen.overlay.OverlayView
import com.galaxy.airviewdictionary.ui.screen.overlay.targethandle.TargetHandleViewModel
import com.galaxy.airviewdictionary.ui.screen.overlay.targethandle.TranslateStatus
import com.galaxy.airviewdictionary.ui.screen.overlay.translation.TranslationErrorView
import com.galaxy.airviewdictionary.ui.screen.overlay.translation.TranslationView
import com.galaxy.airviewdictionary.ui.screen.permissions.ScreenCapturePermissionRequesterActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Singleton
import kotlin.math.max


/**
 * 선택 영역 뷰
 */
@Singleton
open class AreaSelectionView : OverlayView() {

    companion object {
        val INSTANCE: AreaSelectionView by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AreaSelectionView() }
    }

    private lateinit var targetHandleViewModel: TargetHandleViewModel

    override lateinit var layoutParams: WindowManager.LayoutParams

    private lateinit var layoutTopCenter: Point

    private var translateJob: Job? = null

    override val composable: @Composable () -> Unit = @Composable {
        val localView = LocalView.current
        val context = LocalContext.current
        val resources = context.resources
        val lifecycleOwner = LocalLifecycleOwner.current

        val isDarkMode = isSystemInDarkTheme()
        val visionTextColor = colorResource(if (isDarkMode) R.color.vision_text_color_dark else R.color.vision_text_color)

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
            initialValue = TextDetectMode.SELECT
        )

        if (textDetectMode != TextDetectMode.SELECT) {
            clear()
        }

        val isWritingRtl = remember { mutableStateOf(false) }
        val writingDirection = remember { mutableStateOf(WritingDirection.LTR) }
        val sourceLanguageCode by targetHandleViewModel.preferenceRepository.sourceLanguageCodeFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = "auto"
        )
        LaunchedEffect(sourceLanguageCode) {
            writingDirection.value = Language.writingDirection(sourceLanguageCode, false)
            isWritingRtl.value = writingDirection.value == WritingDirection.RTL
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

        val pointerStoppedPosition: Point? by targetHandleViewModel.pointerStoppedPositionFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = null
        )

        val translationViewLiveState: Boolean by TranslationView.liveStateFlow.collectAsStateWithLifecycle()

        // 영역선택 시작
        fun startSelection() {
            translateJob?.cancel()
            selectionStarted.value = true
            targetHandleViewModel.areaSelectingStateFlow.value = true
            haptic()
        }

        // 영역선택 리셋
        fun resetSelection(currentPosition: Point) {
            translateJob?.cancel()
            targetHandleViewModel.cancelCapture()

            selectedCompleted.value = false // 영역선택 취소
            targetHandleViewModel.areaSelectingStateFlow.value = false
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
        fun completeSelection(selectedArea: Rect) {
            selectedCompleted.value = true
            targetHandleViewModel.areaSelectingStateFlow.value = false
            haptic()
            requestTranslate(context, selectedArea)
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
            if (isWritingRtl.value) {
                // 포인터를 layoutTopLeft 위나 오른쪽으로 이동한 경우
                if (currentPosition.y + stoppedDistancePx / 2 < layoutTopCenter.y
                    || currentPosition.x - stoppedDistancePx / 2 > layoutTopCenter.x
                ) {
                    resetSelection(currentPosition) // 선택작업 리셋
                }
                // 포인터를 layoutTopLeft 아래 & 왼쪽으로 이동한 경우
                else {
                    // 영역선택이 시작된 경우
                    if (selectionStarted.value) {
                        if (_pointerPosition.value != null) {
                            // 이전 포인트 보다 layoutTopLeft 위나 오른쪽으로 포인터를 이동하는 경우
                            if (currentPosition.y + stoppedDistancePx / 2 < _pointerPosition.value!!.y
                                || currentPosition.x - stoppedDistancePx / 2 > _pointerPosition.value!!.x
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
            } else {
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
            updateLayout(context)
        } ?: clear()

        if (selectionStarted.value) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(visionTextColor.copy(alpha = if (isDarkMode) 0.18f else 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                if (selectedCompleted.value && !translationViewLiveState) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(dimensionResource(id = R.dimen.target_pointer_progress_dimen)),
                        color = Color(0xFF48baef),
                        strokeWidth = 2.2.dp
                    )
                }
            }
        }

//        Box(
//            modifier = Modifier
//                .fillMaxSize()
//                .background( Color(0x44ffff11)),
//        )
    }

    override fun onServiceConnected(overlayService: OverlayService) {
        targetHandleViewModel = overlayService.getTargetHandleViewModel()
        super.onServiceConnected(overlayService)
    }

    open suspend fun cast(
        applicationContext: Context,
        startPosition: Point
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

    override fun clear() {
        translateJob?.cancel()
        targetHandleViewModel.areaSelectingStateFlow.value = false
        targetHandleViewModel.translateStatusFlow.value = TranslateStatus.Idle
        super.clear()
    }

    private fun requestTranslate(context: Context, selectedArea: Rect) {
        translateJob?.cancel()
        translateJob = launchInOverlayViewCoroutineScope {
            // 남아 있는 실패 안내 창이 캡처에 찍히지 않도록 먼저 해제
            if (TranslationErrorView.INSTANCE.isAttachedToWindow()) {
                TranslationErrorView.INSTANCE.clear()
                // removeView 는 다음 프레임에 반영되므로 캡처 전 잠시 대기 (requestCapture 와 동일)
                delay(50)
            }
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
                return@launchInOverlayViewCoroutineScope
            }

            val sourceLanguageCode: String = targetHandleViewModel.preferenceRepository.sourceLanguageCodeFlow.first()
            val translationKitType: TranslationKitType = targetHandleViewModel.preferenceRepository.translationKitTypeFlow.first()
            if (targetHandleViewModel.translationRepository.usesImageTranslation(translationKitType, sourceLanguageCode)) {
                // AI 이미지 번역 — 읽지 않고 영역을 그대로 보낸다(§25)
                targetHandleViewModel.visionResultFlow.value = Transaction(
                    captureResponse.bitmap, OcrText("", emptyList()), sourceLanguageCode, emptyList(), image = ImageTargets.Area(Rect(selectedArea)),
                )
                return@launchInOverlayViewCoroutineScope
            }

            // 영역선택 이미지
            val selectedAreaBitmap = createOverlaidBitmap(captureResponse.bitmap, selectedArea)

            // Test 캡처 이미지 확인

            val visionResponse: VisionResponse = targetHandleViewModel.visionRepository.request(
                bitmap = selectedAreaBitmap,
                // 읽을 엔진이 없는 언어인데 이미지 번역을 못 하면(원격 스위치를 껐다) auto 로 읽는다
                sourceLanguageCode = if (ImageTranslation.isImageOnlyLanguage(sourceLanguageCode)) "auto" else sourceLanguageCode,
                // 영역 안의 글 전체가 필요하다 — 검출만 하고 멈추지 않는다.
                readAll = true,
            )
            Timber.tag(TAG).d("$selectedArea sourceLanguageCode $sourceLanguageCode")
            Timber.tag(TAG).d("$selectedArea visionResponse $visionResponse")

            if (visionResponse !is VisionResponse.Success) {
                return@launchInOverlayViewCoroutineScope
            }

            // auto 의 지원되지 않는 문자권 관문(성능 P7)이 개입했다 — 읽지 못한 글 대신 영역 이미지를 대상으로 둔다.
            // Claude 이미지 번역을 쓸 수 있으면 영역을 보내고, 아니면 "읽을 수 없는 문자" 안내가 뜬다(TargetHandleViewModel)
            visionResponse.result.unsupportedScript?.let { verdict ->
                targetHandleViewModel.visionResultFlow.value = Transaction(
                    captureResponse.bitmap, OcrText("", emptyList()), visionResponse.result.detectedLanguageCode, emptyList(),
                    image = ImageTargets.Area(Rect(selectedArea)), unsupportedScript = verdict,
                )
                return@launchInOverlayViewCoroutineScope
            }

            targetHandleViewModel.visionResultFlow.value = visionResponse.result
        }
    }
}

fun createOverlaidBitmap(originalBitmap: Bitmap, rect: Rect): Bitmap {
    val maxWidth = max(1, originalBitmap.width)
    val maxHeight = max(1, originalBitmap.height)

    val safeLeft = rect.left.coerceIn(0, maxWidth - 1)
    val safeTop = rect.top.coerceIn(0, maxHeight - 1)
    val safeRight = rect.right.coerceIn(safeLeft + 1, maxWidth)
    val safeBottom = rect.bottom.coerceIn(safeTop + 1, maxHeight)

    val safeWidth = safeRight - safeLeft
    val safeHeight = safeBottom - safeTop

    if (safeWidth <= 0 || safeHeight <= 0) {
        Timber.e(
            "createOverlaidBitmap: Invalid dimensions. " +
                    "rect=($rect), " +
                    "computed=(left=$safeLeft, top=$safeTop, right=$safeRight, bottom=$safeBottom), " +
                    "width=$safeWidth, height=$safeHeight"
        )
        return createBitmap(maxWidth, maxHeight, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.BLACK)
        }
    }

    return try {
        val croppedBitmap = Bitmap.createBitmap(originalBitmap, safeLeft, safeTop, safeWidth, safeHeight)

        val returnBitmap = createBitmap(maxWidth, maxHeight, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.BLACK)
        }

        val canvas = Canvas(returnBitmap)
        canvas.drawBitmap(croppedBitmap, safeLeft.toFloat(), safeTop.toFloat(), null)

        returnBitmap
    } catch (e: IllegalArgumentException) {
        Timber.e(e, "Bitmap.createBitmap failed in createOverlaidBitmap")
        createBitmap(maxWidth, maxHeight, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.BLACK)
        }
    }
}
















