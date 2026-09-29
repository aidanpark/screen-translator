package com.galaxy.airviewdictionary.data.local.vision.kit.paddle

/**
 * 아랍 문자 인식기가 내는 **보이는 순서**(왼쪽부터)의 글을 읽는 순서로 되돌린다(`.docs/vision-engine-design.md` §22).
 *
 * 화면은 유니코드 양방향 알고리즘으로 그려진다 — 오른쪽→왼쪽 흐름 안에서 라틴 글자와 숫자 덩어리만 왼쪽→오른쪽으로 놓이고, 그 밖의 짝 괄호는
 * 거울상 글꼴로 그려진다. 그 역을 이렇게 근사한다.
 *  1. 전체를 뒤집는다.
 *  2. 왼쪽→오른쪽 덩어리는 안의 순서를 되살린다. 덩어리는 강한 LTR 글자(`L`)와 숫자(`EN`·`AN` — 아랍·페르시아 숫자 포함)가 잇닿은 것이고,
 *     사이에 끼었을 때만 함께 묶는 것이 있다: 공백(덩어리 안쪽의 공백), 숫자와 숫자 사이의 숫자 구분자 하나(`ES`·`CS`·`ET` — `3.14`, `1,000`,
 *     `12:30`), 라틴 글자와 라틴 글자 사이의 중립 문자(`Eltabakh/DW`). 덩어리 끝의 공백·부호는 넣지 않는다 — `توجه: متن` 의 `: ` 는 오른쪽→왼쪽 흐름이다.
 *  3. 덩어리 밖의 짝 괄호 `()[]{}<>«»‹›` 는 거울상을 되돌린다.
 *
 * 역은 하나로 정해지지 않는다. 아랍어 문맥에서 숫자 사이의 `-`(`1-2`)나 공백으로 떨어진 두 숫자(`530 000`)는 화면에서 순서가 바뀌어 보이는데
 * (양방향 규칙 W2·N1 — 아랍 글자 뒤의 숫자는 AN 이 되어 `-` 로 이어지지 않고, 숫자 사이 공백은 오른쪽→왼쪽이 된다), 위 규칙은 그것을 한 덩어리로
 * 묶어 화면 순서 그대로 낸다. 그래서 얻은 읽는 순서를 양방향 알고리즘(`java.text.Bidi`, 오른쪽→왼쪽 문단)으로 다시 그려 받은 보이는 순서와
 * 맞춰 본다. 맞으면 그대로, 다르면 숫자가 낀 이음을 적게 가른 후보부터 그려 보고 맞는 첫 후보를 쓴다. 맞는 후보가 없으면(인식이 틀린 글자,
 * 거울상 표 밖의 기호 등) 처음 것을 쓴다(§24).
 *
 * 줄을 잇는 순서는 [com.galaxy.airviewdictionary.data.local.vision.ocr.ReadingOrder] 가 따로 맡는다(예전에는 둘 다 이름이 `ReadingOrder` 였다).
 *
 * 파이썬 포트(`tools/paddle/pipeline.py` 의 `logical_order`)와 같은 알고리즘이다. 안드로이드 타입을 쓰지 않는다 — JVM 시험이 직접 부른다.
 */
internal object VisualOrder {

    private enum class Kind { LTR, DIGIT, SEPARATOR, SPACE, NEUTRAL, RTL }

    private val MIRROR = mapOf(
        '(' to ')', ')' to '(', '[' to ']', ']' to '[', '{' to '}', '}' to '{',
        '<' to '>', '>' to '<', '«' to '»', '»' to '«', '‹' to '›', '›' to '‹',
    )

    /** 글(어휘 한 항목)의 첫 코드 포인트로 가른다 — 사전에는 BMP 밖 글자(`𝑢`)도 있다. */
    private fun kindOf(text: String): Kind {
        if (text.isEmpty()) return Kind.NEUTRAL
        return when (Character.getDirectionality(text.codePointAt(0))) {
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> Kind.LTR
            Character.DIRECTIONALITY_EUROPEAN_NUMBER, Character.DIRECTIONALITY_ARABIC_NUMBER -> Kind.DIGIT
            Character.DIRECTIONALITY_EUROPEAN_NUMBER_SEPARATOR,
            Character.DIRECTIONALITY_COMMON_NUMBER_SEPARATOR,
            Character.DIRECTIONALITY_EUROPEAN_NUMBER_TERMINATOR -> Kind.SEPARATOR
            Character.DIRECTIONALITY_WHITESPACE -> Kind.SPACE
            Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> Kind.RTL
            else -> Kind.NEUTRAL
        }
    }

    /** 덩어리 글자 [left] 와 [right] 사이(둘 다 제외)를 덩어리에 넣는가. */
    private fun joins(kinds: List<Kind>, left: Int, right: Int): Boolean {
        val gap = kinds.subList(left + 1, right)
        return when {
            gap.all { it == Kind.SPACE } -> true
            gap.size == 1 && gap[0] == Kind.SEPARATOR && kinds[left] == Kind.DIGIT && kinds[right] == Kind.DIGIT -> true
            else -> kinds[left] == Kind.LTR && kinds[right] == Kind.LTR && gap.none { it == Kind.RTL }
        }
    }

