package com.galaxy.airviewdictionary.ui.screen.overlay.settings


import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import com.galaxy.airviewdictionary.R
import com.galaxy.airviewdictionary.ui.common.fontDimensionResource
import com.galaxy.airviewdictionary.ui.screen.overlay.OverlayView
import javax.inject.Singleton

/**
 * 메뉴 text 뷰
 */
@Singleton
class SettingsMenuTextView private constructor() : OverlayView() {

    companion object {
        val INSTANCE: SettingsMenuTextView by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { SettingsMenuTextView() }
    }

    override lateinit var layoutParams: WindowManager.LayoutParams

    private val text = mutableStateOf<String?>(null)

    override val composable: @Composable () -> Unit = @Composable {
        if (isAttachedToWindow() && text.value != null) {
            val fontSize = fontDimensionResource(R.dimen.settings_menu_text_size)
            Text(
                text = text.value!!,
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = fontSize)
            )
        }
    }

    /**
     * [width] 는 설정 화면에서 이 이름이 차지한 폭(px). 같은 폭으로 그려야 긴 이름이 설정 화면과 같은 자리에서 줄바꿈된다.
     * 없으면 글자 길이만큼 그린다.
     */
    suspend fun cast(applicationContext: Context, text: String, position: Point, width: Int? = null) {
        layoutParams = WindowManager.LayoutParams(
            width ?: WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            position.x,
            position.y,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_DIM_BEHIND
                    or WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            windowAnimations = android.R.style.Animation_Toast
            dimAmount = 0.70f
        }
        this.text.value = text
        super.cast(applicationContext)
    }

}

















