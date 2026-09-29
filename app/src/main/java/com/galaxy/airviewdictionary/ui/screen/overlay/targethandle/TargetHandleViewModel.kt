package com.galaxy.airviewdictionary.ui.screen.overlay.targethandle

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.view.MotionEvent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeviceUnknown
import androidx.compose.material.icons.filled.Engineering
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.galaxy.airviewdictionary.R
import com.galaxy.airviewdictionary.data.local.capture.CapturePreventedException
import com.galaxy.airviewdictionary.data.local.capture.CaptureRepository
import com.galaxy.airviewdictionary.data.local.capture.CaptureResponse
import com.galaxy.airviewdictionary.data.local.capture.NoMediaProjectionTokenException
import com.galaxy.airviewdictionary.data.local.preference.PreferenceRepository
import com.galaxy.airviewdictionary.data.local.ads.AdGateState
import com.galaxy.airviewdictionary.data.local.secure.SecureRepository
import com.galaxy.airviewdictionary.data.local.secure.UsageInfo
import com.galaxy.airviewdictionary.data.local.tts.TTSReadTarget
import com.galaxy.airviewdictionary.data.local.tts.TTSRepository
import com.galaxy.airviewdictionary.data.local.vision.TextDetectMode
import com.galaxy.airviewdictionary.data.local.vision.VisionRepository
import com.galaxy.airviewdictionary.data.local.capture.ImageCrop
import com.galaxy.airviewdictionary.data.local.vision.model.ImageTargets
import com.galaxy.airviewdictionary.data.local.vision.model.Line
import com.galaxy.airviewdictionary.data.local.vision.model.Paragraph
import com.galaxy.airviewdictionary.data.local.vision.model.VisionResponse
import com.galaxy.airviewdictionary.data.local.vision.model.VisionText
import com.galaxy.airviewdictionary.data.local.vision.model.Word
import com.galaxy.airviewdictionary.data.local.vision.model.Transaction as VisionTransaction
import com.galaxy.airviewdictionary.data.local.vision.ocr.OcrText
import com.galaxy.airviewdictionary.data.remote.firebase.AnalyticsRepository
import com.galaxy.airviewdictionary.data.remote.firebase.RemoteConfigRepository
import com.galaxy.airviewdictionary.data.remote.translation.ImageTranslation
import com.galaxy.airviewdictionary.data.remote.translation.NoTextInImageException
import com.galaxy.airviewdictionary.data.remote.translation.Transaction
import com.galaxy.airviewdictionary.data.remote.translation.TranslationErrorMessages
import com.galaxy.airviewdictionary.data.remote.translation.TranslationContextMode
import com.galaxy.airviewdictionary.data.remote.translation.TranslationKitType
import com.galaxy.airviewdictionary.data.remote.translation.TranslationRepository
import com.galaxy.airviewdictionary.data.local.vision.model.TranslationTarget
import com.galaxy.airviewdictionary.data.remote.translation.TranslationResponse
import com.galaxy.airviewdictionary.extensions.finishService
import com.galaxy.airviewdictionary.extensions.voiceNameMatchesLanguage
import com.galaxy.airviewdictionary.extensions.gotoStore
import com.galaxy.airviewdictionary.extensions.openGoogleApp
import com.galaxy.airviewdictionary.extensions.toPx
import com.galaxy.airviewdictionary.ui.screen.ads.AdGateActivity
import com.galaxy.airviewdictionary.ui.screen.main.SettingsActivity
import com.galaxy.airviewdictionary.ui.screen.overlay.dialog.DialogView
import com.galaxy.airviewdictionary.ui.screen.overlay.menubar.MenuBarView
import com.galaxy.airviewdictionary.ui.screen.overlay.translation.DismissRunningCommand
import com.galaxy.airviewdictionary.ui.screen.overlay.translation.TTSStatus
import com.galaxy.airviewdictionary.ui.screen.overlay.translation.TranslationErrorView
import com.galaxy.airviewdictionary.ui.screen.overlay.translation.TranslationView
import com.galaxy.airviewdictionary.ui.screen.overlay.visiontext.VisionTextView
import com.galaxy.airviewdictionary.ui.screen.permissions.ScreenCapturePermissionRequesterActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.util.Locale
import kotlin.math.sqrt


@Suppress("UNCHECKED_CAST")
class TargetHandleViewModelFactory(
    private val applicationContext: Context,
    private val secureRepository: SecureRepository,
    private val remoteConfigRepository: RemoteConfigRepository,
    private val preferenceRepository: PreferenceRepository,
    private val captureRepository: CaptureRepository,
    private val visionRepository: VisionRepository,
    private val translationRepository: TranslationRepository,
    private val ttsRepository: TTSRepository,
    private val analyticsRepository: AnalyticsRepository,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TargetHandleViewModel::class.java)) {
            return TargetHandleViewModel(
                applicationContext = applicationContext,
                secureRepository = secureRepository,
                remoteConfigRepository = remoteConfigRepository,
                preferenceRepository = preferenceRepository,
                captureRepository = captureRepository,
                visionRepository = visionRepository,
                translationRepository = translationRepository,
                ttsRepository = ttsRepository,
                analyticsRepository = analyticsRepository,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel Class")
    }
}