    /** 읽는 순서의 한 자리 — 보이는 순서의 몇 번째 항목인지와, 그 자리의 글(거울상을 되돌렸으면 바뀐 글). */
    class Placed(val index: Int, val text: String)

    /** 이렇게 많은 이음을 가를 후보까지만 그려 본다(2^n). 한 줄에 숫자가 낀 이음이 이보다 많으면 처음 규칙대로 둔다. */
    private const val MAX_JOINTS = 8

    /** 보이는 순서의 글 [texts] 를 읽는 순서로 놓는다. */
    fun place(texts: List<String>): List<Placed> {
        val kinds = texts.map { kindOf(it) }
        val inRun = BooleanArray(texts.size) { kinds[it] == Kind.LTR || kinds[it] == Kind.DIGIT }
        // 덩어리에 넣은 틈 가운데 숫자가 낀 것 — 양방향 규칙으로는 모호하다
        val joints = mutableListOf<IntRange>()
        var last = -1
        for (i in texts.indices) {
            if (!inRun[i]) continue
            if (last >= 0 && i - last > 1 && joins(kinds, last, i)) {
                for (k in last + 1 until i) inRun[k] = true
                if (kinds[last] == Kind.DIGIT || kinds[i] == Kind.DIGIT) joints.add(last + 1 until i)
            }
            last = i
        }
        val first = assemble(texts, inRun)
        if (joints.isEmpty() || joints.size > MAX_JOINTS) return first
        val shown = folded(texts.joinToString(""))
        if (display(first) == shown) return first
        for (count in 1..joints.size) {
            for (subset in subsets(joints.size, count)) {
                val split = inRun.copyOf()
                for (j in subset) for (k in joints[j]) split[k] = false
                val candidate = assemble(texts, split)
                if (display(candidate) == shown) return candidate
            }
        }
        return first
    }

    /** 덩어리([inRun])는 안의 순서를 지키고 나머지는 한 항목씩, 전체를 뒤집는다. 덩어리 밖의 짝 괄호는 거울상을 되돌린다. */
    private fun assemble(texts: List<String>, inRun: BooleanArray): List<Placed> {
        val segments = mutableListOf<List<Placed>>()
        var i = 0
        while (i < texts.size) {
            if (inRun[i]) {
                var end = i
                while (end < texts.size && inRun[end]) end++
                segments.add((i until end).map { Placed(it, texts[it]) })
                i = end
            } else {
                val text = texts[i]
                val mirrored = if (text.length == 1) MIRROR[text[0]] else null
                segments.add(listOf(Placed(i, mirrored?.toString() ?: text)))
                i++
            }
        }
        return segments.asReversed().flatten()
    }

    /**
     * 읽는 순서의 [logical] 을 오른쪽→왼쪽 문단으로 화면에 놓은 순서의 글([folded]). 짝 괄호의 거울상은 보지 않는다 — 후보끼리는 숫자 사이의 이음만
     * 다르고, 파이썬 포트의 양방향 구현(python-bidi)이 거울상을 하지 않아 두 구현을 같게 둔다.
     */
    private fun display(logical: List<Placed>): String {
        val text = logical.joinToString("") { it.text }
        if (text.isEmpty()) return text
        val bidi = java.text.Bidi(text, java.text.Bidi.DIRECTION_RIGHT_TO_LEFT)
        val levels = ByteArray(text.length) { bidi.getLevelAt(it).toByte() }
        val chars = Array<Any>(text.length) { text[it] }
        java.text.Bidi.reorderVisually(levels, 0, chars, 0, chars.size)
        return folded(chars.joinToString(""))
    }

    /** 짝 괄호를 여는 쪽으로 접는다. */
    private fun folded(text: String): String = buildString(text.length) {
        for (c in text) append(if (c in CLOSING) MIRROR.getValue(c) else c)
    }

    private val CLOSING = setOf(')', ']', '}', '>', '»', '›')

    /** 0 until [n] 에서 [k] 개를 고르는 조합, 사전 순. */
    private fun subsets(n: Int, k: Int): Sequence<IntArray> = sequence {
        val pick = IntArray(k) { it }
        while (true) {
            yield(pick.copyOf())
            var i = k - 1
            while (i >= 0 && pick[i] == n - k + i) i--
            if (i < 0) break
            pick[i]++
            for (j in i + 1 until k) pick[j] = pick[j - 1] + 1
        }
    }

    /** 보이는 순서의 [visual] 을 읽는 순서로. 거울상을 되돌릴 항목은 [mirror] 로 글을 바꾼 새 항목이 된다. */
    fun <T> toLogical(visual: List<T>, textOf: (T) -> String, mirror: (T, String) -> T): List<T> =
        place(visual.map(textOf)).map { placed ->
            val item = visual[placed.index]
            if (placed.text != textOf(item)) mirror(item, placed.text) else item
        }

    /** 코드 포인트 단위로 [toLogical]. */
    fun toLogical(visual: String): String {
        val units = mutableListOf<String>()
        var i = 0
        while (i < visual.length) {
            val n = Character.charCount(visual.codePointAt(i))
            units.add(visual.substring(i, i + n))
            i += n
        }
        return toLogical(units, { it }, { _, mirrored -> mirrored }).joinToString("")
    }
}
