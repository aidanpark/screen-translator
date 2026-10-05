package com.galaxy.airviewdictionary.data.remote.translation

import android.content.Context
import com.galaxy.airviewdictionary.R
import retrofit2.HttpException
import java.io.IOException

/**
 * 번역 API 실패 원인을 사용자에게 보여줄 한 줄 안내로 변환한다.
 *
 * 배경: 실패가 무소음이면 사용자는 "앱 고장"으로 오해한다(리뷰/문의로 확인).
 * 특히 키는 활성인데 계정 크레딧이 없는 경우(Claude 400 / OpenAI 429)를
 * 키 오류와 구분해서 안내해야 사용자가 스스로 해결할 수 있다.
 */
object TranslationErrorMessages {

    fun resolve(context: Context, t: Throwable): String {
        if (t is UnsupportedScriptException) {
            val name = java.util.Locale.forLanguageTag(t.languageCode).getDisplayLanguage(context.resources.configuration.locales[0])
            return context.getString(R.string.error_unsupported_script, name)
        }
        val res = when (t) {
            is HttpException -> when (t.code()) {
                401, 403 -> R.string.error_translate_invalid_key
                404 -> R.string.error_translate_model_unavailable
                400, 429 -> if (isOutOfCredit(t)) R.string.error_translate_no_credit
                else if (t.code() == 429) R.string.error_translate_rate_limited
                else R.string.error_translate_generic

                else -> R.string.error_translate_generic
            }

            is IOException -> R.string.error_translate_network
            else -> R.string.error_translate_generic
        }
        return context.getString(res)
    }

    /**
     * 크레딧/잔액 소진 판별. 제공사별 시그니처:
     * - Anthropic: 400 invalid_request_error "credit balance is too low"
     * - OpenAI: 429 code "insufficient_quota"
     * - Gemini(유료 전환 계정): 429 RESOURCE_EXHAUSTED + billing 언급
     */
    private fun isOutOfCredit(e: HttpException): Boolean {
        val body = try {
            e.response()?.errorBody()?.string().orEmpty()
        } catch (_: Exception) {
            ""
        }
        return listOf("insufficient_quota", "credit balance", "billing", "purchase more credits")
            .any { body.contains(it, ignoreCase = true) }
    }
}

/**
 * auto 의 지원되지 않는 문자권 관문(성능 P7)이 개입했는데 Claude 이미지 번역을 쓸 수 없다 — 번역 대신 "읽을 수 없는 문자" 안내를 띄운다.
 * [languageCode] 는 그 문자권의 대표 언어(안내 문구에 기기 언어로 된 언어 이름을 쓴다).
 */
class UnsupportedScriptException(val languageCode: String) : Exception("unsupported script: $languageCode")
