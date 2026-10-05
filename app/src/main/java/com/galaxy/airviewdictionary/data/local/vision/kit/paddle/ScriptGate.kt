package com.galaxy.airviewdictionary.data.local.vision.kit.paddle

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * auto 의 지원되지 않는 문자권 관문(`.docs/perf-experiment-plan.md` §7, 성능 P7).
 *
 * PP-OCRv5 검출 줄 가운데 넓은 줄 [VOTE] 개를 문자 판별기(§6, `script.onnx` — 합성 줄로 학습한 작은 합성곱 신경망)에 넣어, 읽을 엔진이 없는
 * 문자권이라고 확신할 때만 개입한다. 규칙(§7.1 에서 선택): 줄이 [MIN_LINES] 개 이상이고, 줄의 [MIN_SHARE] 이상이 같은 지원되지 않는 문자권으로
 * 판정되고, 그 줄들의 그 문자권 평균 확률이 [MIN_PROB] 이상이다. 지원 문자권 사이의 판별은 하지 않는다 — 지금 auto 에 맡긴다.
 *
 * 전처리는 평가 도구(`tools/scriptid/crops.py`, `train.py`)와 같아야 한다: 줄 상자를 줄 높이의 15% 만큼 넓혀 자르고, 회색조(Pillow 의 L)로 바꿔
 * 높이 [H] 로 줄인 뒤(Pillow 의 쌍선형 — 줄일 때는 범위를 넓혀 평균한다) 왼쪽 [W] 픽셀만 쓰고, 모자라면 가장자리 중앙값으로 채워 표준화한다.
 * 이 파일은 안드로이드에 기대지 않는다(JVM 단위 시험) — 비트맵 읽기와 추론은 [PaddleKits.scriptGate] 가 한다.
 */
object ScriptGate {

    /** 기기 시간 비교 시험이 끈다(관문을 켠 auto 와 끈 auto 의 지연, 성능 P7 조건 4). */
    @Volatile
    var enabled = true

    const val MODEL = "script.onnx"
    const val H = 32
    const val W = 256
    const val VOTE = 12
    const val MIN_LINES = 3
    const val MIN_SHARE = 0.5
    const val MIN_PROB = 0.5

    /** 학습 때의 문자권 순서(`tools/scriptid/scripts.py` SCRIPTS). 앞의 8개는 앱이 읽는 문자권이다. */
    val SCRIPTS = listOf(
        "latin", "cyrillic", "arabic", "thai", "devanagari", "hangul", "han", "japanese",
        "hebrew", "greek", "bengali", "tamil", "telugu", "georgian", "armenian", "khmer", "lao", "myanmar",
        "ethiopic", "gujarati", "gurmukhi", "kannada", "malayalam", "sinhala", "oriya",
    )
    private const val SUPPORTED = 8

    /** 안내 문구에 쓸 대표 언어(문자권 이름 대신 기기 언어로 된 언어 이름을 쓴다). */
    val LANGUAGE = mapOf(
        "hebrew" to "he", "greek" to "el", "bengali" to "bn", "tamil" to "ta", "telugu" to "te", "georgian" to "ka",
        "armenian" to "hy", "khmer" to "km", "lao" to "lo", "myanmar" to "my", "ethiopic" to "am", "gujarati" to "gu",
        "gurmukhi" to "pa", "kannada" to "kn", "malayalam" to "ml", "sinhala" to "si", "oriya" to "or",
    )

    /** 관문의 판정 — 개입하면 그 문자권과 대표 언어. */
    data class Verdict(val script: String, val language: String)

    /** 줄 상자 `[l, t, r, b]` 중 판별에 쓸 줄 — 가로줄(폭 ≥ 높이 × 2)이고 충분히 큰 것을 넓은 순으로 [VOTE] 개. [screenWidth] 로 크기 문턱을 맞춘다. */
    fun pickLines(boxes: List<IntArray>, screenWidth: Int): List<IntArray> {
        val minHeight = 12.0 * screenWidth / 1440 // 평가 캡처(폭 1440)에서 높이 12px
        return boxes.filter { (it[3] - it[1]) >= minHeight && (it[2] - it[0]) >= 2 * (it[3] - it[1]) }
            .sortedByDescending { it[2] - it[0] }
            .take(VOTE)
    }

