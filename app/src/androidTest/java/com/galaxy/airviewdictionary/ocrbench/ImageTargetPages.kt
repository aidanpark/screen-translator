package com.galaxy.airviewdictionary.ocrbench

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * AI 이미지 번역(`.docs/vision-engine-design.md` §25) 기기 시험의 화면 — 흰 바탕에 문단 셋을 그린다. 문단마다 [sentences] 의 문장 하나를
 * 두 번 이어 쓴다(두세 줄). [pointer] 는 가운데 문단 둘째 줄의 40% 자리다.
 */
internal class ImageTargetPage(
    val bitmap: Bitmap,
    val paragraphs: List<List<Rect>>,
    val texts: List<String>,
    /** 가운데 문단의 문장(두 번 이어 쓴 것의 하나)과 [pointer] 아래 낱말 — 정답. */
    val middleSentence: String,
    val pointerWord: String,
) {

    val pointer: Point
        get() {
            val line = paragraphs[1].getOrElse(1) { paragraphs[1][0] }
            return Point(line.left + line.width() * 2 / 5, line.centerY())
        }

    /** 가운데 문단 상자에 여백을 둔 것 — 영역 선택의 영역. */
    val middleArea: Rect
        get() = Rect(paragraphs[1].first()).apply { paragraphs[1].forEach { union(it) }; inset(-24, -24) }

    companion object {
        fun draw(sentences: List<String>, rtl: Boolean = false, width: Int = 1080, height: Int = 2400, textSize: Float = 44f): ImageTargetPage {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
            val paint = TextPaint().apply { isAntiAlias = true; color = Color.BLACK; this.textSize = textSize }
            val paragraphs = mutableListOf<List<Rect>>()
            val texts = mutableListOf<String>()
            var pointerWord = ""
            var top = 160
            for (i in 0 until 3) {
                val sentence = sentences[i % sentences.size]
                val text = "$sentence $sentence"
                val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width - 120)
                    .setAlignment(if (rtl) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL).build()
                canvas.save(); canvas.translate(60f, top.toFloat()); layout.draw(canvas); canvas.restore()
                val lines = (0 until layout.lineCount).map { l ->
                    Rect(60 + layout.getLineLeft(l).toInt(), top + layout.getLineTop(l), 60 + layout.getLineRight(l).toInt(), top + layout.getLineBottom(l))
                }
                paragraphs += lines
                if (i == 1) {
                    // pointer 와 같은 자리(둘째 줄의 40%)의 낱말 — 공백 없는 문자(크메르·미얀마)는 ICU 낱말 경계로
                    val l = if (layout.lineCount > 1) 1 else 0
                    val x = lines[l].left + lines[l].width() * 2 / 5 - 60f
                    val offset = layout.getOffsetForHorizontal(l, x).coerceIn(0, text.length - 1)
                    val words = java.text.BreakIterator.getWordInstance()
                    words.setText(text)
                    val end = words.following(offset)
                    val start = words.previous()
                    pointerWord = text.substring(start, if (end == java.text.BreakIterator.DONE) text.length else end).trim()
                }
                texts += text
                top += layout.height + (textSize * 3.4f).toInt()
            }
            return ImageTargetPage(bitmap, paragraphs, texts, sentences[1 % sentences.size], pointerWord)
        }

        /** 원문 목록의 이미지 전용 언어 몇 가지 — 문자권이 서로 다르다. */
        val UNREADABLE: Map<String, List<String>> = linkedMapOf(
            "he" to listOf(
                "ירושלים היא עיר הבירה של ישראל והעיר הגדולה ביותר במדינה.",
                "הרכבת הקלה מחברת בין השכונות המרכזיות של העיר.",
                "בקיץ מגיעים אליה מיליוני תיירים מכל העולם.",
            ),
            "bn" to listOf(
                "ঢাকা বাংলাদেশের রাজধানী এবং সবচেয়ে বড় শহর।",
                "বর্ষাকালে শহরের অনেক রাস্তা পানিতে ডুবে যায়।",
                "পুরান ঢাকার খাবার সারা দেশে বিখ্যাত।",
            ),
            "km" to listOf(
                "ភ្នំពេញ គឺជារាជធានី និងជាទីក្រុងធំជាងគេនៃប្រទេសកម្ពុជា។",
                "ទន្លេមេគង្គ ហូរកាត់ទីក្រុងនេះ។",
                "ផ្សារធំថ្មី ជាអគារដ៏ល្បីល្បាញមួយ។",
            ),
            "ka" to listOf(
                "თბილისი არის საქართველოს დედაქალაქი და უდიდესი ქალაქი.",
                "ძველ ქალაქში ბევრი ეკლესია და აბანოა.",
                "ზაფხულში აქ ბევრი ტურისტი ჩამოდის.",
            ),
            "el" to listOf(
                "Η Αθήνα είναι η πρωτεύουσα και η μεγαλύτερη πόλη της Ελλάδας.",
                "Η Ακρόπολη βρίσκεται στο κέντρο της πόλης.",
                "Το καλοκαίρι έρχονται πολλοί τουρίστες.",
            ),
            "ta" to listOf(
                "சென்னை தமிழ்நாட்டின் தலைநகரம் மற்றும் பெரிய நகரம் ஆகும்.",
                "மெரினா கடற்கரை உலகின் நீளமான கடற்கரைகளில் ஒன்று.",
                "இங்கு பல பழைய கோயில்கள் உள்ளன.",
            ),
            "am" to listOf(
                "አዲስ አበባ የኢትዮጵያ ዋና ከተማ እና ትልቁ ከተማ ናት።",
                "ከተማዋ በከፍታ ቦታ ላይ ትገኛለች።",
                "ብዙ ዓለም አቀፍ ድርጅቶች እዚህ ይገኛሉ።",
            ),
            "my" to listOf(
                "နေပြည်တော်သည် မြန်မာနိုင်ငံ၏ မြို့တော် ဖြစ်သည်။",
                "ရန်ကုန်မြို့တွင် ရွှေတိဂုံဘုရား ရှိသည်။",
                "မိုးရာသီတွင် မိုးများစွာ ရွာသည်။",
            ),
            "te" to listOf(
                "హైదరాబాద్ తెలంగాణ రాష్ట్ర రాజధాని మరియు అతిపెద్ద నగరం.",
                "ఈ నగరం బిర్యానీకి ప్రసిద్ధి చెందింది.",
                "చార్మినార్ నగరంలోని ప్రముఖ కట్టడం.",
            ),
            "kn" to listOf(
                "ಬೆಂಗಳೂರು ಕರ್ನಾಟಕದ ರಾಜಧಾನಿ ಮತ್ತು ದೊಡ್ಡ ನಗರ.",
                "ಈ ನಗರವನ್ನು ಉದ್ಯಾನ ನಗರಿ ಎಂದು ಕರೆಯುತ್ತಾರೆ.",
                "ಇಲ್ಲಿ ಅನೇಕ ತಂತ್ರಜ್ಞಾನ ಕಂಪನಿಗಳಿವೆ.",
            ),
            "ml" to listOf(
                "തിരുവനന്തപുരം കേരളത്തിന്റെ തലസ്ഥാനമാണ്.",
                "ഈ നഗരം കടലിനോട് ചേർന്നാണ് സ്ഥിതി ചെയ്യുന്നത്.",
                "പത്മനാഭസ്വാമി ക്ഷേത്രം ഇവിടെയാണ്.",
            ),
            "si" to listOf(
                "කොළඹ ශ්‍රී ලංකාවේ විශාලතම නගරයයි.",
                "නගරයේ බොහෝ වෙළඳසැල් ඇත.",
                "වර්ෂා කාලයේදී මහ වැසි ඇද හැලේ.",
            ),
            "lo" to listOf(
                "ວຽງຈັນ ແມ່ນນະຄອນຫຼວງຂອງປະເທດລາວ.",
                "ແມ່ນ້ຳຂອງໄຫຼຜ່ານເມືອງນີ້.",
                "ມີວັດວາອາຣາມຫຼາຍແຫ່ງໃນເມືອງ.",
            ),
            "hy" to listOf(
                "Երևանը Հայաստանի մայրաքաղաքն է։",
                "Քաղաքից երևում է Արարատ լեռը։",
                "Այստեղ շատ թանգարաններ կան։",
            ),
            "gu" to listOf(
                "અમદાવાદ ગુજરાતનું સૌથી મોટું શહેર છે.",
                "શહેરમાં ઘણી જૂની પોળો છે.",
                "ઉનાળામાં અહીં ખૂબ ગરમી પડે છે.",
            ),
            "pa" to listOf(
                "ਅੰਮ੍ਰਿਤਸਰ ਪੰਜਾਬ ਦਾ ਇੱਕ ਪ੍ਰਸਿੱਧ ਸ਼ਹਿਰ ਹੈ।",
                "ਇੱਥੇ ਹਰਿਮੰਦਰ ਸਾਹਿਬ ਸਥਿਤ ਹੈ।",
                "ਹਰ ਰੋਜ਼ ਹਜ਼ਾਰਾਂ ਲੋਕ ਇੱਥੇ ਆਉਂਦੇ ਹਨ।",
            ),
            "kk" to listOf(
                "Астана — Қазақстан Республикасының астанасы және ірі қаласы.",
                "Қалада көптеген заманауи ғимараттар бар.",
                "Қыста мұнда өте суық болады.",
            ),
            "mn" to listOf(
                "Улаанбаатар бол Монгол улсын нийслэл юм.",
                "Хотод олон сүм хийд бий.",
                "Өвөл энд маш хүйтэн байдаг.",
            ),
            "ti" to listOf(
                "ኣስመራ ርእሰ ከተማ ኤርትራ እያ።",
                "ከተማ ኣብ ልዕሊ ቦታ ትርከብ።",
                "ብዙሓት ሰባት ኣብዚ ይነብሩ።",
            ),
            "yi" to listOf(
                "ניו יאָרק איז די גרעסטע שטאָט אין אַמעריקע.",
                "אין שטאָט וווינען פֿיל מענטשן.",
                "דער ווינטער איז דאָ זייער קאַלט.",
            ),
        )

        /** auto 시험 — 읽을 수 있는 문자와 읽을 엔진이 없는 문자. */
        val AUTO: Map<String, List<String>> = linkedMapOf(
            "ko" to listOf(
                "서울은 대한민국의 수도이자 가장 큰 도시이다.",
                "한강이 도시 한가운데를 가로질러 흐른다.",
                "지하철이 도시 곳곳을 연결한다.",
            ),
            "en" to listOf(
                "The museum opens at nine and closes at six on weekdays.",
                "Tickets can be bought online or at the front desk.",
                "Photography is not allowed in the special exhibition.",
            ),
            "ar" to listOf(
                "القاهرة هي عاصمة مصر وأكبر مدنها.",
                "يمر نهر النيل عبر وسط المدينة.",
                "تشتهر المدينة بأسواقها القديمة.",
            ),
            "he" to UNREADABLE.getValue("he"),
        )

        val RTL = setOf("he", "ar", "yi")
    }
}
