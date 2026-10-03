package com.galaxy.airviewdictionary.data.remote.translation

/**
 * AI 이미지 번역(§25)의 하이라이트를 대상 줄·문단에서 번역한 단어·문장 자리로 좁힌다.
 *
 * 이미지 경로는 글을 읽지 않아 단어·문장의 자리를 모른다 — 단어 모드의 대상은 줄, 문장 모드의 대상은 문단이다. 모델은 표시를 보고 단어·문장을
 * 고르고, 그 줄·문단 전체 글([context])도 함께 돌려준다. 고른 글([source])이 [context] 의 몇 번째 글자인지를, 글자 폭이 고르다고 보고 줄 폭에
 * 비례해 화면 자리로 옮긴다. 줄이 여럿이면 줄을 읽는 순서로 이어 붙인 하나의 띠로 본다. 어림이므로 조금 넓혀 잡는다.
 *
 * 상자는 `[left, top, right, bottom]` 이다(JVM 단위 시험에서 돌게 android.graphics.Rect 를 쓰지 않는다).
 */
object ImageTargetNarrowing {

    /**
     * [lines](읽는 순서의 줄 상자) 위에서 [source] 가 차지하는 줄별 상자. [context] 에서 [source] 를 못 찾으면 null — 부르는 쪽은 원래 대상을 그대로 쓴다.
     * [source] 가 [context] 에 여러 번 나오면 [pointer](화면 좌표 x, y)에 가장 가까운 것을 고른다. [rtl] 이면 줄 안에서 오른쪽부터 읽는다.
     */
    fun narrow(
        lines: List<IntArray>,
        context: String,
        source: String,
        pointer: IntArray?,
        rtl: Boolean,
    ): List<IntArray>? {
        if (lines.isEmpty()) return null
        val text = normalize(context)
        val target = normalize(source)
        if (text.isEmpty() || target.isEmpty()) return null
        val ranges = occurrences(text, target)
        if (ranges.isEmpty()) return null

        val weights = text.codePoints().toArray().map { weight(it) }
        val totalWeight = weights.sum()
        if (totalWeight <= 0.0) return null
        val widths = lines.map { (it[2] - it[0]).coerceAtLeast(0) }
        val totalWidth = widths.sum()
        if (totalWidth <= 0) return null
        val scale = totalWidth / totalWeight
        // 글자(코드 포인트) 경계마다의 누적 폭
        val prefix = DoubleArray(weights.size + 1).also { for (i in weights.indices) it[i + 1] = it[i] + weights[i] * scale }
        val cpIndex = codePointIndexOfChar(text)

        val pointerAt = pointer?.let { along(lines, widths, it, rtl) }
        val range = if (pointerAt == null) ranges.first() else ranges.minBy { (s, e) ->
            val mid = (prefix[cpIndex[s]] + prefix[cpIndex[e]]) / 2
            kotlin.math.abs(mid - pointerAt)
        }
        val from = prefix[cpIndex[range.first]]
        val to = prefix[cpIndex[range.second]]
        return spans(lines, widths, from, to, rtl)
    }

    /** 줄 높이에 대한 좌우 여백 비율 — 글자 폭이 고르지 않아 생기는 어긋남을 덮는다. */
    const val PAD_RATIO = 0.3

    /** 좁힌 상자의 최소 폭(줄 높이 배수). 한두 글자 단어도 보이게 한다. */
    const val MIN_WIDTH_RATIO = 0.8

    /** 공백을 하나로 모으고 앞뒤를 자른다 — 모델이 줄바꿈을 넣거나 빼도 같은 글로 본다. */
    internal fun normalize(s: String): String = s.replace(Regex("\\s+"), " ").trim()

