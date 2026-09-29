package com.galaxy.airviewdictionary.ui.screen.main


import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.content.res.Configuration
import android.graphics.Point
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import android.view.WindowInsets
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.ScrollState
import androidx.compose.ui.semantics.Role
import com.galaxy.airviewdictionary.data.remote.translation.TranslationContextMode
import com.galaxy.airviewdictionary.data.remote.translation.TranslationDomain
import com.galaxy.airviewdictionary.data.remote.translation.TranslationStrength
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FiberNew
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VoiceChat
import androidx.compose.material.icons.outlined.Api
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.toColorInt
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.galaxy.airviewdictionary.BuildConfig
import com.galaxy.airviewdictionary.R
import com.galaxy.airviewdictionary.data.local.capture.CaptureRepository
import com.galaxy.airviewdictionary.data.local.screen.ScreenInfoHolder
import com.galaxy.airviewdictionary.data.local.preference.PreferenceRepository
import com.galaxy.airviewdictionary.data.local.tts.TTSReadTarget
import com.galaxy.airviewdictionary.data.local.vision.TextDetectMode
import com.galaxy.airviewdictionary.data.remote.translation.ImageTranslation
import com.galaxy.airviewdictionary.data.remote.translation.TranslationKitType
import com.galaxy.airviewdictionary.data.remote.translation.claude.ClaudeKit
import com.galaxy.airviewdictionary.data.remote.translation.deepl.DeepLKit
import com.galaxy.airviewdictionary.data.remote.translation.gemini.GeminiKit
import com.galaxy.airviewdictionary.data.remote.translation.openai.OpenAiKit
import com.galaxy.airviewdictionary.extensions.finishService
import com.galaxy.airviewdictionary.extensions.gotoStore
import com.galaxy.airviewdictionary.extensions.startActivitySafely
import com.galaxy.airviewdictionary.extensions.toPx
import com.galaxy.airviewdictionary.extensions.vibrate
import com.galaxy.airviewdictionary.ui.common.AutoRefreshEveryMinute
import com.galaxy.airviewdictionary.ui.common.fontDimensionResource
import com.galaxy.airviewdictionary.ui.common.safeScaffoldContentWindowInsets
import com.galaxy.airviewdictionary.ui.screen.AVDActivity
import com.galaxy.airviewdictionary.ui.screen.intro.SplashActivity
import com.galaxy.airviewdictionary.ui.screen.overlay.dialog.DialogView
import com.galaxy.airviewdictionary.ui.screen.overlay.languagelist.LanguageListView
import com.galaxy.airviewdictionary.ui.screen.overlay.menubar.MenuBar
import com.galaxy.airviewdictionary.ui.screen.overlay.menubar.MenuBarView
import com.galaxy.airviewdictionary.ui.screen.overlay.menubar.MenuConfig
import com.galaxy.airviewdictionary.ui.screen.overlay.settings.HelpTextDetectModeView
import com.galaxy.airviewdictionary.ui.screen.overlay.settings.HelpTranslationKitView
import com.galaxy.airviewdictionary.ui.screen.overlay.settings.SliderDialogView
import com.galaxy.airviewdictionary.ui.screen.overlay.targethandle.TargetHandleView
import com.galaxy.airviewdictionary.ui.screen.overlay.voicelist.VoiceListView
import com.galaxy.airviewdictionary.ui.screen.permissions.ScreenCapturePermissionRequesterActivity
import com.galaxy.airviewdictionary.ui.theme.ScreenTranslatorTheme
import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.ceil
import kotlin.math.round
import kotlin.math.roundToInt


@AndroidEntryPoint
class SettingsActivity : AVDActivity() {

    companion object {

        // 공개 가능한 소스가 게시되는 퍼블릭 저장소
        private const val GITHUB_REPO_URL = "https://github.com/AidanPark/android-screen-translator"

        // 웹 사용 가이드 (모드별 데모 영상 + FAQ)
        private const val HOW_TO_USE_GUIDE_URL = "https://aidanpark.github.io/screen-translator/guide/"

        // 개인정보처리방침 (Play Console에 등록된 주소와 동일)
        private const val PRIVACY_POLICY_URL = "https://aidanpark.github.io/privacy.html"

        fun start(context: Context) {
            val intent = Intent(context, SettingsActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        val liveStateFlow = MutableStateFlow(false)

        val menuBarViewSettlePositionFlow = MutableStateFlow<Point?>(null)
    }

    private val viewModel: SettingsViewModel by viewModels()

    private val settingFloatFlow = MutableStateFlow(1.0f)

    private val settingStringFlow = MutableStateFlow("")

//    private val snackMessageFlow = MutableStateFlow("")

    @OptIn(ExperimentalSharedTransitionApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        ScreenInfoHolder.collectAndStoreScreenInfo(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val isDarkMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val colorWhite = "#FFf2f1f4".toColorInt()
            val colorBlack = "#FF010102".toColorInt()
            window.decorView.setOnApplyWindowInsetsListener { view, insets ->
                val statusBarInsets = insets.getInsets(WindowInsets.Type.statusBars())
                view.setBackgroundColor(if (isDarkMode) colorBlack else colorWhite)
                view.setPadding(0, statusBarInsets.top, 0, 0)
                insets
            }
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !isDarkMode
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = !isDarkMode
        }

        setContent {
            ScreenTranslatorTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val lifecycleOwner = LocalLifecycleOwner.current

                    val isDarkMode = isSystemInDarkTheme()
                    val backgroundColor = if (isDarkMode) Color(0xFF010102) else Color(0xFFf2f1f4)

                    val snackBarHostState = remember { SnackbarHostState() }

//                    LaunchedEffect(snackMessageFlow) {
//                        snackMessageFlow.collect { message ->
//                            if (message.isNotEmpty()) {
//                                lifecycleScope.launch {
//                                    snackBarHostState.showSnackbar(
//                                        message = message,
//                                        duration = SnackbarDuration.Short,
////                                        actionLabel = "snackbar"
//                                    )
//                                }
//                            }
//                        }
//                    }

                    Scaffold(
                        // 기본값(ScaffoldDefaults.contentWindowInsets)은 Compose 인셋을 읽어
                        // 위장 프레임워크 기기에서 NoSuchMethodError 를 일으킨다.
                        contentWindowInsets = safeScaffoldContentWindowInsets,
                        snackbarHost = { SnackbarHost(hostState = snackBarHostState) }
                    ) { _paddingValues: PaddingValues ->
                        val paddingValues = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            PaddingValues()
                        } else {
                            _paddingValues
                        }
                        Box(
                            modifier = Modifier
                                .background(backgroundColor)
                                .padding(paddingValues)
                        ) {
                            Settings(
                                paddingValues = paddingValues
                            )
                        }
                    }

                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (LanguageListView.INSTANCE.isRunning.get()) {
                    LanguageListView.INSTANCE.clear()
                } else if (HelpTextDetectModeView.INSTANCE.isRunning.get()) {
                    HelpTextDetectModeView.INSTANCE.clear()
                } else if (HelpTranslationKitView.INSTANCE.isRunning.get()) {
                    HelpTranslationKitView.INSTANCE.clear()
                } else if (SliderDialogView.INSTANCE.isRunning.get()) {
                    closeTranslation()
                    SliderDialogView.INSTANCE.clear()
                } else if (VoiceListView.INSTANCE.isRunning.get()) {
                    VoiceListView.INSTANCE.clear()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val isDarkMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val colorWhite = "#FFf2f1f4".toColorInt()
            val colorBlack = "#FF010102".toColorInt()
            window.statusBarColor = if (isDarkMode) colorBlack else colorWhite
            window.navigationBarColor = if (isDarkMode) colorBlack else colorWhite
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
    }

    override fun onResume() {
        super.onResume()

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val areNotificationsEnabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || notificationManager.areNotificationsEnabled()
        val canDrawOverlays = android.provider.Settings.canDrawOverlays(applicationContext)

        if (!areNotificationsEnabled || !canDrawOverlays) {
            val intent = Intent(applicationContext, SplashActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            finish()
            return
        }

        liveStateFlow.value = true

        lifecycleScope.launch {
            if (!MenuBarView.INSTANCE.isRunning.get()) {
                MenuBarView.INSTANCE.cast(applicationContext)
            }
            if (!TargetHandleView.INSTANCE.isRunning.get()) {
                TargetHandleView.INSTANCE.cast(applicationContext)
//                snackMessageFlow.value = getString(R.string.snack_message_start_foreground_service)
            }

            delay(1000)
            if (isActive) {
                if (CaptureRepository.mediaProjectionToken == null) {
                    // 화면 캡처 권한을 요청
                    val intent = Intent(this@SettingsActivity, ScreenCapturePermissionRequesterActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }
            }
        }
    }

    override fun onPause() {
        closeTranslation()
        LanguageListView.INSTANCE.clear()
        SliderDialogView.INSTANCE.clear()
        VoiceListView.INSTANCE.clear()
        HelpTextDetectModeView.INSTANCE.clear()
        HelpTranslationKitView.INSTANCE.clear()
        liveStateFlow.value = false
        super.onPause()
    }

    private var _textDetectMode: TextDetectMode? = null

    private fun runTranslation(point: Point, textDetectMode: TextDetectMode) {
        _textDetectMode = textDetectMode
        TargetHandleView.INSTANCE.runTranslation(point, textDetectMode)
    }

    private fun closeTranslation() {
        TargetHandleView.INSTANCE.closeTranslation(_textDetectMode)
    }

    enum class MenuItemPosition {
        Single,
        Top,
        Middle,
        Bottom,
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //                                                                                            //
    //                                          Composable                                        //
    //                                                                                            //
    ////////////////////////////////////////////////////////////////////////////////////////////////
    @SuppressLint("LocalContextConfigurationRead")
    @OptIn(ExperimentalSharedTransitionApi::class)
    @Composable
    fun Settings(
        paddingValues: PaddingValues,
    ) {
        val context = LocalContext.current
        val localView = LocalView.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val coroutineScope = rememberCoroutineScope()

        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode: Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }

        val latestVersionCode by viewModel.latestVersionCodeFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = 0
        )

        val layoutDirection = LocalLayoutDirection.current
        val isRtl = layoutDirection == LayoutDirection.Rtl
        val configuration = LocalConfiguration.current
        val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT

        val menuExpandAnimationDuration = 300

        val contentPadding = 9.dp
        val cornerRound = 32.dp
        // 태블릿/폴더블 등 넓은 화면에서 설정 목록이 좌우로 늘어나지 않도록 읽기 좋은 최대 폭으로 제한하고 중앙 정렬한다. (폰은 화면보다 좁으므로 영향 없음)
        val maxContentWidth = 600.dp
        val startPadding = paddingValues.calculateLeftPadding(layoutDirection).toPx(context)

        val isDarkMode = isSystemInDarkTheme()
        val contentColor = if (isDarkMode) Color(0xFFfcfcfc) else Color(0xFF010000)
        val subContentColor = if (isDarkMode) Color(0xFFb7b7ba) else Color(0xFF626265)
        val switchScale = 0.70f
        val switchThumbColor = if (isDarkMode) Color.Black else Color.White
        val switchTrackColor = if (isDarkMode) Color(0xFF6a91b2) else Color(0xFF446987)
        val dividerColor = if (isDarkMode) Color(0xFF343434) else Color(0xFFd5d5d5)
        val buttonColor = if (isDarkMode) Color(0xFFfafafa) else Color(0xFF171717)

        // Pointer docking delay
        val dockingDelayTextOffset = remember { mutableStateOf(Point(0, 0)) }
        val dockingDelaySubtextOffset = remember { mutableStateOf(Point(0, 0)) }
        val dockingDelay by viewModel.preferenceRepository.dockingDelayFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = 3000L
        )

        // Haptic feedback to detection
        val dragHandleHaptic by viewModel.preferenceRepository.dragHandleHapticFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = false
        )

        // Menubar Visibility
        val menuBarVisibility by viewModel.preferenceRepository.menuBarVisibilityFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = true
        )

        // Menubar transparency
        val menuBarTransparencyTextOffset = remember { mutableStateOf(Point(0, 0)) }
        val menuBarTransparencySubtextOffset = remember { mutableStateOf(Point(0, 0)) }
        val menuBarTransparency by viewModel.preferenceRepository.menuBarTransparencyFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = 1.0f
        )

        // Menubar Composition
        val menuBarConfigTextOffset = remember { mutableStateOf(Point(0, 0)) }
        val menuBarConfigSubOffset = remember { mutableStateOf(Point(0, 0)) }
        val menuBarConfig by viewModel.preferenceRepository.menuBarConfigFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = MenuConfig.WHOLE
        )

