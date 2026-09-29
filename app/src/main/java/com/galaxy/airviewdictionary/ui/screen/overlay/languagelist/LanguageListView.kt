package com.galaxy.airviewdictionary.ui.screen.overlay.languagelist


import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galaxy.airviewdictionary.R
import com.galaxy.airviewdictionary.data.remote.translation.ImageTranslation
import com.galaxy.airviewdictionary.data.remote.translation.Language
import com.galaxy.airviewdictionary.data.remote.translation.TranslationKitType
import com.galaxy.airviewdictionary.data.remote.translation.claude.ClaudeKit
import com.galaxy.airviewdictionary.core.OverlayService
import com.galaxy.airviewdictionary.ui.screen.overlay.OverlayView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Singleton


/**
 * 언어 선택 뷰
 */
@Singleton
class LanguageListView private constructor() : OverlayView() {

    enum class Type {
        SOURCE,
        TARGET,
    }

    companion object {
        val INSTANCE: LanguageListView by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { LanguageListView() }
    }

    private lateinit var viewModel: LanguageListViewModel

    override lateinit var layoutParams: WindowManager.LayoutParams

    private val type = mutableStateOf(Type.SOURCE)

    override val composable: @Composable () -> Unit = @Composable {
        if (isAttachedToWindow()) {
            LanguageList()
        }
    }

    suspend fun cast(applicationContext: Context, type: Type) {
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_DIM_BEHIND
                    or WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            windowAnimations = android.R.style.Animation_Toast
            dimAmount = 0.5f
        }

