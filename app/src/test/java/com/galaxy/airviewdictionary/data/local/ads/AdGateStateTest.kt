package com.galaxy.airviewdictionary.data.local.ads

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 광고 게이트 사용권 규칙 검증.
 *
 * 고정해 두는 것:
 *  - "이미 더 긴 유예가 남아 있으면 줄이지 않는다" — 실패로 받은 5분이 뒤이은 스킵 때문에
 *    깎이면 오프라인 사용자가 앱을 쓸 수 없게 된다.
 *  - 연속 실패 억제는 만료되면 다시 광고를 시도한다(영구 차단이 아니다).
 *  - Remote Config 가 깨져도 게이트가 통째로 사라지거나 즉시 재게이트로 돌아가지 않는다.
 */
class AdGateStateTest {

    private val policy = AdGatePolicy(
        failureThreshold = 3,
        backoffHours = 24,
        skipCooldownSeconds = 60,
    )

    private val failureMillis = 5 * 60 * 1_000L

    /** 시각 비교의 실행 지연을 흡수할 여유. */
    private val toleranceMillis = 5_000L

    @Before
    fun setUp() {
        AdGateState.resetForTest()
    }

    @After
    fun tearDown() {
        AdGateState.resetForTest()
    }

    private fun recordFailure(): Long? =
        AdGateState.recordAdLoadFailure(policy.failureThreshold, policy.backoffMillis)

    @Test
    fun `초기 상태에서는 광고를 봐야 한다`() {
        assertFalse(AdGateState.isUsable())
        assertEquals(0L, AdGateState.remainingWindowMillis())
    }

    // ---- 스킵 쿨다운 ----

    @Test
    fun `스킵하면 쿨다운 동안 게이트가 뜨지 않는다`() {
        AdGateState.grantSkipWindow(policy.skipCooldownMillis)

        assertTrue(AdGateState.isUsable())
        val remaining = AdGateState.remainingWindowMillis()
        assertTrue(
            "잔여 $remaining ms 가 60초 근처여야 한다",
            remaining in (policy.skipCooldownMillis - toleranceMillis)..policy.skipCooldownMillis
        )
    }

    @Test
    fun `스킵 쿨다운은 Remote Config 값을 따른다`() {
        val short = policy.copy(skipCooldownSeconds = 15)
        AdGateState.grantSkipWindow(short.skipCooldownMillis)

        val remaining = AdGateState.remainingWindowMillis()
        assertTrue(
            "잔여 $remaining ms 가 15초 근처여야 한다",
            remaining in (15_000L - toleranceMillis)..15_000L
        )
    }

    @Test
    fun `스킵 쿨다운 0이면 즉시 재게이트된다`() {
        AdGateState.grantSkipWindow(policy.copy(skipCooldownSeconds = 0).skipCooldownMillis)

        assertFalse("쿨다운 0 은 예전 동작(즉시 재게이트)이어야 한다", AdGateState.isUsable())
    }

    @Test
    fun `스킵을 반복해도 유예가 누적되지 않는다`() {
        repeat(5) { AdGateState.grantSkipWindow(policy.skipCooldownMillis) }

        val remaining = AdGateState.remainingWindowMillis()
        assertTrue(
            "잔여 $remaining ms 가 60초를 넘으면 안 된다",
            remaining in (policy.skipCooldownMillis - toleranceMillis)..policy.skipCooldownMillis
        )
    }

    // ---- 유예 창은 줄어들지 않는다 ----

    @Test
    fun `실패 유예 5분은 뒤이은 스킵으로 짧아지지 않는다`() {
        AdGateState.grantFailureWindow()
        AdGateState.grantSkipWindow(policy.skipCooldownMillis)

        val remaining = AdGateState.remainingWindowMillis()
        assertTrue(
            "잔여 $remaining ms 가 5분 근처로 유지돼야 한다",
            remaining in (failureMillis - toleranceMillis)..failureMillis
        )
    }

    @Test
    fun `스킵 유예 중 실패가 나면 5분으로 늘어난다`() {
        AdGateState.grantSkipWindow(policy.skipCooldownMillis)
        AdGateState.grantFailureWindow()

        val remaining = AdGateState.remainingWindowMillis()
        assertTrue(
            "잔여 $remaining ms 가 5분 근처여야 한다",
            remaining in (failureMillis - toleranceMillis)..failureMillis
        )
    }

    // ---- 연속 실패 억제 (러시아·이란 대응) ----

    @Test
    fun `연속 실패가 임계치에 닿기 전에는 억제되지 않는다`() {
        assertNull(recordFailure())
        assertNull(recordFailure())

        assertEquals(2, AdGateState.failureStreak)
        assertFalse("아직 임계치 전이라 게이트가 열려야 한다", AdGateState.isUsable())
    }

    @Test
    fun `연속 3회 실패하면 24시간 억제된다`() {
        repeat(2) { recordFailure() }
        val until = recordFailure()

        assertTrue("임계치 도달 시 만료 시각을 돌려줘야 한다", until != null)
        assertTrue(AdGateState.isUsable())

        val expected = System.currentTimeMillis() + policy.backoffMillis
        assertTrue(
            "만료 시각 $until 이 24시간 뒤 근처여야 한다",
            until!! in (expected - toleranceMillis)..expected
        )
        assertEquals("임계치 도달 후 연속 실패 횟수는 초기화된다", 0, AdGateState.failureStreak)
    }

