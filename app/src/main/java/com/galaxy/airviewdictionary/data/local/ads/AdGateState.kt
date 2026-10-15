package com.galaxy.airviewdictionary.data.local.ads

import androidx.annotation.VisibleForTesting

/**
 * 광고 게이트 세션 상태 (메모리 전용 → 프로세스 종료 시 자동 리셋).
 *
 * 정책:
 *  - 리워드 광고를 끝까지 시청하면 [adFreeSession] = true → 앱 종료까지 광고 없이 사용.
 *    Remote Config 의 `ad_free_minutes` 가 0 보다 크면 그 시간 동안만 면제하고, 만료되면 다시 광고를 본다.
 *  - 광고 로드/표시 실패(오프라인, no-fill 등 기술적 사유)면 5분 사용권 부여 → 만료되면 다시 광고.
 *    (유예가 없으면 오프라인 사용자는 앱을 쓸 수 없다)
 *  - 광고 로드가 연속으로 실패하면([AdGatePolicy]) 한동안 게이트를 아예 열지 않는다.
 *    광고가 제공되지 않는 지역(러시아·이란 등)에서 수익 없이 경험만 깎는 것을 막는다.
 *  - 사용자가 광고를 스킵(중간에 닫기·홈키 중단 등)하면 짧은 사용권 부여 → 만료되면 다시 광고.
 *    시간은 [AdGatePolicy.skipCooldownSeconds] (Remote Config) 에서 온다.
 *    유예가 0이면 광고를 보지 않는 사용자에게 번역할 때마다 게이트가 열려, 노출 없는 광고 요청만
 *    무한히 쌓인다(AdMob 무효 트래픽 위험). 실제로 한 지역에서 전체 요청의 절반이 이렇게 발생했다.
 *    즉시 재게이트가 시청으로 이어지지도 않았다 — 게이트의 약 75% 가 그대로 스킵으로 끝났다.
 */
object AdGateState {

    private const val FAILURE_GRANT_MINUTES = 5

    /** 광고 완주로 이번 세션 동안 광고 없이 사용 가능한 상태. */
    @Volatile
    var adFreeSession: Boolean = false
        private set

    /** 광고 완주로 부여된 기한 있는 면제의 만료 시각(epoch millis). 만료 없는 면제는 [adFreeSession] 이다. */
    @Volatile
    private var adFreeUntilMillis: Long = 0L

    /** 로드/표시 실패로 부여된 임시 사용권 만료 시각(epoch millis). */
    @Volatile
    private var usableUntilMillis: Long = 0L

    /**
     * 광고 로드 연속 실패로 게이트를 열지 않는 만료 시각(epoch millis).
     * DataStore 의 `ad_gate_suppressed_until` 을 미러링한다 — [isUsable] 이 동기 호출이라
     * 매번 DataStore 를 읽을 수 없어서 메모리에 들고 있는다.
     */
    @Volatile
    private var suppressedUntilMillis: Long = 0L

    /** 광고 로드 연속 실패 횟수. 한 번이라도 성공하면 0 으로 돌아간다. */
    @Volatile
    var failureStreak: Int = 0
        private set

    /**
     * 광고를 끝까지 시청함 → 면제. [durationMillis] 가 0 이하면 세션 내내(프로세스가 끝날 때까지), 양수면 그 시간 동안이다.
     * 이미 만료 없는 면제를 받았으면 기한 있는 면제로 바꾸지 않는다.
     */
    fun grantAdFreeSession(durationMillis: Long = 0L) {
        if (durationMillis <= 0L) {
            adFreeSession = true
            return
        }
        adFreeUntilMillis = maxOf(adFreeUntilMillis, System.currentTimeMillis() + durationMillis)
    }

    /** 광고 로드/표시 실패(기술적 사유) → 5분 임시 사용권 부여. */
    fun grantFailureWindow() {
        extendUsableWindow(FAILURE_GRANT_MINUTES * 60_000L)
    }

    /**
     * 사용자가 광고를 스킵 → [cooldownMillis] 만큼 임시 사용권 부여.
     * 시간은 Remote Config 의 [AdGatePolicy.skipCooldownMillis] 에서 온다(릴리스 없이 조정 가능).
     */
    fun grantSkipWindow(cooldownMillis: Long) {
        if (cooldownMillis <= 0L) return
        extendUsableWindow(cooldownMillis)
    }

    /**
     * 사용권을 [durationMillis] 뒤까지 늘린다.
     * 이미 더 긴 유예가 남아 있으면 줄이지 않는다 — 실패로 받은 5분이
     * 뒤이은 스킵 때문에 60초로 깎이면 안 된다.
     */
    private fun extendUsableWindow(durationMillis: Long) {
        usableUntilMillis =
            maxOf(usableUntilMillis, System.currentTimeMillis() + durationMillis)
    }

    /**
     * 앱 시작 시 DataStore 에 저장된 값을 메모리로 올린다.
     * (프로세스가 죽어도 억제가 유지돼야 러시아·이란 사용자가 실행할 때마다 실패를 다시 겪지 않는다)
     */
    fun hydrate(streak: Int, suppressedUntil: Long) {
        failureStreak = streak
        suppressedUntilMillis = suppressedUntil
    }

    /**
     * 광고 로드 실패를 기록한다.
     * 연속 실패가 [failureThreshold] 에 닿으면 [backoffMillis] 만큼 게이트를 억제하고
     * 그 만료 시각을 돌려준다(저장용). 아직 임계치 전이면 null.
     */
    @Synchronized
    fun recordAdLoadFailure(failureThreshold: Int, backoffMillis: Long): Long? {
        failureStreak += 1
        if (failureStreak < failureThreshold) return null
        failureStreak = 0
        suppressedUntilMillis = System.currentTimeMillis() + backoffMillis
        return suppressedUntilMillis
    }

    /** 광고 로드 성공 → 연속 실패 기록과 억제를 모두 해제한다. 바뀐 게 있으면 true. */
    @Synchronized
    fun recordAdLoadSuccess(): Boolean {
        val changed = failureStreak != 0 || suppressedUntilMillis != 0L
        failureStreak = 0
        suppressedUntilMillis = 0L
        return changed
    }

    /** 지금 광고 없이 사용 가능한지. */
    fun isUsable(): Boolean {
        val now = System.currentTimeMillis()
        return adFreeSession || now < adFreeUntilMillis || now < usableUntilMillis || now < suppressedUntilMillis
    }

    /** 테스트 전용: 세션 상태를 초기화한다(프로세스 전역 object 라 테스트 간 오염을 막는다). */
    @VisibleForTesting
    fun resetForTest() {
        adFreeSession = false
        adFreeUntilMillis = 0L
        usableUntilMillis = 0L
        suppressedUntilMillis = 0L
        failureStreak = 0
    }

    /** 부여된 임시 사용권 잔여 밀리초(없으면 0). */
    fun remainingWindowMillis(): Long =
        if (adFreeSession) Long.MAX_VALUE
        else (maxOf(usableUntilMillis, adFreeUntilMillis) - System.currentTimeMillis()).coerceAtLeast(0L)
}
