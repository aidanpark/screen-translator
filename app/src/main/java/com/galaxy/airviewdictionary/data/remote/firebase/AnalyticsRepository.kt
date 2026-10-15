package com.galaxy.airviewdictionary.data.remote.firebase

import android.content.Context
import com.galaxy.airviewdictionary.BuildConfig
import com.galaxy.airviewdictionary.data.local.vision.TextDetectMode
import com.galaxy.airviewdictionary.data.remote.translation.Transaction
import com.google.firebase.Firebase
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics
import com.google.firebase.analytics.logEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt


object Event {
    const val SECURE = "secure"
    const val TRANSLATE = "translate"
    const val TIME_TAKEN = "time_taken"
    const val AD_GATE = "ad_gate"
}

object Param {
    const val SECURE_DETAIL = "detail"

    const val TEXT_DETECT_MODE = "detectMode"
    const val SOURCE_LANGUAGE_CODE = "sourceCode"
    const val TARGET_LANGUAGE_CODE = "targetCode"
    const val DETECTED_LANGUAGE_CODE = "detectedCode"
    const val TRANSLATION_KIT_TYPE = "kit"

    const val DOCKING_DELAY = "dockDelay"
    const val DRAG_HANDLE_HAPTIC = "haptic"
    const val MENU_BAR_TRANSPARENCY = "menuTransparency"
    const val MENU_BAR_COMPOSITION = "menuComposition"
    const val TRANSLATION_TRANSPARENCY = "transTransparency"
    const val TRANSLATION_CLOSE_DELAY = "closeDelay"
    const val REPLY_TRANSPARENCY = "replyTransparency"
    const val AUTOMATIC_TRANSLATION_PLAYBACK = "autoTTS"
    const val TTS_VOICE = "TTSVoice"
    const val TTS_SPEECH_RATE = "TTSRate"

    /** 광고 안내 팝업의 단계(`AdGateStage`)와 그 사유. */
    const val AD_STAGE = "stage"
    const val AD_REASON = "reason"
    const val AD_FREE_MINUTES = "free_min"
    const val AD_FAILURE_STREAK = "streak"

    const val INSTALL_COUNT = "install_count"
    const val TRIAL_COUNT = "trial_count"

    const val HOURS_TAKEN = "hours_"
    const val DAYS_TAKEN = "days_"
}

@Singleton
class AnalyticsRepository @Inject constructor(@ApplicationContext val context: Context) {

    private val TAG = javaClass.simpleName

    private val firebaseAnalytics: FirebaseAnalytics = Firebase.analytics

    fun secureReport(eventDetail: String) {
        if (BuildConfig.DEBUG) return

        firebaseAnalytics.logEvent(Event.SECURE) {
            param(Param.SECURE_DETAIL, eventDetail)
        }
    }

    fun screenViewReport(className: String) {
        if (BuildConfig.DEBUG) return

        firebaseAnalytics.logEvent(FirebaseAnalytics.Event.SCREEN_VIEW) {
            param(FirebaseAnalytics.Param.SCREEN_CLASS, className)
        }
    }

    fun settingsReport(
        dockDelay: String,
        haptic: String,
        menuTransparency: String,
        menuComposition: String,
        transTransparency: String,
        closeDelay: String,
        replyTransparency: String,
        autoTTS: String,
        TTSVoice: String,
        TTSRate: String,
    ) {
        if (BuildConfig.DEBUG) return
        firebaseAnalytics.logEvent(FirebaseAnalytics.Event.SELECT_ITEM) {
            param(Param.DOCKING_DELAY, dockDelay)
            param(Param.DRAG_HANDLE_HAPTIC, haptic)
            param(Param.MENU_BAR_TRANSPARENCY, menuTransparency)
            param(Param.MENU_BAR_COMPOSITION, menuComposition)
            param(Param.TRANSLATION_TRANSPARENCY, transTransparency)
            param(Param.TRANSLATION_CLOSE_DELAY, closeDelay)
            param(Param.REPLY_TRANSPARENCY, replyTransparency)
            param(Param.AUTOMATIC_TRANSLATION_PLAYBACK, autoTTS)
            param(Param.TTS_VOICE, TTSVoice)
            param(Param.TTS_SPEECH_RATE, TTSRate)
        }
    }