    @Test
    fun `중간에 한 번이라도 성공하면 연속 실패가 초기화된다`() {
        repeat(2) { recordFailure() }
        AdGateState.recordAdLoadSuccess()
        val until = recordFailure()

        assertNull("성공 후 실패 1회이므로 아직 억제되면 안 된다", until)
        assertEquals(1, AdGateState.failureStreak)
    }

    @Test
    fun `억제 중 광고 로드에 성공하면 즉시 해제된다`() {
        repeat(3) { recordFailure() }
        assertTrue(AdGateState.isUsable())

        assertTrue("해제되었으니 변경됨을 알려야 한다", AdGateState.recordAdLoadSuccess())
        assertFalse(AdGateState.isUsable())
    }

    @Test
    fun `저장된 억제 상태를 복원하면 게이트가 열리지 않는다`() {
        AdGateState.hydrate(streak = 0, suppressedUntil = System.currentTimeMillis() + 60 * 60_000L)

        assertTrue(AdGateState.isUsable())
    }

    @Test
    fun `만료된 억제 상태를 복원하면 다시 광고를 시도한다`() {
        AdGateState.hydrate(streak = 0, suppressedUntil = System.currentTimeMillis() - 1_000L)

        assertFalse(AdGateState.isUsable())
    }

    // ---- 정책 값 해석 ----

    @Test
    fun `억제 기능이 꺼진 설정을 구분한다`() {
        assertFalse(policy.copy(backoffHours = 0).isBackoffEnabled)
        assertFalse(policy.copy(failureThreshold = 0).isBackoffEnabled)
        assertTrue(policy.isBackoffEnabled)
    }

    @Test
    fun `앱 기본값은 억제 3회 24시간 스킵 60초 면제 만료 없음이다`() {
        val default = AdGatePolicy.DEFAULT
        assertEquals(3, default.failureThreshold)
        assertEquals(24, default.backoffHours)
        assertEquals(60 * 1_000L, default.skipCooldownMillis)
        assertEquals(0, default.adFreeMinutes)
        assertTrue(default.isBackoffEnabled)
    }

    // ---- 완주 ----

    @Test
    fun `광고를 완주하면 세션 내내 사용 가능하다`() {
        AdGateState.grantAdFreeSession()

        assertTrue(AdGateState.adFreeSession)
        assertTrue(AdGateState.isUsable())
        assertEquals(Long.MAX_VALUE, AdGateState.remainingWindowMillis())
    }

    @Test
    fun `완주 상태는 이후 스킵에 영향받지 않는다`() {
        AdGateState.grantAdFreeSession()
        AdGateState.grantSkipWindow(policy.skipCooldownMillis)

        assertTrue(AdGateState.isUsable())
        assertEquals(Long.MAX_VALUE, AdGateState.remainingWindowMillis())
    }

    // ---- 기한 있는 면제(Remote Config ad_free_minutes) ----

    @Test
    fun `면제 시간이 0이면 만료 없이 면제된다`() {
        AdGateState.grantAdFreeSession(AdGatePolicy.DEFAULT.adFreeMillis)

        assertTrue(AdGateState.adFreeSession)
        assertEquals(0L, AdGatePolicy.DEFAULT.adFreeMinutes.toLong())
        assertEquals(Long.MAX_VALUE, AdGateState.remainingWindowMillis())
    }

    @Test
    fun `면제 시간이 있으면 그 시간 동안만 사용 가능하다`() {
        val sixtyMinutes = policy.copy(adFreeMinutes = 60)
        AdGateState.grantAdFreeSession(sixtyMinutes.adFreeMillis)

        assertFalse("기한 있는 면제는 세션 면제가 아니다", AdGateState.adFreeSession)
        assertTrue(AdGateState.isUsable())
        val remaining = AdGateState.remainingWindowMillis()
        assertTrue(
            "잔여 $remaining ms 가 60분 근처여야 한다",
            remaining in (60 * 60_000L - toleranceMillis)..(60 * 60_000L)
        )
    }

    @Test
    fun `기한 있는 면제는 만료되면 다시 광고를 본다`() {
        AdGateState.grantAdFreeSession(durationMillis = 1L)
        Thread.sleep(20)

        assertFalse(AdGateState.isUsable())
    }

    @Test
    fun `기한 있는 면제는 뒤이은 스킵으로 짧아지지 않는다`() {
        AdGateState.grantAdFreeSession(policy.copy(adFreeMinutes = 60).adFreeMillis)
        AdGateState.grantSkipWindow(policy.skipCooldownMillis)

        assertTrue(AdGateState.remainingWindowMillis() > 59 * 60_000L)
    }

    @Test
    fun `음수 면제 시간은 만료 없음으로 읽는다`() {
        assertEquals(0L, policy.copy(adFreeMinutes = -5).adFreeMillis)
    }
}
