package com.galaxy.airviewdictionary.data.local.vision.model

sealed interface VisionResponse {
    data class Success(val result: VisionResult) : VisionResponse
    data class Error(val t: Throwable) : VisionResponse
}

