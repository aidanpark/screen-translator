package com.galaxy.airviewdictionary.data.local.vision.kit.paddle

import android.content.Context
import android.os.SystemClock
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * PP-OCRv5 모델 파일을 찾는다.
 *
 * 스토어 설치에서는 Play Asset Delivery 팩(`paddle_models`, fast-follow)에 있다 — 앱 설치가 끝나면 Play 가 받아 준다.
 * installDebug 로 깐 디버그 빌드에는 같은 파일이 APK 에셋으로 들어 있다(app/build.gradle.kts). 팩을 먼저 보고, 없으면 에셋을 본다.
 *
 * 있는지 보는 일([has])은 디스크를 본다. 준비 판정이 요청마다(주 스레드에서도) 부르므로, 있는 파일은 한 번 찾으면 다시 보지 않고 없는 파일은
 * [RECHECK_MILLIS] 동안 없는 것으로 둔다. 없으면 받기를 다시 청한다([requestIfMissing]) — 앱 시작 때 한 번만 청하면 그때 받지 못한 기기(네트워크
 * 없음 등)는 서비스가 며칠 떠 있는 동안 PP-OCRv5 를 못 쓴다.
 */
open class PaddleModelFiles(private val context: Context) {

    private val TAG = javaClass.simpleName

    private val packs by lazy { AssetPackManagerFactory.getInstance(context) }

    /** 팩이 내려와 있는 디렉터리. 아직 없거나(받는 중) 스토어 설치가 아니면 null. */
    private fun packDir(): File? =
        runCatching { packs.getPackLocation(PACK)?.assetsPath() }.getOrNull()?.let { File(it, DIR) }

    /** 찾은 파일. 팩은 앱이 떠 있는 동안 사라지지 않는다(지우는 것은 앱 업데이트·삭제뿐이다). */
    private val present: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 없던 파일 → 마지막으로 본 때(elapsedRealtime). */
    private val missingAt = ConcurrentHashMap<String, Long>()

    open fun has(name: String): Boolean {
        if (name in present) return true
        val now = SystemClock.elapsedRealtime()
        missingAt[name]?.let { if (now - it < RECHECK_MILLIS) return false }
        val found = packDir()?.let { File(it, name).isFile } == true ||
            runCatching { context.assets.list(DIR)?.contains(name) == true }.getOrDefault(false)
        if (found) {
            present += name
            missingAt.remove(name)
        } else {
            missingAt[name] = now
            requestIfMissing()
        }
        return found
    }

    /** [name] 의 내용. 어디에도 없으면 null. 기기 시험이 깨진 모델을 넘기려고 연다. */
    open fun read(name: String): ByteArray? {
        packDir()?.let { dir -> File(dir, name).takeIf { it.isFile }?.let { return it.readBytes() } }
        return try {
            context.assets.open("$DIR/$name").use { it.readBytes() }
        } catch (e: IOException) {
            null
        }
    }

    /**
     * 팩이 아직 없으면 받기를 청한다. fast-follow 는 Play 가 알아서 받지만, 설치 직후 바로 앱을 열면 아직일 수 있다.
     * 스토어 밖 설치(디버그)에서는 실패하는데, 그때는 에셋으로 돈다. 프로세스 전체에서 [REQUEST_INTERVAL_MILLIS] 에 한 번까지만 청한다.
     */
    fun requestIfMissing() {
        val now = SystemClock.elapsedRealtime()
        synchronized(PaddleModelFiles::class.java) {
            if (lastRequestAt != 0L && now - lastRequestAt < REQUEST_INTERVAL_MILLIS) return
            lastRequestAt = now
        }
        if (packDir() != null) return
        runCatching {
            packs.fetch(listOf(PACK))
                .addOnFailureListener { Timber.tag(TAG).i("paddle_models fetch 못 함: ${it.message}") }
        }
    }

    companion object {
        const val PACK = "paddle_models"
        const val DIR = "paddle"

        /** 없던 파일을 다시 볼 때까지. 팩이 막 내려온 뒤 이만큼 안에 PP-OCRv5 로 넘어간다. */
        private const val RECHECK_MILLIS = 30_000L

        /** 받기를 다시 청하는 최소 간격. */
        private const val REQUEST_INTERVAL_MILLIS = 10 * 60_000L

        /** 마지막으로 받기를 청한 때(elapsedRealtime). 0 이면 아직. 앱과 엔진이 이 클래스를 따로 만들므로 프로세스 전체에서 하나다. */
        private var lastRequestAt = 0L
    }
}
