package com.galaxy.airviewdictionary.ui.screen.ads

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import com.google.android.play.core.review.testing.FakeReviewManager
import com.google.android.play.core.review.ReviewManagerFactory
import com.google.android.play.core.ktx.requestReview
import com.google.android.play.core.ktx.launchReview
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.galaxy.airviewdictionary.BuildConfig
import com.galaxy.airviewdictionary.R
import com.galaxy.airviewdictionary.data.local.ads.AdGateState
import com.galaxy.airviewdictionary.data.local.ads.RewardedAdCache
import com.galaxy.airviewdictionary.data.local.preference.PreferenceRepository
import com.galaxy.airviewdictionary.data.remote.firebase.RemoteConfigRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.galaxy.airviewdictionary.ui.screen.main.GoogleMobileAdsConsentManager
import com.galaxy.airviewdictionary.ui.screen.overlay.targethandle.TargetHandleView
import com.galaxy.airviewdictionary.ui.screen.overlay.translation.TranslationErrorView
import com.galaxy.airviewdictionary.ui.screen.overlay.translation.TranslationView
import com.galaxy.airviewdictionary.ui.screen.overlay.visiontext.VisionTextView
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 광고 게이트 전용 투명 액티비티.
 * 설정 화면을 노출하지 않고, 안내 다이얼로그 → 리워드 광고 순서로 진행한다.
 *
 * 흐름:
 * 1. 안내 다이얼로그 표시 + 광고 로드 시작 (확인 버튼 비활성)
 * 2. 로드 성공/실패가 확정되면 확인 버튼 활성화
 * 3. 확인 클릭 → 로드 성공이면 광고 표시 / 실패면 기술적 실패로 종료(5분 사용권)
 *    (광고를 띄우기 시작하면 다이얼로그 대신 진행 표시만 남는다)
 * 4. 끝까지 봤으면 두 번째 게이트부터 인앱 리뷰를 한 번 청한 뒤 종료
 *
 * 보상 규칙:
 * - 끝까지 시청(onUserEarnedReward): 앱 종료 시까지 광고 없이 사용
 * - 로드 실패 / 표시 실패 / 동의 미확보 (기술적 사유): 5분 사용권 부여 후 종료
 * - 스킵(중간에 닫기·뒤로가기·홈키 중단): 짧은 쿨다운(Remote Config `skip_cooldown_seconds`, 기본 60초) 뒤 다음 번역 시 게이트가 다시 뜬다
 */
@AndroidEntryPoint
class AdGateActivity : ComponentActivity() {

    @Inject
    lateinit var preferenceRepository: PreferenceRepository

    @Inject
    lateinit var remoteConfigRepository: RemoteConfigRepository


    private val TAG = javaClass.simpleName

