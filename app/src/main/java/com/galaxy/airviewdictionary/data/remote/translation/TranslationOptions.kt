package com.galaxy.airviewdictionary.data.remote.translation

import com.galaxy.airviewdictionary.R

/**
 * AI 번역 엔진의 문맥/스타일 옵션과 프롬프트.
 *
 * 사용자 API 키로 동작하는 엔진(OpenAI·Gemini·Claude)이 함께 쓴다(Google/DeepL 은 프롬프트 개념이 없다).
 */

/** 번역할 문장 주변 텍스트를 얼마나 함께 보낼지. */
enum class TranslationContextMode(val labelResourceId: Int) {
    /** 대상 문장만 보낸다. */
    OFF(R.string.translation_context_off),

    /** 대상이 속한 문단(주변 문장)까지 참고용으로 보낸다. 기본값. */
    NEARBY(R.string.translation_context_nearby),

    /** 화면에서 인식된 텍스트 전부를 참고용으로 보낸다. */
    SCREEN(R.string.translation_context_screen);

    companion object {
        val DEFAULT = NEARBY
        fun from(name: String?): TranslationContextMode =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** 원문에 얼마나 충실하게 옮길지. */
enum class TranslationStrength(val labelResourceId: Int, val promptClause: String) {
    LITERAL(
        labelResourceId = R.string.translation_strength_literal,
        promptClause = "Stay close to the source wording and structure.",
    ),
    NATURAL(
        labelResourceId = R.string.translation_strength_natural,
        promptClause = "Prefer wording that reads naturally to a native speaker, " +
                "while keeping the original meaning intact.",
    ),
    FREE(
        labelResourceId = R.string.translation_strength_free,
        promptClause = "Convey the intent idiomatically; rephrase freely when a literal " +
                "rendering would read awkwardly.",
    );

    companion object {
        val DEFAULT = LITERAL
        fun from(name: String?): TranslationStrength =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** 번역 대상의 분야. 용어 선택과 말투의 기준이 된다. */
enum class TranslationDomain(val labelResourceId: Int, val promptClause: String?) {
    GENERAL(R.string.translation_domain_general, null),
    GAME(
        R.string.translation_domain_game,
        "The text is from a video game UI or dialogue; keep game terminology and character voice.",
    ),
    COMIC(
        R.string.translation_domain_comic,
        "The text is from a comic or webtoon; keep the speech style of the speaker and casual dialogue.",
    ),
    TECH(
        R.string.translation_domain_tech,
        "The text is technical documentation; keep technical terms accurate and leave code, " +
                "identifiers and product names unchanged.",
    ),
    BUSINESS(
        R.string.translation_domain_business,
        "The text is business correspondence; keep a professional and courteous register.",
    );

    companion object {
        val DEFAULT = GENERAL
        fun from(name: String?): TranslationDomain =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * AI 번역 엔진들이 공유하는 시스템 프롬프트. 번역할 것은 화면에서 OCR 로 읽은 글이다(단어·문장·문단, 영역 선택은 여러 줄).
 *
 * 원문 언어를 모르면([sourceLanguageName] = null, auto) 원문 언어를 적지 않고 번역만 청한다. 따로 판정을 청하던 문구("Detect the source
 * language")는 뺐다 — 모델은 글을 읽으며 언어를 알고, 글 번역에는 판정한 언어를 돌려받는 곳이 없다(§24.13). 판정 언어를 돌려받는 것은
 * Claude 이미지 번역뿐이고, 그 프롬프트는 [ImageTranslation.systemPrompt] 가 따로 만든다(§25). auto 에 ML Kit 식별값을 원문으로 넣지 않는
 * 이유는 `TranslationSourceLanguage.forKit` 참고.
 *
 * 출력 형식 제약을 스타일 지시보다 **먼저** 둔다. 순서가 뒤바뀌면 분야/강도 문구가
 * "번역문만 출력" 규칙을 흔들어 설명문이 섞여 나온다.
 */
fun buildTranslationSystemPrompt(
    sourceLanguageName: String?,
    targetLanguageName: String,
    strength: TranslationStrength,
    domain: TranslationDomain,
    hasContext: Boolean,
): String {
    val fromClause = if (sourceLanguageName == null) {
        "Translate the user's text into $targetLanguageName."
    } else {
        "Translate the user's text from $sourceLanguageName into $targetLanguageName."
    }
    return buildString {
        append("You are a professional translation engine. ")
        append(fromClause)
        append(" Output ONLY the translated text — no quotes, no explanations, no notes, and no source text.")
        append(" Preserve the original meaning, tone, and line breaks.")
        append(" If the text is already in $targetLanguageName, return it unchanged.")
        append(" ")
        append(strength.promptClause)
        domain.promptClause?.let {
            append(" ")
            append(it)
        }
        if (hasContext) {
            append(
                " The user message contains a <context> block and a <text> block." +
                        " The <context> block is surrounding text from the same screen, provided only to" +
                        " resolve pronouns, omitted subjects and ambiguous words." +
                        " Translate ONLY the contents of the <text> block." +
                        " Never translate, quote or mention the <context> block, and do not output the tags."
            )
        }
    }
}

/** 문맥이 있으면 태그로 구분해 한 메시지에 담는다. */
fun buildTranslationUserMessage(sourceText: String, contextText: String?): String {
    if (contextText.isNullOrBlank()) return sourceText
    return "<context>\n$contextText\n</context>\n<text>\n$sourceText\n</text>"
}
