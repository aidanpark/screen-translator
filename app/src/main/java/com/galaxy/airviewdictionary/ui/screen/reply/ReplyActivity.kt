package com.galaxy.airviewdictionary.ui.screen.reply

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.galaxy.airviewdictionary.data.local.preference.PreferenceRepository
import com.galaxy.airviewdictionary.R
import com.galaxy.airviewdictionary.data.remote.translation.TranslationKitType
import com.galaxy.airviewdictionary.data.remote.translation.Language
import com.galaxy.airviewdictionary.data.remote.translation.Transaction
import com.galaxy.airviewdictionary.ui.screen.AVDActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

const val TAG = "ReplyActivity"

@AndroidEntryPoint
class ReplyActivity : AVDActivity() {

    companion object {
        const val RESULT_TEXT = "RESULT_TEXT"
        const val DETECTED_LANGUAGE_CODE = "DETECTED_LANGUAGE_CODE"
        const val TARGET_LANGUAGE_CODE = "TARGET_LANGUAGE_CODE"

        fun start(context: Context, translationResultText: String?, detectedLanguageCode: String?, targetLanguageCode: String?) {
            Timber.tag(TAG).d("start ReplyActivity [$detectedLanguageCode] [$targetLanguageCode] [$translationResultText]")
            val intent = Intent(context, ReplyActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.putExtra(RESULT_TEXT, translationResultText)
            intent.putExtra(DETECTED_LANGUAGE_CODE, detectedLanguageCode)
            intent.putExtra(TARGET_LANGUAGE_CODE, targetLanguageCode)
            context.startActivity(intent)
        }
    }

    private val viewModel: ReplyViewModel by viewModels()

    private var translationResultTextState by mutableStateOf<String?>(null)
    private var detectedLanguageCodeState by mutableStateOf<String?>(null)
    private var targetLanguageCodeState by mutableStateOf<String?>(null)

    private var keyboardStateDebounceJob: Job? = null
    private val keyboardStateDScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val keyboardStates = mutableListOf<Boolean>()
    private var lastReportedKeyboardState: Boolean? = null

    private var latestTranslatedText: String? = null
    private var transaction: Transaction? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateIntentData(intent)

        /**
         * 키보드 상태 확인을 위한 FrameLayout
         */
        val rootView = KeyboardAwareFrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val composeView = ComposeView(this).apply {
            setContent {
                if (!translationResultTextState.isNullOrBlank() &&
                    !detectedLanguageCodeState.isNullOrBlank() &&
                    !targetLanguageCodeState.isNullOrBlank()
                ) {
                    ReplyScreen(
                        translationResultText = translationResultTextState!!,
                        detectedLanguageCode = detectedLanguageCodeState!!,
                        targetLanguageCode = targetLanguageCodeState!!
                    )
                }
            }
        }

        rootView.addView(composeView)
        setContentView(rootView)

        /**
         * 키보드 상태 확인
         */
        lifecycleScope.launch {
            delay(1000)
            rootView.keyboardListener = { isKeyboardVisible ->
                keyboardStates.add(isKeyboardVisible)

                keyboardStateDebounceJob?.cancel()
                keyboardStateDebounceJob = keyboardStateDScope.launch {
                    delay(200)

                    val finalState = keyboardStates.any { it }

                    if (finalState != lastReportedKeyboardState) {
                        lastReportedKeyboardState = finalState
                        Timber.tag(TAG).d("✅ 키보드 상태: $finalState")
                        if (!finalState) {
                            copyClipboard()
                            finish()
                        }
                    }
                    keyboardStates.clear()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            updateIntentData(intent)
        }
    }

    private fun updateIntentData(intent: Intent) {
        val translationResultText = intent.getStringExtra(RESULT_TEXT)
        val detectedLanguageCode = intent.getStringExtra(DETECTED_LANGUAGE_CODE)
        val targetLanguageCode = intent.getStringExtra(TARGET_LANGUAGE_CODE)

        if (!translationResultText.isNullOrBlank() &&
            !detectedLanguageCode.isNullOrBlank() &&
            !targetLanguageCode.isNullOrBlank()
        ) {
            Timber.tag(TAG).d("updateIntentData → [$detectedLanguageCode] [$targetLanguageCode] [$translationResultText]")

            translationResultTextState = translationResultText
            detectedLanguageCodeState = detectedLanguageCode
            targetLanguageCodeState = targetLanguageCode
        } else {
            Timber.tag(TAG).d("Received invalid intent data → finishing")
            finish()
        }
    }

    private fun copyClipboard(text: String? = latestTranslatedText) {
        Timber.tag(TAG).d("📋 copyClipboard : $text")
        if (!text.isNullOrBlank()) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Reply Text", text)
            clipboard.setPrimaryClip(clip)
            if(transaction != null) {
                viewModel.analyticsRepository.replyReport(transaction = transaction!!)
            }
        }
    }

    @Composable
    fun ReplyScreen(
        translationResultText: String,
        detectedLanguageCode: String,
        targetLanguageCode: String
    ) {
        viewModel.setSourceLanguageCode(targetLanguageCode)
        viewModel.setTargetLanguageCode(detectedLanguageCode)

        val activity = LocalContext.current as Activity
        val lifecycleOwner = LocalLifecycleOwner.current
        val isDarkMode = isSystemInDarkTheme()

        val focusRequester = remember { FocusRequester() }
        val keyboardController = LocalSoftwareKeyboardController.current

        val scope = rememberCoroutineScope()
        var userInput by rememberSaveable { mutableStateOf("") }
        var debounceJob by remember { mutableStateOf<Job?>(null) }

        val translationState by viewModel.translationFlow.collectAsStateWithLifecycle()

        // Reply transparency
        val replyTransparency by viewModel.preferenceRepository.replyTransparencyFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = PreferenceRepository.DEFAULT_TRANSPARENCY
        )

        // translationKit Type
        val translationKitType by viewModel.preferenceRepository.translationKitTypeFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = TranslationKitType.GOOGLE
        )

