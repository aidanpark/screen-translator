package com.galaxy.airviewdictionary.data.local.ads

/**
 * 광고 게이트 동작 정책. Remote Config 의 `ad_gate_failure_backoff` 한 항목에 JSON 으로 담는다.
 *
 * ```json
 * { "failure_threshold": 3, "backoff_hours": 24, "skip_cooldown_seconds": 60 }
 * ```
 *
 * - [failureThreshold] / [backoffHours] — 광고 로드가 연속으로 실패하면 그 시간 동안 게이트를 열지 않는다.
 *   국가를 판정하지 않고 "이 기기에서 광고가 실제로 안 나온다"는 결과로 판단한다. 러시아는 2022년부터
 *   광고 게재가 중단돼 일치율이 0% 이고, 이란은 제재로 요청 자체가 나가지 않는다. 이런 사용자에게
 *   게이트는 수익 없이 5분마다 창을 띄우고 최대 15초를 기다리게 할 뿐이다. 결과 기반이라 앞으로
 *   어떤 지역이 막혀도 목록 관리 없이 자동 대응된다. 억제는 만료되면 다시 광고를 시도한다(영구 차단 아님).
 * - [skipCooldownSeconds] — 사용자가 광고를 스킵한 뒤 게이트를 다시 열지 않는 시간.
 *   0 이면 스킵 즉시 재게이트(예전 동작). 이 값의 손익은 eCPM 이 높은 국가에 집중되므로
 *   배포 후 노출수를 보고 앱 릴리스 없이 조정할 수 있어야 한다.
 */
data class AdGatePolicy(
    val failureThreshold: Int,
    val backoffHours: Int,
    val skipCooldownSeconds: Int,
) {
    /** 연속 실패 억제 기능이 켜져 있는지. 둘 중 하나라도 0 이하이면 꺼진 것으로 본다. */
    val isBackoffEnabled: Boolean get() = failureThreshold > 0 && backoffHours > 0

    val backoffMillis: Long get() = backoffHours * 60L * 60L * 1_000L

    val skipCooldownMillis: Long get() = skipCooldownSeconds.coerceAtLeast(0) * 1_000L

    companion object {
        /** 앱 기본값(`remote_config_defaults.xml` 과 같다). 콘솔 JSON 에 빠진 필드는 이 값으로 채운다. */
        val DEFAULT = AdGatePolicy(
            failureThreshold = 3,
            backoffHours = 24,
            skipCooldownSeconds = 60,
        )

        /**
         * Remote Config 값의 형식이 깨졌을 때 쓰는 값.
         * 억제는 끄고 스킵 쿨다운만 유지해, 잘못된 설정으로 게이트가 통째로 사라지지 않게 한다.
         */
        val FALLBACK = AdGatePolicy(
            failureThreshold = 0,
            backoffHours = 0,
            skipCooldownSeconds = 60,
        )
    }
}
