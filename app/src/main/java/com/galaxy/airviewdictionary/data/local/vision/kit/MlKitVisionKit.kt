package com.galaxy.airviewdictionary.data.local.vision.kit

import android.graphics.Bitmap
import androidx.lifecycle.Lifecycle
import com.galaxy.airviewdictionary.data.local.vision.ocr.OcrLine
import com.galaxy.airviewdictionary.data.local.vision.ocr.OcrText
import com.galaxy.airviewdictionary.data.local.vision.ocr.toOcrText
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException


enum class TextRecognizerType {
    TEXT,
    CHINESE,
    KOREAN,
    JAPANESE,
    DEVANAGARI,
}

/** ML Kit 문자 인식기 하나. 검출과 인식을 쪼갤 수 없어 [detect] 에서 화면을 통째로 읽는다. */
class MlKitVisionKit(val type: TextRecognizerType) : VisionKit {

    private val TAG = javaClass.simpleName

    override val name: String get() = type.name

    private  var recognizer: TextRecognizer

    init {
        recognizer = createTextRecognizer(type)
    }

    private fun createTextRecognizer(type: TextRecognizerType): TextRecognizer {
        return when (type) {
            TextRecognizerType.TEXT -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            TextRecognizerType.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            TextRecognizerType.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            TextRecognizerType.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            TextRecognizerType.DEVANAGARI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        }
    }

    override fun addObserver(lifecycle: Lifecycle) {
        lifecycle.addObserver(recognizer)
    }

    /** ML Kit 결과 타입은 여기서 밖으로 나가지 않는다. */
    override suspend fun detect(screen: Bitmap): OcrText = process(InputImage.fromBitmap(screen, 0)).toOcrText()

    /** [detect] 가 이미 다 읽었다. */
    override suspend fun recognize(screen: Bitmap, lines: List<OcrLine>): List<OcrLine> = lines

    private suspend fun process(inputImage: InputImage): Text =
        suspendCancellableCoroutine { continuation ->
            Timber.tag(TAG).d("---------- process ---------")
            recognizer.process(inputImage)
                .addOnSuccessListener { result ->
                    continuation.resume(result)
                }
                .addOnFailureListener { exception ->
                    if (exception is MlKitException && exception.message?.contains("closed") == true) {
                        Timber.tag(TAG).d("TextRecognizer needs to be reinitialized.")
                        recognizer = createTextRecognizer(type)
                        recognizer.process(inputImage)
                            .addOnSuccessListener { result ->
                                continuation.resume(result)
                            }
                            .addOnFailureListener { ex ->
                                continuation.resumeWithException(ex)
                            }
                    } else {
                        continuation.resumeWithException(exception)
                    }
                }
        }

    /** 인식기를 닫는다 — 앱은 수명 관찰자([addObserver])로 닫고, 이 함수는 비용 측정 시험(`MlKitCostTest`)만 쓴다. */
    fun closeRecognizer() {
        recognizer.close()
    }
}