        // Translation transparency
        val translationTransparencyTextOffset = remember { mutableStateOf(Point(0, 0)) }
        val translationTransparencySubtextOffset = remember { mutableStateOf(Point(0, 0)) }
        val translationPoint = remember { mutableStateOf(Point(0, 0)) }
        val translationTransparency by viewModel.preferenceRepository.translationTransparencyFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = 1.0f
        )

        // Translation close delay
        val translationCloseDelayTextOffset = remember { mutableStateOf(Point(0, 0)) }
        val translationCloseDelaySubtextOffset = remember { mutableStateOf(Point(0, 0)) }
        val translationCloseDelay by viewModel.preferenceRepository.translationCloseDelayFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = 1600L
        )

        // Reply transparency
        val replyTransparencyTextOffset = remember { mutableStateOf(Point(0, 0)) }
        val replyTransparencySubtextOffset = remember { mutableStateOf(Point(0, 0)) }
        val replyTransparency by viewModel.preferenceRepository.replyTransparencyFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = 1.0f
        )

        // Automatic translation playback
        val automaticTranslationPlayback by viewModel.preferenceRepository.automaticTranslationPlaybackFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = false
        )

        // TTS read target
        val ttsReadTarget by viewModel.preferenceRepository.ttsReadTargetFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = TTSReadTarget.SOURCE
        )

        // TTS Speech rate
        val ttsSpeechRateTextOffset = remember { mutableStateOf(Point(0, 0)) }
        val ttsSpeechRateIconOffset = remember { mutableStateOf(Point(0, 0)) }
        val ttsSpeechRate by viewModel.preferenceRepository.ttsSpeechRateFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = 1.0f
        )

        // TTS Voices
        val ttsAvailableVoices by viewModel.ttsRepository.availableVoicesFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = emptyList()
        )

        // TTS Voice
        val ttsCurrentVoice by viewModel.ttsRepository.currentVoiceFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = null
        )

        // Text detect mode
        val textDetectMode by viewModel.preferenceRepository.textDetectModeFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = TextDetectMode.SENTENCE
        )

        // source language
        val sourceLanguageCode by viewModel.preferenceRepository.sourceLanguageCodeFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = "auto"
        )
        val sourceLanguage = viewModel.translationRepository.getSupportedSourceLanguage(sourceLanguageCode)

        // target language
        val targetLanguageCode by viewModel.preferenceRepository.targetLanguageCodeFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = context.resources.configuration.locales.get(0).language
        )
        val targetLanguage = viewModel.translationRepository.getSupportedTargetLanguage(targetLanguageCode)

        // translationKit Type
        val kitType by viewModel.preferenceRepository.translationKitTypeFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = TranslationKitType.GOOGLE
        )

        fun getTransparencyValueText(transparency: Float): String {
            return "${ceil((1.0f - transparency) * 100).toInt()}%"
        }

        fun getSecondValueText(second: Long): String {
            return "${round(second / 1000.0 * 10) / 10} sec"
        }

        AutoRefreshEveryMinute {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // ActionBar
                Row(
                    modifier = Modifier
                        .height(58.dp)
                        .fillMaxWidth()
//                    .background(Color(0x3399ffff))
                        .padding(start = 18.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(id = R.string.settings_title),
                        color = contentColor,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 22.sp),
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .weight(1f)
                    )

                    // Share IconButton
                    IconButton(
                        onClick = {
//                        Toast.makeText(context, "Share clicked", Toast.LENGTH_SHORT).show()
                            val appPackageName = context.packageName
                            val appStoreLink = "https://play.google.com/store/apps/details?id=$appPackageName"
                            val appName = context.getString(R.string.app_name)
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "$appName: $appStoreLink")
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share via"))
                        },
                        modifier = Modifier.align(Alignment.CenterVertically)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share",
                            modifier = Modifier
                                .size(18.dp)
                                .alpha(0.75f),
                            tint = contentColor
                        )
                    }

                    // Power IconButton
                    IconButton(
                        onClick = {
                            viewModel.analyticsRepository.settingsReport(
                                dockDelay = dockingDelay.toString(),
                                haptic = dragHandleHaptic.toString(),
                                menuTransparency = (menuBarTransparency * 100).roundToInt().toString(),
                                menuComposition = menuBarConfig.name,
                                transTransparency = (translationTransparency * 100).roundToInt().toString(),
                                closeDelay = translationCloseDelay.toString(),
                                replyTransparency = (replyTransparency * 100).roundToInt().toString(),
                                autoTTS = automaticTranslationPlayback.toString(),
                                TTSVoice = ttsCurrentVoice?.name ?: "unknown",
                                TTSRate = BigDecimal(ttsSpeechRate.toDouble()).setScale(1, RoundingMode.HALF_UP).toString(),
                            )

                            coroutineScope.launch {
                                Firebase.analytics.setAnalyticsCollectionEnabled(false)
                                delay(200L)
                                finish()
//                            moveTaskToBack(true)
                                delay(200L)
                                applicationContext.finishService()
                            }
                        },
                        modifier = Modifier.align(Alignment.CenterVertically)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = "Exit App",
                            modifier = Modifier
                                .size(21.dp)
                                .alpha(0.75f),
                            tint = contentColor
                        )
                    }
                }

                // MenuBarView Area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (isPortrait) 56.dp else 38.dp)