        // 배경 및 텍스트/아이콘 색상
        val backgroundColor = if (isDarkMode) Color(0xFFFEFEFE) else Color(0xFF1F1F1F)
        val translationResultTextColor = colorResource(if (isDarkMode) R.color.selected_text_color else R.color.selected_text_color_dark)
        val contentColor = if (isDarkMode) Color(0xFF454545) else Color(0xFFFDFDFD)
        val languageCodeColor = if (isDarkMode) Color(0xFFFDFDFD) else Color(0xFF454545)
        val borderColor = if (isDarkMode) Color(0xFFD6D6D6) else Color(0xFF6A6A6A)
        val roundedCornerShape = RoundedCornerShape(16.dp)
        val contentPadding = 12.dp

        LaunchedEffect(Unit) {
            delay(400)
            focusRequester.requestFocus()
            keyboardController?.show()
        }

        LaunchedEffect(translationState.resultText, userInput) {
            if (userInput.isEmpty()) {
                latestTranslatedText = null
                transaction = null
            } else {
                latestTranslatedText = translationState.resultText
                transaction = translationState
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
                .alpha(replyTransparency)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {
                    Timber
                        .tag(TAG)
                        .d("Background clicked → finish activity")
                    activity.finish()
                }
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Bottom,
            ) {
                Card(
                    modifier = Modifier
                        .clickable(enabled = false) { }
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .padding(contentPadding)
                        .border(1.dp, borderColor, roundedCornerShape),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = backgroundColor
                    ),
                    shape = roundedCornerShape,
                ) {
                    Column {
                        Row(modifier = Modifier.padding(top = 8.dp, start = 12.dp, end = 12.dp)) {
                            Box(
                                modifier = Modifier.padding(vertical = 3.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .background(
                                            color = contentColor,
                                            shape = RoundedCornerShape(4.dp)
                                        )
                                        .padding(horizontal = 6.dp, vertical = 1.dp) // 내부 여백
                                ) {
                                    Text(
                                        text = Language(targetLanguageCode).displayShortName,
                                        color = languageCodeColor,
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            val scrollState0 = rememberScrollState()
                            Box(
                                modifier = Modifier
                                    .padding(start = 6.dp)
                                    .heightIn(min = 0.dp, max = 110.dp)
                                    .verticalScroll(scrollState0)
                            ) {
                                Text(
                                    modifier = Modifier,
                                    text = translationResultText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = contentColor,
                                    softWrap = true
                                )
                            }
                        }

                        Row(modifier = Modifier.padding(top = 2.dp, start = 12.dp, end = 12.dp)) {
                            Box(
                                modifier = Modifier.padding(vertical = 3.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .background(
                                            color = translationResultTextColor,
                                            shape = RoundedCornerShape(4.dp)
                                        )
                                        .padding(horizontal = 6.dp, vertical = 1.dp) // 내부 여백
                                ) {
                                    Text(
                                        text = Language(detectedLanguageCode).displayShortName,
                                        color = Color.White,
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            val scrollState1 = rememberScrollState()
                            Box(
                                modifier = Modifier
                                    .padding(start = 6.dp)
                                    .heightIn(min = 0.dp, max = 110.dp)
                                    .verticalScroll(scrollState1)
                            ) {
                                Text(
                                    modifier = Modifier,
                                    text = if (userInput.isNotEmpty() && !translationState.resultText.isNullOrEmpty()) {
                                        translationState.resultText!!
                                    } else {
                                        ""
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = translationResultTextColor,
                                    softWrap = true
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 번역 kit 이미지
                            Image(
                                painter = painterResource(id = translationKitType.logoResourceId),
                                contentDescription = "Translated by $translationKitType",
                                modifier = Modifier
                                    .sizeIn(maxHeight = 16.dp)
                                    .padding(start = 8.dp),
                                contentScale = ContentScale.Fit,
                            )

                            Spacer(modifier = Modifier.weight(1f)) // 가운데 공간

                            // Copy
                            IconButton(
                                onClick = { copyClipboard() },
                                modifier = Modifier
                                    .size(32.dp)
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
                        }
                    }
                }

                TextField(
                    value = userInput,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { state ->
                            Timber
                                .tag(TAG)
                                .d("onFocusChanged [${state.isFocused}]")
                        },
//                    colors = TextFieldDefaults.colors(
//                        focusedContainerColor = MaterialTheme.colorScheme.surface,
//                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
//                        disabledContainerColor = MaterialTheme.colorScheme.surface,
//                    ),
                    onValueChange = {
                        userInput = it
                        debounceJob?.cancel()
                        debounceJob = scope.launch {
                            delay(200)
                            Timber.tag(TAG).d("requestTranslate %s", userInput)
                            viewModel.request(userInput)
                        }
                    },
                    keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        copyClipboard()
                        activity.finish()
                    }),
                    maxLines = 3,
                    singleLine = false
                )
            }
        }
    }
}

class KeyboardAwareFrameLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private var lastHeight = 0
    var keyboardListener: ((Boolean) -> Unit)? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val newHeight = MeasureSpec.getSize(heightMeasureSpec)
        if (lastHeight != 0) {
            val heightDiff = lastHeight - newHeight
            val isKeyboardVisible = heightDiff > lastHeight * 0.15
            keyboardListener?.invoke(isKeyboardVisible)
        }
        lastHeight = newHeight
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}