    /**
     * 화면 픽셀(ARGB, 행 우선 [width] × [height])에서 줄 [box] 하나를 판별기 입력([H] × [W], 표준화된 값)으로 만든다.
     */
    fun lineInput(pixels: IntArray, width: Int, height: Int, box: IntArray): FloatArray {
        val c = cropRect(box, width, height)
        val cw = c[2] - c[0]; val ch = c[3] - c[1]
        val crop = IntArray(cw * ch)
        for (y in 0 until ch) System.arraycopy(pixels, (c[1] + y) * width + c[0], crop, y * cw, cw)
        return cropInput(crop, cw, ch)
    }

    /** 줄 상자를 줄 높이의 15% 만큼 넓혀 화면 안으로 자른 사각형 `[x0, y0, x1, y1]`(폭 · 높이 ≥ 1). */
    fun cropRect(box: IntArray, width: Int, height: Int): IntArray {
        val m = ((box[3] - box[1]) * 0.15).toInt()
        val x0 = max(0, box[0] - m).coerceAtMost(width - 1); val y0 = max(0, box[1] - m).coerceAtMost(height - 1)
        val x1 = min(width, box[2] + m).coerceAtLeast(x0 + 1); val y1 = min(height, box[3] + m).coerceAtLeast(y0 + 1)
        return intArrayOf(x0, y0, x1, y1)
    }

    /** 자른 줄의 픽셀(ARGB, [cw] × [ch])을 판별기 입력([H] × [W], 표준화된 값)으로. */
    fun cropInput(crop: IntArray, cw: Int, ch: Int): FloatArray {
        // Pillow convert("L"): (r*19595 + g*38470 + b*7471 + 0x8000) >> 16
        val gray = FloatArray(cw * ch)
        for (i in gray.indices) {
            val p = crop[i]
            gray[i] = (((p shr 16 and 0xFF) * 19595 + (p shr 8 and 0xFF) * 38470 + (p and 0xFF) * 7471 + 0x8000) shr 16).toFloat()
        }
        val outW = max(1, (cw * H.toDouble() / ch).roundToInt())
        val keep = min(outW, W) // 왼쪽 W 픽셀만 필요하다
        val scaled = resize(gray, cw, ch, outW, H, keep)
        return fitAndNormalize(scaled, keep)
    }

    /** Pillow 의 쌍선형 크기 조정(Image.resize BILINEAR — 줄일 때 범위를 넓힌 삼각 필터)을 가로 → 세로로. 출력은 왼쪽 [keepW] 열만 만든다. */
    internal fun resize(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int, keepW: Int): FloatArray {
        // 픽셀마다 객체를 만들지 않는다 — 기기에서 줄 12개에 30ms 넘게 들었다(성능 P7 기기 측정)
        val horiz = FloatArray(keepW * sh)
        val hw = weights(sw, dw, keepW)
        val hStart = hw.first
        val hWeights = hw.second
        for (y in 0 until sh) {
            val row = y * sw
            for (x in 0 until keepW) {
                val ws = hWeights[x]
                val base = row + hStart[x]
                var acc = 0f
                for (k in ws.indices) acc += src[base + k] * ws[k]
                horiz[y * keepW + x] = clampRound(acc)
            }
        }
        val out = FloatArray(keepW * dh)
        val vw = weights(sh, dh, dh)
        val vStart = vw.first
        val vWeights = vw.second
        for (y in 0 until dh) {
            val ws = vWeights[y]
            val start = vStart[y]
            for (x in 0 until keepW) {
                var acc = 0f
                for (k in ws.indices) acc += horiz[(start + k) * keepW + x] * ws[k]
                out[y * keepW + x] = clampRound(acc)
            }
        }
        return out
    }

