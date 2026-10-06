package com.galaxy.airviewdictionary.data.remote.firebase

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Remote Config `service_available` 판정(코드 정리 A1) — 값이 없거나 깨졌으면 열린 것으로 본다. */
class ServiceAvailableTest {

    private fun available(raw: String?, country: String = "KR") = RemoteConfigRepository.serviceAvailable(raw, country)

    @Test
    fun 나라_값이_기본값보다_앞선다() {
        assertFalse(available("""{"default": true, "KR": false}"""))
        assertTrue(available("""{"default": false, "KR": true}"""))
        assertFalse(available("""{"default": false, "KR": true}""", country = "US"))
    }

    @Test
    fun 문자열_불리언도_읽는다() {
        assertFalse(available("""{"default": true, "KR": "false"}"""))
        assertFalse(available("""{"default": "FALSE"}"""))
        assertTrue(available("""{"default": false, "KR": "True"}"""))
    }

    @Test
    fun 값이_없거나_깨졌으면_열린다() {
        assertTrue(available(null))
        assertTrue(available(""))
        assertTrue(available("{"))
        assertTrue(available("[]"))
        assertTrue(available("""{"default": "no"}"""))
    }
}
