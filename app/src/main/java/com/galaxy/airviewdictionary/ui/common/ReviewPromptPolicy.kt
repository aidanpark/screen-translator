package com.galaxy.airviewdictionary.ui.common

/**
 * 인앱 리뷰를 청해도 되는가 — 순수한 판정 규칙이다.
 *
 * 리뷰 창은 사용자가 이 앱을 충분히 써 본 뒤, 방해가 되지 않는 때에, 너무 자주 아니게 청한다.
 * 실제로 창을 띄울지는 Play 가 사용자별 할당량으로 정하고, 별점을 남겼는지도 앱에 알려 주지 않는다. 그래서 앱은
 * 청한 시각만 남기고 [MIN_DAYS_BETWEEN_PROMPTS] 일이 지나면 다시 청한다(예전에는 한 번 띄우면 영영 다시 묻지 않았다).
 *
 * 만족 여부를 먼저 묻고 만족한 사용자에게만 리뷰 창을 띄우지 않는다 — Play 인앱 리뷰 지침이 금하는 방식이다.
 * 리뷰를 보상과 엮지도 않는다.
 */
object ReviewPromptPolicy {

    /** 번역을 이만큼 써 본 뒤에 청한다(번역 시도 횟수, [com.galaxy.airviewdictionary.data.local.secure.SecureRepository]). */
    const val MIN_TRANSLATIONS = 15

    /**
     * 포인터 번역이 오류 없이 이만큼 이어서 성공한 순간이 "아하" 시점이다 — 누적 횟수를 채우지 않았어도 청한다.
     * 번역에서 오류가 나면 이어짐이 끊긴다.
     */
    const val MIN_CONSECUTIVE_SUCCESSES = 5

    /** 마지막으로 청한 뒤 이만큼의 날이 지나야 다시 청한다. */
    const val MIN_DAYS_BETWEEN_PROMPTS = 90

    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    /**
     * @param translationCount 지금까지의 번역 시도 횟수
     * @param consecutiveSuccesses 오류 없이 이어서 성공한 포인터 번역 수. 이 값을 모르는 곳(설정 화면 · 광고 게이트)은 0
     * @param lastPromptAtMillis 마지막으로 청한 시각(epoch millis). 청한 적이 없으면 0
     * @param nowMillis 지금 시각(epoch millis)
     */
    fun isDue(translationCount: Int, consecutiveSuccesses: Int, lastPromptAtMillis: Long, nowMillis: Long): Boolean {
        if (translationCount < MIN_TRANSLATIONS && consecutiveSuccesses < MIN_CONSECUTIVE_SUCCESSES) return false
        if (lastPromptAtMillis <= 0L) return true
        return nowMillis - lastPromptAtMillis >= MIN_DAYS_BETWEEN_PROMPTS * DAY_MILLIS
    }
}
