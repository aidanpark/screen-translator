package com.galaxy.airviewdictionary.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 인앱 리뷰를 청해도 되는가 — 사용량 · 간격의 경계. */
class ReviewPromptPolicyTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_000_000_000_000L

    @Test
    fun notDueBeforeEnoughTranslations() {
        assertFalse(ReviewPromptPolicy.isDue(ReviewPromptPolicy.MIN_TRANSLATIONS - 1, 0, 0L, now))
        assertTrue(ReviewPromptPolicy.isDue(ReviewPromptPolicy.MIN_TRANSLATIONS, 0, 0L, now))
    }

    @Test
    fun consecutiveSuccessesAreDueBeforeTheTotalIsReached() {
        val few = ReviewPromptPolicy.MIN_TRANSLATIONS - 1
        assertFalse(ReviewPromptPolicy.isDue(few, ReviewPromptPolicy.MIN_CONSECUTIVE_SUCCESSES - 1, 0L, now))
        assertTrue(ReviewPromptPolicy.isDue(few, ReviewPromptPolicy.MIN_CONSECUTIVE_SUCCESSES, 0L, now))
    }

    @Test
    fun consecutiveSuccessesStillRespectTheInterval() {
        val last = now - (ReviewPromptPolicy.MIN_DAYS_BETWEEN_PROMPTS - 1) * day
        assertFalse(ReviewPromptPolicy.isDue(1, ReviewPromptPolicy.MIN_CONSECUTIVE_SUCCESSES, last, now))
    }

    @Test
    fun neverPromptedIsDueOnceUsedEnough() {
        assertTrue(ReviewPromptPolicy.isDue(50, 0, 0L, now))
    }

    @Test
    fun promptedRecentlyIsNotDue() {
        val last = now - (ReviewPromptPolicy.MIN_DAYS_BETWEEN_PROMPTS - 1) * day
        assertFalse(ReviewPromptPolicy.isDue(500, 0, last, now))
    }

    @Test
    fun promptedLongAgoIsDueAgain() {
        val last = now - ReviewPromptPolicy.MIN_DAYS_BETWEEN_PROMPTS * day
        assertTrue(ReviewPromptPolicy.isDue(500, 0, last, now))
    }
}
