package com.galaxy.airviewdictionary.ui.common

import android.app.Activity
import com.galaxy.airviewdictionary.BuildConfig
import com.galaxy.airviewdictionary.data.local.preference.PreferenceRepository
import com.galaxy.airviewdictionary.data.local.secure.SecureRepository
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestReview
import com.google.android.play.core.review.ReviewManagerFactory
import com.google.android.play.core.review.testing.FakeReviewManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * 인앱 리뷰 요청의 공통부 — 청해도 되는지 판정하고([isDue]), 청한 시각을 남기고([markPrompted]), Play 의 리뷰 흐름을
 * 띄운다([requestAndLaunch]). 규칙은 [ReviewPromptPolicy] 다. 광고 게이트([com.galaxy.airviewdictionary.ui.screen.ads.AdGateActivity])와
 * 설정 화면이 같이 쓴다.
 */
object ReviewPrompter {

    private const val TAG = "ReviewPrompter"

    /** Play 가 응답하지 않아도 화면이 남지 않도록 두는 한도. */
    private const val REQUEST_TIMEOUT_MILLIS = 5_000L

    suspend fun isDue(
        preferenceRepository: PreferenceRepository,
        secureRepository: SecureRepository,
        consecutiveSuccesses: Int = 0,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        var last = preferenceRepository.lastReviewPromptAtFlow.first()
        if (last <= 0L && preferenceRepository.isReviewDoneFlow.first()) {
            // 예전 방식(한 번 띄우면 다시 묻지 않음)으로 이미 청한 사용자는 업데이트한 날부터 간격을 센다.
            // 업데이트 직후 한꺼번에 다시 청하지 않는다.
            preferenceRepository.update(PreferenceRepository.LAST_REVIEW_PROMPT_AT, nowMillis)
            last = nowMillis
        }
        return ReviewPromptPolicy.isDue(
            translationCount = secureRepository.getTrialCount(),
            consecutiveSuccesses = consecutiveSuccesses,
            lastPromptAtMillis = last,
            nowMillis = nowMillis,
        )
    }

    /** 리뷰 흐름을 실제로 띄울 때 부른다. 띄우지 못했으면 부르지 않아 다음 기회에 다시 청한다. */
    fun markPrompted(preferenceRepository: PreferenceRepository, nowMillis: Long = System.currentTimeMillis()) {
        preferenceRepository.update(PreferenceRepository.LAST_REVIEW_PROMPT_AT, nowMillis)
        preferenceRepository.update(PreferenceRepository.IS_REVIEW_DONE, true)
    }

    /**
     * 리뷰 흐름을 청해 띄운다. 띄웠으면 true. Play 밖 설치 · 응답 지연 · 할당량 등으로 띄우지 못하면 false.
     * 띄울지는 Play 가 정하므로 true 가 별점을 남겼다는 뜻은 아니다.
     */
    suspend fun requestAndLaunch(activity: Activity, preferenceRepository: PreferenceRepository): Boolean = try {
        val manager = if (BuildConfig.DEBUG) FakeReviewManager(activity) else ReviewManagerFactory.create(activity)
        val reviewInfo = withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) { manager.requestReview() }
        if (reviewInfo == null) {
            Timber.tag(TAG).w("in-app review not ready in time")
            false
        } else {
            markPrompted(preferenceRepository)
            manager.launchReview(activity, reviewInfo)
            true
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.tag(TAG).w(e, "in-app review request failed")
        false
    }
}