    /** 출력 [count] 칸의 (시작 위치, 가중치들). Pillow precompute_coeffs 와 같다(쌍선형 지지 1, 줄일 때 배율만큼 넓힌다). */
    private fun weights(inSize: Int, outSize: Int, count: Int): Pair<IntArray, Array<FloatArray>> {
        val scale = inSize.toDouble() / outSize
        val filterScale = max(scale, 1.0)
        val support = filterScale
        val starts = IntArray(count)
        val ws = Array(count) { FloatArray(0) }
        for (i in 0 until count) {
            val center = (i + 0.5) * scale
            val xmin = max((center - support + 0.5).toInt(), 0)
            val xmax = min((center + support + 0.5).toInt(), inSize)
            val w = FloatArray(max(0, xmax - xmin))
            var sum = 0.0
            for (x in xmin until xmax) {
                val v = max(0.0, 1.0 - abs((x - center + 0.5) / filterScale))
                w[x - xmin] = v.toFloat(); sum += v
            }
            if (sum > 0) for (k in w.indices) w[k] = (w[k] / sum).toFloat()
            starts[i] = xmin; ws[i] = w
        }
        return starts to ws
    }

    private fun clampRound(v: Float): Float {
        val r = (v + 0.5f).toInt() // 0 이상이라 반올림과 같다
        return (if (r > 255) 255 else if (r < 0) 0 else r).toFloat()
    }

    /** 폭 [w] 의 줄([H] 행)을 폭 [W] 로 — 모자라면 가장자리 중앙값으로 채우고 — 줄마다 표준화한다(평균 0, 표준편차 1). */
    internal fun fitAndNormalize(a: FloatArray, w: Int): FloatArray {
        val out = FloatArray(H * W)
        if (w >= W) {
            for (y in 0 until H) for (x in 0 until W) out[y * W + x] = a[y * w + x]
        } else {
            val border = FloatArray(2 * H + 2 * w)
            var n0 = 0
            for (y in 0 until H) { border[n0++] = a[y * w]; border[n0++] = a[y * w + w - 1] }
            for (x in 0 until w) { border[n0++] = a[x]; border[n0++] = a[(H - 1) * w + x] }
            border.sort()
            val n = border.size
            val median = if (n % 2 == 1) border[n / 2] else (border[n / 2 - 1] + border[n / 2]) / 2
            val fill = median.toInt().toFloat() // numpy 가 uint8 배열에 넣으며 버린다
            for (y in 0 until H) for (x in 0 until W) out[y * W + x] = if (x < w) a[y * w + x] else fill
        }
        var mean = 0.0
        for (v in out) mean += v / 255.0
        mean /= out.size
        var varSum = 0.0
        for (v in out) { val d = v / 255.0 - mean; varSum += d * d }
        val std = sqrt(varSum / out.size)
        for (i in out.indices) out[i] = ((out[i] / 255.0 - mean) / (std + 1e-3)).toFloat()
        return out
    }

    /** 줄별 확률([줄][문자권], 넓은 줄부터)로 관문을 판정한다. 개입하지 않으면 null. */
    fun judge(probs: List<FloatArray>): Verdict? {
        if (probs.size < MIN_LINES) return null
        val top = probs.map { p -> p.indices.maxBy { p[it] } }
        for (k in top.distinct()) {
            if (k < SUPPORTED) continue
            val members = probs.indices.filter { top[it] == k }
            val share = members.size.toDouble() / probs.size
            val mean = members.map { probs[it][k].toDouble() }.average()
            if (share >= MIN_SHARE && mean >= MIN_PROB) {
                val script = SCRIPTS[k]
                return Verdict(script, LANGUAGE.getValue(script))
            }
        }
        return null
    }

    /** 로짓 → 확률. */
    fun softmax(logits: FloatArray): FloatArray {
        val m = logits.max()
        val e = FloatArray(logits.size) { kotlin.math.exp((logits[it] - m).toDouble()).toFloat() }
        val s = e.sum()
        return FloatArray(e.size) { e[it] / s }
    }
}