    /**
     * [target] 이 [text] 에 나오는 자리들(char 인덱스, 끝 미포함). 그대로 → 대소문자 무시 → 앞뒤 문장부호를 뗀 것 순으로 찾는다.
     */
    internal fun occurrences(text: String, target: String): List<Pair<Int, Int>> {
        fun all(needle: String, ignoreCase: Boolean): List<Pair<Int, Int>> {
            if (needle.isEmpty()) return emptyList()
            val out = mutableListOf<Pair<Int, Int>>()
            var at = text.indexOf(needle, 0, ignoreCase)
            while (at >= 0) {
                out += at to at + needle.length
                at = text.indexOf(needle, at + 1, ignoreCase)
            }
            return out
        }
        all(target, false).takeIf { it.isNotEmpty() }?.let { return it }
        all(target, true).takeIf { it.isNotEmpty() }?.let { return it }
        val trimmed = target.trim { isPunctuationOrSpace(it) }
        if (trimmed != target) all(trimmed, true).takeIf { it.isNotEmpty() }?.let { return it }
        return emptyList()
    }

    /** 앞뒤에서 뗄 글자 — 공백·문장부호·기호. 결합 부호(모음 기호 등)는 글자의 일부라 떼지 않는다. */
    private fun isPunctuationOrSpace(c: kotlin.Char): Boolean = Character.isWhitespace(c) || when (Character.getType(c).toByte()) {
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION,
        Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL -> true
        else -> false
    }

    /** 글자 하나가 차지하는 폭(보통 글자 = 1). 결합 부호(히브리 모음점·인도계 윗점 등)와 서식 문자는 폭이 없다. */
    internal fun weight(codePoint: Int): Double = when (Character.getType(codePoint).toByte()) {
        Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.FORMAT, Character.CONTROL -> 0.0
        else -> 1.0
    }

    /** char 인덱스 → 그 자리까지의 코드 포인트 수. 끝(length)도 담는다. */
    private fun codePointIndexOfChar(text: String): IntArray {
        val out = IntArray(text.length + 1)
        var cp = 0
        var i = 0
        while (i < text.length) {
            val step = Character.charCount(text.codePointAt(i))
            for (k in 0 until step) out[i + k] = cp
            cp++
            i += step
        }
        out[text.length] = cp
        return out
    }

    /** 포인터가 이어 붙인 띠에서 몇 번째 폭 자리인가. 포인터에 가장 가까운 줄을 쓴다. */
    private fun along(lines: List<IntArray>, widths: List<Int>, pointer: IntArray, rtl: Boolean): Double {
        val (px, py) = pointer[0] to pointer[1]
        val index = lines.indices.minBy { i ->
            val b = lines[i]
            when {
                py < b[1] -> b[1] - py
                py > b[3] -> py - b[3]
                else -> 0
            }
        }
        val b = lines[index]
        val inLine = (if (rtl) b[2] - px else px - b[0]).coerceIn(0, widths[index])
        return widths.take(index).sum() + inLine.toDouble()
    }

    /** 띠의 [from]..[to] 를 줄별 상자로 되돌린다. */
    private fun spans(lines: List<IntArray>, widths: List<Int>, from: Double, to: Double, rtl: Boolean): List<IntArray> {
        val out = mutableListOf<IntArray>()
        var start = 0.0
        for (i in lines.indices) {
            val end = start + widths[i]
            val a = maxOf(from, start)
            val z = minOf(to, end)
            if (z > a || (to == from && from in start..end)) {
                val b = lines[i]
                val height = b[3] - b[1]
                val pad = height * PAD_RATIO
                var lo = a - start - pad
                var hi = z - start + pad
                val minWidth = height * MIN_WIDTH_RATIO
                if (hi - lo < minWidth) {
                    val mid = (lo + hi) / 2
                    lo = mid - minWidth / 2
                    hi = mid + minWidth / 2
                }
                // 줄 끝에 걸리면 안쪽으로 민다 — 줄 밖으로 잘려 최소 폭보다 좁아지지 않게
                val width = widths[i].toDouble()
                if (lo < 0) { hi -= lo; lo = 0.0 }
                if (hi > width) { lo -= hi - width; hi = width }
                lo = lo.coerceIn(0.0, width)
                hi = hi.coerceIn(0.0, width)
                val left = if (rtl) b[2] - hi else b[0] + lo
                val right = if (rtl) b[2] - lo else b[0] + hi
                out += intArrayOf(Math.round(left).toInt(), b[1], Math.round(right).toInt(), b[3])
            }
            start = end
        }
        return out
    }
}
