package com.galaxy.airviewdictionary.ui.screen.ads

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.galaxy.airviewdictionary.data.local.preference.PreferenceRepository
import com.galaxy.airviewdictionary.ui.common.ReviewPrompter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 인앱 리뷰를 청하기 위한 투명 액티비티 — Play 의 리뷰 흐름은 액티비티가 있어야 띄울 수 있고, 번역은 다른 앱 위의 오버레이에서
 * 일어나기 때문에 필요하다. 규칙은 [com.galaxy.airviewdictionary.ui.common.ReviewPromptPolicy], 요청은 [ReviewPrompter] 다.
 * 리뷰 흐름을 띄웠든 못 띄웠든 끝나면 바로 닫는다.
 */
@AndroidEntryPoint
class ReviewActivity : ComponentActivity() {

    @Inject
    lateinit var preferenceRepository: PreferenceRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            ReviewPrompter.requestAndLaunch(this@ReviewActivity, preferenceRepository)
            finish()
        }
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, ReviewActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