    companion object {

        /** 광고 게이트가 살아 있는지 여부 (중복 실행 방지용). 오버레이를 가릴지는 [coveringFlow] 가 정한다. */
        val liveStateFlow = MutableStateFlow(false)

        /**
         * 광고 게이트나 광고 화면이 플로팅 오버레이를 가리고 있는가. 메뉴바는 이것을 보고 숨는다(핸들·번역창·인식 하이라이트는
         * [coverOverlays] 가 직접 숨긴다).
         *
         * 게이트가 살아 있는지([liveStateFlow])와 다르다. 광고를 클릭해 다른 앱에 가 있는 동안에는 게이트가 살아 있어도 오버레이를
         * 되돌리고, 보상 뒤 게이트가 닫힌 다음에도 광고 화면으로 돌아오면 다시 가린다(App 이 광고 화면의 시작·정지를 알린다).
         */
        val coveringFlow = MutableStateFlow(false)

        /** 광고 SDK 의 전체 화면 광고 액티비티. 우리 프로세스에서 게이트의 태스크 위에 뜬다. */
        private const val AD_SCREEN = "com.google.android.gms.ads.AdActivity"

        /** [activity] 가 오버레이를 가려야 하는 화면인가 — 게이트 자신이거나 광고 화면. */
        fun coversOverlays(activity: Activity): Boolean = activity is AdGateActivity || isAdScreen(activity)

        fun isAdScreen(activity: Activity): Boolean = activity.javaClass.name == AD_SCREEN

        /** 플로팅 오버레이가 게이트·광고 위에 뜨지 않도록 숨긴다. */
        fun coverOverlays() {
            coveringFlow.value = true
            TargetHandleView.INSTANCE.hideTemporarily()
            TranslationView.INSTANCE.hideTemporarily()
            TranslationErrorView.INSTANCE.hideTemporarily()
            VisionTextView.INSTANCE.hideTemporarily()
        }

        /** [coverOverlays] 로 숨긴 오버레이를 되돌린다. */
        fun uncoverOverlays() {
            coveringFlow.value = false
            TargetHandleView.INSTANCE.showFromTemporaryHide()
            TranslationView.INSTANCE.showFromTemporaryHide()
            TranslationErrorView.INSTANCE.showFromTemporaryHide()
            VisionTextView.INSTANCE.showFromTemporaryHide()
        }

        /**
         * 광고 화면이 내려갔다. 게이트가 살아 있으면 두고(게이트가 닫힐 때 되돌린다), 없으면 되돌린다 — 보상을 받은 뒤 광고를 클릭해
         * 나갔다가 돌아와 광고를 닫은 경우다. 게이트는 앱이 백그라운드로 갈 때 이미 닫혔다([finishIfAppBackgrounded]).
         */
        fun onAdScreenStopped() {
            val gate = liveInstance?.get()
            if (gate == null || gate.isFinishing || gate.isDestroyed) uncoverOverlays()
        }

        /** 현재 살아 있는 게이트. 앱이 백그라운드로 내려갔을 때 정리하기 위해 들고 있는다. */
        private var liveInstance: WeakReference<AdGateActivity>? = null

        /**
         * 앱 전체가 백그라운드로 내려갔을 때 살아 있는 게이트를 정리한다.
         * 광고 전이면 스킵으로, 보상을 받은 뒤(리뷰 단계 포함)면 그냥 종료한다.
         * 광고를 보는 도중이면 게이트는 두고 오버레이만 되돌린다.
         *
         * 게이트는 onCreate 에서 플로팅 오버레이를 숨기고 onDestroy 에서 복원하는데,
         * 광고 도중/직후에 홈키로 나가면 stop 만 되고 destroy 는 되지 않아
         * 핸들과 메뉴바가 숨겨진 채로 영영 남는다. 여기서 정리하면 기존 onDestroy
         * 경로를 그대로 타서 복원된다. (홈키 중단은 원래 스킵 취급이다)
         *
         * 광고가 화면에 떠 있는 동안에는 우리 액티비티가 started 상태라 호출되지 않으므로,
         * 핸들이 광고 위에 노출될 일은 없다.
         */
        fun finishIfAppBackgrounded() {
            val activity = liveInstance?.get() ?: return
            if (activity.isFinishing || activity.isDestroyed) return
            if (activity.rewardEarned || activity.requestingReview) {
                // 보상은 이미 받았다(adFreeSession). 남은 일은 리뷰뿐이라 게이트를 붙잡아 둘 이유가 없다.
                // 붙잡아 두면 두 가지가 샌다:
                // - ReviewInfo 를 기다리는 중이면, 응답이 온 뒤 백그라운드에서 리뷰를 띄워 다른 앱 위에
                //   dim 과 리뷰 창이 뜬다(오버레이 권한이 있어 백그라운드 시작 제한을 받지 않는다).
                // - 리뷰 창이 뜬 채 나갔으면 launchReview 가 끝나지 않는다. 게이트 태스크는 최근 앱에 없어
                //   돌아올 길도 없고, 그동안 메뉴바·핸들·번역창이 숨겨진 채 남는다.
                // 광고 클릭으로 나간 경우라도 돌아와 광고를 마저 봐서 받을 보상이 더 없다.
                // 돌아와 본 광고 끝 화면은 App 이 광고 화면의 시작을 보고 다시 가린다([coversOverlays]).
                Timber.tag(activity.TAG).i("App backgrounded after the reward; finishing gate")
                activity.runOnUiThread { activity.finishGate() }
                return
            }
            if (activity.adShown) {
                // 광고가 이미 표시된 뒤의 이탈은 "광고 클릭 → 광고주 페이지"일 수 있다.
                // 여기서 게이트를 닫아버리면 돌아와 광고를 마저 봐도 보상을 받지 못한다.
                // 게이트는 살려두고 숨겨둔 오버레이만 되돌려, 홈 화면에서 핸들·메뉴바가 사라진 채
                // 남는 것만 막는다. 광고 화면으로 돌아오면 App 이 다시 가린다([coversOverlays]).
                Timber.tag(activity.TAG).i("App backgrounded during ad; keeping gate, restoring overlays")
                activity.runOnUiThread { uncoverOverlays() }
                return
            }
            Timber.tag(activity.TAG).i("App backgrounded before ad; finishing gate as skip")
            activity.runOnUiThread { activity.finishAsSkip() }
        }

        private val isMobileAdsInitializeCalled = AtomicBoolean(false)

        /** 광고 로드 대기 한계. 초과 시 로드 실패로 확정한다 */
        private const val LOAD_TIMEOUT_MILLIS = 15_000L

        /** 인앱 리뷰를 청하기 시작하는 게이트 순번(누적). */
        private const val REVIEW_FROM_GATE_COUNT = 2

        /** 인앱 리뷰 요청 대기 한계: ReviewInfo 받기 + 게이트가 다시 앞에 오기까지. 넘기면 리뷰 없이 닫는다. */
        private const val REVIEW_REQUEST_TIMEOUT_MILLIS = 5_000L

        fun start(context: Context) {
            val intent = Intent(context, AdGateActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    /** 광고 로드 진행 상태. 확인 버튼은 Loading 이 아닐 때만 활성화된다. */
    private enum class AdLoadState { Loading, Loaded, Failed }

    private val adLoadStateFlow = MutableStateFlow(AdLoadState.Loading)

    private lateinit var googleMobileAdsConsentManager: GoogleMobileAdsConsentManager

    private var isRewardedAdLoading = false

    private var rewardedAd: RewardedAd? = null

    private var finished = false

    /** 이 게이트의 광고 로드 실패를 이미 기록했다 — 시간 초과 뒤 같은 로드가 늦게 실패해도 한 번만 센다(코드 정리 A6). */
    private var loadFailureRecorded = false

    /**
     * 확인을 눌러 광고를 띄우기 시작했는지. 이때부터 다이얼로그 대신 진행 표시만 보인다 —
     * 광고는 한 번만 띄우므로 확인 버튼이 남아 있으면 눌러도 아무 일이 없다(광고가 닫힌 뒤 리뷰를 기다리는 동안 등).
     */
    private val adStartedFlow = MutableStateFlow(false)

    /** 광고가 전체화면으로 표시되었는지 여부. 종료 콜백 유실 대비 안전망(onResume)에서 사용. */
    private var adShown = false

    /** 광고를 끝까지 봐서 보상(adFreeSession)을 받았는지. */
    private var rewardEarned = false

    private var timeoutJob: Job? = null

    /**
     * 인앱 리뷰를 청하는 중(ReviewInfo 받기부터 리뷰 창이 닫힐 때까지).
     * 그동안은 onResume 안전망이 게이트를 스킵으로 닫지 않고, 앱이 백그라운드로 가면 게이트를 바로 닫는다.
     */
    private var requestingReview = false

    /** 리뷰 창을 띄웠다. 이 뒤로 게이트가 다시 앞에 오면 리뷰 창은 닫힌 것이다. */
    private var reviewLaunched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 15(SDK 35)+ edge-to-edge 강제에 맞춰 이전 버전에서도 동일 동작 (Play Console 권장 조치)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        liveStateFlow.value = true
        liveInstance = WeakReference(this)
        // 게이트가 열린 횟수를 센다. 화면 재생성(다크 모드·글꼴 크기·멀티 윈도우 등)은 새 게이트가 아니다.
        // 값은 필드에 들고 있지 않는다 — 재생성되면 필드는 0 으로 돌아간다. 리뷰를 정할 때 저장소에서 다시 읽는다.
        if (savedInstanceState == null) {
            lifecycleScope.launch { preferenceRepository.incrementAdGateOpenCount() }
        }

        // 뒤 화면 전체를 어둡게 덮는다 (SplashActivity 와 동일한 검증된 패턴: 윈도우 레벨 dim)
        val layoutParams = window.attributes
        layoutParams.dimAmount = 0.50f
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = layoutParams

        // 광고 게이트~광고 종료 동안 플로팅 오버레이(핸들/번역창/인식 하이라이트)가 광고 위에 떠 있지 않도록 숨긴다.
        // 메뉴바(MenuBarView)는 coveringFlow 를 구독하여 스스로 숨긴다.
        coverOverlays()

        // 뒤로가기로 다이얼로그를 닫는 것은 광고 스킵과 동일 취급 (스킵 쿨다운 적용)
        onBackPressedDispatcher.addCallback(this) {
            finishAsSkip()
        }

        setContent {
            val adLoadState by adLoadStateFlow.collectAsState()
            val adStarted by adStartedFlow.collectAsState()

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (adStarted) {
                    // 광고를 띄운 뒤에는 게이트가 닫히거나 리뷰 창이 뜨기를 기다릴 뿐이다.
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                } else Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xF2222222),
                ) {
                    Column(
                        modifier = Modifier
                            .width(300.dp)
                            .height(320.dp)
                            .padding(horizontal = 24.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // 콘텐츠 영역: 남는 공간을 차지하며 세로 중앙 정렬
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // 상태 아이콘 슬롯 (고정 크기 - 상태가 바뀌어도 창 크기가 변하지 않도록)
                            Box(
                                modifier = Modifier.height(48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                when (adLoadState) {
                                    AdLoadState.Loading -> CircularProgressIndicator(
                                        color = Color.White,
                                        modifier = Modifier.size(40.dp)
                                    )

                                    AdLoadState.Loaded -> Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = "Ad ready",
                                        tint = Color(0xFF81C784),
                                        modifier = Modifier.size(48.dp)
                                    )

                                    AdLoadState.Failed -> Icon(
                                        imageVector = Icons.Rounded.CloudOff,
                                        contentDescription = "Ad unavailable",
                                        tint = Color(0x99FFFFFF),
                                        modifier = Modifier.size(44.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(18.dp))
                            // 로딩 문구 슬롯 (2줄 높이 예약 - 사라져도 창 크기가 변하지 않도록)
                            Text(
                                text = if (adLoadState == AdLoadState.Loading) stringResource(R.string.ad_gate_loading) else "",
                                color = Color.White,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                                minLines = 2,
                                maxLines = 2,
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = stringResource(R.string.ad_gate_reward_notice),
                                color = Color(0xB3FFFFFF),
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        // 확인 버튼: 다이얼로그 하단에 전체 너비로 고정. 로드 확정 전에는 비활성.
                        Button(
                            onClick = {
                                when (adLoadStateFlow.value) {
                                    AdLoadState.Loaded -> showRewardedVideo()
                                    AdLoadState.Failed -> finishAsFailure()
                                    AdLoadState.Loading -> Unit // disabled 상태라 도달하지 않음
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            // 광고 로드 성공/실패가 확정되어야 활성화된다
                            enabled = adLoadState != AdLoadState.Loading,
                        ) {
                            Text(
                                text = stringResource(android.R.string.ok),
                                fontSize = 15.sp,
                            )
                        }
                    }
                }
            }
        }

        // 광고 로드가 오래 걸리면 로드 실패로 확정한다 (버튼이 영영 비활성으로 남지 않도록)
        timeoutJob = lifecycleScope.launch {
            delay(LOAD_TIMEOUT_MILLIS)
            if (!finished && adLoadStateFlow.value == AdLoadState.Loading) {
                Timber.tag(TAG).w("Ad load timeout -> Failed")
                adLoadStateFlow.value = AdLoadState.Failed
                // 타임아웃도 "이 기기에서 광고가 안 나온다"는 신호다 (이란처럼 요청이 나가지 않는 경우)
                recordAdLoadFailure()
            }
        }

        googleMobileAdsConsentManager = GoogleMobileAdsConsentManager.getInstance(this)
        googleMobileAdsConsentManager.gatherConsent(this) { error ->
            if (error != null) {
                Timber.tag(TAG).d("gatherConsent error ${error.errorCode}: ${error.message}")
            }
            if (googleMobileAdsConsentManager.canRequestAds) {
                initializeMobileAdsSdk()
            } else {
                // 동의 미확보 → 로드 실패로 확정
                adLoadStateFlow.value = AdLoadState.Failed
            }
        }

        // 이전 세션에서 확보한 동의로 즉시 로드 시도
        if (googleMobileAdsConsentManager.canRequestAds) {
            initializeMobileAdsSdk()
        }
    }

    override fun onResume() {
        super.onResume()
        // 리뷰 창을 띄운 뒤 게이트가 다시 앞에 왔다 = 리뷰 창이 닫혔다. 보통은 launchReview 가 곧 끝나
        // 게이트를 닫는다. 끝나지 않더라도 게이트가 남지 않게 잠시 기다렸다가 닫는다.
        if (reviewLaunched && !finished) {
            lifecycleScope.launch {
                delay(1000) // 정상 흐름이 먼저 닫을 시간
                if (!finished) {
                    Timber.tag(TAG).w("Back at the gate but the review flow did not finish; finishing (safety net)")
                    finishGate()
                }
            }
            return
        }
        // 안전망: 광고가 표시된 후 게이트로 복귀했는데(=광고가 닫혔는데)
        // 종료 콜백(onAdDismissed/onAdFailedToShow)이 유실된 경우에도
        // 게이트가 화면에 남지 않도록 잠시 기다렸다가 스킵 처리로 종료한다.
        // 리뷰를 청하는 중이면 건드리지 않는다 — 요청 한도가 끝을 보장한다.
        if (adShown && !finished && !requestingReview) {
            lifecycleScope.launch {
                delay(1000) // 정상 콜백이 먼저 처리될 시간
                if (adShown && !finished && !requestingReview) {
                    Timber.tag(TAG).w("Ad closed but no dismiss callback; finishing as skip (safety net)")
                    finishAsSkip()
                }
            }
        }
    }

    override fun onDestroy() {
        liveStateFlow.value = false
        if (liveInstance?.get() === this) liveInstance = null
        timeoutJob?.cancel()
        // 숨겨둔 플로팅 오버레이 복원 (메뉴바는 coveringFlow 변경으로 스스로 복원)
        uncoverOverlays()
        super.onDestroy()
    }

    private fun initializeMobileAdsSdk() {
        if (isMobileAdsInitializeCalled.getAndSet(true)) {
            loadRewardedAd()
            return
        }

        // 개발자 기기는 릴리스 빌드에서도 항상 테스트 광고를 받는다.
        // (개발자가 실광고를 직접 시청/클릭하면 AdMob 무효 트래픽으로 계정 제재 위험이 있다)
        // 값은 기기 광고 ID 의 해시라 다른 사용자 기기에는 아무 영향이 없다.
        MobileAds.setRequestConfiguration(
            RequestConfiguration.Builder().setTestDeviceIds(
                listOf(
                    "BA6732E32C6CA0D01FB929ECC2FDA19F", // 개발 에뮬레이터
                    "D6702C0742CE9DD6BBA2193ED921D92E", // SM-G991N 실기기
                    "DCD83E4C403226BC195BA6E28FD369EC", // SM-S947N 실기기
                )
            ).build()
        )

        CoroutineScope(Dispatchers.IO).launch {
            // Initialize the Google Mobile Ads SDK on a background thread.
            MobileAds.initialize(this@AdGateActivity) {}
            runOnUiThread {
                loadRewardedAd()
            }
        }
    }

    private fun loadRewardedAd() {
        if (finished || isRewardedAdLoading || rewardedAd != null) return

        // 이전 게이트에서 로드해두고 표시하지 않은 광고가 있으면 새로 요청하지 않는다.
        // (요청만 쌓이고 노출이 없으면 AdMob 무효 트래픽으로 본다 — [RewardedAdCache])
        RewardedAdCache.get()?.let { cached ->
            Timber.tag(TAG).i("loadRewardedAd: 캐시된 광고 재사용")
            rewardedAd = cached
            timeoutJob?.cancel()
            adLoadStateFlow.value = AdLoadState.Loaded
            return
        }

        isRewardedAdLoading = true

        val adUnitId =
            if (BuildConfig.DEBUG) {
                "ca-app-pub-xxxxxxxxxxxxxxxx/xxxxxxxxxx" // Test ad unit ID
            } else {
                remoteConfigRepository.adUnitId()
            }
        Timber.tag(TAG).i("loadRewardedAd adUnitId $adUnitId")

        RewardedAd.load(
            // 로드한 광고는 액티비티보다 오래 살아남아 재사용되므로(RewardedAdCache),
            // 액티비티를 붙들지 않도록 applicationContext 로 로드한다. 표시할 때만 액티비티를 넘긴다.
            applicationContext,
            adUnitId,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    Timber.tag(TAG).d("onAdFailedToLoad: ${adError.message}")
                    isRewardedAdLoading = false
                    rewardedAd = null
                    recordAdLoadFailure()
                    // 로드 실패 확정 → 확인 버튼 활성화 (누르면 기술적 실패로 종료 — 5분 사용권)
                    adLoadStateFlow.value = AdLoadState.Failed
                }

                override fun onAdLoaded(ad: RewardedAd) {
                    Timber.tag(TAG).d("Ad was loaded.")
                    isRewardedAdLoading = false
                    rewardedAd = ad
                    // 표시하기 전까지 보관 → 스킵으로 게이트가 닫혀도 다음 번에 재사용한다.
                    RewardedAdCache.put(ad)
                    recordAdLoadSuccess()
                    timeoutJob?.cancel()
                    // 로드 성공 확정 → 확인 버튼 활성화 (누르면 광고 표시)
                    adLoadStateFlow.value = AdLoadState.Loaded
                }
            },
        )
    }

    /**
     * 광고 로드 실패를 기록한다. 연속 실패가 임계치에 닿으면 일정 시간 게이트를 열지 않는다.
     * 광고가 제공되지 않는 지역(러시아·이란 등)에서 수익 없이 경험만 깎는 것을 막는다.
     */
    private fun recordAdLoadFailure() {
        if (loadFailureRecorded) return
        loadFailureRecorded = true
        val policy = remoteConfigRepository.getAdGatePolicy()
        if (!policy.isBackoffEnabled) return

        val suppressedUntil =
            AdGateState.recordAdLoadFailure(policy.failureThreshold, policy.backoffMillis)
        preferenceRepository.update(
            PreferenceRepository.AD_LOAD_FAILURE_STREAK,
            AdGateState.failureStreak,
        )
        if (suppressedUntil != null) {
            preferenceRepository.update(
                PreferenceRepository.AD_GATE_SUPPRESSED_UNTIL,
                suppressedUntil,
            )
            Timber.tag(TAG).i(
                "광고 로드 ${policy.failureThreshold}회 연속 실패 — ${policy.backoffHours}시간 게이트 억제"
            )
        }
    }

    /** 광고가 한 번이라도 로드되면 연속 실패 기록과 억제를 해제한다. */
    private fun recordAdLoadSuccess() {
        if (!AdGateState.recordAdLoadSuccess()) return
        preferenceRepository.update(PreferenceRepository.AD_LOAD_FAILURE_STREAK, 0)
        preferenceRepository.update(PreferenceRepository.AD_GATE_SUPPRESSED_UNTIL, 0L)
        Timber.tag(TAG).i("광고 로드 성공 — 연속 실패 기록/억제 해제")
    }

    private fun showRewardedVideo() {
        // 연타로 같은 광고를 두 번 띄우지 않는다(두 번째는 표시 실패로 게이트를 닫아 버린다).
        if (finished || adShown || adStartedFlow.value) return

        val ad = rewardedAd
        if (ad == null) {
            finishAsSkip()
            return
        }

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                Timber.tag(TAG).d("Ad dismissed. earned=$rewardEarned")
                rewardedAd = null
                if (rewardEarned) {
                    // 완주 → 이미 adFreeSession 이 부여됐다. 때가 됐으면 리뷰를 청한 뒤 닫는다.
                    requestReviewIfDueThenFinish()
                } else {
                    // 끝까지 보지 않고 닫음(홈키 중단 포함)은 스킵과 동일 취급.
                    finishAsSkip()
                }
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                Timber.tag(TAG).d("Ad failed to show: ${adError.message}")
                rewardedAd = null
                // 표시에 실패한 광고를 캐시에 두면 다음 게이트에서도 같은 실패를 반복한다.
                RewardedAdCache.clear()
                finishAsFailure()
            }

            override fun onAdShowedFullScreenContent() {
                Timber.tag(TAG).d("Ad showed fullscreen content.")
                adShown = true
                // 한 번 표시한 광고는 다시 보여줄 수 없다.
                RewardedAdCache.clear()
            }
        }

        adStartedFlow.value = true
        ad.show(this) {
            // 끝까지 시청 → 이번 세션 동안 광고 없이 사용
            rewardEarned = true
            AdGateState.grantAdFreeSession()
            Timber.tag(TAG).i("User earned the reward -> ad-free session")
        }
    }

    /**
     * 사용자 스킵 취급: 쿨다운만큼 유예를 주고 종료 → 그 뒤 번역부터 게이트가 다시 뜬다.
     * 쿨다운 길이는 Remote Config 에서 온다 ([AdGatePolicy.skipCooldownSeconds]).
     */
    private fun finishAsSkip() {
        if (finished) return
        AdGateState.grantSkipWindow(remoteConfigRepository.getAdGatePolicy().skipCooldownMillis)
        finishGate()
    }

    /** 기술적 실패(로드/표시/동의) 취급: 5분 사용권 부여 후 종료 */
    private fun finishAsFailure() {
        if (finished) return
        AdGateState.grantFailureWindow()
        finishGate()
    }

    /**
     * 두 번째 게이트부터, 광고를 끝까지 본 직후에 한 번 인앱 리뷰를 청한다. 스킵·실패 직후에는 청하지 않는다 —
     * 다음 게이트에서 끝까지 봤을 때 다시 본다.
     *
     * 리뷰는 보상과 엮지 않는다(Play 정책). 사용권은 광고를 봐서 이미 받았다.
     * 실제로 띄울지는 Play 가 정하고(사용자별 할당량), 별점을 남겼는지도 알려 주지 않는다.
     * 그래서 리뷰 흐름을 한 번 띄우고 나면 다시 묻지 않는다([PreferenceRepository.IS_REVIEW_DONE]).
     * 게이트가 앞에 없어 띄우지 못했으면 기록하지 않고 다음 게이트에서 다시 청한다.
     */
    private fun requestReviewIfDueThenFinish() {
        // 안전망이나 백그라운드 정리로 이미 닫힌 뒤 늦게 온 콜백이면 리뷰를 띄우지 않는다.
        if (finished) return
        // 코루틴이 돌기 전에 세운다 — 그 사이 onResume 안전망이 게이트를 스킵으로 닫지 않도록.
        requestingReview = true
        lifecycleScope.launch {
            try {
                if (isReviewDue()) requestAndLaunchReview()
            } finally {
                requestingReview = false
            }
            finishGate()
        }
    }

    private suspend fun isReviewDue(): Boolean =
        preferenceRepository.adGateOpenCountFlow.first() >= REVIEW_FROM_GATE_COUNT &&
                !preferenceRepository.isReviewDoneFlow.first()

    /** 리뷰 창을 청해 띄우고, 사용자가 닫을 때까지 기다린다. 띄울 수 없으면 그냥 돌아온다. */
    private suspend fun requestAndLaunchReview() {
        try {
            val manager = if (BuildConfig.DEBUG) FakeReviewManager(applicationContext) else ReviewManagerFactory.create(applicationContext)
            // 요청이 응답하지 않아도 게이트가 남지 않게 한도를 둔다. 광고가 닫혔다는 콜백은 게이트가 다시
            // 앞에 오기 전에 올 수 있어서, 게이트가 앞(RESUMED)에 올 때까지도 같은 한도 안에서 기다린다.
            val reviewInfo = withTimeoutOrNull(REVIEW_REQUEST_TIMEOUT_MILLIS) {
                manager.requestReview().also { lifecycle.withResumed { } }
            }
            if (reviewInfo == null) {
                Timber.tag(TAG).w("in-app review not ready in time; finishing without it")
                return
            }
            // 기다리는 사이 게이트가 닫혔거나 앱이 백그라운드로 갔으면 띄우지 않는다.
            // 백그라운드에서 띄우면 다른 앱 위에 dim 과 리뷰 창이 뜬다.
            if (finished || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                Timber.tag(TAG).i("Gate no longer in front; skipping in-app review")
                return
            }
            // 실제로 띄울 때만 기록한다. 건너뛴 경우는 다음 게이트에서 다시 청한다.
            preferenceRepository.update(PreferenceRepository.IS_REVIEW_DONE, true)
            reviewLaunched = true
            // 띄운 뒤에는 사용자가 닫을 때까지 기다린다. 끝나지 않을 때는 onResume 안전망과
            // finishIfAppBackgrounded 가 게이트를 닫는다.
            manager.launchReview(this, reviewInfo)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Play 밖 설치·Play 스토어 없음 등. 다음 게이트에서 다시 청한다.
            Timber.tag(TAG).w(e, "in-app review request failed")
        }
    }

    private fun finishGate() {
        if (finished) return
        finished = true
        timeoutJob?.cancel()
        finish()
    }
}
