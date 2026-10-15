package com.galaxy.airviewdictionary.data.local.ads

/**
 * 광고 게이트(광고 안내 팝업) 동작 정책. Remote Config 의 `ad_` 접두사 항목 네 개에서 온다 — 각각 정수 하나다.
 *
 * | 필드 | Remote Config 키 | 기본값 |
 * |---|---|---|
 * | [failureThreshold] | `ad_failure_threshold` | 3 |
 * | [backoffHours] | `ad_backoff_hours` | 24 |
 * | [skipCooldownSeconds] | `ad_skip_cooldown_seconds` | 60 |
 * | [adFreeMinutes] | `ad_free_minutes` | 0 |
 *
 * - [failureThreshold] / [backoffHours] — 광고 로드가 연속으로 실패하면 그 시간 동안 게이트를 열지 않는다.
 *   국가를 판정하지 않고 "이 기기에서 광고가 실제로 안 나온다"는 결과로 판단한다. 러시아는 2022년부터
 *   광고 게재가 중단돼 일치율이 0% 이고, 이란은 제재로 요청 자체가 나가지 않는다. 이런 사용자에게
 *   게이트는 수익 없이 5분마다 창을 띄우고 최대 15초를 기다리게 할 뿐이다. 결과 기반이라 앞으로
 *   어떤 지역이 막혀도 목록 관리 없이 자동 대응된다. 억제는 만료되면 다시 광고를 시도한다(영구 차단 아님).
 * - [skipCooldownSeconds] — 사용자가 광고를 스킵한 뒤 게이트를 다시 열지 않는 시간.
 *   0 이면 스킵 즉시 재게이트(예전 동작). 이 값의 손익은 eCPM 이 높은 국가에 집중되므로
 *   배포 후 노출수를 보고 앱 릴리스 없이 조정할 수 있어야 한다.
 * - [adFreeMinutes] — 광고를 끝까지 본 뒤 광고 안내 팝업을 다시 열지 않는 시간(분). 0 이면 만료 없이 프로세스가 끝날 때까지
 *   면제다(예전 동작). 메뉴바를 켜 둔 채 프로세스가 며칠 사는 사용자는 광고를 한 번만 보게 되므로, 값을 올려 다시 보게 한다.
 */
data class AdGatePolicy(
    val failureThreshold: Int,
    val backoffHours: Int,
    val skipCooldownSeconds: Int,
    val adFreeMinutes: Int = 0,
) {
    /** 연속 실패 억제 기능이 켜져 있는지. 둘 중 하나라도 0 이하이면 꺼진 것으로 본다. */
    val isBackoffEnabled: Boolean get() = failureThreshold > 0 && backoffHours > 0

    val backoffMillis: Long get() = backoffHours * 60L * 60L * 1_000L

    val skipCooldownMillis: Long get() = skipCooldownSeconds.coerceAtLeast(0) * 1_000L

    /** 광고 완주 뒤 면제 시간. 0 은 만료 없음(프로세스가 끝날 때까지)이다. */
    val adFreeMillis: Long get() = adFreeMinutes.coerceAtLeast(0) * 60_000L

    companion object {
        /** 앱 기본값(`remote_config_defaults.xml` 과 같다). 콘솔 값을 못 받았거나 숫자가 아니면 이 값을 쓴다. */
        val DEFAULT = AdGatePolicy(
            failureThreshold = 3,
            backoffHours = 24,
            skipCooldownSeconds = 60,
            adFreeMinutes = 0,
        )

    }
}
