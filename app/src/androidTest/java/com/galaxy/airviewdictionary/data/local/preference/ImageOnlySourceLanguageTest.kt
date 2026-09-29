package com.galaxy.airviewdictionary.data.local.preference

import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.galaxy.airviewdictionary.data.remote.translation.TranslationKitType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 화면 글자를 읽을 엔진이 없는 원문 언어(히브리어 등)는 Claude 이미지 번역으로만 원문이 된다(`.docs/vision-engine-design.md` §25) —
 * 엔진이 Claude 일 때만 그대로 읽고, 다른 엔진이면 auto 로 읽는다. 실제 DataStore 로 본다. 기기의 원래 값은 되돌린다.
 */
@RunWith(AndroidJUnit4::class)
class ImageOnlySourceLanguageTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = PreferenceRepository(context)

    private var savedSource: String? = null
    private var savedKit: String? = null

    @Before
    fun save() = runBlocking {
        val prefs = context.preferenceDataStore.data.first()
        savedSource = prefs[PreferenceRepository.SOURCE_LANGUAGE_CODE]
        savedKit = prefs[PreferenceRepository.TRANSLATION_KIT_TYPE]
    }

    @After
    fun restore() = runBlocking {
        context.preferenceDataStore.edit { prefs ->
            savedSource?.let { prefs[PreferenceRepository.SOURCE_LANGUAGE_CODE] = it } ?: prefs.remove(PreferenceRepository.SOURCE_LANGUAGE_CODE)
            savedKit?.let { prefs[PreferenceRepository.TRANSLATION_KIT_TYPE] = it } ?: prefs.remove(PreferenceRepository.TRANSLATION_KIT_TYPE)
        }
        Unit
    }

    private fun set(source: String, kit: TranslationKitType) = runBlocking {
        context.preferenceDataStore.edit { prefs ->
            prefs[PreferenceRepository.SOURCE_LANGUAGE_CODE] = source
            prefs[PreferenceRepository.TRANSLATION_KIT_TYPE] = kit.name
        }
        Unit
    }

    @Test
    fun 이미지_전용_언어는_Claude_일_때만_그대로_읽는다() = runBlocking {
        set("he", TranslationKitType.CLAUDE)
        assertEquals("he", repository.sourceLanguageCodeFlow.first())
        set("bn", TranslationKitType.CLAUDE)
        assertEquals("bn", repository.sourceLanguageCodeFlow.first())
        for (kit in TranslationKitType.entries.filter { it != TranslationKitType.CLAUDE }) {
            set("he", kit)
            assertEquals("$kit", "auto", repository.sourceLanguageCodeFlow.first())
        }
    }

    @Test
    fun 읽을_수_있는_언어는_엔진과_무관하다() = runBlocking {
        for (kit in TranslationKitType.entries) {
            set("ko", kit)
            assertEquals("$kit", "ko", repository.sourceLanguageCodeFlow.first())
            set("ar", kit)
            assertEquals("$kit", "ar", repository.sourceLanguageCodeFlow.first())
        }
    }
}
