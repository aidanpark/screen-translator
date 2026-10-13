package com.galaxy.airviewdictionary.ui.screen.overlay.settings


import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.galaxy.airviewdictionary.core.OverlayService
import com.galaxy.airviewdictionary.ui.screen.overlay.OverlayView
import kotlinx.coroutines.flow.MutableStateFlow
import timber.log.Timber
import javax.inject.Singleton


/**
 * Settings Slider 뷰
 */
@Singleton
class SliderDialogView private constructor() : OverlayView() {

    companion object {
        val INSTANCE: SliderDialogView by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { SliderDialogView() }

        val liveStateFlow = MutableStateFlow(false)
    }

    private lateinit var viewModel: SliderDialogViewModel

    private val initialValue = mutableStateOf<Float?>(null)
    private val valueRange = mutableStateOf<ClosedFloatingPointRange<Float>?>(null)
    private val steps = mutableIntStateOf(0)
    private val onValueChange = mutableStateOf<((Float) -> Unit)?>(null)
    private val onDismissRequest = mutableStateOf<(() -> Unit)?>(null)
    private val speechRateText = mutableStateOf<String?>(null)

    override var layoutParams: WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.CENTER
    }

    override val composable: @Composable () -> Unit = @Composable {
        if (isAttachedToWindow() && initialValue.value != null && valueRange.value != null && onValueChange.value != null && onDismissRequest.value != null) {
            SliderDialog(
                initialValue = initialValue.value!!,
                valueRange = valueRange.value!!,
                steps = steps.intValue,
                onValueChange = onValueChange.value!!,
                onDismissRequest = onDismissRequest.value!!,
            )
        }
    }

    override fun onServiceConnected(overlayService: OverlayService) {
        viewModel = overlayService.getSliderDialogViewModel()
        super.onServiceConnected(overlayService)
    }

    suspend fun cast(
        applicationContext: Context,
        initialValue: Float,
        valueRange: ClosedFloatingPointRange<Float>,
        steps: Int = 0,
        onValueChange: (Float) -> Unit,
        menuText: Pair<String, Point>? = null,
        menuTextWidth: Int? = null,
        menuSubtext: Pair<MutableStateFlow<String>, Point>? = null,
        dockingDelayText: Pair<MutableStateFlow<String>, Point>? = null,
        menuBarVisibilityText: Pair<MutableStateFlow<String>, Point>? = null,
        menuBarConfigText: Pair<MutableStateFlow<String>, Point>? = null,
        speechRateText: Pair<MutableStateFlow<Float>, Point>? = null,
        onDismissRequest: () -> Unit,
    ) {
        menuText?.let {
            SettingsMenuTextView.INSTANCE.cast(applicationContext, it.first, it.second, menuTextWidth)
        }
        menuSubtext?.let {
            SettingsMenuSubtextView.INSTANCE.cast(applicationContext, it.first, it.second)
        }
        dockingDelayText?.let {
            SettingsDockingDelayView.INSTANCE.cast(applicationContext, it.first, it.second)
        }
        menuBarVisibilityText?.let {
            SettingsMenuBarTransparencyView.INSTANCE.cast(applicationContext, it.first, it.second)
        }
        menuBarConfigText?.let {
            SettingsMenuBarConfigView.INSTANCE.cast(applicationContext, it.first, it.second)
        }
        speechRateText?.let {
            SettingsTTSSpeechRateView.INSTANCE.cast(applicationContext, it.first, it.second)
        }

        this.initialValue.value = initialValue
        this.valueRange.value = valueRange
        this.steps.intValue = steps
        this.onValueChange.value = onValueChange
        this.onDismissRequest.value = onDismissRequest
        this.speechRateText.value = if (speechRateText != null) menuText?.first else null
        super.cast(applicationContext)

        speechRateText?.let {
            viewModel.playSampleVoice()
        }

        liveStateFlow.value = true
    }

    override fun clear() {
        liveStateFlow.value = false
        SettingsMenuTextView.INSTANCE.clear()
        SettingsMenuSubtextView.INSTANCE.clear()
        SettingsDockingDelayView.INSTANCE.clear()
        SettingsMenuBarTransparencyView.INSTANCE.clear()
        SettingsMenuBarConfigView.INSTANCE.clear()
        SettingsTTSSpeechRateView.INSTANCE.clear()
        super.clear()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun SliderDialog(
        initialValue: Float,
        valueRange: ClosedFloatingPointRange<Float>,
        steps: Int,
        onValueChange: (Float) -> Unit,
        onDismissRequest: () -> Unit
    ) {
        val configuration = LocalConfiguration.current
        val horizontalPadding = if (configuration.orientation == Configuration.ORIENTATION_PORTRAIT) 72.dp else 192.dp
        val sliderPosition = remember { mutableFloatStateOf(initialValue) }

        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
                    .padding(horizontal = horizontalPadding)
                    .height(140.dp)
                    .fillMaxWidth()
//                    .background(Color(0x33fafafa))
            ) {
                val interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }
                Slider(
                    thumb = {
                        SliderDefaults.Thumb(
                            interactionSource = interactionSource,
                            colors = SliderDefaults.colors().copy(thumbColor = Color(0xFF6a91b2)),
                            thumbSize = DpSize(15.dp, 40.dp)
                        )
                    },
                    value = sliderPosition.floatValue,
                    steps = steps,
                    onValueChange = {
                        sliderPosition.floatValue = it
                        Timber.tag(TAG).d("onValueChange $it")
                        onValueChange(it)
                    },
                    colors = SliderDefaults.colors(
                        activeTrackColor = Color(0xFFaccfeb),
                        inactiveTrackColor = Color(0xFF6a91b2),
                    ),
                    valueRange = valueRange,
                    onValueChangeFinished = {
                        speechRateText.value?.let {
                            viewModel.playSampleVoice()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
//                        .background(Color(0x55fafafa))
                        .align(Alignment.Center)
                )
            }

            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(bottom = 24.dp + 140.dp)
                    .pointerInput(Unit) {
                        detectTapGestures {
                            onDismissRequest()
                        }
                    }
//                    .background(Color(0x55fafafa))
            )
        }
    }
}

















