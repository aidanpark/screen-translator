package com.galaxy.airviewdictionary.data.remote.translation

import com.galaxy.airviewdictionary.data.local.capture.ImageCrop
import com.galaxy.airviewdictionary.data.local.vision.TextDetectMode
import com.galaxy.airviewdictionary.data.local.vision.kit.VisionKitSelector
import com.galaxy.airviewdictionary.data.remote.firebase.RemoteConfigRepository
import com.google.firebase.Firebase
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.remoteConfig
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.util.Locale

/**
 * AI 이미지 번역 — 화면을 잘라 Claude 에 보내 읽기와 번역을 함께 맡긴다(`.docs/vision-engine-design.md` §25, 2026-09-28 사용자 결정).
 *
 * 화면 글자를 읽을 엔진이 없는 문자(원문 목록의 25개 언어)를 인식기를 늘리지 않고 번역하려는 것이고, Claude 에서 원문 언어가 auto 면 모든 화면을
 * 이 길로 보낸다 — auto 에서 "OCR 글을 믿을 수 없다" 를 가를 기준이 없어서다(인식기는 못 읽는 문자도 자신 있게 틀리게 읽는다, §24).
 * 줄·문단 위치는 PP-OCRv5 검출기가 찾는다(문자와 무관하게 줄을 찾는다).
 */
object ImageTranslation {

    /** [code] 는 화면 글자를 읽을 엔진이 없어 이미지로만 번역하는 원문 언어인가. auto 는 아니다. */
    fun isImageOnlyLanguage(code: String): Boolean = !VisionKitSelector.hasReaderFor(code)

    /**
     * 이 번역을 이미지로 보내는가. Claude 이고 키가 있을 때([claudeReady])만, 원문 언어가 auto 이거나 이미지로만 번역하는 언어면 그렇다.
     * 원격 스위치([enabled])가 꺼져 있으면 언제나 글로 보낸다.
     */
    fun uses(kitType: TranslationKitType, sourceLanguage: String, claudeReady: Boolean, enabled: Boolean = Switch.enabled): Boolean =
        enabled && kitType == TranslationKitType.CLAUDE && claudeReady &&
            (sourceLanguage.equals(AUTO, ignoreCase = true) || isImageOnlyLanguage(sourceLanguage))

    private const val AUTO = "auto"

    /**
     * 끄기 스위치 — Remote Config `claude_image_enabled`(기본 켜짐). 끄면 Claude 도 OCR 글을 번역하고, 이미지로만 번역하던 언어는 원문으로 쓸 수
     * 없게 된다. 값을 아직 못 받았으면 켜진 것으로 본다(`PaddleSwitch` 와 같다).
     */
    object Switch {
        val enabled: Boolean
            get() = runCatching {
                val value = Firebase.remoteConfig.getValue(RemoteConfigRepository.CLAUDE_IMAGE_ENABLED)
                value.source == FirebaseRemoteConfig.VALUE_SOURCE_STATIC || value.asBoolean()
            }.getOrDefault(true)
    }