class TargetHandleViewModel(
    private val applicationContext: Context,
    private val secureRepository: SecureRepository,
    val remoteConfigRepository: RemoteConfigRepository,
    val preferenceRepository: PreferenceRepository,
    val captureRepository: CaptureRepository,
    val visionRepository: VisionRepository,
    val translationRepository: TranslationRepository,
    val ttsRepository: TTSRepository,
    val analyticsRepository: AnalyticsRepository,
) : ViewModel() {

    private val TAG = javaClass.simpleName

    /** 문맥으로 보낼 최대 글자 수. 토큰 폭증과 지연을 막는 상한. */
    private val MAX_CONTEXT_CHARS = 4000

    /** 마지막으로 대상이 정해진 포인터 위치. */
    private var lastPointerStoppedPosition: Point? = null

    /**
     * target handle 모션 이벤트 flow
     */
    val motionEventFlow = MutableStateFlow(MotionEvent.INVALID_POINTER_ID)

    /**
     * target handle pointer 위치 Flow
     */
    val pointerPositionFlow = MutableStateFlow<Point?>(null)

    /**
     * target handle 이 docking 되었는지의 여부 flow
     */
    val dockStateFlow = MutableStateFlow<Boolean>(false)

    /**
     * 캡처된 bitmap 의 OCR api 요청 결과.
     */
    val visionResultFlow = MutableStateFlow<com.galaxy.airviewdictionary.data.local.vision.model.Transaction?>(null)

    /**
     * screen capture 진행상태의 flow.
     * [CaptureStatus.Idle] 캡처기능 유휴상태
     * [CaptureStatus.Requested] 캡처 요청, 결과 대기 상태
     * [CaptureStatus.Captured] 캡처 결과 수신 상태
     */
    val captureStatusFlow = MutableStateFlow(CaptureStatus.Idle)

    /**
     * 번역 진행상태의 flow.
     * [TranslateStatus.Idle] 번역 요청 유휴상태
     * [TranslateStatus.Requested] 번역 요청, 결과 대기 상태
     * [TranslateStatus.Translated] 번역 결과 수신 상태
     */
    val translateStatusFlow = MutableStateFlow(TranslateStatus.Idle)

    /**
     * 포인터가 멈춘 자리의 대상을 찾는 중인가 — OCR 결과를 기다리거나 가리킨 문단을 읽고 있다.
     * 표적 안의 프로그레스가 번역 요청([TranslateStatus.Requested]) 전부터 돌게 한다. 검출만 된 화면(PP-OCRv5)은 가리킨 문단을
     * 읽는 데 1초 남짓 걸리고 auto 는 OCR 자체가 몇 초 걸려, 이것 없이는 머무는 동안 아무 표시가 없다.
     */
    val targetPendingFlow = MutableStateFlow(false)

    /** 이번 제스처의 OCR 이 도는 중인가. 결과([visionResultFlow])가 오기 전에 포인터가 멈추면 대상 찾기가 기다린다. */
    private val ocrRunningFlow = MutableStateFlow(false)


    ////////////////////////////////////////////////////////////////////////////////////////////////
    //                                                                                            //
    //                                         SecureInfo                                         //
    //                                                                                            //
    ////////////////////////////////////////////////////////////////////////////////////////////////



    ////////////////////////////////////////////////////////////////////////////////////////////////
    //                                                                                            //
    //                                       Service Operation                                    //
    //                                                                                            //
    ////////////////////////////////////////////////////////////////////////////////////////////////

    private data class RemoteConfig(
        val serviceAvailable: Boolean,
        val latestVersionCode: Long,
        val forceUpdate: Boolean,
    )

    private val serviceOperationInfoFlow: Flow<RemoteConfig> =
        remoteConfigRepository.remoteConfigFlow
            .filterNotNull()
            .map { remoteConfig ->
                Timber.tag(TAG).i("remoteConfig: $remoteConfig")

                val serviceAvailable: Boolean = remoteConfig[RemoteConfigRepository.SERVICE_AVAILABLE_KEY]?.asString()?.let {
                    val jsonObject = JSONObject(it)
                    Timber.tag(TAG).d("jsonObject: $jsonObject")
                    val defaultServiceAvailable = jsonObject.getBoolean("default")
                    Timber.tag(TAG).d("defaultServiceAvailable: $defaultServiceAvailable")
                    jsonObject.optBoolean(Locale.getDefault().country, defaultServiceAvailable)
                } ?: true
                Timber.tag(TAG).i("serviceAvailable: $serviceAvailable")

                val packageInfo = applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0)
                val versionCode: Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode
                } else {
                    packageInfo.versionCode.toLong()
                }
                Timber.tag(TAG).i("versionCode: $versionCode")
                val forceUpdateVersionCode = remoteConfig[RemoteConfigRepository.FORCE_UPDATE_VERSION_CODE_KEY]?.asLong() ?: 0

                RemoteConfig(
                    serviceAvailable = serviceAvailable,
                    latestVersionCode = remoteConfig[RemoteConfigRepository.LATEST_VERSION_CODE_KEY]?.asLong() ?: 0,
                    forceUpdate = versionCode < forceUpdateVersionCode,
                )
            }
            .distinctUntilChanged()

    private fun collectServiceOperationInfoFlow() {
        viewModelScope.launch {
            serviceOperationInfoFlow
                .collect { remoteConfig: RemoteConfig ->
                    Timber.tag(TAG).i("remoteConfig: $remoteConfig")
                    // 서비스 점검중 입니다.
                    if (!remoteConfig.serviceAvailable) {
                        delay(5000)
                        DialogView.INSTANCE.cast(
                            applicationContext = applicationContext,
                            icon = Icons.Default.Engineering,
                            dialogTitle = applicationContext.getString(R.string.message_service_unavailable),
                            dialogText = applicationContext.getString(R.string.message_service_unavailable_detail),
                            onConfirm = { applicationContext.finishService() }
                        )
                    }
                    // 강제 업데이트
                    else if (remoteConfig.forceUpdate) {
                        delay(5000)
                        DialogView.INSTANCE.cast(
                            applicationContext = applicationContext,
                            icon = Icons.Default.Update,
                            dialogTitle = applicationContext.getString(R.string.message_force_update),
                            dialogText = applicationContext.getString(R.string.message_force_update_detail),
                            onConfirm = { applicationContext.gotoStore(finishService = true) },
                        )
                    }
                }
        }
    }


    ////////////////////////////////////////////////////////////////////////////////////////////////
    //                                                                                            //
    //                                        preference                                          //
    //                                                                                            //
    ////////////////////////////////////////////////////////////////////////////////////////////////

    private var _textDetectMode = TextDetectMode.SENTENCE

    val textDetectMode: TextDetectMode
        get() = _textDetectMode

    private var _dragHandleDocking = true

    val dragHandleDocking: Boolean
        get() = _dragHandleDocking

    private var _dockingDelay = 3000L

    val dockingDelay: Long
        get() = _dockingDelay

    private var ttsSpeechRate = 1.0f

    private var ttsReadTarget = TTSReadTarget.SOURCE

    private fun collectPreference() {
        viewModelScope.launch {
            preferenceRepository.textDetectModeFlow.collect { newValue ->
                _textDetectMode = newValue
            }
        }

        viewModelScope.launch {
            preferenceRepository.dragHandleDockingFlow.collect { newValue ->
                _dragHandleDocking = newValue
            }
        }

        viewModelScope.launch {
            preferenceRepository.dockingDelayFlow.collect { newValue ->
                _dockingDelay = newValue
            }
        }

        viewModelScope.launch {
            preferenceRepository.ttsSpeechRateFlow
                .collect { ttsSpeechRate_ ->
                    ttsSpeechRate = ttsSpeechRate_
                }
        }

        viewModelScope.launch {
            preferenceRepository.ttsReadTargetFlow
                .collect { ttsReadTarget_ ->
                    ttsReadTarget = ttsReadTarget_
                }
        }
    }

    fun updateTextDetectMode(textDetectMode: TextDetectMode) {
        preferenceRepository.update(PreferenceRepository.TEXT_DETECT_MODE, textDetectMode.name)
    }

    fun updateTranslationKitType(kitType: TranslationKitType) {
        preferenceRepository.update(PreferenceRepository.TRANSLATION_KIT_TYPE, kitType.name)
    }


    ////////////////////////////////////////////////////////////////////////////////////////////////
    //                                                                                            //
    //                                      Capture request                                       //
    //                                                                                            //
    ////////////////////////////////////////////////////////////////////////////////////////////////

    private fun collectTargetHandleMotionEvent() {
        viewModelScope.launch {
            motionEventFlow
                .filterNotNull()
                .collect { motionEvent ->
                    if (motionEvent == MotionEvent.ACTION_DOWN) {
                        Timber.tag(TAG).i("#### TargetHandle motionEvent MotionEvent.ACTION_DOWN ####")
                        if (
                            textDetectMode == TextDetectMode.WORD
                            || textDetectMode == TextDetectMode.SENTENCE
                            || textDetectMode == TextDetectMode.PARAGRAPH
                        ) {
                            requestCapture()
                        }
                    } else if (motionEvent == MotionEvent.ACTION_UP
                        || motionEvent == MotionEvent.ACTION_CANCEL
                    ) {
                        // ACTION_CANCEL 은 시스템이 제스처를 가져갔을 때 온다(삼성 엣지 뒤로가기 등).
                        // 여기서 정리하지 않으면 captureStatus 가 Requested 로 남아
                        // 핸들이 alpha 0.01 인 채 "사라진" 것처럼 보인다.
                        Timber.tag(TAG).i("#### TargetHandle motionEvent $motionEvent (UP/CANCEL) ####")
                        cancelCapture()
                    }
                }
        }
    }

    /**
     * [TargetHandleView] 의 요청에 따라 화면캡처를 수행한다.
     * 캡처된 bitmap 의 OCR 을 요청한다.
     */
    /**
     * 제스처 세대. 새 캡처(=새 제스처)마다 증가하며,
     * 이전 제스처의 늦은 번역 실패가 새 제스처 위에 표시되는 것을 막는 기준이 된다.
     */
    private var captureEpoch = 0

    /**
     * 진행 중인 OCR. 새 제스처가 시작되면 취소한다 — 앞 제스처의 느린 결과(auto 는 4초 가까이 걸린다)가 새 제스처의 결과를
     * 덮어쓰지 않게, 그리고 새 제스처의 OCR 과 CPU 를 다투지 않게. 캡처 자체는 취소하지 않는다(취소되면 프레임 생산을 멈추지 못한다).
     */
    private var visionJob: Job? = null

    private fun requestCapture() {
        Timber.tag(TAG).i("#### requestCapture() ####")

        val epoch = ++captureEpoch
        visionJob?.cancel()
        visionJob = null

        // 남아 있는 실패 안내 창은 새 제스처 시작 시 해제한다.
        // (캡처 이미지에 안내 창이 찍혀 OCR 에 섞이는 것도 방지)
        if (TranslationErrorView.INSTANCE.isAttachedToWindow()) {
            TranslationErrorView.INSTANCE.clear()
        }

        visionResultFlow.value = null
        ocrRunningFlow.value = true
        captureStatusFlow.value = CaptureStatus.Requested

        viewModelScope.launch {
            Timber.tag(TAG).d("requestCapture viewModelScope.launch -------------- 0")
            // 캡처 전 화면에 보여지는 OverlayView 들을 숨기기 위한 딜레이
            delay(50)
            Timber.tag(TAG).d("requestCapture viewModelScope.launch -------------- 1")
            val captureResponse: CaptureResponse = captureRepository.request()
            Timber.tag(TAG).d("requestCapture viewModelScope.launch -------------- 2 $captureResponse")
            if (captureResponse is CaptureResponse.Success) {
                Timber.tag(TAG).d("captureResponse.bitmap ${captureResponse.bitmap.width} ${captureResponse.bitmap.height}")

                val motionEventState = motionEventFlow.first()
                Timber.tag(TAG).d("requestCapture motionEventState $motionEventState")
                if (epoch != captureEpoch) {
                    // 캡처가 돌아오기 전에 새 제스처가 시작됐다 — 이 캡처는 새 제스처의 것이 아니다. 상태는 새 제스처가 쥐고 있다.
                    Timber.tag(TAG).d("requestCapture stale capture (epoch $epoch -> $captureEpoch)")
                } else if (motionEventState == MotionEvent.ACTION_DOWN || motionEventState == MotionEvent.ACTION_MOVE) {
                    captureStatusFlow.value = CaptureStatus.Captured
                    visionJob = launch {
                        try {
                            requestVision(captureResponse.bitmap, epoch)
                        } finally {
                            // 새 제스처가 시작됐으면 OCR 상태는 그 제스처의 것이다.
                            if (epoch == captureEpoch) ocrRunningFlow.value = false
                        }
                    }
                } else {
                    // 캡처가 돌아오기 전에 제스처가 끝났거나 취소된 경우.
                    // 되돌리지 않으면 Requested 로 남아 핸들이 계속 투명하다.
                    cancelCapture()
                }
            } else if (captureResponse is CaptureResponse.Error) {
                Timber.tag(TAG).d("CaptureResponse.Error ${captureResponse.t.toString()}")
                if (captureResponse.t is NoMediaProjectionTokenException) {
                    captureStatusFlow.value = CaptureStatus.PermissionRequested
                    ocrRunningFlow.value = false
                    // 화면 캡처 권한을 요청
                    val intent = Intent(
                        applicationContext,
                        ScreenCapturePermissionRequesterActivity::class.java
                    )
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    applicationContext.startActivity(intent)
                } else {
                    if (captureResponse.t is CapturePreventedException) {
                        // 캡처 방지 알림
                        // captureResponse.t.checkerBitmap 처리
                    }
                    // 권한 요청이 아닌 모든 실패는 상태를 되돌린다.
                    // (되돌리지 않으면 핸들이 투명한 채로 남는다)
                    cancelCapture()
                }
            }
        }
    }

    fun cancelCapture() {
        pointerPositionFlow.value = null
        pointerPositionedTranslationFlow.value = null
        currentTargetFlow.value = null
        if (captureStatusFlow.value != CaptureStatus.PermissionRequested) {
            captureStatusFlow.value = CaptureStatus.Idle
        }
        translateStatusFlow.value = TranslateStatus.Idle
        targetPendingFlow.value = false
        ocrRunningFlow.value = false
        visionResultFlow.value = null
    }

    fun restartCaptureRepository() {
        captureRepository.restart()
    }


    ////////////////////////////////////////////////////////////////////////////////////////////////
    //                                                                                            //
    //                                        ml-kit vision                                       //
    //                                                                                            //
    ////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * 캡처된 bitmap 의 OCR 을 요청한다.
     */
    /**
     * 번역 대상 주변의 화면 텍스트를 문맥으로 모은다.
     *
     * 설정이 [TranslationContextMode.OFF] 이거나 문맥이 대상과 같으면 null 을 돌려 보내
     * 기존과 동일하게 대상 문장만 번역되도록 한다. 문맥을 쓰지 않는 엔진은 이 값을 무시한다.
     */
    private suspend fun buildContextText(
        kitType: TranslationKitType,
        transaction: com.galaxy.airviewdictionary.data.local.vision.model.Transaction,
        target: com.galaxy.airviewdictionary.data.local.vision.model.VisionText,
    ): String? {
        val mode = preferenceRepository.contextModeFlow(kitType).first()
        if (mode == TranslationContextMode.OFF) return null

        // 검출만 된 화면이면 지금까지 읽은 문단만 문맥이 된다(.docs/vision-engine-design.md §10.3).
        // 화면 전체 문맥은 대상 문단의 위아래 이웃까지 읽고 나서 모은다(§18) — 번역이 그만큼 늦어진다.
        if (mode == TranslationContextMode.SCREEN && transaction.unread != null) readNeighbours(transaction, target)
        val paragraphs = transaction.readParagraphs()
        if (paragraphs.isEmpty()) return null

        val sources = when (mode) {
            TranslationContextMode.SCREEN -> paragraphs
            // 대상이 속한 문단(= 주변 문장). 어느 문단에도 걸치지 않으면 문맥 없이 보낸다.
            TranslationContextMode.NEARBY ->
                paragraphs.filter { android.graphics.Rect.intersects(it.boundingBox, target.boundingBox) }
            TranslationContextMode.OFF -> emptyList()
        }

        val context = sources.joinToString("\n") { it.representation }
            .trim()
            .take(MAX_CONTEXT_CHARS)

        // 문맥이 대상 문장 그 자체뿐이면 보낼 이유가 없다(토큰만 늘어난다).
        return context.takeIf { it.isNotBlank() && it != target.representation.trim() }
    }

    /** [epoch] 는 이 캡처를 요청한 제스처 세대. 결과가 돌아왔을 때 세대가 바뀌었으면(새 제스처) 버린다. */
    private suspend fun requestVision(capturedBitmap: Bitmap, epoch: Int) {
        Timber.tag(TAG).i("#### requestVision() ####")

        val sourceLanguageCode: String = preferenceRepository.sourceLanguageCodeFlow.first()
        val translationKitType: TranslationKitType = preferenceRepository.translationKitTypeFlow.first()
        val visionResponse: VisionResponse = if (translationRepository.usesImageTranslation(translationKitType, sourceLanguageCode)) {
            // AI 이미지 번역 — 줄 위치만 찾는다(§25). 모델 팩이 아직 없으면 auto 는 글 번역으로, 읽을 엔진이 없는 언어는 포인터 둘레 띠로
            visionRepository.requestImageTargets(capturedBitmap, sourceLanguageCode)
                ?: if (ImageTranslation.isImageOnlyLanguage(sourceLanguageCode)) {
                    VisionResponse.Success(
                        VisionTransaction(capturedBitmap, OcrText("", emptyList()), sourceLanguageCode, emptyList(), image = ImageTargets.PointerBand)
                    )
                } else null
        } else {
            null
        } ?: visionRepository.request(
            bitmap = capturedBitmap,
            // 읽을 엔진이 없는 언어인데 이미지 번역을 못 하면(원격 스위치를 껐다) auto 로 읽는다
            sourceLanguageCode = if (ImageTranslation.isImageOnlyLanguage(sourceLanguageCode)) "auto" else sourceLanguageCode,
        )

        if (epoch != captureEpoch) {
            Timber.tag(TAG).d("drop stale visionResult (epoch $epoch -> $captureEpoch)")
            return
        }
        if (visionResponse is VisionResponse.Success) {
            val motionEventState = motionEventFlow.first()
            if (epoch == captureEpoch && (motionEventState == MotionEvent.ACTION_DOWN || motionEventState == MotionEvent.ACTION_MOVE)) {
                Timber.tag(TAG).i("set visionResult : ${visionResponse.result.ocr.blocks.size} blocks")
                visionResultFlow.value = visionResponse.result
            }
        } else if (visionResponse is VisionResponse.Error) {
            Timber.tag(TAG).e("visionResponse err ${visionResponse.t}")
        }
    }


    ////////////////////////////////////////////////////////////////////////////////////////////////
    //                                                                                            //
    //                                         번역 대상 지정                                       //
    //                                                                                            //
    ////////////////////////////////////////////////////////////////////////////////////////////////

    /** 포인터 멈춤 으로 인정되는 거리 마진 */
    private val POINTER_STOPPED_MARGIN_DISTANCE: Int = applicationContext.resources.getDimensionPixelSize(R.dimen.targethandle_view_pointer_stopped_distance)

    /** 포인터 멈춤 으로 인정되는 시간 마진 */
    private val POINTER_STOPPED_MARGIN_DURATION: Long
        get() = if (textDetectMode == TextDetectMode.SELECT) 220 else 80

    /**
     * [pointerPositionFlow] (포인터 위치) Flow 를 포인터가 머무는 위치로 변환 발행.
     */
    val pointerStoppedPositionFlow: Flow<Point?> = channelFlow {
        var _pointerPosition: Point? = null
        var lastEmittedPoint: Point? = null // 마지막으로 emit된 Point를 추적
        var timerJob: Job? = null

        // Helper function to calculate distance
        fun calculateDistance(point1: Point, point2: Point): Double {
            val dx = point1.x - point2.x
            val dy = point1.y - point2.y
            return sqrt((dx * dx + dy * dy).toDouble())
        }

        // Cancel the timer job
        fun cancelTimer() {
            timerJob?.cancel()
            timerJob = null
        }

        // Start the timer to emit the position
        fun startTimer(pointerPosition: Point?) {
            cancelTimer() // Cancel any existing timer
            timerJob = launch {
                delay(POINTER_STOPPED_MARGIN_DURATION)
                pointerPosition?.let { currentPoint ->
                    // Emit only if the distance to the last emitted point is greater than the margin
                    val isNotDuplicate = lastEmittedPoint?.let {
                        calculateDistance(currentPoint, it) > POINTER_STOPPED_MARGIN_DISTANCE
                    } ?: true // If lastEmittedPoint is null, it's not a duplicate

                    if (isNotDuplicate) {
                        send(currentPoint) // Emit the position using `send`
                        lastEmittedPoint = currentPoint // Update the last emitted point
                    }
                }
                _pointerPosition = null // Reset for the next emit
                cancelTimer()
            }
        }

        // Combine pointerPositionFlow and motionEventFlow
        combine(pointerPositionFlow, motionEventFlow) { pointerPosition, motionEvent ->
            Pair(pointerPosition, motionEvent)
        }.collectLatest { (pointerPosition, motionEvent) ->
            if (pointerPosition != null &&
                (motionEvent == MotionEvent.ACTION_DOWN || motionEvent == MotionEvent.ACTION_MOVE)
            ) {
                if (_pointerPosition == null) {
                    _pointerPosition = pointerPosition
                    startTimer(pointerPosition) // Start the timer for the first time
                } else {
                    if (calculateDistance(pointerPosition, _pointerPosition!!) <= POINTER_STOPPED_MARGIN_DISTANCE) {
                        // Pointer is within the margin, continue waiting
                    } else {
                        cancelTimer() // Cancel the ongoing timer
                        _pointerPosition = null // Reset the pointer position
                        send(null) // Emit null using `send`
                    }
                }
            } else {
                cancelTimer() // Cancel the timer when pointer is invalid
                _pointerPosition = null
                lastEmittedPoint = null
                send(null) // Emit null using `send`
            }
        }
    }

    /** 포인터가 멈춘 자리의 대상 찾기. */
    private sealed interface TargetLookup {
        /** 찾는 중이다 — OCR 결과를 기다리거나 가리킨 문단을 읽고 있다. */
        data object Pending : TargetLookup

        /** 찾았다. 그 자리에 글이 없으면 null. [position] 은 찾은 포인터 자리다(이미지 번역의 표시 자리, §25). */
        data class Found(val visionText: VisionText?, val position: Point? = null) : TargetLookup
    }

    /** 대상 찾기의 입력. [visionResult] 가 null 이면 OCR 이 아직 돌고 있다. */
    private class LookupInput(
        val position: Point,
        val visionResult: com.galaxy.airviewdictionary.data.local.vision.model.Transaction?,
    )

    /**
     * [pointerStoppedPositionFlow] (포인터가 머무는 위치) 와 [visionResultFlow] 를 취합하여 해당 위치의 대상을 찾는다.
     *
     * 고르기는 `transformLatest` 에서 한다. 검출만 된 화면은 고르기 전에 가리킨 문단을 읽어야 하는데, 포인터가 다음 문단으로 가면
     * 읽던 것을 취소해야 하기 때문이다. 다 읽힌 화면(ML Kit)은 읽을 것이 없어 멈추지 않고 바로 고른다.
     * 기다려야 하면(OCR 이 아직 돌거나 문단을 읽어야 하면) 먼저 [TargetLookup.Pending] 을 낸다 — 표적의 프로그레스를 켜는 신호다.
     *
     * 하나로 돌려 나눠 준다. 번역 요청([collectVisionTextForTranslationView])·핸들·인식 하이라이트가 이 결과를 보는데, 각자 수집하면(cold)
     * 포인터가 멈출 때마다 대상 찾기가 세 번 돌고 문단 읽기도 세 번 청한다. 늦게 붙는 쪽(대상이 정해진 뒤 뜨는 인식 하이라이트)은 마지막 결과를
     * 곧바로 받는다 — 각자 수집할 때는 새로 멈춤 판정(80ms)부터 다시 했다.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val targetLookupFlow: SharedFlow<TargetLookup> = combine(
        // filterNotNull 을 쓰면 안 된다. [pointerStoppedPositionFlow] 는 포인터가 마진을 벗어나
        // 대상을 떠났을 때 null 을 보내는데, 그것이 "진행 중인 번역을 취소하라"는 신호다.
        // 걸러내면 그 신호가 아래로 전달되지 않아, 이미 떠난 대상의 요청이 끝까지 진행된다.
        pointerStoppedPositionFlow,
        visionResultFlow,
        ocrRunningFlow,
    ) { pointerStoppedPosition, visionResult, ocrRunning ->
        // 다만 null 을 "대상을 떠났다"로 읽는 것은 포인터로 대상을 고르는 모드에서만 옳다.
        // SELECT 는 손을 뗀 뒤에 영역을 그리는데, ACTION_UP 에서도 null 이 나가므로
        // 그대로 받으면 영역 선택을 마치는 순간 대상이 없다고 판단해 번역이 아예 안 된다.
        val stoppedPosition = pointerStoppedPosition
            ?: lastPointerStoppedPosition.takeIf { textDetectMode == TextDetectMode.SELECT }
            ?: return@combine null
        // 위의 SELECT 대체값으로 쓴다. pointerStoppedPositionFlow 는 channelFlow 라
        // 나중에 값을 다시 꺼낼 수 없어, 대상이 정해지는 이 시점에 붙잡아 둔다.
        lastPointerStoppedPosition = stoppedPosition
        when {
            visionResult != null -> LookupInput(stoppedPosition, visionResult)
            ocrRunning -> LookupInput(stoppedPosition, null)
            else -> null
        }
    }
        // OCR 이 끝나면 결과가 먼저 오고 ocrRunning 이 뒤따라 꺼진다. 그 두 번째 신호에 읽던 문단을 취소하고 다시 읽지 않게 거른다.
        .distinctUntilChanged { old, new -> old?.position == new?.position && old?.visionResult === new?.visionResult }
        .transformLatest { input ->
            val visionResult = input?.visionResult
            when {
                input == null -> emit(TargetLookup.Found(null))
                visionResult == null -> emit(TargetLookup.Pending)
                else -> {
                    if (needsReading(visionResult, input.position)) emit(TargetLookup.Pending)
                    emit(
                        TargetLookup.Found(
                            getPointerPositionedVisionText(
                                visionResult = visionResult,
                                pointerPosition = input.position,
                                textDetectMode = textDetectMode
                            ),
                            input.position,
                        )
                    )
                }
            }
        }
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(), replay = 1)

    /** 포인터가 머무는 위치의 VisionText. 그 자리에 글이 없으면 null. */
    val pointerPositionedVisionTextFlow: Flow<VisionText?> = targetLookupFlow
        .filterIsInstance<TargetLookup.Found>()
        .map { it.visionText }
        .distinctUntilChanged()

    /** 포인터 자리의 문단을 아직 읽지 않았나 — 읽는 동안 대상 찾기가 기다린다. SELECT 는 문단을 읽지 않는다. */
    private fun needsReading(
        visionResult: com.galaxy.airviewdictionary.data.local.vision.model.Transaction,
        pointerPosition: Point,
    ): Boolean {
        if (textDetectMode == TextDetectMode.SELECT) return false
        val unread = visionResult.unread ?: return false
        return visionResult.paragraphs.find { paragraph ->
            paragraph.boundingBox.contains(pointerPosition.x, pointerPosition.y)
        }?.let { unread.needsReading(it) } == true
    }

    private suspend fun getPointerPositionedVisionText(
        visionResult: com.galaxy.airviewdictionary.data.local.vision.model.Transaction,
        pointerPosition: Point,
        textDetectMode: TextDetectMode,
    ): VisionText? {
        visionResult.image?.let { return imageTarget(visionResult, it, pointerPosition, textDetectMode) }
        if (textDetectMode == TextDetectMode.SELECT) {
            val boundingBox = visionResult.ocr.blockBoundingBoxUnion()
            val averageTextBlockHeight = visionResult.ocr.averageBlockHeight()
            val writingDirection = visionResult.mostFrequentWritingDirection()
            if (boundingBox != null && averageTextBlockHeight > 0 && writingDirection != null) {
                return Word(
                    boundingBox = boundingBox,
                    representation = visionResult.ocr.text,
                    writingDirection = writingDirection,
                    chars = emptyList(),
                    presetFontHeight = averageTextBlockHeight
                )
            }
            return null
        }

        // 문단은 검출 상자로 고르고, 그 안의 문장·단어는 읽은 문단에서 고른다.
        val positionedParagraph: Paragraph? = visionResult.paragraphs.find { paragraph ->
            paragraph.boundingBox.contains(pointerPosition.x, pointerPosition.y)
        }?.let { readParagraph(visionResult, it) }
        if (textDetectMode == TextDetectMode.PARAGRAPH) {
            return positionedParagraph
        }

        if (textDetectMode == TextDetectMode.SENTENCE) {
            return positionedParagraph?.sentences?.find { sentence ->
                sentence.boundingPolygon.contains(pointerPosition)
            }
        }

        val positionedLine: Line? = positionedParagraph?.lines?.find { line ->
            val expandedRect = expandedRect(line.boundingBox)
            expandedRect.contains(pointerPosition.x, pointerPosition.y)
        }
        val positionedWord: Word? = positionedLine?.words?.find { word ->
            val expandedRect = expandedRect(word.boundingBox)
            expandedRect.contains(pointerPosition.x, pointerPosition.y)
        }
        return positionedWord
    }

    /**
     * AI 이미지 번역의 대상(§25). 글을 읽지 않았으므로 단어는 모른다 — 단어 모드는 포인터 아래 검출 줄, 문장·문단 모드는 그 문단이다.
     * 번역할 단어·문장·문단은 모델이 잘라 보낸 이미지의 표시를 보고 고른다. 대상의 상자가 자를 높이이자 하이라이트다.
     */
    private fun imageTarget(
        visionResult: com.galaxy.airviewdictionary.data.local.vision.model.Transaction,
        image: ImageTargets,
        pointerPosition: Point,
        textDetectMode: TextDetectMode,
    ): VisionText? = when (image) {
        is ImageTargets.Area -> imageBox(image.rect)
        ImageTargets.PointerBand -> {
            val half = (if (textDetectMode == TextDetectMode.WORD) IMAGE_BAND_HALF_WORD_DP else IMAGE_BAND_HALF_DP).dp.toPx(applicationContext)
            imageBox(Rect(0, pointerPosition.y - half, visionResult.bitmap.width, pointerPosition.y + half))
        }
        ImageTargets.Detected -> {
            val paragraph = visionResult.paragraphs.find { it.boundingBox.contains(pointerPosition.x, pointerPosition.y) }
            if (textDetectMode == TextDetectMode.WORD) {
                paragraph?.lines?.find { expandedRect(it.boundingBox).contains(pointerPosition.x, pointerPosition.y) }
            } else paragraph
        }
    }

    /** 글 없는 대상 상자 — 영역 선택의 영역과 포인터 둘레 띠. 번역창 글자 크기는 보통 글자 높이로 둔다. */
    private fun imageBox(box: Rect): VisionText? {
        if (box.width() <= 0 || box.height() <= 0) return null
        return Word(
            boundingBox = box,
            representation = "",
            writingDirection = com.galaxy.airviewdictionary.data.local.vision.WritingDirection.LTR,
            chars = emptyList(),
            presetFontHeight = IMAGE_BOX_FONT_HEIGHT_DP.dp.toPx(applicationContext).toDouble(),
        )
    }

    /**
     * [target] 이 든 문단의 앞뒤 문단(화면의 문단 순서)을 읽어 둔다. 읽은 문단은 캐시돼 같은 화면의 다음 번역도 쓴다.
     * 뒤 읽기다 — 그사이 포인터가 다른 문단을 가리키면 그 문단 읽기에 양보하고, 읽지 못한 이웃은 문맥에서 빠진다.
     */
    private suspend fun readNeighbours(
        transaction: com.galaxy.airviewdictionary.data.local.vision.model.Transaction,
        target: com.galaxy.airviewdictionary.data.local.vision.model.VisionText,
    ) {
        val paragraphs = transaction.paragraphs
        val at = paragraphs.indexOfFirst { android.graphics.Rect.intersects(it.boundingBox, target.boundingBox) }
        if (at < 0) return
        listOfNotNull(paragraphs.getOrNull(at - 1), paragraphs.getOrNull(at + 1)).forEach {
            readParagraph(transaction, it, background = true)
        }
    }

    /** 문단의 글을 채운다. 읽기에 실패하면 대상이 없는 것으로 본다 — 흐름을 끊지 않는다. */
    private suspend fun readParagraph(
        visionResult: com.galaxy.airviewdictionary.data.local.vision.model.Transaction,
        paragraph: Paragraph,
        background: Boolean = false,
    ): Paragraph? = try {
        visionRepository.readParagraph(visionResult, paragraph, background)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "readParagraph 실패")
        null
    }

    /**
     * TextDetectMode.LINE 과 TextDetectMode.WORD 에 판정 마진을 주기 위해 boundingBox 크기를 늘리기 위한 함수
     */
    private fun expandedRect(rect: Rect, delta: Int = 3.dp.toPx(applicationContext)): Rect {
        return Rect(rect.left, rect.top - delta, rect.right, rect.bottom + delta)
    }

    /**
     * 포인터가 머무는 위치의 VisionText 를 확인하고 아래의 작업 수행.
     * - VisionTextView 를 launch 한다.
     * - 해당 text 에 대한 번역을 요청한다.
     * - 번역이 완료되면 TranslationView 를 launch 한다.
     */
    private fun collectVisionTextForTranslationView() {
        viewModelScope.launch {
            // 마지막으로 아래로 넘긴 대상. 같은 대상을 다시 잡으면 넘기지 않는다([isSameTarget]).
            var forwarded: TargetLookup.Found? = null
            targetLookupFlow
                // 찾는 동안에는 프로그레스만 켠다 — 아래로 넘기지 않으니 진행 중이던 번역은 그대로 간다.
                // 끄는 것은 대상이 정해진 쪽이다. 새 대상이면 번역 요청을 걸면서 끈다(먼저 끄면 그 사이 한 프레임 깜빡인다).
                // 같은 대상이거나 빈 자리면 여기서 끈다.
                .filter { lookup ->
                    if (lookup !is TargetLookup.Found) {
                        targetPendingFlow.value = true
                        return@filter false
                    }
                    val last = forwarded
                    val isNew = last == null
                            || (last.visionText != lookup.visionText && !isSameTarget(last.visionText, lookup.visionText))
                            || movedWithinImageTarget(last, lookup)
                    if (isNew) forwarded = lookup
                    if (!isNew || lookup.visionText == null) targetPendingFlow.value = false
                    isNew
                }
                .map { it as TargetLookup.Found }
                // collect 가 아니라 collectLatest 다. 새 신호가 오면 진행 중이던 번역 요청이
                // 취소된다. collect 는 순차 수집이라 앞 요청이 끝나야 다음이 시작되고,
                // 떠난 대상의 요청도 끝까지 진행된다 — 느린 번역(2~4초)에서는 요청이 줄을 서서
                // 도착하는 결과가 언제나 한참 전에 떠난 대상의 것이 된다(2026-09-22 실측).
                .collectLatest { found ->
                    val pointerPositionedVisionText = found.visionText
                    // 핸들이 대상을 떠났다. 진행 중이던 요청은 위에서 이미 취소됐다.
                    if (pointerPositionedVisionText == null) {
                        currentTargetFlow.value = null
                        return@collectLatest
                    }
                    VisionTextView.INSTANCE.cast(applicationContext, pointerPositionedVisionText)

                    visionResultFlow.value?.let { visionResultTransaction ->
                        val translationKitType: TranslationKitType = preferenceRepository.translationKitTypeFlow.first()
                        val targetLanguageCode: String = preferenceRepository.targetLanguageCodeFlow.first()
                        val motionEventState = motionEventFlow.first()
                        if (motionEventState == MotionEvent.ACTION_DOWN || motionEventState == MotionEvent.ACTION_MOVE) {
                            translateStatusFlow.value = TranslateStatus.Requested
                            targetPendingFlow.value = false

                            Timber.tag(TAG).d("sourceText ${pointerPositionedVisionText.representation}")
                            Timber.tag(TAG).d("translationKitType $translationKitType")

                            // 자동 감지(auto)일 때는 화면 전체 OCR 텍스트가 아니라 실제 번역할 문장으로 언어를 감지한다.
                            // 화면에 앱 오버레이("Auto → 한국어" 등)나 브라우저의 다른 언어 UI 가 섞여 있으면
                            // 전체 텍스트 기반 감지가 엉뚱한 언어를 반환해(예: 영어 문장을 ko 로) 원문→원문 무번역이 되기 때문.
                            // 이미지 번역(§25)은 읽은 글이 없다 — 언어는 모델이 판정해 돌려준다.
                            val sourceLanguagePref = preferenceRepository.sourceLanguageCodeFlow.first()
                            val image = visionResultTransaction.image != null
                            val sourceLanguageCode = if (image) {
                                sourceLanguagePref
                            } else if (sourceLanguagePref.equals("auto", ignoreCase = true)) {
                                val perTextCode = visionRepository.identifyLanguage(pointerPositionedVisionText.representation)
                                if (perTextCode == "und") visionResultTransaction.detectedLanguageCode else perTextCode
                            } else {
                                visionResultTransaction.detectedLanguageCode
                            }
                            // 엔진에 넘기는 값은 따로 정한다 — auto 면 AI 엔진은 스스로 판정하고, 식별 못 한 값("und")은 auto 로(§23).
                            // 위의 식별값은 확정 원문 언어의 대체값(confirmTransaction)과 TTS 기록에 쓴다.
                            val kitSourceLanguageCode = TranslationSourceLanguage.forKit(translationKitType, sourceLanguagePref, sourceLanguageCode)
                            Timber.tag(TAG).d("sourceLanguageCode $sourceLanguageCode -> kit $kitSourceLanguageCode (pref $sourceLanguagePref)")

                            // TTS 목소리 목록 등이 참조하는 "마지막 번역의 실제 소스 언어"를 기록
                            sourceLanguageCode.takeIf { it.isNotBlank() && it != "auto" && it != "und" }?.let {
                                preferenceRepository.update(PreferenceRepository.LAST_USED_SOURCE_LANGUAGE_CODE, it)
                            }

                            val motionEventState = motionEventFlow.first()
                            Timber.tag(TAG).d("motionEventState $motionEventState")
                            if (motionEventState == MotionEvent.ACTION_DOWN || motionEventState == MotionEvent.ACTION_MOVE) {
                                // 이 요청이 속한 제스처 세대 — 실패 안내의 낡음 판정에 쓴다.
                                val requestEpoch = captureEpoch
                                // 이 시도의 신원. 결과가 돌아왔을 때 번역창이 이 값으로 짝을 맞춘다.
                                val target = TranslationTarget(
                                    id = ++targetSequence,
                                    visionText = pointerPositionedVisionText,
                                )
                                currentTargetFlow.value = target
                                val response = if (image) {
                                    requestImageTranslation(visionResultTransaction, pointerPositionedVisionText, found.position, sourceLanguagePref, targetLanguageCode)
                                } else {
                                    val contextText = buildContextText(
                                        kitType = translationKitType,
                                        transaction = visionResultTransaction,
                                        target = pointerPositionedVisionText,
                                    )
                                    translationRepository.request(
                                        translationKitType,
                                        kitSourceLanguageCode,
                                        targetLanguageCode,
                                        pointerPositionedVisionText.representation,
                                        contextText,
                                    )
                                }
                                response
                                    .also {
                                        // 실패 안내는 손을 뗀 뒤 응답이 도착해도 반드시 표시한다.
                                        // 번역창 파이프라인(translationFlow)은 ACTION_UP 이후에는
                                        // 컨텐츠를 발행하지 않으므로(dismiss 전용) 자체 플로우를 가진
                                        // TranslationErrorView 가 번역창과 같은 위치에 사유를 띄운다.
                                        if (it is TranslationResponse.Error) {
                                            Timber.tag(TAG).d("Response Error ${it.t}")
                                            translateStatusFlow.value = TranslateStatus.Translated
                                            // 표시 아래에 글이 없다는 답 — 빈 자리에 머문 것과 같이 조용히 끝낸다
                                            if (it.t is NoTextInImageException) return@also
                                            // 새 제스처가 이미 시작됐다면 낡은 실패 안내는 버린다.
                                            // (다음 제스처 위·캡처 프레임에 이전 안내가 찍히는 것 방지)
                                            if (requestEpoch != captureEpoch) {
                                                return@also
                                            }
                                            // 리워드 광고 위에는 안내를 띄우지 않는다.
                                            // (게이트가 가린 뒤 attach 되는 창은 hideTemporarily 로 못 가린다)
                                            // 설정 화면은 제외하지 않는다 — 설정에서 키 입력 직후
                                            // 그 자리에서 번역을 테스트하는 흐름이 많고, 성공 말풍선과
                                            // 동일하게 실패 안내도 보여야 한다.
                                            if (AdGateActivity.coveringFlow.value) {
                                                return@also
                                            }
                                            val errorTransaction = Transaction(
                                                targetId = target.id,
                                                requestedSourceLanguageCode = sourceLanguagePref,
                                                resolvedSourceLanguageCode = TranslationSourceLanguage.normalized(sourceLanguageCode),
                                                targetLanguageCode = targetLanguageCode,
                                                sourceText = pointerPositionedVisionText.representation,
                                                translationKitType = translationKitType,
                                                resultText = "⚠ " + TranslationErrorMessages.resolve(applicationContext, it.t),
                                            )
                                            // 닫기 지연시간을 따르되, 사유를 읽을 시간은 보장한다.
                                            val closeDelay = preferenceRepository.translationCloseDelayFlow.first()
                                                .coerceAtLeast(3500L)
                                            TranslationErrorView.INSTANCE.cast(
                                                applicationContext,
                                                errorTransaction,
                                                pointerPositionedVisionText,
                                                closeDelay
                                            )
                                            return@also
                                        }

                                        // 여기부터는 성공이다(실패는 위에서 끝났다).
                                        val transaction = confirmTransaction(
                                            target = target,
                                            kitResult = (it as TranslationResponse.Success).result,
                                            requestedSourceLanguageCode = sourceLanguagePref,
                                            ocrSourceLanguageCode = sourceLanguageCode,
                                        )
                                        // 이미지 번역은 원문 언어를 모델이 판정했다 — TTS 목소리 등이 참조하는 마지막 원문 언어로 남긴다
                                        if (image) transaction.resolvedSourceLanguageCode?.let {
                                            preferenceRepository.update(PreferenceRepository.LAST_USED_SOURCE_LANGUAGE_CODE, it)
                                        }
                                        // 번역 한 건에 한 번 센다 — 손을 뗀 뒤 도착해 창이 뜨지 않아도 번역은 한 것이다.
                                        // 번역창 컴포저블에서 세면 다시 그릴 때마다 중복될 수 있다.
                                        analyticsRepository.translationReport(transaction, textDetectMode)

                                        val motionEventState = motionEventFlow.first()
                                        if (motionEventState == MotionEvent.ACTION_DOWN || motionEventState == MotionEvent.ACTION_MOVE) {
                                            translateStatusFlow.value = TranslateStatus.Translated
                                            Timber.tag(TAG).d("translationRepository Translated transaction $transaction")

                                            TranslationView.INSTANCE.cast(
                                                applicationContext,
                                                transaction,
                                                pointerPositionedVisionText
                                            )
                                            pointerPositionedTranslationFlow.value = transaction
                                        }
                                    }
                            }
                        } else {
                            targetPendingFlow.value = false
                        }
                    } ?: run { targetPendingFlow.value = false }
                }
        }
    }

    /**
     * 대상 자리를 잘라 Claude 에 보낸다(AI 이미지 번역, §25). 포인터 모드는 화면 폭 전체 × 대상(줄·문단·띠) 높이로 자르고 포인터 자리에
     * 표시를 그린다. 영역 선택은 영역을 그대로 자른다.
     */
    private suspend fun requestImageTranslation(
        visionResult: com.galaxy.airviewdictionary.data.local.vision.model.Transaction,
        target: VisionText,
        pointer: Point?,
        sourceLanguageCode: String,
        targetLanguageCode: String,
    ): TranslationResponse {
        val mode = textDetectMode
        val box = target.boundingBox
        val crop = withContext(Dispatchers.Default) {
            if (visionResult.image is ImageTargets.Area) ImageCrop.area(visionResult.bitmap, box)
            else ImageCrop.strip(
                visionResult.bitmap, box.top, box.bottom, lineHeightOf(target), pointer,
                marginRatio = if (target is Paragraph) ImageCrop.PARAGRAPH_MARGIN else ImageCrop.LINE_MARGIN,
            )
        } ?: return TranslationResponse.Error(NoTextInImageException())
        return try {
            translationRepository.requestImage(sourceLanguageCode, targetLanguageCode, crop, mode)
        } finally {
            crop.recycle()
        }
    }

    /**
     * 이미지 번역(§25)의 단어·문장 모드는 대상이 줄·문단이라, 같은 줄·문단 안에서 다른 단어·문장으로 옮겨도 대상이 같다([isSameTarget]).
     * 포인터가 다른 줄로 가거나(세로로 줄 높이의 [IMAGE_MOVE_LINE_RATIO] 배 넘게) 가로로 줄 높이의 [IMAGE_MOVE_WORD_RATIO](단어)·
     * [IMAGE_MOVE_SENTENCE_RATIO](문장) 배 넘게 옮기면 새 대상으로 본다 — 모델이 옮긴 표시를 보고 다시 고른다. 같은 단어 안에서 크게 옮기면 같은
     * 단어를 한 번 더 청할 수 있다(글자 위치를 모른다).
     */
    private fun movedWithinImageTarget(last: TargetLookup.Found, now: TargetLookup.Found): Boolean {
        val image = visionResultFlow.value?.image ?: return false
        if (image is ImageTargets.Area) return false
        val mode = textDetectMode
        if (mode != TextDetectMode.WORD && mode != TextDetectMode.SENTENCE) return false
        val target = now.visionText ?: return false
        val from = last.position ?: return false
        val to = now.position ?: return false
        val lineHeight = (if (target is Paragraph) target.lines.find { it.boundingBox.contains(to.x, to.y) }?.boundingBox?.height() else null)
            ?: if (image == ImageTargets.PointerBand) IMAGE_BAND_HALF_WORD_DP.dp.toPx(applicationContext) else lineHeightOf(target)
        if (kotlin.math.abs(to.y - from.y) > lineHeight * IMAGE_MOVE_LINE_RATIO) return true
        val ratio = if (mode == TextDetectMode.WORD) IMAGE_MOVE_WORD_RATIO else IMAGE_MOVE_SENTENCE_RATIO
        return kotlin.math.abs(to.x - from.x) > lineHeight * ratio
    }

    /** 대상의 줄 높이 — 자를 때 위아래 여백의 기준이다. */
    private fun lineHeightOf(target: VisionText): Int = when (target) {
        is Paragraph -> target.lines.map { it.boundingBox.height() }.average().takeIf { !it.isNaN() }?.toInt() ?: target.boundingBox.height()
        else -> target.boundingBox.height()
    }

    /**
     * 포인터가 머무는 위치의 번역 결과.
     */
    private val pointerPositionedTranslationFlow = MutableStateFlow<Transaction?>(null)

    /**
     * 지금 번역을 시도 중인 대상. 요청을 보내는 시점에 새로 만들어 채운다.
     *
     * 번역창은 이 대상과 [pointerPositionedTranslationFlow] 의 결과가 같은 신원일 때만 뜬다.
     * 텍스트 내용으로 짝을 맞추지 않는다 — 킷이 돌려준 원문이 OCR 텍스트와 같다는 보장이 없다
     * (예전 AI 이미지 경로에서는 늘 달랐다. .docs/vision-engine-design.md §21 에서 걷어냈다).
     */
    private val currentTargetFlow = MutableStateFlow<TranslationTarget?>(null)

    /** 번역 시도마다 하나씩 올라가는 일련번호. [TranslationTarget.id] 가 된다. */
    private var targetSequence = 0L

    /**
     * 두 인식 결과가 같은 대상을 다시 잡은 것인가. 같으면 새 번역 요청을 보내지 않는다.
     *
     * 텍스트로 비교하면 안 된다 — ML Kit 에 인식기가 없는 문자(아랍어·페르시아어·태국어 등)는
     * 같은 화면을 다시 캡처할 때마다 다른 쓰레기를 내놓는다.
     * ("=ll ০ 5 l ৩cgএ" → "3১ 9 d.০৬9 ৬৬]" → ">LJl J9%IJ০l" — 2026-09-22 실측)
     * 그래서 사용자가 같은 문단에 머물러 있어도 캡처마다 새 대상이 되고, 그때마다 유료 API
     * 요청이 한 번씩 나간다(10초 동안 4회 관측). 화면이 그대로면 기하는 캡처가 달라져도 그대로다.
     *
     * 판정은 겹침 비율(IoU)로 한다. 단어 모드에서 이웃 단어는 박스가 떨어져 있어 다른 대상이 되고,
     * 화면이 스크롤되면 박스가 움직여 역시 다른 대상이 된다.
     */
    private fun isSameTarget(old: VisionText?, new: VisionText?): Boolean {
        if (old == null || new == null) return (old == null) == (new == null)
        val a = old.boundingBox
        val b = new.boundingBox
        val overlap = Rect(a)
        if (!overlap.intersect(b)) return false
        val overlapArea = overlap.width().toLong() * overlap.height()
        val unionArea = a.width().toLong() * a.height() +
                b.width().toLong() * b.height() - overlapArea
        if (unionArea <= 0L) return false
        return overlapArea.toFloat() / unionArea >= SAME_TARGET_MIN_OVERLAP
    }

    /**
     * 킷이 보고한 결과를 화면·TTS·답장·애널리틱스가 그대로 믿고 쓸 수 있게 확정한다.
     *
     * 확정이 여기 한 곳에 모여 있어야 하는 이유: 킷마다 아는 것이 다르다.
     * 원문 언어를 판정해 돌려주는 킷도 있고 아닌 킷도 있다. 이 차이를 여기서 한 번 흡수하지 않으면
     * 소비처마다 "킷 값이냐 OCR 값이냐"를 따로 판단하게 되고, 그러다 조용히 어긋난다.
     *
     * @param ocrSourceLanguageCode OCR 텍스트로 앱이 판정한 언어. 킷이 판정하지 못했을 때만 쓴다.
     */
    private fun confirmTransaction(
        target: TranslationTarget,
        kitResult: Transaction,
        requestedSourceLanguageCode: String,
        ocrSourceLanguageCode: String?,
    ): Transaction = Transaction(
        targetId = target.id,
        requestedSourceLanguageCode = requestedSourceLanguageCode,
        // 킷이 판정했으면 그 값을, 아니면 OCR 판정값을 쓴다.
        // "auto"/"und" 는 언어가 아니므로 판정 못 한 것으로 본다 — 고정 영역과 같은 규칙이다(TranslationSourceLanguage).
        resolvedSourceLanguageCode = TranslationSourceLanguage.resolved(kitResult.resolvedSourceLanguageCode, ocrSourceLanguageCode),
        targetLanguageCode = kitResult.targetLanguageCode,
        // 킷이 원문을 못 돌려줬으면 OCR 값으로 대신한다.
        sourceText = kitResult.sourceText?.takeIf { it.isNotBlank() }
            ?: target.visionText.representation,
        translationKitType = kitResult.translationKitType,
        resultText = kitResult.resultText,
        modelName = kitResult.modelName,
    )


    ////////////////////////////////////////////////////////////////////////////////////////////////
    //                                                                                            //
    //                                        TranslationState                                    //
    //                                                                                            //
    ////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * dismiss 제어를 위한 커맨드
     */
    private val dismissRunningCommandFlow = MutableStateFlow(DismissRunningCommand.RESUME)

    fun resumeDismissRunning() {
        dismissRunningCommandFlow.value = DismissRunningCommand.RESUME
    }

    fun pauseDismissRunning() {
        dismissRunningCommandFlow.value = DismissRunningCommand.PAUSE
    }

    fun rerunDismissRunning() {
        dismissRunningCommandFlow.value = DismissRunningCommand.RERUN
    }

    private data class TranslationStateData(
        val target: TranslationTarget?,
        val translation: Transaction?,
        val motionEvent: Int?,
        val ttsStatus: TTSStatus,
        val dismissRunningCommand: DismissRunningCommand
    )

    /**
     * 포인터 포지션의 VisionText,
     * 포인터 포지션의 Translation,
     * 포인터 MotionEvent,
     * TTS 재생상태,
     * dismiss 제어 커맨드,
     * dismiss delay time
     * 위 6가지 상태를 종합적으로 고려하여 TranslationView 에서 사용할 데이타를 발행한다.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val translationFlow: Flow<Pair<VisionText, Transaction>?> = combine(
        currentTargetFlow,
        pointerPositionedTranslationFlow,
        motionEventFlow,
        ttsRepository.ttsStatusFlow,
        dismissRunningCommandFlow
    ) { target, translation, motionEvent, ttsStatus, dismissRunningCommand ->
        TranslationStateData(target, translation, motionEvent, ttsStatus, dismissRunningCommand)
    }.flatMapLatest { (target, translation, motionEvent, ttsStatus, dismissRunningCommand) ->



        if (motionEvent == null) {
            emptyFlow()
        }

        // MotionEvent.ACTION_UP인 경우 일정시간 후 null emit
        else if (motionEvent == MotionEvent.ACTION_UP) {
            // TTS 재생 중이 아니라면
            if (ttsStatus != TTSStatus.Playing) {
                // DismissRunningCommand.RESUME 이라면 일정시간 후 null emit.
                if (dismissRunningCommand == DismissRunningCommand.RESUME) {
                    val translationCloseDelay = preferenceRepository.translationCloseDelayFlow.first()
                    delay(translationCloseDelay)
                    flowOf(null)
                }
                // DismissRunningCommand.PAUSE 이라면 아무 데이타도 발행하지 않음.
                else if (dismissRunningCommand == DismissRunningCommand.PAUSE) {
                    emptyFlow()
                }
                // DismissRunningCommand.RERUN 이라면 아무 데이타도 발행하지 않고 DismissRunningCommand.RESUME 상태로 변경해줌.
                else {
                    resumeDismissRunning()
                    emptyFlow()
                }
            }
            // TTS 재생 중 이라면 TTS 종료 대기. 아무 데이타도 발행하지 않음.
            else {
                emptyFlow()
            }
        }

        // MotionEvent.ACTION_UP이 아닌 경우
        else {
            // TargetHandle 위치의 텍스트 번역이 된 경우
            // 이 번역이 지금 시도 중인 대상의 것인지 신원으로 본다.
            // 텍스트 내용으로 비교하지 않는다 — 킷이 돌려준 원문이 OCR 텍스트와 다르면
            // 아래 else 로 떨어져 번역창을 바로 닫는다.
            if (
                target != null &&
                translation != null &&
                translation.targetId == target.id
            ) {
                // TTS 재생 중 이라면 stop.
                if (ttsStatus == TTSStatus.Playing) {
                    // 여기에서 stopTTS() 를 하면 Automatic TTS playback 기능이 제대로 작동하지 않음
//                    ttsRepository.stopTTS()
                }
                // 번역 데이타를 emit.
                flowOf(Pair(target.visionText, translation))
            }
            // TargetHandle 위치의 텍스트 번역이 없는 경우
            else {
                // TTS 재생 중 이라면 stop.
                if (ttsStatus == TTSStatus.Playing) {
                    ttsRepository.stopTTS()
                }
                // null emit.
                flowOf(null)
            }
        }
    }


    ////////////////////////////////////////////////////////////////////////////////////////////////
    //                                                                                            //
    //                              AreaSelectionView, FixedAreaView                              //
    //                                                                                            //
    ////////////////////////////////////////////////////////////////////////////////////////////////

    val areaSelectingStateFlow = MutableStateFlow(false)


    ////////////////////////////////////////////////////////////////////////////////////////////////
    //                                                                                            //
    //                                             TTS                                            //
    //                                                                                            //
    ////////////////////////////////////////////////////////////////////////////////////////////////

    // 읽기 대상 설정에 따라 목소리를 맞출 언어. (소스: 번역 시 감지된 언어, 타겟: 번역 대상 언어)
    private val pointerPositionReadLanguageCodeFlow = combine(
        pointerPositionedTranslationFlow,
        preferenceRepository.ttsReadTargetFlow,
    ) { translation, readTarget ->
        when (readTarget) {
            TTSReadTarget.SOURCE -> translation?.resolvedSourceLanguageCode
            TTSReadTarget.TARGET -> translation?.targetLanguageCode
        }
    }.distinctUntilChanged()

    private fun collectTranslationVoiceFlow() {
        viewModelScope.launch {
            combine(
                preferenceRepository.ttsOrderedVoiceNamesFlow.distinctUntilChanged(),
                pointerPositionReadLanguageCodeFlow.filterNotNull().distinctUntilChanged()
            ) { orderedVoiceNames, readLanguageCode ->
                Pair(orderedVoiceNames, readLanguageCode)
            }
                .collect { (orderedVoiceNames, readLanguageCode) ->
                    Timber.tag(TAG).i("Ordered Voice Names: $orderedVoiceNames")
                    Timber.tag(TAG).i("Read Language Code: $readLanguageCode")
                    // 우선순위 목록에서 먼저 찾고, 목록에 해당 언어의 목소리가 없으면 기기 목소리 전체에서 찾는다
                    val matchingVoiceName = orderedVoiceNames.firstOrNull { voiceName ->
                        voiceNameMatchesLanguage(voiceName, readLanguageCode)
                    } ?: ttsRepository.availableVoicesFlow.filterNotNull().first().map { voice -> voice.name }.firstOrNull { voiceName ->
                        voiceNameMatchesLanguage(voiceName, readLanguageCode)
                    }
                    Timber.tag(TAG).i("matchingVoiceName: $matchingVoiceName")
                    matchingVoiceName?.let {
                        ttsRepository.setVoice(matchingVoiceName)
                    }
                }
        }
    }

    /**
     * 읽기 대상 설정에 따라 번역의 소스 또는 타겟 텍스트를,
     * 그 텍스트의 언어에 맞는 목소리로 읽는다.
     * (소스 언어가 auto 면 번역 시 감지된 언어를 사용한다)
     */
    fun playTTS(translation: Transaction) {
        val (text, languageCode) = when (ttsReadTarget) {
            TTSReadTarget.SOURCE -> translation.sourceText to translation.resolvedSourceLanguageCode
            TTSReadTarget.TARGET -> translation.resultText to translation.targetLanguageCode
        }
        if (text == null) return
        ttsRepository.playTTSForLanguage(text, languageCode?.takeIf { it != "auto" }, ttsSpeechRate)
    }


    ////////////////////////////////////////////////////////////////////////////////////////////////
    //                                                                                            //
    //                                           구매 유도                                         //
    //                                                                                            //
    ////////////////////////////////////////////////////////////////////////////////////////////////

    fun increaseTrialCount(): Int {
        return secureRepository.increaseTrialCount()
    }

    private fun collectAdGateInfo() {
        // 참여도 분석(elapsed*)의 기준점인 최초 사용 시각을 세션 시작 시 확정한다.
        viewModelScope.launch {
            UsageInfo.getFirstUseTime(applicationContext)
        }

        // 광고 로드 연속 실패로 부여된 게이트 억제 상태를 메모리로 올린다.
        // AdGateState 는 프로세스 전역 object 라 앱을 껐다 켜면 비어 있다.
        viewModelScope.launch {
            AdGateState.hydrate(
                streak = preferenceRepository.adLoadFailureStreakFlow.first(),
                suppressedUntil = preferenceRepository.adGateSuppressedUntilFlow.first(),
            )
        }

        // 번역 카운트 통계 (앱 리뷰 유도 및 사용량 통계용)
        viewModelScope.launch {
            pointerPositionedTranslationFlow
                .filterNotNull()
                .filter { translation -> translation.resultText != null }
                .distinctUntilChanged { old, new -> old.sourceText == new.sourceText }
                .collect {
                    val trialCount = increaseTrialCount()
                    if (
                        trialCount == 100
                        || trialCount == 200
                        || trialCount == 300
                        || trialCount == 500
                    ) {
                        val hoursTaken = UsageInfo.elapsedHoursSinceFirstUse(applicationContext)
                        analyticsRepository.hoursTakenReport(trialCount, hoursTaken)
                    } else if (trialCount % 1000 == 0) {
                        val daysTaken = UsageInfo.elapsedDaysSinceFirstUse(applicationContext)
                        analyticsRepository.daysTakenReport(trialCount, daysTaken)
                    }
                }
        }

        /**
         * 광고 게이트:
         *   번역 수행 시, 광고 시청/5분 사용권으로 사용 가능한 상태가 아니고(AdGateState.isUsable() == false)
         *   설정 화면 상태가 아니면 리워드 광고를 띄운다.
         *   광고를 끝까지 보면 이번 세션 동안 다시 뜨지 않고, 로드/표시 실패면 5분 유예,
         *   스킵(중간 닫기·홈키 중단)이면 짧은 쿨다운 뒤 다시 뜬다(Remote Config 로 조정).
         */
        viewModelScope.launch {
            pointerPositionedTranslationFlow
                .filterNotNull()
                .filter { translation -> translation.resultText != null }
                .distinctUntilChanged { old, new -> old.sourceText == new.sourceText }
                .collect {
                    if (!AdGateState.isUsable() && !SettingsActivity.liveStateFlow.value && !AdGateActivity.liveStateFlow.value) {
                        // 일정 시간 후가 아니라, 핸들에서 손가락을 떼어(ACTION_UP) 번역이 종료된 시점에 광고 게이트를 연다.
                        // (결과 수신 전에 이미 손을 뗀 상태라면 즉시 연다)
                        motionEventFlow.first { motionEvent ->
                            motionEvent == MotionEvent.ACTION_UP
                                    || motionEvent == MotionEvent.ACTION_CANCEL
                                    || motionEvent == MotionEvent.INVALID_POINTER_ID
                        }
                        showAdGate()
                    }
                }
        }
    }

    fun showAdGate() {
        // 리워드 광고 표시를 위해 투명 광고 게이트 액티비티를 연다.
        AdGateActivity.start(applicationContext)
    }

    init {
        Timber.tag(TAG).i("#### init ####")
        secureRepository.acquire()
        captureRepository.acquire()
        translationRepository.acquire()
        ttsRepository.acquire()
        collectServiceOperationInfoFlow()
        collectAdGateInfo()
        collectPreference()
        collectTargetHandleMotionEvent()
        collectVisionTextForTranslationView()
        collectTranslationVoiceFlow()
    }

    override fun onCleared() {
        lastPointerStoppedPosition = null
        secureRepository.release()
        captureRepository.release()
        translationRepository.release()
        ttsRepository.release()
        super.onCleared()
    }
}

/**
 * 같은 대상으로 볼 겹침 비율(IoU). 캡처 간 박스 흔들림은 흡수하고,
 * 이웃 문단·줄로 옮긴 것은 다른 대상으로 잡을 만큼의 값.
 */
private const val SAME_TARGET_MIN_OVERLAP = 0.8f

/** 모델 팩이 아직 없을 때 이미지 번역이 자르는 포인터 위아래 높이(dp) — 단어 모드는 한 줄 남짓, 문장·문단 모드는 몇 줄(§25). */
private const val IMAGE_BAND_HALF_WORD_DP = 28
private const val IMAGE_BAND_HALF_DP = 84

/** 이미지 번역의 단어·문장 모드에서 새 대상으로 보는 포인터 이동(줄 높이 배수) — [TargetHandleViewModel.movedWithinImageTarget]. */
private const val IMAGE_MOVE_LINE_RATIO = 0.8
private const val IMAGE_MOVE_WORD_RATIO = 1.5
private const val IMAGE_MOVE_SENTENCE_RATIO = 4.0

/** 글 없는 대상 상자(영역·띠)의 번역창 글자 높이 기준(dp). */
private const val IMAGE_BOX_FONT_HEIGHT_DP = 18