    fun translationReport(
        transaction: Transaction,
        textDetectMode: TextDetectMode?,
    ) {
        if (BuildConfig.DEBUG) return

        firebaseAnalytics.logEvent(Event.TRANSLATE) {
            param(Param.SOURCE_LANGUAGE_CODE, transaction.requestedSourceLanguageCode ?: "unknown")
            param(Param.TARGET_LANGUAGE_CODE, transaction.targetLanguageCode ?: "unknown")
            param(Param.TRANSLATION_KIT_TYPE, transaction.translationKitType?.name ?: "unknown")
            param(Param.TEXT_DETECT_MODE, textDetectMode?.name ?: "unknown")
            param(Param.DETECTED_LANGUAGE_CODE, transaction.resolvedSourceLanguageCode ?: "unknown")
        }
    }

    /**
     * 답장 화면에서 번역문을 클립보드로 복사했을 때. 화면 번역과 같은 [Event.TRANSLATE] 로 남기되
     * detectMode 를 [REPLY_DETECT_MODE] 로 둬서 리포트에서 걸러낼 수 있게 한다
     * (안 두면 번역 수가 부풀고 detectMode 에 "(not set)" 행이 생긴다).
     *
     * detectedCode 는 원문 언어가 정해졌을 때만 남긴다 — 킷이 판정한 값, 없으면 답장의 원문 언어(사용자가 쓴 언어).
     * 둘 다 없으면 비워 둔다.
     */
    fun replyReport(transaction: Transaction) {
        if (BuildConfig.DEBUG) return

        val detectedCode = transaction.resolvedSourceLanguageCode
            ?: transaction.requestedSourceLanguageCode?.takeUnless { it.equals("auto", ignoreCase = true) }
        firebaseAnalytics.logEvent(Event.TRANSLATE) {
            param(Param.SOURCE_LANGUAGE_CODE, transaction.requestedSourceLanguageCode ?: "unknown")
            param(Param.TARGET_LANGUAGE_CODE, transaction.targetLanguageCode ?: "unknown")
            param(Param.TRANSLATION_KIT_TYPE, transaction.translationKitType?.name ?: "unknown")
            param(Param.TEXT_DETECT_MODE, REPLY_DETECT_MODE)
            detectedCode?.takeIf { it.isNotBlank() }?.let { param(Param.DETECTED_LANGUAGE_CODE, it.lowercase()) }
        }
    }

    /**
     * 광고 안내 팝업의 흐름 한 단계를 보고한다 — 열림 · 광고 준비 · 시청 시작 · 완주 · 스킵 · 실패. 열림 대비 완주 비율이
     * 광고 수익의 상한이다. [reason] 은 단계 안의 구분(스킵을 닫은 경로, 실패의 원인)이다.
     */
    fun adGateReport(stage: String, reason: String? = null, freeMinutes: Int? = null, failureStreak: Int? = null) {
        if (BuildConfig.DEBUG) return

        firebaseAnalytics.logEvent(Event.AD_GATE) {
            param(Param.AD_STAGE, stage)
            reason?.let { param(Param.AD_REASON, it) }
            freeMinutes?.let { param(Param.AD_FREE_MINUTES, it.toLong()) }
            failureStreak?.let { param(Param.AD_FAILURE_STREAK, it.toLong()) }
        }
    }

    fun hoursTakenReport(trialCount: Int, hour: Int) {
        if (BuildConfig.DEBUG) return

        firebaseAnalytics.logEvent(Event.TIME_TAKEN) {
            param("${Param.HOURS_TAKEN}$trialCount", hour.toLong())
        }
    }

    fun daysTakenReport(trialCount: Int, day: Int) {
        if (BuildConfig.DEBUG) return

        firebaseAnalytics.logEvent(Event.TIME_TAKEN) {
            param("${Param.DAYS_TAKEN}$trialCount", day.toLong())
        }
    }

    /** [adGateReport] 의 단계 값. */
    object AdGateStage {
        const val OPEN = "open"
        const val READY = "ready"
        const val SHOWN = "shown"
        const val REWARD = "reward"
        const val SKIP = "skip"
        const val LOAD_FAIL = "load_fail"
        const val FAIL_OK = "fail_ok"
        const val SUPPRESSED = "suppressed"
    }

    companion object {
        /** 답장 화면에서 나온 [Event.TRANSLATE] 의 detectMode 값. [TextDetectMode] 이름과 겹치지 않는다. */
        const val REPLY_DETECT_MODE = "REPLY"
    }
}