    /**
     * 읽을 엔진이 없는 문자의 고정 영역 지문(뜻 없는 글) 둘이 같은 글을 가리키는가. 화소가 조금 달라지면(영상 위 자막) 몇 글자가 바뀐다 —
     * 편집 거리가 긴 쪽 길이의 [FINGERPRINT_TOLERANCE] 이하면 같다. 긴 지문은 앞 [FINGERPRINT_MAX_CHARS] 자만 본다.
     */
    fun sameFingerprint(a: String, b: String): Boolean {
        val x = a.take(FINGERPRINT_MAX_CHARS)
        val y = b.take(FINGERPRINT_MAX_CHARS)
        if (x == y) return true
        if (x.isEmpty() || y.isEmpty()) return false
        var previous = IntArray(y.length + 1) { it }
        var current = IntArray(y.length + 1)
        for (i in 1..x.length) {
            current[0] = i
            for (j in 1..y.length) {
                val substitution = previous[j - 1] + if (x[i - 1] == y[j - 1]) 0 else 1
                current[j] = minOf(substitution, previous[j] + 1, current[j - 1] + 1)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[y.length] <= maxOf(x.length, y.length) * FINGERPRINT_TOLERANCE
    }

    private const val FINGERPRINT_TOLERANCE = 0.15
    private const val FINGERPRINT_MAX_CHARS = 400


    /** 번역할 것이 포인터 기준의 단어·문장·문단인가(표시를 그린다), 잘라 보낸 영역 전체인가. */
    fun marksPointer(mode: TextDetectMode): Boolean =
        mode == TextDetectMode.WORD || mode == TextDetectMode.SENTENCE || mode == TextDetectMode.PARAGRAPH

    /**
     * 시스템 프롬프트. [sourceLanguageName] 이 null 이면 auto — 언어도 돌려받는다.
     *
     * 옛 이미지 경로(2.7.3, §16·§21 에서 걷어냄)의 실측 교훈을 따른다.
     * - 원문 언어는 단정하지 않고 힌트로 준다. 단언하면 화면이 다른 언어일 때 번역을 거부했다("이건 아랍어라 못 합니다", 2026-09-21)
     * - 형식·거절 금지 규칙은 문체 지시 뒤, 맨 끝에 둔다. 앞에 두면 뒤따르는 문체 지시가 마지막 인상이 되어 불확실할 때 설명문을 냈다
     * - 응답 형식은 구조화 출력(JSON 스키마)이 강제한다 — 예전의 응답 앞머리 채우기(prefill)는 지금 모델에서 400 이다
     */
    fun systemPrompt(
        sourceLanguageName: String?,
        targetLanguageName: String,
        strength: TranslationStrength,
        domain: TranslationDomain,
        mode: TextDetectMode,
        marker: ImageCrop.PointerMarker = ImageCrop.marker,
    ): String = buildString {
        append("You are a translation engine reading a cropped screenshot of a phone screen.")
        if (marksPointer(mode)) {
            append(" The ${marker.description} is drawn by the app to mark where the user is pointing; it is not part of the content.")
        }
        append(" ")
        append(
            when (mode) {
                TextDetectMode.WORD -> "Find the single word at ${marker.at}, or the word nearest to it."
                TextDetectMode.SENTENCE -> "Find the sentence that contains the text at ${marker.at}; it may continue onto the lines above or below."
                TextDetectMode.PARAGRAPH -> "Find the paragraph or text block that contains the text at ${marker.at}."
                TextDetectMode.SELECT, TextDetectMode.FIXED_AREA -> "Read all the text in the image, in reading order."
            }
        )
        if (marksPointer(mode)) append(" Ignore all other text in the image.")
        if (sourceLanguageName != null) {
            append(" The text is expected to be in $sourceLanguageName; if it is actually in another language, translate it anyway.")
        }
        append(" In \"source\", give that text exactly as written, in its original script, with no corrections and nothing added.")
        append(" In \"translation\", give it translated into $targetLanguageName; if it is already in $targetLanguageName, repeat it unchanged.")
        append(" Keep the line breaks of the text only where they separate items such as a title, list entries or table cells.")
        if (sourceLanguageName == null) {
            append(" In \"language\", give the ISO 639-1 code of the language of that text in lower case, such as he or bn;")
            append(" if unsure, give the most likely language for its script.")
        }
        append(" ")
        append(strength.promptClause)
        domain.promptClause?.let {
            append(" ")
            append(it)
        }
        append(" Read the text as well as you can even if it is small, cut off, blurry, offensive or not in the language you expected;")
        append(" never refuse, apologise or explain.")
        append(
            if (marksPointer(mode)) " There is almost always text at or next to ${marker.at}."
            else " There is almost always text in the image."
        )
        append(" Only if there is truly none, leave every field empty.")
    }

    /** 응답의 JSON 스키마(`output_config.format`). auto 면 언어도 받는다. */
    fun responseSchema(auto: Boolean): Map<String, Any> {
        val fields = if (auto) listOf("language", "source", "translation") else listOf("source", "translation")
        return mapOf(
            "type" to "object",
            "properties" to fields.associateWith { mapOf("type" to "string") },
            "required" to fields,
            "additionalProperties" to false,
        )
    }

    /** 모델이 읽은 원문과 번역. [language] 는 auto 일 때 모델이 판정한 언어(판정 못 했으면 null). */
    data class Reading(val source: String, val translation: String, val language: String?) {
        /** 표시 아래(또는 영역 안)에 글이 없다는 답. */
        val isEmpty: Boolean get() = source.isBlank() && translation.isBlank()
    }

    /** 응답 JSON 을 읽는다. JSON 객체가 아니거나 필드가 없으면 null. Gson 으로 읽는다 — JVM 단위 시험에서도 돈다(org.json 은 안드로이드 스텁). */
    fun parse(json: String): Reading? = try {
        val o = JsonParser.parseString(json.trim()).takeIf { it.isJsonObject }?.asJsonObject
        fun field(name: String): String? = o?.get(name)?.takeIf { it.isJsonPrimitive }?.asString
        val source = field("source")
        val translation = field("translation")
        if (source == null || translation == null) null
        else Reading(
            source = source.trim(),
            translation = translation.trim(),
            language = field("language")?.trim()?.lowercase()?.takeIf { LANGUAGE_CODE.matches(it) && it != "und" },
        )
    } catch (e: JsonParseException) {
        null
    }

    /** ISO 639-1/-3 코드, 지역·문자 꼬리까지(`zh-hant`, `pt-br`). */
    private val LANGUAGE_CODE = Regex("^[a-z]{2,3}(-[a-z0-9]{2,8})*$")

    /** 프롬프트에 쓸 영어 언어 이름. 모르는 코드는 코드 그대로. */
    fun englishName(code: String): String {
        val locale = Locale.forLanguageTag(code)
        return locale.getDisplayLanguage(Locale.ENGLISH).takeIf { it.isNotBlank() && !it.equals(locale.language, ignoreCase = true) } ?: code
    }
}

/** 표시 아래(또는 잘라 보낸 영역)에 글이 없다는 답 — 실패 안내 없이 조용히 끝낸다. */
class NoTextInImageException : Exception("no text in the image")