        this.type.value = type
        super.cast(applicationContext, false)
    }

    override fun onServiceConnected(overlayService: OverlayService) {
        viewModel = overlayService.getLanguageListViewModel()
        super.onServiceConnected(overlayService)
    }

    @Composable
    fun LanguageList() {
        val context = LocalContext.current
        // 원문 목록의 이미지 전용 언어가 Claude 키를 본다(LanguageItem)
        LaunchedEffect(Unit) { ClaudeKit.refreshAvailability(context) }
        val lifecycleOwner = LocalLifecycleOwner.current
        val configuration = LocalConfiguration.current
        val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val screenWidth = configuration.screenWidthDp.dp
        val viewWidth = if (isPortrait) screenWidth else screenWidth * (2f / 3f)

        // 다크 모드 여부
        val isDarkMode = isSystemInDarkTheme()

        // 배경 및 텍스트/아이콘 색상
        val backgroundColor = if (isDarkMode) Color(0xFF1F1F1F) else Color(0xFFFEFEFE)
        val headerFooterColor = if (isDarkMode) Color(0xFFFFFFFF) else Color(0xFF000000)
        val contentColor: Color = if (isDarkMode) Color(0xFFFDFDFD) else Color(0xFF232323)
        val contentDisabledColor: Color = if (isDarkMode) Color(0xFF898989) else Color(0xFF898989)
        val borderColor = if (isDarkMode) Color(0xFF6A6A6A) else Color(0xFFD6D6D6)
        val roundedCornerShape = RoundedCornerShape(24.dp)
        val shadowPadding = 1.dp
        val contentHorizontalPadding: Dp = 16.dp

        val languages = if (type.value == Type.SOURCE) viewModel.supportedLanguagesAsSource else viewModel.supportedLanguagesAsTarget
        Timber.tag(TAG).d("languages.size  ${languages.size} ${languages.map { it.displayName }}")

        val languageCodeHistory by (if (type.value == Type.SOURCE) viewModel.sourceLanguageCodeHistoryFlow else viewModel.targetLanguageCodeHistoryFlow).collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = emptyList<Language>()
        )

        // source language
        val sourceLanguageCode by viewModel.preferenceRepository.sourceLanguageCodeFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = "auto"
        )
        val sourceLanguage: Language = viewModel.translationRepository.getSupportedSourceLanguage(sourceLanguageCode)

        // target language
        val targetLanguageCode by viewModel.preferenceRepository.targetLanguageCodeFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = configuration.locales.get(0).language
        )
        val targetLanguage: Language = viewModel.translationRepository.getSupportedTargetLanguage(targetLanguageCode)

        val oppositeLanguage = if (type.value == Type.SOURCE) targetLanguage else sourceLanguage

        val listState = rememberLazyListState()

        val showHeaderDivider = remember {
            derivedStateOf {
                listState.firstVisibleItemScrollOffset > 0
            }
        }

        val showFooterDivider = remember {
            derivedStateOf {
                listState.canScrollForward
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = contentHorizontalPadding),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(viewWidth)
                    .padding(shadowPadding)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(
                            width = 0.1.dp,
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
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Header
                        Text(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Transparent)
                                .padding(start = contentHorizontalPadding, top = 16.dp, bottom = 4.dp, end = contentHorizontalPadding),
                            text = stringResource(id = if (type.value == Type.SOURCE) R.string.title_language_list_view_source else R.string.title_language_list_view_target),
                            style = MaterialTheme.typography.titleLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                            color = if (isDarkMode) Color.White else Color.Black
                        )

                        AnimatedVisibility(visible = showHeaderDivider.value) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = contentHorizontalPadding),
                                thickness = 0.2.dp,
                                color = Color.Gray
                            )
                        }

                        // 언어 리스트
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            state = listState
                        ) {
                            items(languageCodeHistory) { language ->
                                LanguageItem(
                                    language = language,
                                    oppositeLanguage = oppositeLanguage,
                                    contentColor = contentColor,
                                    contentDisabledColor = contentDisabledColor,
                                    contentHorizontalPadding = contentHorizontalPadding,
                                ) { selectedLanguage ->
                                    viewModel.updateLanguage(type.value == Type.SOURCE, selectedLanguage, oppositeLanguage)
                                    clear()
                                }
                            }

                            if (languageCodeHistory.isNotEmpty()) {
                                item {
                                    DottedDivider(
                                        horizontalPadding = contentHorizontalPadding,
                                    )
                                }
                            }

                            items(languages) { language ->
                                LanguageItem(
                                    language = language,
                                    oppositeLanguage = oppositeLanguage,
                                    contentColor = contentColor,
                                    contentDisabledColor = contentDisabledColor,
                                    contentHorizontalPadding = contentHorizontalPadding,
                                ) { selectedLanguage ->
                                    viewModel.updateLanguage(type.value == Type.SOURCE, selectedLanguage, oppositeLanguage)
                                    clear()
                                }
                            }
                        }

                        AnimatedVisibility(visible = showFooterDivider.value) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = contentHorizontalPadding),
                                thickness = 0.2.dp,
                                color = Color.Gray
                            )
                        }

                        // Footer
                        val coroutineScope = rememberCoroutineScope()
                        TextButton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 32.dp, vertical = 18.dp),
                            onClick = {
                                coroutineScope.launch {
                                    delay(200L)
                                    clear()
                                }
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
                        ) {
                            Text(
                                text = stringResource(id = android.R.string.cancel),
                                color = headerFooterColor,
                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 19.sp),
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun LanguageItem(
        language: Language,
        oppositeLanguage: Language,
        contentColor: Color,
        contentDisabledColor: Color,
        contentHorizontalPadding: Dp,
        onClick: (selectedLanguage: Language) -> Unit,
    ) {
        val coroutineScope = rememberCoroutineScope()
        val isDarkMode = isSystemInDarkTheme()
        // 화면 글자를 읽을 엔진이 없는 언어는 Claude 이미지 번역으로만 원문이 된다 — 지원 엔진은 Claude 하나만 보이고(supportKitTypes),
        // Claude 키가 있어야 고를 수 있다(§25)
        val claudeActivated by ClaudeKit.keyActivatedStateFlow.collectAsStateWithLifecycle()
        val imageOnly = type.value == Type.SOURCE && ImageTranslation.isImageOnlyLanguage(language.code)
        val enabled = (!imageOnly || claudeActivated && ImageTranslation.Switch.enabled) &&
                language.supportKitTypes.intersect(oppositeLanguage.supportKitTypes.toSet()).isNotEmpty()
        // 고를 수 없는 원문 언어는 엔진 로고도 회색으로 — 컬러 로고는 "이 엔진으로 번역된다" 로 읽힌다
        val grayLogos = type.value == Type.SOURCE && !enabled

        Button(
            onClick = {
                coroutineScope.launch {
                    delay(200L)
                    onClick(language)
                }
            },
            enabled = enabled,
            colors = ButtonDefaults.textButtonColors(contentColor = if (enabled) contentColor else contentDisabledColor),
            shape = RectangleShape,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RectangleShape),
            contentPadding = PaddingValues(horizontal = contentHorizontalPadding, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    horizontalAlignment = Alignment.Start,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = language.displayName,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
                        color = if (enabled) contentColor else contentDisabledColor
                    )
                    Text(
                        text = language.localDisplayName,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                        color = Color.Gray
                    )
                }
                language.supportKitTypes.forEach { kitType ->
                    val support =
                        if (type.value == Type.SOURCE) {
                            viewModel.translationRepository.isSupportedAsSource(kitType, language.code, oppositeLanguage.code)
                        } else {
                            viewModel.translationRepository.isSupportedAsTarget(kitType, language.code, oppositeLanguage.code)
                        }
                    Image(
                        painter = painterResource(
                            id = when {
                                !grayLogos -> kitType.ciResourceId
                                isDarkMode -> kitType.ciGrayDarkResourceId
                                else -> kitType.ciGrayResourceId
                            }
                        ),
                        contentDescription = "$kitType logo",
                        modifier = Modifier
                            // OpenAI 로고는 캔버스를 꽉 채워 같은 dp 에서 더 커 보이므로 살짝 줄인다
                            .size(if (kitType == TranslationKitType.OPENAI) 19.dp else 22.dp)
                            .align(Alignment.CenterVertically)
                            .alpha(if (support) 1.0f else 0.25f),
                    )
                }
            }
        }
    }
}


@Composable
fun DottedDivider(
    thickness: Dp = 0.7.dp,
    horizontalPadding: Dp
) {
    val isDarkMode = isSystemInDarkTheme()
    val dividerColor = if (isDarkMode) Color(0xFF6A6A6A) else Color(0xFF444444)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding)
    ) {
        val canvasWidth = size.width
        val pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 7f), 0f)
        drawLine(
            color = dividerColor,
            start = Offset(x = 0f, y = 0f),
            end = Offset(x = canvasWidth, y = 0f),
            pathEffect = pathEffect,
            strokeWidth = thickness.toPx()
        )
    }
}