//                    .background(Color(0x5517fa23))
                        .onGloballyPositioned { layoutCoordinates ->
                            val center = layoutCoordinates.boundsInRoot().center
                            Timber
                                .tag(TAG)
                                .d("paddingValues $paddingValues")
                            val endPadding = paddingValues
                                .calculateRightPadding(layoutDirection)
                                .toPx(context)
                            Timber
                                .tag(TAG)
                                .d("startPadding $startPadding")
                            Timber
                                .tag(TAG)
                                .d("endPadding $endPadding")
                            val posX = (endPadding - startPadding) / 2
                            Timber
                                .tag(TAG)
                                .d("posX $posX")

                            val topPadding = paddingValues
                                .calculateTopPadding()
                                .toPx(context)
                            Timber
                                .tag(TAG)
                                .d("topPadding $topPadding")
                            val posY = center.y.toInt() - topPadding - (if (isPortrait) 0.dp else 12.dp).toPx(context)
                            Timber
                                .tag(TAG)
                                .d("menuBarViewSettlePosition ${Point(posX, posY)}")

                            menuBarViewSettlePositionFlow.value = Point(posX, posY)
                        }
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = contentPadding, top = contentPadding, end = contentPadding),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Surface(
                        modifier = Modifier
                            .widthIn(max = maxContentWidth)
                            .fillMaxSize(),
                        shape = RoundedCornerShape(cornerRound),
                        color = Color.Transparent
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(viewModel.scrollState)
                        ) {
                            MenuCategory(
                                painter = painterResource(id = R.drawable.ic_drag_handle),
                                categoryName = getString(R.string.settings_menu_cat_pointer),
                                isRtl = isRtl,
                            )

                            MenuItem(
                                menuItemPosition = MenuItemPosition.Top,
                                onClick = {
                                    coroutineScope.launch {
                                        settingStringFlow.value = getSecondValueText(dockingDelay)
                                        SliderDialogView.INSTANCE.cast(
                                            applicationContext = applicationContext,
                                            initialValue = dockingDelay.toFloat(),
                                            valueRange = 1000f..15000f,
                                            steps = 13,
                                            onValueChange = { value ->
                                                Timber.tag(TAG).d("Pointer docking delay onValueChange : $value")
                                                viewModel.updateDockingDelay(value.toLong())
                                                viewModel.updateDragHandleDocking(value < 15000.0f)
                                                settingStringFlow.value = getSecondValueText(value.toLong())
                                            },
                                            menuText = Pair(getString(R.string.settings_menu_pointer_docking_delay), dockingDelayTextOffset.value),
                                            dockingDelayText = Pair(settingStringFlow, dockingDelaySubtextOffset.value),
                                            onDismissRequest = {
                                                SliderDialogView.INSTANCE.clear()
                                            },
                                        )
                                    }
                                }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 50.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    MenuText(
                                        text = getString(R.string.settings_menu_pointer_docking_delay),
                                        onTextPositioned = { offset ->
                                            dockingDelayTextOffset.value = Point(offset.x - startPadding, offset.y)
                                        },
                                    )
                                    Box(
                                        modifier = Modifier
                                            .height(38.dp)
                                            .width(80.dp)
//                                        .background(Color(0x33aaff22))
                                            .onGloballyPositioned { layoutCoordinates ->
                                                val offset = layoutCoordinates.positionOnScreen()
                                                val startPadding = paddingValues
                                                    .calculateLeftPadding(layoutDirection)
                                                    .toPx(context)
                                                val posX = offset.x.toInt() + layoutCoordinates.size.width - startPadding
                                                dockingDelaySubtextOffset.value = Point(posX, offset.y.toInt())
                                            },
                                        contentAlignment = Alignment.CenterEnd
                                    ) {
                                        if (dockingDelay < 15000.0f) {
                                            Text(
                                                modifier = Modifier.padding(end = 6.dp),
                                                text = getSecondValueText(dockingDelay),
                                                color = subContentColor,
                                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = fontDimensionResource(R.dimen.settings_menu_subtext_size)),
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.AllInclusive,
                                                contentDescription = "Pointer docking delay Infinity",
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .padding(end = 12.dp),
                                                tint = subContentColor
                                            )
                                        }
                                    }
                                }
                            }

                            MenuItem(
                                menuItemPosition = MenuItemPosition.Bottom,
                                onClick = {
                                    if (!dragHandleHaptic) {
                                        context.vibrate()
                                    }
                                    viewModel.updateDragHandleHaptic(!dragHandleHaptic)
                                }
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    MenuText(
                                        text = getString(R.string.settings_menu_haptic_feedback_to_detection),
                                    )
                                    Switch(
                                        checked = dragHandleHaptic,
                                        onCheckedChange = { value ->
                                            if (value) {
                                                context.vibrate()
                                            }
                                            viewModel.updateDragHandleHaptic(value)
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = switchThumbColor,
                                            checkedTrackColor = switchTrackColor
                                        ),
                                        modifier = Modifier
                                            .scale(switchScale)
                                            .align(Alignment.CenterVertically)
                                            .semantics {
                                                contentDescription = if (dragHandleHaptic) {
                                                    "Haptic feedback to detection on"
                                                } else {
                                                    "Haptic feedback to detection off"
                                                }
                                            }
                                    )
                                }
                            }

                            MenuCategory(
                                painter = painterResource(id = R.drawable.ic_menubar),
                                categoryName = getString(R.string.settings_menu_cat_menubar),
                                iconSize = 25.dp,
                                isRtl = isRtl,
                            )

                            MenuItem(
                                menuItemPosition = if (menuBarVisibility) MenuItemPosition.Top else MenuItemPosition.Single,
                                onClick = {
                                    viewModel.updateMenuBarVisibility(!menuBarVisibility)
                                }
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    MenuText(
                                        text = getString(R.string.settings_menu_menubar_visible),
                                    )
                                    Switch(
                                        checked = menuBarVisibility,
                                        onCheckedChange = { value ->
                                            viewModel.updateMenuBarVisibility(value)
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = switchThumbColor,
                                            checkedTrackColor = switchTrackColor
                                        ),
                                        modifier = Modifier
                                            .scale(switchScale)
                                            .align(Alignment.CenterVertically)
                                            .semantics {
                                                contentDescription = if (menuBarVisibility) {
                                                    "Show menu bar on"
                                                } else {
                                                    "Show menu bar off"
                                                }
                                            }
                                    )
                                }
                            }

                            AnimatedVisibility(
                                visible = menuBarVisibility,
                                enter = expandVertically(animationSpec = tween(menuExpandAnimationDuration)),
                                exit = shrinkVertically(animationSpec = tween(menuExpandAnimationDuration)),
                            ) {
                            MenuItem(
                                menuItemPosition = MenuItemPosition.Middle,
                                onClick = {
                                    coroutineScope.launch {
                                        settingStringFlow.value = getTransparencyValueText(menuBarTransparency)
                                        SliderDialogView.INSTANCE.cast(
                                            applicationContext = applicationContext,
                                            initialValue = 1.0f - menuBarTransparency,
                                            valueRange = 0.0f..0.50f,
                                            onValueChange = { value ->
                                                Timber.tag(TAG).d("Menubar transparency onValueChange : $value")
                                                viewModel.updateMenuBarTransparency(1.0f - value)
                                                settingStringFlow.value = getTransparencyValueText(1.0f - value)
                                            },
                                            menuText = Pair(getString(R.string.settings_menu_menubar_transparency), menuBarTransparencyTextOffset.value),
                                            menuBarVisibilityText = Pair(settingStringFlow, menuBarTransparencySubtextOffset.value),
                                            onDismissRequest = {
                                                SliderDialogView.INSTANCE.clear()
                                            },
                                        )
                                        MenuBarView.INSTANCE.cast(applicationContext, true)
                                    }
                                }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 50.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    MenuText(
                                        text = getString(R.string.settings_menu_menubar_transparency),
                                        onTextPositioned = { offset ->
                                            menuBarTransparencyTextOffset.value = Point(offset.x - startPadding, offset.y)
                                        },
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .onGloballyPositioned { layoutCoordinates ->
                                                val offset = layoutCoordinates.positionOnScreen()
                                                val startPadding = paddingValues
                                                    .calculateLeftPadding(layoutDirection)
                                                    .toPx(context)
                                                val posX = offset.x.toInt() + layoutCoordinates.size.width - startPadding
                                                menuBarTransparencySubtextOffset.value = Point(posX, offset.y.toInt())
                                            },
                                        contentAlignment = Alignment.CenterEnd
                                    ) {
                                        Text(
                                            modifier = Modifier.padding(end = 6.dp),
                                            text = getTransparencyValueText(menuBarTransparency),
                                            color = subContentColor,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = fontDimensionResource(R.dimen.settings_menu_subtext_size)),
                                        )
                                    }
                                }
                            }
                            }

                            AnimatedVisibility(
                                visible = menuBarVisibility,
                                enter = expandVertically(animationSpec = tween(menuExpandAnimationDuration)),
                                exit = shrinkVertically(animationSpec = tween(menuExpandAnimationDuration)),
                                content = {
                                    fun onClick() {
                                        coroutineScope.launch {
                                            settingStringFlow.value = menuBarConfig.name
                                            SliderDialogView.INSTANCE.cast(
                                                applicationContext = applicationContext,
                                                initialValue = when (menuBarConfig) {
                                                    MenuConfig.WHOLE -> 0.0f
                                                    MenuConfig.DETECT_MODE_LANGUAGE -> 1.0f
                                                    MenuConfig.LANGUAGE_TRANSLATION_KIT -> 2.0f
                                                    MenuConfig.LANGUAGE -> 3.0f
                                                    MenuConfig.WHOLE_SHORT -> 4.0f
                                                    MenuConfig.DETECT_MODE_LANGUAGE_SHORT -> 5.0f
                                                    MenuConfig.LANGUAGE_SHORT_TRANSLATION_KIT -> 6.0f
                                                    MenuConfig.LANGUAGE_SHORT -> 7.0f
                                                    MenuConfig.DETECT_MODE_TRANSLATION_KIT -> 8.0f
                                                    MenuConfig.DETECT_MODE -> 9.0f
                                                    MenuConfig.TRANSLATION_KIT -> 10.0f

                                                    MenuConfig.V_DETECT_MODE -> 11.0f
                                                    MenuConfig.V_DETECT_MODE_TRANSLATION_KIT -> 12.0f
                                                    MenuConfig.V_LANGUAGE -> 13.0f
                                                    MenuConfig.V_LANGUAGE_TRANSLATION_KIT -> 14.0f
                                                    MenuConfig.V_DETECT_MODE_LANGUAGE -> 15.0f
                                                    MenuConfig.V_WHOLE -> 16.0f
                                                },
                                                valueRange = 0.0f..16.0f,
                                                steps = 15,
                                                onValueChange = { value ->
                                                    Timber.tag(TAG).d("onValueChange $value")
                                                    val updatedMenuBarConfig =
                                                        when (value.roundToInt().toFloat()) {
                                                            0.0f -> MenuConfig.WHOLE
                                                            1.0f -> MenuConfig.DETECT_MODE_LANGUAGE
                                                            2.0f -> MenuConfig.LANGUAGE_TRANSLATION_KIT
                                                            3.0f -> MenuConfig.LANGUAGE
                                                            4.0f -> MenuConfig.WHOLE_SHORT
                                                            5.0f -> MenuConfig.DETECT_MODE_LANGUAGE_SHORT
                                                            6.0f -> MenuConfig.LANGUAGE_SHORT_TRANSLATION_KIT
                                                            7.0f -> MenuConfig.LANGUAGE_SHORT
                                                            8.0f -> MenuConfig.DETECT_MODE_TRANSLATION_KIT
                                                            9.0f -> MenuConfig.DETECT_MODE
                                                            10.0f -> MenuConfig.TRANSLATION_KIT

                                                            11.0f -> MenuConfig.V_DETECT_MODE
                                                            12.0f -> MenuConfig.V_DETECT_MODE_TRANSLATION_KIT
                                                            13.0f -> MenuConfig.V_LANGUAGE
                                                            14.0f -> MenuConfig.V_LANGUAGE_TRANSLATION_KIT
                                                            15.0f -> MenuConfig.V_DETECT_MODE_LANGUAGE
                                                            16.0f -> MenuConfig.V_WHOLE

                                                            else -> MenuConfig.WHOLE
                                                        }
                                                    viewModel.updateMenuBarConfig(updatedMenuBarConfig)
                                                    settingStringFlow.value = updatedMenuBarConfig.name
                                                },
                                                menuText = Pair(getString(R.string.settings_menu_menubar_composition), menuBarConfigTextOffset.value),
                                                menuBarConfigText = Pair(settingStringFlow, menuBarConfigSubOffset.value),
                                                onDismissRequest = {
                                                    SliderDialogView.INSTANCE.clear()
                                                },
                                            )
                                        }
                                    }

                                    Box(
                                        modifier = Modifier
                                            .wrapContentSize()
                                            .background(
                                                color = if (isDarkMode) Color(0xFF171717) else Color(0xFFfafafa),
                                                shape = RoundedCornerShape(bottomStart = cornerRound, bottomEnd = cornerRound)
                                            )
                                    ) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(horizontal = 18.dp),
                                            thickness = 0.7.dp,
                                            color = dividerColor
                                        )

                                        Button(
                                            onClick = { onClick() },
                                            colors = ButtonDefaults.textButtonColors(contentColor = buttonColor),
                                            shape = RoundedCornerShape(bottomStart = cornerRound, bottomEnd = cornerRound),
                                            modifier = Modifier.wrapContentSize()
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(min = 60.dp)
                                                    .onGloballyPositioned { layoutCoordinates ->
                                                        val offset = layoutCoordinates.positionOnScreen()
                                                        val startPadding = paddingValues
                                                            .calculateLeftPadding(layoutDirection)
                                                            .toPx(context)
                                                        val posX = offset.x.toInt() + layoutCoordinates.size.width - startPadding
                                                        menuBarConfigSubOffset.value = Point(posX, offset.y.toInt())
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                MenuText(
                                                    text = getString(R.string.settings_menu_menubar_composition),
                                                    maxLines = 2,
                                                    onTextPositioned = { offset ->
                                                        menuBarConfigTextOffset.value = Point(offset.x - startPadding, offset.y)
                                                    },
                                                    modifier = Modifier
                                                        .align(Alignment.TopStart)
                                                        .fillMaxWidth(0.5f)
                                                        .padding(top = 14.dp, start = 5.dp)
                                                )
                                                val scaleFactor = 0.48f
                                                Box(
                                                    modifier = Modifier
                                                        .align(Alignment.TopEnd)
                                                        .padding(top = 15.dp, bottom = 15.dp, end = 8.dp)
                                                        .wrapContentSize()
                                                        .layout { measurable, constraints ->
                                                            val placeable = measurable.measure(constraints)

                                                            // 스케일링된 크기 계산
                                                            val width = (placeable.width * scaleFactor).toInt()
                                                            val height = (placeable.height * scaleFactor).toInt()

                                                            layout(width, height) {
                                                                placeable.placeRelative(0, 0)
                                                            }
                                                        }
                                                ) {
                                                    MenuBar(
                                                        menuConfig = menuBarConfig,
                                                        scaleFactor = scaleFactor,
                                                        shadowPadding = 0.dp,
                                                        borderWidth = 1.2.dp,
                                                        textDetectMode = textDetectMode,
                                                        sourceLanguageCode = sourceLanguageCode,
                                                        sourceLanguage = sourceLanguage,
                                                        targetLanguageCode = targetLanguageCode,
                                                        targetLanguage = targetLanguage,
                                                        translationKitType = kitType,
                                                        isSwappable = { sourceLanguageCode, targetLanguageCode, kitType ->
                                                            viewModel.isLanguageSwappable(sourceLanguageCode, targetLanguageCode, kitType)
                                                        },
                                                        modifier = Modifier.semantics {
                                                            contentDescription = "Menu composition"
                                                        }
                                                    )
                                                    // MenuBar 위치 클릭시 동작
                                                    Box(
                                                        modifier = Modifier
                                                            .matchParentSize()
                                                            .clickable(
                                                                onClick = { onClick() },
                                                                indication = null,  // 누름 효과 없애기
                                                                interactionSource = remember { MutableInteractionSource() }
                                                            )
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            )

                            MenuCategory(
                                icon = Icons.Outlined.Api,
                                categoryName = getString(R.string.settings_menu_cat_api_key),
                                isRtl = isRtl,
                            )

                            var showDeepLApiKeyDialog by remember { mutableStateOf(false) }
                            var deepLKeyActivated by remember { mutableStateOf(DeepLKit.getStoredApiKey(context) != null) }
                            var showOpenAiApiKeyDialog by remember { mutableStateOf(false) }
                            var openAiKeyActivated by remember { mutableStateOf(OpenAiKit.getStoredApiKey(context) != null) }
                            var showGeminiApiKeyDialog by remember { mutableStateOf(false) }
                            var geminiKeyActivated by remember { mutableStateOf(GeminiKit.getStoredApiKey(context) != null) }
                            var showClaudeApiKeyDialog by remember { mutableStateOf(false) }
                            var claudeKeyActivated by remember { mutableStateOf(ClaudeKit.getStoredApiKey(context) != null) }

                            MenuTextItem(
                                menuItemPosition = MenuItemPosition.Top,
                                text = getString(R.string.settings_menu_deepl_api_key),
                                paddingValues = paddingValues,
                                subText = if (deepLKeyActivated) getString(R.string.deepl_key_active) else null,
                                onClick = {
                                    showDeepLApiKeyDialog = true
                                }
                            )

                            MenuTextItem(
                                menuItemPosition = MenuItemPosition.Middle,
                                text = getString(R.string.settings_menu_openai_api_key),
                                paddingValues = paddingValues,
                                subText = if (openAiKeyActivated) getString(R.string.deepl_key_active) else null,
                                onClick = {
                                    showOpenAiApiKeyDialog = true
                                }
                            )

                            MenuTextItem(
                                menuItemPosition = MenuItemPosition.Middle,
                                text = getString(R.string.settings_menu_gemini_api_key),
                                paddingValues = paddingValues,
                                subText = if (geminiKeyActivated) getString(R.string.deepl_key_active) else null,
                                onClick = {
                                    showGeminiApiKeyDialog = true
                                }
                            )

                            MenuTextItem(
                                menuItemPosition = MenuItemPosition.Bottom,
                                text = getString(R.string.settings_menu_claude_api_key),
                                paddingValues = paddingValues,
                                subText = if (claudeKeyActivated) getString(R.string.deepl_key_active) else null,
                                onClick = {
                                    showClaudeApiKeyDialog = true
                                }
                            )

                            if (showDeepLApiKeyDialog) {
                                DeepLApiKeyDialog(
                                    onDismissRequest = {
                                        showDeepLApiKeyDialog = false
                                        deepLKeyActivated = DeepLKit.getStoredApiKey(context) != null
                                    }
                                )
                            }

                            if (showOpenAiApiKeyDialog) {
                                OpenAiApiKeyDialog(
                                    onDismissRequest = {
                                        showOpenAiApiKeyDialog = false
                                        openAiKeyActivated = OpenAiKit.getStoredApiKey(context) != null
                                    }
                                )
                            }

                            if (showGeminiApiKeyDialog) {
                                GeminiApiKeyDialog(
                                    onDismissRequest = {
                                        showGeminiApiKeyDialog = false
                                        geminiKeyActivated = GeminiKit.getStoredApiKey(context) != null
                                    }
                                )
                            }

                            if (showClaudeApiKeyDialog) {
                                ClaudeApiKeyDialog(
                                    onDismissRequest = {
                                        showClaudeApiKeyDialog = false
                                        claudeKeyActivated = ClaudeKit.getStoredApiKey(context) != null
                                    }
                                )
                            }

                            MenuCategory(
                                painter = painterResource(id = R.drawable.ic_translation_window),
                                categoryName = getString(R.string.settings_menu_cat_translation),
                                isRtl = isRtl,
                            )

                            MenuTextItem(
                                menuItemPosition = MenuItemPosition.Top,
                                text = getString(R.string.settings_menu_translation_transparency),
                                paddingValues = paddingValues,
                                onTextPositioned = { offset ->
                                    translationTransparencyTextOffset.value = Point(offset.x - startPadding, offset.y)
                                },
                                onGloballyPositioned = { layoutCoordinates ->
                                    val center = layoutCoordinates.boundsInWindow().center
                                    val startPadding = paddingValues.calculateLeftPadding(layoutDirection).toPx(context)
                                    val posX = center.x.toInt() - startPadding
                                    translationPoint.value = Point(posX, center.y.toInt())
                                },
                                subText = getTransparencyValueText(translationTransparency),
                                onSubtextPositioned = { offset ->
                                    translationTransparencySubtextOffset.value = Point(offset.x - startPadding, offset.y)
                                },
                                onClick = {
                                    Timber.tag(TAG).d("onClick Translation transparency ${CaptureRepository.mediaProjectionToken}")
                                    if (CaptureRepository.mediaProjectionToken == null) {
                                        // 화면 캡처 권한을 요청
                                        val intent = Intent(
                                            context,
                                            ScreenCapturePermissionRequesterActivity::class.java
                                        )
                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        context.startActivity(intent)
                                    } else {
                                        runTranslation(translationPoint.value, textDetectMode)
                                        coroutineScope.launch {
                                            settingStringFlow.value = getTransparencyValueText(translationTransparency)
                                            SliderDialogView.INSTANCE.cast(
                                                applicationContext = applicationContext,
                                                initialValue = 1.0f - translationTransparency,
                                                valueRange = 0.0f..0.5f,
                                                onValueChange = { value ->
                                                    viewModel.updateTranslationTransparency(1.0f - value)
                                                    settingStringFlow.value = getTransparencyValueText(1.0f - value)
                                                },
                                                menuText = Pair(getString(R.string.settings_menu_translation_transparency), translationTransparencyTextOffset.value),
                                                menuSubtext = Pair(settingStringFlow, translationTransparencySubtextOffset.value),
                                                onDismissRequest = {
                                                    closeTranslation()
                                                    SliderDialogView.INSTANCE.clear()
                                                },
                                            )
                                        }
                                    }
                                }
                            )

                            MenuTextItem(
                                menuItemPosition = MenuItemPosition.Middle,
                                text = getString(R.string.settings_menu_translation_close_delay),
                                paddingValues = paddingValues,
                                onTextPositioned = { offset ->
                                    translationCloseDelayTextOffset.value = Point(offset.x - startPadding, offset.y)
                                },
                                subText = getSecondValueText(translationCloseDelay),
                                onSubtextPositioned = { offset ->
                                    translationCloseDelaySubtextOffset.value = Point(offset.x - startPadding, offset.y)
                                },
                                onClick = {
                                    coroutineScope.launch {
                                        settingStringFlow.value = getSecondValueText(translationCloseDelay)
                                        SliderDialogView.INSTANCE.cast(
                                            applicationContext = applicationContext,
                                            initialValue = translationCloseDelay.toFloat(),
                                            valueRange = 500.0f..7000.0f,
                                            steps = 12,
                                            onValueChange = { value ->
                                                viewModel.updateTranslationCloseDelay(value.toLong())
                                                settingStringFlow.value = getSecondValueText(value.toLong())
                                            },
                                            menuText = Pair(getString(R.string.settings_menu_translation_close_delay), translationCloseDelayTextOffset.value),
                                            menuSubtext = Pair(settingStringFlow, translationCloseDelaySubtextOffset.value),
                                            onDismissRequest = {
                                                SliderDialogView.INSTANCE.clear()
                                            },
                                        )
                                    }
                                }
                            )

                            MenuTextItem(
                                menuItemPosition = MenuItemPosition.Bottom,
                                text = getString(R.string.settings_menu_reply_transparency),
                                paddingValues = paddingValues,
                                onTextPositioned = { offset ->
                                    replyTransparencyTextOffset.value = Point(offset.x - startPadding, offset.y)
                                },
                                subText = getTransparencyValueText(replyTransparency),
                                onSubtextPositioned = { offset ->
                                    replyTransparencySubtextOffset.value = Point(offset.x - startPadding, offset.y)
                                },
                                onClick = {
                                    coroutineScope.launch {
                                        settingStringFlow.value = getTransparencyValueText(replyTransparency)
                                        SliderDialogView.INSTANCE.cast(
                                            applicationContext = applicationContext,
                                            initialValue = 1.0f - replyTransparency,
                                            valueRange = 0.0f..0.5f,
                                            onValueChange = { value ->
                                                viewModel.updateReplyTransparency(1.0f - value)
                                                settingStringFlow.value = getTransparencyValueText(1.0f - value)
                                            },
                                            menuText = Pair(getString(R.string.settings_menu_reply_transparency), replyTransparencyTextOffset.value),
                                            menuSubtext = Pair(settingStringFlow, replyTransparencySubtextOffset.value),
                                            onDismissRequest = {
                                                SliderDialogView.INSTANCE.clear()
                                            },
                                        )
                                    }
                                }
                            )

                            if (ttsCurrentVoice != null) {
                                MenuCategory(
                                    icon = Icons.Default.VoiceChat,
                                    categoryName = getString(R.string.settings_menu_cat_tts),
                                    isRtl = isRtl,
                                )

                                // 읽기 대상 (소스/타겟)
                                MenuItem(
                                    menuItemPosition = MenuItemPosition.Top,
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(end = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        MenuText(
                                            text = getString(R.string.settings_menu_tts_read_target),
                                        )
                                        Row(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(17.dp))
                                                .background(if (isDarkMode) Color(0xFF2b2b2b) else Color(0xFFe8e8e8))
                                                .padding(3.dp)
                                        ) {
                                            TTSReadTarget.entries.forEach { readTarget ->
                                                val selected = ttsReadTarget == readTarget
                                                val label = when (readTarget) {
                                                    TTSReadTarget.SOURCE -> getString(R.string.settings_tts_read_target_source)
                                                    TTSReadTarget.TARGET -> getString(R.string.settings_tts_read_target_target)
                                                }
                                                Text(
                                                    text = label,
                                                    color = if (selected) switchThumbColor else Color(0xFF848487),
                                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = fontDimensionResource(R.dimen.settings_menu_subtext_size)),
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(14.dp))
                                                        .background(if (selected) switchTrackColor else Color.Transparent)
                                                        .clickable {
                                                            viewModel.updateTtsReadTarget(readTarget)
                                                        }
                                                        .padding(horizontal = 12.dp, vertical = 5.dp)
                                                        .semantics {
                                                            contentDescription = "TTS read target ${readTarget.name}"
                                                        },
                                                )
                                            }
                                        }
                                    }
                                }

                                // 번역 자동 읽기 (읽기 대상에 따라 소스/타겟 텍스트를 자동 재생)
                                MenuItem(
                                    menuItemPosition = MenuItemPosition.Middle,
                                    onClick = {
                                        viewModel.updateAutomaticTranslationPlayback(!automaticTranslationPlayback)
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        MenuText(
                                            text = getString(R.string.settings_menu_automated_read_aloud),
                                        )
                                        Switch(
                                            checked = automaticTranslationPlayback,
                                            onCheckedChange = { value ->
                                                viewModel.updateAutomaticTranslationPlayback(value)
                                            },
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = switchThumbColor,
                                                checkedTrackColor = switchTrackColor
                                            ),
                                            modifier = Modifier
                                                .scale(switchScale)
                                                .align(Alignment.CenterVertically)
                                                .semantics {
                                                    contentDescription = if (automaticTranslationPlayback) {
                                                        "Automated read aloud on"
                                                    } else {
                                                        "Automated read aloud off"
                                                    }
                                                },
                                        )
                                    }
                                }

                                if (ttsAvailableVoices.isNotEmpty()) {
                                    MenuTextItem(
                                        menuItemPosition = MenuItemPosition.Middle,
                                        text = getString(R.string.settings_menu_tts_voices),
                                        paddingValues = paddingValues,
                                        subText = ttsCurrentVoice?.name,
                                        onClick = {
                                            coroutineScope.launch {
                                                VoiceListView.INSTANCE.cast(applicationContext)
                                            }
                                        }
                                    )
                                }

                                MenuItem(
                                    menuItemPosition = MenuItemPosition.Bottom,
                                    onClick = {
                                        coroutineScope.launch {
                                            settingFloatFlow.value = ttsSpeechRate
                                            SliderDialogView.INSTANCE.cast(
                                                applicationContext = applicationContext,
                                                initialValue = ttsSpeechRate,
                                                valueRange = 0.5f..2.0f,
                                                steps = 6,
                                                onValueChange = { value ->
                                                    viewModel.updateTtsSpeechRate(value)
                                                    settingFloatFlow.value = value
                                                },
                                                menuText = Pair(getString(R.string.settings_menu_tts_rate), ttsSpeechRateTextOffset.value),
                                                speechRateText = Pair(settingFloatFlow, ttsSpeechRateIconOffset.value),
                                                onDismissRequest = {
                                                    SliderDialogView.INSTANCE.clear()
                                                },
                                            )
                                        }
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 50.dp)
                                            .padding(end = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        MenuText(
                                            text = getString(R.string.settings_menu_tts_rate),
                                            onTextPositioned = { offset ->
                                                ttsSpeechRateTextOffset.value = Point(offset.x - startPadding, offset.y)
                                            },
                                        )

                                        /*
                                            Speech rate. 1.0 is the normal speech rate,
                                            lower values slow down the speech (0.5 is half the normal speech rate),
                                            greater values accelerate it (2.0 is twice the normal speech rate).
                                         */
                                        var isToggled by remember { mutableStateOf(false) }
                                        LaunchedEffect(ttsSpeechRate) {
                                            while (true) {
                                                delay((((2.2f - ttsSpeechRate) / 4) * 1000).toLong())
                                                isToggled = !isToggled
                                            }
                                        }

                                        // 얼굴(테두리·눈)은 고정하고 입만 바꾼다. 두 그림 전체를 Crossfade 하면 겹치는 테두리·눈이
                                        // 전환 중간에 옅어져(두 반투명 층 ≈ 75%) 깜빡이는 것처럼 보였다.
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .semantics { contentDescription = getString(R.string.settings_menu_tts_rate) }
                                                .onGloballyPositioned { layoutCoordinates ->
                                                    val offset = layoutCoordinates.positionOnScreen()
                                                    val startPadding = paddingValues
                                                        .calculateLeftPadding(layoutDirection)
                                                        .toPx(context)
                                                    val posX = offset.x.toInt() - startPadding
                                                    ttsSpeechRateIconOffset.value = Point(posX, offset.y.toInt())
                                                }
                                        ) {
                                            Image(
                                                painter = painterResource(id = R.drawable.tts_rate_face),
                                                contentDescription = null,
                                                colorFilter = ColorFilter.tint(Color(0xFF848487)),
                                                modifier = Modifier.fillMaxSize()
                                            )
                                            Crossfade(targetState = isToggled, label = "Crossfade") { toggled ->
                                                Image(
                                                    painter = painterResource(id = if (toggled) R.drawable.tts_rate_mouth_0 else R.drawable.tts_rate_mouth_1),
                                                    contentDescription = null,
                                                    colorFilter = ColorFilter.tint(Color(0xFF848487)),
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            MenuCategory(
                                icon = Icons.Outlined.Info,
                                categoryName = getString(R.string.settings_menu_cat_about),
                                isRtl = isRtl,
                            )

                            MenuItem(
                                menuItemPosition = MenuItemPosition.Top,
                                onClick = {
                                    coroutineScope.launch {
                                        // 오버레이 안내(핸들 말풍선, 설정 재진입 코치마크)도 첫 실행처럼 다시 보여준다.
                                        // 브라우저가 뜨며 설정이 멈추는 순간이 코치마크의 조건이라 초기화를 먼저 끝낸다.
                                        viewModel.preferenceRepository.resetOverlayGuides()
                                        val guideIntent = Intent(Intent.ACTION_VIEW, HOW_TO_USE_GUIDE_URL.toUri())
                                        context.startActivitySafely(guideIntent)
                                        viewModel.analyticsRepository.screenViewReport("HowToUseGuide")
                                    }
                                },
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 50.dp)
                                        .padding(end = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    MenuText(
                                        text = getString(R.string.settings_menu_how_to_use),
                                    )
                                }
                            }

                            MenuItem(
                                menuItemPosition = MenuItemPosition.Middle,
                                onClick = {
                                    val privacyPolicyIntent = Intent(Intent.ACTION_VIEW, PRIVACY_POLICY_URL.toUri())
                                    context.startActivitySafely(privacyPolicyIntent)
                                    viewModel.analyticsRepository.screenViewReport("PrivacyPolicy")
                                },
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 50.dp)
                                        .padding(end = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    MenuText(
                                        text = getString(R.string.settings_menu_privacy_policy),
                                    )
                                }
                            }

                            MenuItem(
                                menuItemPosition = MenuItemPosition.Middle,
                                onClick = {
                                    context.gotoStore(
                                        newTask = false,
                                        finishService = false
                                    )
                                    viewModel.analyticsRepository.screenViewReport("AppVersion")
                                },
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 50.dp)
                                        .padding(end = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    MenuText(
                                        text = getString(R.string.settings_menu_app_version),
                                    )
                                    if (latestVersionCode > versionCode) {
                                        Row(
                                            modifier = Modifier.wrapContentSize(),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            MenuSubText(
                                                text = "${packageInfo.versionName}",
                                                paddingValues = paddingValues
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Icon(
                                                imageVector = Icons.Default.FiberNew,
                                                contentDescription = "new version",
                                                modifier = Modifier
                                                    .size(32.dp)
                                                    .padding(end = 8.dp),
                                                tint = Color(0xFF446987)
                                            )
                                        }
                                    } else {
                                        MenuSubText(
                                            text = "${packageInfo.versionName}  ${getString(R.string.settings_menu_app_version_latest)}",
                                            paddingValues = paddingValues
                                        )
                                    }
                                }
                            }

                            MenuItem(
                                menuItemPosition = MenuItemPosition.Bottom,
                                onClick = {
                                    val githubIntent = Intent(Intent.ACTION_VIEW, GITHUB_REPO_URL.toUri())
                                    context.startActivitySafely(githubIntent)
                                    viewModel.analyticsRepository.screenViewReport("GitHub")
                                },
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 50.dp)
                                        .padding(end = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    MenuText(
                                        text = getString(R.string.settings_menu_github),
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun MenuCategory(
        icon: ImageVector? = null,
        painter: Painter? = null,
        iconSize: Dp = 22.dp,
        categoryName: String,
        isRtl: Boolean,
    ) {
        val isDarkMode = isSystemInDarkTheme()
        val menuCategoryColor = if (isDarkMode) Color(0xFFb7b7ba) else Color(0xFF626265)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(start = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = "$categoryName Icon",
                    modifier = Modifier
                        .size(iconSize)
                        .graphicsLayer {
                            if (isRtl) rotationY = 180f
                        },
                    tint = menuCategoryColor
                )
            }
            if (painter != null) {
                Image(
                    modifier = Modifier
                        .size(iconSize)
                        .graphicsLayer {
                            if (isRtl) rotationY = 180f
                        },
                    painter = painter,
                    contentDescription = "$categoryName Image",
                    contentScale = ContentScale.Fit,
                    colorFilter = ColorFilter.tint(menuCategoryColor)
                )
            }
            Text(
                modifier = Modifier.padding(start = 8.dp),
                text = categoryName,
                color = menuCategoryColor,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
            )
        }
    }

    @Composable
    fun MenuItem(
        menuItemPosition: MenuItemPosition,
        onClick: (() -> Unit)? = null,
        composable: @Composable () -> Unit,
    ) {
        val cornerRound = 32.dp
        val coroutineScope = rememberCoroutineScope()
        val shape = when (menuItemPosition) {
            MenuItemPosition.Single -> RoundedCornerShape(cornerRound)
            MenuItemPosition.Top -> RoundedCornerShape(topStart = cornerRound, topEnd = cornerRound)
            MenuItemPosition.Middle -> RoundedCornerShape(0.dp)
            MenuItemPosition.Bottom -> RoundedCornerShape(bottomStart = cornerRound, bottomEnd = cornerRound)
        }

        val isDarkMode = isSystemInDarkTheme()
        val backgroundColor = if (isDarkMode) Color(0xFF171717) else Color(0xFFfafafa)
        val dividerColor = if (isDarkMode) Color(0xFF343434) else Color(0xFFd5d5d5)
        val buttonColor = if (isDarkMode) Color(0xFFfafafa) else Color(0xFF171717)

        Box(
            modifier = Modifier
                .wrapContentSize()
                .background(
                    color = backgroundColor,
                    shape = shape
                )
        ) {
            if (menuItemPosition == MenuItemPosition.Middle || menuItemPosition == MenuItemPosition.Bottom) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 18.dp),
                    thickness = 0.7.dp,
                    color = dividerColor
                )
            }

            Button(
                enabled = onClick != null,
                onClick = {
                    onClick?.let {
                        coroutineScope.launch {
                            delay(200L)
                            onClick()
                        }
                    }
                },
                colors = ButtonDefaults.textButtonColors(contentColor = buttonColor),
                shape = shape,
                modifier = Modifier
                    .wrapContentSize(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 50.dp)
                        .padding(top = 5.dp, bottom = 5.dp, start = 5.dp, end = 0.dp),
                ) {
                    composable()
                }
            }
        }
    }

    @Composable
    fun MenuTextItem(
        menuItemPosition: MenuItemPosition,
        text: String,
        paddingValues: PaddingValues,
        onTextPositioned: ((Point) -> Unit)? = null,
        onGloballyPositioned: ((LayoutCoordinates) -> Unit)? = null,
        subText: String? = null,
        onSubtextPositioned: ((Point) -> Unit)? = null,
        onClick: (() -> Unit)? = null,
        menuTextModifier: Modifier = Modifier
    ) {
        MenuItem(
            menuItemPosition = menuItemPosition,
            onClick = onClick,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MenuText(
                    text = text,
                    onGloballyPositioned = onGloballyPositioned,
                    onTextPositioned = onTextPositioned,
                    modifier = menuTextModifier
                )
                subText?.let {
                    MenuSubText(
                        text = it,
                        paddingValues = paddingValues,
                        onSubtextPositioned = onSubtextPositioned
                    )
                }
            }
        }
    }

    /**
     * API 키 다이얼로그의 번역 모델 선택 필드. 라디오 그룹 대신 드롭다운으로 처리해 팝업 높이를 줄여,
     * 상단 오버레이 메뉴바에 가려지지 않도록 한다.
     */
    /**
     * 세로 스크롤 영역 오른쪽에 스크롤바를 그린다.
     *
     * 다이얼로그를 키우지 않고 옵션을 넣으려면 내용이 잘릴 수밖에 없어서,
     * "아래에 더 있다"는 신호가 필요하다. 스크롤이 필요 없을 때는 그리지 않는다.
     */
    private fun Modifier.verticalScrollbar(
        scrollState: ScrollState,
        color: Color,
        width: Dp = 3.dp,
    ): Modifier = drawWithContent {
        drawContent()
        val maxValue = scrollState.maxValue
        if (maxValue <= 0) return@drawWithContent

        val viewportHeight = size.height
        val totalHeight = viewportHeight + maxValue
        val thumbHeight = (viewportHeight / totalHeight * viewportHeight)
            .coerceAtLeast(24.dp.toPx())
        val travel = viewportHeight - thumbHeight
        val thumbOffsetY = travel * (scrollState.value.toFloat() / maxValue)
        val thumbWidth = width.toPx()

        drawRoundRect(
            color = color,
            topLeft = Offset(size.width - thumbWidth, thumbOffsetY),
            size = Size(thumbWidth, thumbHeight),
            cornerRadius = CornerRadius(thumbWidth / 2, thumbWidth / 2),
        )
    }

    /**
     * 라디오 버튼 한 줄로 고르는 옵션 그룹.
     * 항목 수가 적고 값이 배타적이라 드롭다운보다 한눈에 들어온다.
     */
    @Composable
    private fun <T> RadioOptionGroup(
        label: String,
        options: List<T>,
        selected: T?,
        enabled: Boolean,
        labelOf: (T) -> String,
        titleColor: Color,
        contentColor: Color,
        linkColor: Color,
        onSelected: (T) -> Unit,
        description: String? = null,
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = label,
            color = titleColor,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            fontWeight = FontWeight.Bold,
        )
        if (description != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                color = contentColor.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        options.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = option == selected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onSelected(option) },
                    )
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = option == selected,
                    onClick = null,
                    enabled = enabled,
                    colors = RadioButtonDefaults.colors(
                        selectedColor = linkColor,
                        unselectedColor = contentColor.copy(alpha = 0.6f),
                    ),
                )
                Text(
                    text = labelOf(option),
                    color = contentColor,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                )
            }
        }
    }

    /**
     * AI 번역 엔진 설정 창의 API 키 입력칸. 비었을 때는 붙여넣기, 채워졌을 때는 지우기 아이콘을 끝에 둔다.
     * 붙여넣은 키는 앞뒤 공백·줄바꿈을 뗀다(콘솔에서 복사할 때 딸려 오기 쉽다).
     */
    @Composable
    private fun ApiKeyField(
        value: String,
        onValueChange: (String) -> Unit,
        enabled: Boolean,
        isError: Boolean,
        contentColor: Color,
        linkColor: Color,
    ) {
        val context = LocalContext.current
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(text = "API Key") },
            singleLine = true,
            enabled = enabled,
            isError = isError,
            trailingIcon = {
                if (value.isEmpty()) {
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val pasted = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
                                ?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
                            if (!pasted.isNullOrEmpty()) onValueChange(pasted)
                        },
                        enabled = enabled,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentPaste,
                            contentDescription = stringResource(id = android.R.string.paste),
                            modifier = Modifier.size(19.dp), // 기본 24dp 의 80%
                        )
                    }
                } else {
                    IconButton(
                        onClick = { onValueChange("") },
                        enabled = enabled,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Clear,
                            contentDescription = stringResource(id = R.string.api_key_clear),
                            modifier = Modifier.size(19.dp), // 기본 24dp 의 80%
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = contentColor,
                unfocusedTextColor = contentColor,
                focusedBorderColor = linkColor,
                focusedLabelColor = linkColor,
                cursorColor = linkColor,
            ),
        )
    }

    @Composable
    private fun ModelDropdownField(
        label: String,
        models: List<String>,
        selectedModel: String?,
        enabled: Boolean,
        titleColor: Color,
        contentColor: Color,
        linkColor: Color,
        onModelSelected: (String) -> Unit,
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = label,
            color = titleColor,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
        )
        Spacer(modifier = Modifier.height(4.dp))
        var expanded by remember { mutableStateOf(false) }
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = if (enabled) linkColor.copy(alpha = 0.6f) else contentColor.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(4.dp),
                    )
                    .clickable(enabled = enabled) { expanded = true }
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selectedModel ?: models.firstOrNull().orEmpty(),
                    color = contentColor,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = contentColor,
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                models.forEach { model ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = model,
                                color = if (model == selectedModel) linkColor else contentColor,
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                            )
                        },
                        onClick = {
                            onModelSelected(model)
                            expanded = false
                        },
                    )
                }
            }
        }
    }

    /**
     * DeepL API 키 입력 팝업.
     * 언어 선택 팝업과 같은 카드 스타일로, 로고/안내/계정 링크와 키 입력·저장을 제공한다.
     */
    @Composable
    fun DeepLApiKeyDialog(
        onDismissRequest: () -> Unit,
    ) {
        val context = LocalContext.current
        val isDarkMode = isSystemInDarkTheme()
        val backgroundColor = if (isDarkMode) Color(0xFF1F1F1F) else Color(0xFFFEFEFE)
        val titleColor = if (isDarkMode) Color(0xFFFFFFFF) else Color(0xFF000000)
        val contentColor = if (isDarkMode) Color(0xFFFDFDFD) else Color(0xFF232323)
        val linkColor = if (isDarkMode) Color(0xFF6A91B2) else Color(0xFF446987)

        var apiKeyInput by remember { mutableStateOf(DeepLKit.getStoredApiKey(context) ?: "") }
        var isValidating by remember { mutableStateOf(false) }
        var showInvalidKeyError by remember { mutableStateOf(false) }
        val coroutineScope = rememberCoroutineScope()

        /**
         * 저장 처리.
         * - 빈 값: 검증 없이 키 삭제(비활성화). 선택된 엔진이 DeepL 이면 Google 로 되돌린다.
         * - 값 있음: 사용량 조회로 검증. 무효 키는 저장하지 않고 오류 표시,
         *   네트워크 오류는 키 문제로 볼 수 없으므로 저장하고 안내만 한다.
         */
        fun saveApiKey() {
            val trimmedKey = apiKeyInput.trim()
            if (trimmedKey.isEmpty()) {
                DeepLKit.storeApiKey(context, "")
                coroutineScope.launch {
                    if (viewModel.preferenceRepository.translationKitTypeFlow.first() == TranslationKitType.DEEPL) {
                        viewModel.preferenceRepository.update(PreferenceRepository.TRANSLATION_KIT_TYPE, TranslationKitType.GOOGLE.name)
                    }
                }
                onDismissRequest()
                return
            }

            isValidating = true
            showInvalidKeyError = false
            coroutineScope.launch {
                when (DeepLKit.validateApiKey(trimmedKey)) {
                    DeepLKit.KeyValidationResult.VALID -> {
                        DeepLKit.storeApiKey(context, trimmedKey)
                        onDismissRequest()
                    }

                    DeepLKit.KeyValidationResult.INVALID -> {
                        isValidating = false
                        showInvalidKeyError = true
                    }

                    DeepLKit.KeyValidationResult.NETWORK_ERROR -> {
                        DeepLKit.storeApiKey(context, trimmedKey)
                        Toast.makeText(context, getString(R.string.deepl_key_network_error), Toast.LENGTH_LONG).show()
                        onDismissRequest()
                    }
                }
            }
        }

        @Composable
        fun LinkText(text: String, url: String) {
            Text(
                text = text,
                color = linkColor,
                textDecoration = TextDecoration.Underline,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                modifier = Modifier
                    .clickable {
                        context.startActivitySafely(Intent(Intent.ACTION_VIEW, url.toUri()))
                    }
                    .padding(vertical = 6.dp),
            )
        }

        Dialog(onDismissRequest = onDismissRequest) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = backgroundColor,
                        shape = RoundedCornerShape(24.dp)
                    )
                    .padding(horizontal = 24.dp, vertical = 22.dp)
            ) {
                // 로고 + 타이틀
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(id = R.drawable.ci_deepl),
                        contentDescription = "DeepL logo",
                        modifier = Modifier.size(26.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = TranslationKitType.DEEPL.text,
                        color = titleColor,
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = getString(R.string.deepl_key_guide),
                    color = contentColor,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = getString(R.string.deepl_key_signup_guide),
                    color = contentColor,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                )

                Spacer(modifier = Modifier.height(10.dp))

                LinkText(
                    text = getString(R.string.deepl_key_link_subscription),
                    url = DeepLKit.URL_SUBSCRIPTION,
                )
                LinkText(
                    text = getString(R.string.deepl_key_link_keys),
                    url = DeepLKit.URL_API_KEYS,
                )

                Spacer(modifier = Modifier.height(14.dp))

                ApiKeyField(
                    value = apiKeyInput,
                    onValueChange = {
                        apiKeyInput = it
                        showInvalidKeyError = false
                    },
                    enabled = !isValidating,
                    isError = showInvalidKeyError,
                    contentColor = contentColor,
                    linkColor = linkColor,
                )

                if (showInvalidKeyError) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = getString(R.string.deepl_key_invalid),
                        color = Color(0xFFB3261E),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = onDismissRequest,
                        enabled = !isValidating,
                    ) {
                        Text(
                            text = stringResource(id = android.R.string.cancel),
                            color = contentColor,
                            fontSize = 15.sp,
                        )
                    }
                    TextButton(
                        onClick = { saveApiKey() },
                        enabled = !isValidating,
                    ) {
                        Text(
                            text = if (isValidating) getString(R.string.deepl_key_validating) else getString(R.string.label_save),
                            color = linkColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                        )
                    }
                }
            }
        }
    }

    /**
     * OpenAI API 키 입력 + 번역 모델 선택 팝업. DeepL 다이얼로그와 같은 카드 스타일.
     * 모델 후보는 Remote Config([RemoteConfigRepository.TRANSLATE_MODELS])에서 온다.
     */
    @Composable
    fun OpenAiApiKeyDialog(
        onDismissRequest: () -> Unit,
    ) {
        val context = LocalContext.current
        val isDarkMode = isSystemInDarkTheme()
        val backgroundColor = if (isDarkMode) Color(0xFF1F1F1F) else Color(0xFFFEFEFE)
        val titleColor = if (isDarkMode) Color(0xFFFFFFFF) else Color(0xFF000000)
        val contentColor = if (isDarkMode) Color(0xFFFDFDFD) else Color(0xFF232323)
        val linkColor = if (isDarkMode) Color(0xFF6A91B2) else Color(0xFF446987)

        var apiKeyInput by remember { mutableStateOf(OpenAiKit.getStoredApiKey(context) ?: "") }
        var isValidating by remember { mutableStateOf(false) }
        var showInvalidKeyError by remember { mutableStateOf(false) }
        val coroutineScope = rememberCoroutineScope()
        val scrollState = rememberScrollState()

        // 번역 모델 후보(Remote Config)와 현재 선택값
        val models = remember { viewModel.remoteConfigRepository.getOpenAiTranslateModels() }
        var selectedModel by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            // 저장된 모델이 현재 후보 목록에 없으면(원격에서 목록 변경) 첫 번째를 기본 선택으로 둔다.
            val saved = viewModel.preferenceRepository.openAiModelFlow.first()
            selectedModel = saved?.takeIf { it in models } ?: models.firstOrNull()
        }

        fun persistModel() {
            selectedModel?.let { model ->
                coroutineScope.launch {
                    viewModel.preferenceRepository.update(PreferenceRepository.OPENAI_MODEL, model)
                }
            }
        }

        // 문맥/스타일 옵션. 저장값이 없으면 각 enum 의 기본값(주변 문장 / 직역 / 일반).
        var contextMode by remember { mutableStateOf(TranslationContextMode.DEFAULT) }
        var strength by remember { mutableStateOf(TranslationStrength.DEFAULT) }
        var domain by remember { mutableStateOf(TranslationDomain.DEFAULT) }
        LaunchedEffect(Unit) {
            contextMode = viewModel.preferenceRepository.openAiContextModeFlow.first()
            strength = viewModel.preferenceRepository.openAiTranslationStrengthFlow.first()
            domain = viewModel.preferenceRepository.openAiTranslationDomainFlow.first()
        }

        fun persistOptions() {
            coroutineScope.launch {
                viewModel.preferenceRepository.update(PreferenceRepository.OPENAI_CONTEXT_MODE, contextMode.name)
                viewModel.preferenceRepository.update(PreferenceRepository.OPENAI_TRANSLATION_STRENGTH, strength.name)
                viewModel.preferenceRepository.update(PreferenceRepository.OPENAI_TRANSLATION_DOMAIN, domain.name)
            }
        }

        /**
         * 저장 처리.
         * - 빈 값: 검증 없이 키 삭제(비활성화). 선택된 엔진이 OpenAI 면 Google 로 되돌린다.
         * - 값 있음: 모델 조회로 검증. 무효 키는 저장하지 않고 오류 표시,
         *   네트워크 오류는 키 문제로 볼 수 없으므로 저장하고 안내만 한다.
         * 모델 선택은 키 유효성과 무관하게 항상 저장한다.
         */
        fun saveApiKey() {
            persistModel()
            persistOptions()
            val trimmedKey = apiKeyInput.trim()
            if (trimmedKey.isEmpty()) {
                OpenAiKit.storeApiKey(context, "")
                coroutineScope.launch {
                    if (viewModel.preferenceRepository.translationKitTypeFlow.first() == TranslationKitType.OPENAI) {
                        viewModel.preferenceRepository.update(PreferenceRepository.TRANSLATION_KIT_TYPE, TranslationKitType.GOOGLE.name)
                    }
                }
                onDismissRequest()
                return
            }

            isValidating = true
            showInvalidKeyError = false
            coroutineScope.launch {
                when (OpenAiKit.validateApiKey(trimmedKey)) {
                    OpenAiKit.KeyValidationResult.VALID -> {
                        OpenAiKit.storeApiKey(context, trimmedKey)
                        // 키 유효 ≠ 크레딧 보유 — 기대치를 미리 맞춰 "앱 고장" 오해를 줄인다
                        Toast.makeText(context, context.getString(R.string.key_saved_credit_hint), Toast.LENGTH_LONG).show()
                        onDismissRequest()
                    }

                    OpenAiKit.KeyValidationResult.INVALID -> {
                        isValidating = false
                        showInvalidKeyError = true
                    }

                    OpenAiKit.KeyValidationResult.NETWORK_ERROR -> {
                        OpenAiKit.storeApiKey(context, trimmedKey)
                        Toast.makeText(context, getString(R.string.deepl_key_network_error), Toast.LENGTH_LONG).show()
                        onDismissRequest()
                    }
                }
            }
        }

        @Composable
        fun LinkText(text: String, url: String) {
            Text(
                text = text,
                color = linkColor,
                textDecoration = TextDecoration.Underline,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                modifier = Modifier
                    .clickable {
                        context.startActivitySafely(Intent(Intent.ACTION_VIEW, url.toUri()))
                    }
                    .padding(vertical = 6.dp),
            )
        }

        Dialog(onDismissRequest = onDismissRequest) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = backgroundColor,
                        shape = RoundedCornerShape(24.dp)
                    )
                    .padding(horizontal = 24.dp, vertical = 22.dp)
            ) {
                // 로고 + 타이틀
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(id = R.drawable.ci_openai),
                        contentDescription = "OpenAI logo",
                        modifier = Modifier.size(26.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = TranslationKitType.OPENAI.text,
                        color = titleColor,
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                    )
                }

                // 다이얼로그를 키우지 않기 위해 본문만 스크롤시킨다.
                // 오른쪽 스크롤바가 "아래에 더 있다"는 신호가 된다.
                Column(
                    modifier = Modifier
                        .heightIn(max = 340.dp)
                        .verticalScrollbar(scrollState, contentColor.copy(alpha = 0.35f))
                        .verticalScroll(scrollState)
                        .padding(end = 8.dp),
                ) {
                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = getString(R.string.openai_key_guide),
                    color = contentColor,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = getString(R.string.openai_key_signup_guide),
                    color = contentColor,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                )
                Spacer(modifier = Modifier.height(10.dp))

                LinkText(
                    text = getString(R.string.openai_key_link_keys),
                    url = OpenAiKit.URL_API_KEYS,
                )
                LinkText(
                    text = getString(R.string.openai_key_link_billing),
                    url = OpenAiKit.URL_BILLING,
                )

                Spacer(modifier = Modifier.height(14.dp))

                ApiKeyField(
                    value = apiKeyInput,
                    onValueChange = {
                        apiKeyInput = it
                        showInvalidKeyError = false
                    },
                    enabled = !isValidating,
                    isError = showInvalidKeyError,
                    contentColor = contentColor,
                    linkColor = linkColor,
                )

                if (showInvalidKeyError) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = getString(R.string.deepl_key_invalid),
                        color = Color(0xFFB3261E),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    )
                }

                // 번역 모델 선택
                if (models.isNotEmpty()) {
                    ModelDropdownField(
                        label = getString(R.string.openai_model_label),
                        models = models,
                        selectedModel = selectedModel,
                        enabled = !isValidating,
                        titleColor = titleColor,
                        contentColor = contentColor,
                        linkColor = linkColor,
                        onModelSelected = { selectedModel = it },
                    )
                }

                    RadioOptionGroup(
                        label = getString(R.string.translation_context_label),
                        description = getString(R.string.translation_context_guide),
                        options = TranslationContextMode.entries,
                        selected = contextMode,
                        enabled = !isValidating,
                        labelOf = { getString(it.labelResourceId) },
                        titleColor = titleColor,
                        contentColor = contentColor,
                        linkColor = linkColor,
                        onSelected = { contextMode = it },
                    )

                    RadioOptionGroup(
                        label = getString(R.string.translation_strength_label),
                        options = TranslationStrength.entries,
                        selected = strength,
                        enabled = !isValidating,
                        labelOf = { getString(it.labelResourceId) },
                        titleColor = titleColor,
                        contentColor = contentColor,
                        linkColor = linkColor,
                        onSelected = { strength = it },
                    )

                    RadioOptionGroup(
                        label = getString(R.string.translation_domain_label),
                        options = TranslationDomain.entries,
                        selected = domain,
                        enabled = !isValidating,
                        labelOf = { getString(it.labelResourceId) },
                        titleColor = titleColor,
                        contentColor = contentColor,
                        linkColor = linkColor,
                        onSelected = { domain = it },
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = onDismissRequest,
                        enabled = !isValidating,
                    ) {
                        Text(
                            text = stringResource(id = android.R.string.cancel),
                            color = contentColor,
                            fontSize = 15.sp,
                        )
                    }
                    TextButton(
                        onClick = { saveApiKey() },
                        enabled = !isValidating,
                    ) {
                        Text(
                            text = if (isValidating) getString(R.string.deepl_key_validating) else getString(R.string.label_save),
                            color = linkColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                        )
                    }
                }
            }
        }
    }

    /**
     * Gemini API 키 입력 + 번역 모델 선택 팝업. OpenAI 다이얼로그와 같은 카드 스타일.
     * 모델 후보는 Remote Config([RemoteConfigRepository.TRANSLATE_MODELS])에서 온다.
     */
    @Composable
    fun GeminiApiKeyDialog(
        onDismissRequest: () -> Unit,
    ) {
        val context = LocalContext.current
        val isDarkMode = isSystemInDarkTheme()
        val backgroundColor = if (isDarkMode) Color(0xFF1F1F1F) else Color(0xFFFEFEFE)
        val titleColor = if (isDarkMode) Color(0xFFFFFFFF) else Color(0xFF000000)
        val contentColor = if (isDarkMode) Color(0xFFFDFDFD) else Color(0xFF232323)
        val linkColor = if (isDarkMode) Color(0xFF6A91B2) else Color(0xFF446987)

        var apiKeyInput by remember { mutableStateOf(GeminiKit.getStoredApiKey(context) ?: "") }
        var isValidating by remember { mutableStateOf(false) }
        var showInvalidKeyError by remember { mutableStateOf(false) }
        val coroutineScope = rememberCoroutineScope()
        val scrollState = rememberScrollState()

        // 번역 모델 후보(Remote Config)와 현재 선택값
        val models = remember { viewModel.remoteConfigRepository.getGeminiTranslateModels() }
        var selectedModel by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            val saved = viewModel.preferenceRepository.geminiModelFlow.first()
            selectedModel = saved?.takeIf { it in models } ?: models.firstOrNull()
        }

        fun persistModel() {
            selectedModel?.let { model ->
                coroutineScope.launch {
                    viewModel.preferenceRepository.update(PreferenceRepository.GEMINI_MODEL, model)
                }
            }
        }

        // 문맥/스타일 옵션. 저장값이 없으면 각 enum 의 기본값(주변 문장 / 직역 / 일반).
        var contextMode by remember { mutableStateOf(TranslationContextMode.DEFAULT) }
        var strength by remember { mutableStateOf(TranslationStrength.DEFAULT) }
        var domain by remember { mutableStateOf(TranslationDomain.DEFAULT) }
        LaunchedEffect(Unit) {
            contextMode = viewModel.preferenceRepository.geminiContextModeFlow.first()
            strength = viewModel.preferenceRepository.geminiTranslationStrengthFlow.first()
            domain = viewModel.preferenceRepository.geminiTranslationDomainFlow.first()
        }

        fun persistOptions() {
            coroutineScope.launch {
                viewModel.preferenceRepository.update(PreferenceRepository.GEMINI_CONTEXT_MODE, contextMode.name)
                viewModel.preferenceRepository.update(PreferenceRepository.GEMINI_TRANSLATION_STRENGTH, strength.name)
                viewModel.preferenceRepository.update(PreferenceRepository.GEMINI_TRANSLATION_DOMAIN, domain.name)
            }
        }

        fun saveApiKey() {
            persistModel()
            persistOptions()
            val trimmedKey = apiKeyInput.trim()
            if (trimmedKey.isEmpty()) {
                GeminiKit.storeApiKey(context, "")
                coroutineScope.launch {
                    if (viewModel.preferenceRepository.translationKitTypeFlow.first() == TranslationKitType.GEMINI) {
                        viewModel.preferenceRepository.update(PreferenceRepository.TRANSLATION_KIT_TYPE, TranslationKitType.GOOGLE.name)
                    }
                }
                onDismissRequest()
                return
            }

            isValidating = true
            showInvalidKeyError = false
            coroutineScope.launch {
                when (GeminiKit.validateApiKey(trimmedKey)) {
                    GeminiKit.KeyValidationResult.VALID -> {
                        GeminiKit.storeApiKey(context, trimmedKey)
                        // 키 유효 ≠ 크레딧 보유 — 기대치를 미리 맞춰 "앱 고장" 오해를 줄인다
                        Toast.makeText(context, context.getString(R.string.key_saved_credit_hint), Toast.LENGTH_LONG).show()
                        onDismissRequest()
                    }

                    GeminiKit.KeyValidationResult.INVALID -> {
                        isValidating = false
                        showInvalidKeyError = true
                    }

                    GeminiKit.KeyValidationResult.NETWORK_ERROR -> {
                        GeminiKit.storeApiKey(context, trimmedKey)
                        Toast.makeText(context, getString(R.string.deepl_key_network_error), Toast.LENGTH_LONG).show()
                        onDismissRequest()
                    }
                }
            }
        }

        @Composable
        fun LinkText(text: String, url: String) {
            Text(
                text = text,
                color = linkColor,
                textDecoration = TextDecoration.Underline,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                modifier = Modifier
                    .clickable {
                        context.startActivitySafely(Intent(Intent.ACTION_VIEW, url.toUri()))
                    }
                    .padding(vertical = 6.dp),
            )
        }

        Dialog(onDismissRequest = onDismissRequest) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = backgroundColor,
                        shape = RoundedCornerShape(24.dp)
                    )
                    .padding(horizontal = 24.dp, vertical = 22.dp)
            ) {
                // 로고 + 타이틀
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(id = R.drawable.ci_gemini),
                        contentDescription = "Gemini logo",
                        modifier = Modifier.size(26.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = TranslationKitType.GEMINI.text,
                        color = titleColor,
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                    )
                }

                // 다이얼로그를 키우지 않기 위해 본문만 스크롤시킨다.
                // 오른쪽 스크롤바가 "아래에 더 있다"는 신호가 된다.
                Column(
                    modifier = Modifier
                        .heightIn(max = 340.dp)
                        .verticalScrollbar(scrollState, contentColor.copy(alpha = 0.35f))
                        .verticalScroll(scrollState)
                        .padding(end = 8.dp),
                ) {
                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = getString(R.string.gemini_key_guide),
                    color = contentColor,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = getString(R.string.gemini_key_signup_guide),
                    color = contentColor,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                )

                Spacer(modifier = Modifier.height(10.dp))

                LinkText(
                    text = getString(R.string.openai_key_link_keys),
                    url = GeminiKit.URL_API_KEYS,
                )
                LinkText(
                    text = getString(R.string.openai_key_link_billing),
                    url = GeminiKit.URL_BILLING,
                )

                Spacer(modifier = Modifier.height(14.dp))

                ApiKeyField(
                    value = apiKeyInput,
                    onValueChange = {
                        apiKeyInput = it
                        showInvalidKeyError = false
                    },
                    enabled = !isValidating,
                    isError = showInvalidKeyError,
                    contentColor = contentColor,
                    linkColor = linkColor,
                )

                if (showInvalidKeyError) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = getString(R.string.deepl_key_invalid),
                        color = Color(0xFFB3261E),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    )
                }

                // 번역 모델 선택
                if (models.isNotEmpty()) {
                    ModelDropdownField(
                        label = getString(R.string.openai_model_label),
                        models = models,
                        selectedModel = selectedModel,
                        enabled = !isValidating,
                        titleColor = titleColor,
                        contentColor = contentColor,
                        linkColor = linkColor,
                        onModelSelected = { selectedModel = it },
                    )
                }

                    RadioOptionGroup(
                        label = getString(R.string.translation_context_label),
                        description = getString(R.string.translation_context_guide),
                        options = TranslationContextMode.entries,
                        selected = contextMode,
                        enabled = !isValidating,
                        labelOf = { getString(it.labelResourceId) },
                        titleColor = titleColor,
                        contentColor = contentColor,
                        linkColor = linkColor,
                        onSelected = { contextMode = it },
                    )

                    RadioOptionGroup(
                        label = getString(R.string.translation_strength_label),
                        options = TranslationStrength.entries,
                        selected = strength,
                        enabled = !isValidating,
                        labelOf = { getString(it.labelResourceId) },
                        titleColor = titleColor,
                        contentColor = contentColor,
                        linkColor = linkColor,
                        onSelected = { strength = it },
                    )

                    RadioOptionGroup(
                        label = getString(R.string.translation_domain_label),
                        options = TranslationDomain.entries,
                        selected = domain,
                        enabled = !isValidating,
                        labelOf = { getString(it.labelResourceId) },
                        titleColor = titleColor,
                        contentColor = contentColor,
                        linkColor = linkColor,
                        onSelected = { domain = it },
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = onDismissRequest,
                        enabled = !isValidating,
                    ) {
                        Text(
                            text = stringResource(id = android.R.string.cancel),
                            color = contentColor,
                            fontSize = 15.sp,
                        )
                    }
                    TextButton(
                        onClick = { saveApiKey() },
                        enabled = !isValidating,
                    ) {
                        Text(
                            text = if (isValidating) getString(R.string.deepl_key_validating) else getString(R.string.label_save),
                            color = linkColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                        )
                    }
                }
            }
        }
    }

    /**
     * Claude API 키 입력 + 번역 모델 선택 팝업. OpenAI/Gemini 다이얼로그와 같은 카드 스타일.
     * 모델 후보는 Remote Config([RemoteConfigRepository.TRANSLATE_MODELS])에서 온다.
     */
    @Composable
    fun ClaudeApiKeyDialog(
        onDismissRequest: () -> Unit,
    ) {
        val context = LocalContext.current
        val isDarkMode = isSystemInDarkTheme()
        val backgroundColor = if (isDarkMode) Color(0xFF1F1F1F) else Color(0xFFFEFEFE)
        val titleColor = if (isDarkMode) Color(0xFFFFFFFF) else Color(0xFF000000)
        val contentColor = if (isDarkMode) Color(0xFFFDFDFD) else Color(0xFF232323)
        val linkColor = if (isDarkMode) Color(0xFF6A91B2) else Color(0xFF446987)

        var apiKeyInput by remember { mutableStateOf(ClaudeKit.getStoredApiKey(context) ?: "") }
        var isValidating by remember { mutableStateOf(false) }
        var showInvalidKeyError by remember { mutableStateOf(false) }
        val coroutineScope = rememberCoroutineScope()
        val scrollState = rememberScrollState()

        // 번역 모델 후보(Remote Config)와 현재 선택값
        val models = remember { viewModel.remoteConfigRepository.getClaudeTranslateModels() }
        var selectedModel by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            val saved = viewModel.preferenceRepository.claudeModelFlow.first()
            selectedModel = saved?.takeIf { it in models } ?: models.firstOrNull()
        }

        fun persistModel() {
            selectedModel?.let { model ->
                coroutineScope.launch {
                    viewModel.preferenceRepository.update(PreferenceRepository.CLAUDE_MODEL, model)
                }
            }
        }

        // 문맥/스타일 옵션. 저장값이 없으면 각 enum 의 기본값(주변 문장 / 직역 / 일반).
        var contextMode by remember { mutableStateOf(TranslationContextMode.DEFAULT) }
        var strength by remember { mutableStateOf(TranslationStrength.DEFAULT) }
        var domain by remember { mutableStateOf(TranslationDomain.DEFAULT) }
        LaunchedEffect(Unit) {
            contextMode = viewModel.preferenceRepository.claudeContextModeFlow.first()
            strength = viewModel.preferenceRepository.claudeTranslationStrengthFlow.first()
            domain = viewModel.preferenceRepository.claudeTranslationDomainFlow.first()
        }

        fun persistOptions() {
            coroutineScope.launch {
                viewModel.preferenceRepository.update(PreferenceRepository.CLAUDE_CONTEXT_MODE, contextMode.name)
                viewModel.preferenceRepository.update(PreferenceRepository.CLAUDE_TRANSLATION_STRENGTH, strength.name)
                viewModel.preferenceRepository.update(PreferenceRepository.CLAUDE_TRANSLATION_DOMAIN, domain.name)
            }
        }

        fun saveApiKey() {
            persistModel()
            persistOptions()
            val trimmedKey = apiKeyInput.trim()
            if (trimmedKey.isEmpty()) {
                ClaudeKit.storeApiKey(context, "")
                coroutineScope.launch {
                    if (viewModel.preferenceRepository.translationKitTypeFlow.first() == TranslationKitType.CLAUDE) {
                        viewModel.preferenceRepository.update(PreferenceRepository.TRANSLATION_KIT_TYPE, TranslationKitType.GOOGLE.name)
                    }
                    // Claude 이미지 번역으로만 원문이 되는 언어였으면 auto 로 바꾼다(§25, 사용자 결정)
                    if (ImageTranslation.isImageOnlyLanguage(viewModel.preferenceRepository.sourceLanguageCodeFlow.first())) {
                        viewModel.preferenceRepository.update(PreferenceRepository.SOURCE_LANGUAGE_CODE, "auto")
                    }
                }
                onDismissRequest()
                return
            }

            isValidating = true
            showInvalidKeyError = false
            coroutineScope.launch {
                when (ClaudeKit.validateApiKey(trimmedKey)) {
                    ClaudeKit.KeyValidationResult.VALID -> {
                        ClaudeKit.storeApiKey(context, trimmedKey)
                        // 키 유효 ≠ 크레딧 보유 — 기대치를 미리 맞춰 "앱 고장" 오해를 줄인다
                        Toast.makeText(context, context.getString(R.string.key_saved_credit_hint), Toast.LENGTH_LONG).show()
                        onDismissRequest()
                    }

                    ClaudeKit.KeyValidationResult.INVALID -> {
                        isValidating = false
                        showInvalidKeyError = true
                    }

                    ClaudeKit.KeyValidationResult.NETWORK_ERROR -> {
                        ClaudeKit.storeApiKey(context, trimmedKey)
                        Toast.makeText(context, getString(R.string.deepl_key_network_error), Toast.LENGTH_LONG).show()
                        onDismissRequest()
                    }
                }
            }
        }

        @Composable
        fun LinkText(text: String, url: String) {
            Text(
                text = text,
                color = linkColor,
                textDecoration = TextDecoration.Underline,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                modifier = Modifier
                    .clickable {
                        context.startActivitySafely(Intent(Intent.ACTION_VIEW, url.toUri()))
                    }
                    .padding(vertical = 6.dp),
            )
        }

        Dialog(onDismissRequest = onDismissRequest) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = backgroundColor,
                        shape = RoundedCornerShape(24.dp)
                    )
                    .padding(horizontal = 24.dp, vertical = 22.dp)
            ) {
                // 로고 + 타이틀
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(id = R.drawable.ci_claude),
                        contentDescription = "Claude logo",
                        modifier = Modifier.size(26.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = TranslationKitType.CLAUDE.text,
                        color = titleColor,
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                    )
                }

                // 다이얼로그를 키우지 않기 위해 본문만 스크롤시킨다.
                // 오른쪽 스크롤바가 "아래에 더 있다"는 신호가 된다.
                Column(
                    modifier = Modifier
                        .heightIn(max = 340.dp)
                        .verticalScrollbar(scrollState, contentColor.copy(alpha = 0.35f))
                        .verticalScroll(scrollState)
                        .padding(end = 8.dp),
                ) {
                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = getString(R.string.claude_key_guide),
                    color = contentColor,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = getString(R.string.claude_key_signup_guide),
                    color = contentColor,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                )

                Spacer(modifier = Modifier.height(10.dp))

                LinkText(
                    text = getString(R.string.openai_key_link_keys),
                    url = ClaudeKit.URL_API_KEYS,
                )
                LinkText(
                    text = getString(R.string.openai_key_link_billing),
                    url = ClaudeKit.URL_BILLING,
                )

                Spacer(modifier = Modifier.height(14.dp))

                ApiKeyField(
                    value = apiKeyInput,
                    onValueChange = {
                        apiKeyInput = it
                        showInvalidKeyError = false
                    },
                    enabled = !isValidating,
                    isError = showInvalidKeyError,
                    contentColor = contentColor,
                    linkColor = linkColor,
                )

                if (showInvalidKeyError) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = getString(R.string.deepl_key_invalid),
                        color = Color(0xFFB3261E),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    )
                }

                // 번역 모델 선택
                if (models.isNotEmpty()) {
                    ModelDropdownField(
                        label = getString(R.string.openai_model_label),
                        models = models,
                        selectedModel = selectedModel,
                        enabled = !isValidating,
                        titleColor = titleColor,
                        contentColor = contentColor,
                        linkColor = linkColor,
                        onModelSelected = { selectedModel = it },
                    )
                }

                    RadioOptionGroup(
                        label = getString(R.string.translation_context_label),
                        description = getString(R.string.translation_context_guide),
                        options = TranslationContextMode.entries,
                        selected = contextMode,
                        enabled = !isValidating,
                        labelOf = { getString(it.labelResourceId) },
                        titleColor = titleColor,
                        contentColor = contentColor,
                        linkColor = linkColor,
                        onSelected = { contextMode = it },
                    )

                    RadioOptionGroup(
                        label = getString(R.string.translation_strength_label),
                        options = TranslationStrength.entries,
                        selected = strength,
                        enabled = !isValidating,
                        labelOf = { getString(it.labelResourceId) },
                        titleColor = titleColor,
                        contentColor = contentColor,
                        linkColor = linkColor,
                        onSelected = { strength = it },
                    )

                    RadioOptionGroup(
                        label = getString(R.string.translation_domain_label),
                        options = TranslationDomain.entries,
                        selected = domain,
                        enabled = !isValidating,
                        labelOf = { getString(it.labelResourceId) },
                        titleColor = titleColor,
                        contentColor = contentColor,
                        linkColor = linkColor,
                        onSelected = { domain = it },
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = onDismissRequest,
                        enabled = !isValidating,
                    ) {
                        Text(
                            text = stringResource(id = android.R.string.cancel),
                            color = contentColor,
                            fontSize = 15.sp,
                        )
                    }
                    TextButton(
                        onClick = { saveApiKey() },
                        enabled = !isValidating,
                    ) {
                        Text(
                            text = if (isValidating) getString(R.string.deepl_key_validating) else getString(R.string.label_save),
                            color = linkColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun MenuText(
        modifier: Modifier = Modifier,
        text: String,
        fontSize: TextUnit? = null,
        maxLines: Int = Int.MAX_VALUE,
        onGloballyPositioned: ((LayoutCoordinates) -> Unit)? = null,
        onTextPositioned: ((Point) -> Unit)? = null,
    ) {
        val isDarkMode = isSystemInDarkTheme()
        val textColor = if (isDarkMode) Color(0xFFfcfcfc) else Color(0xFF010000)
        val resolvedFontSize = fontSize ?: fontDimensionResource(R.dimen.settings_menu_text_size)

        Text(
            text = text,
            color = textColor,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = resolvedFontSize),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier.onGloballyPositioned { layoutCoordinates ->
                onGloballyPositioned?.let { it(layoutCoordinates) }
                val offset = layoutCoordinates.positionOnScreen()
                onTextPositioned?.let { it(Point(offset.x.toInt(), offset.y.toInt())) }
            },
        )
    }

    @Composable
    fun MenuSubText(
        text: String,
        paddingValues: PaddingValues,
        onSubtextPositioned: ((Point) -> Unit)? = null,
    ) {
        val context = LocalContext.current
        val isDarkMode = isSystemInDarkTheme()
        val layoutDirection = LocalLayoutDirection.current
        val subTextColor = if (isDarkMode) Color(0xFFb7b7ba) else Color(0xFF626265)
        val fontSize = fontDimensionResource(R.dimen.settings_menu_subtext_size)

        Text(
            modifier = Modifier
                .padding(end = 6.dp)
                .onGloballyPositioned { layoutCoordinates ->
                    val offset = layoutCoordinates.positionOnScreen()
                    val startPadding = paddingValues
                        .calculateLeftPadding(layoutDirection)
                        .toPx(context)
                    val posX = offset.x.toInt() + layoutCoordinates.size.width - startPadding
                    onSubtextPositioned?.let { it(Point(offset.x.toInt() + layoutCoordinates.size.width, offset.y.toInt())) }
                },
            text = text,
            color = subTextColor,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = fontSize),
        )

    }
}