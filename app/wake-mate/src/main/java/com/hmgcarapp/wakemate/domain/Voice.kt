package com.hmgcarapp.wakemate.domain

/** 음성 응답 해석(순수). 상시 청취는 하지 않고 질문 직후 1회 인식 결과만 해석한다. */
object VoiceIntent {
    const val MAX_COMPACT = 14

    private val accept = listOf(
        "응", "네", "넵", "예", "그래", "그래요", "좋아", "좋아요", "좋습니다", "오케이", "ok", "okay", "yes",
        "알겠어", "알겠어요", "알겠습니다", "안내해줘", "안내해주세요", "안내해", "시작해줘", "시작해", "해줘", "해주세요", "부탁해", "부탁해요",
    )
    private val overwrite = listOf("바꿔줘", "바꿔줘요", "바꿔주세요", "바꿔", "변경해줘", "변경해주세요", "그렇게해줘", "그렇게해주세요")
    private val quizWords = listOf("퀴즈", "게임", "끝말잇기")
    private val notSleepy = listOf("졸리지않", "안졸려", "안졸립", "괜찮아", "괜찮아요", "괜찮습니다", "멀쩡", "깼어", "깨어있", "안졸")
    private val rejectWords = setOf("아니", "아니요", "아뇨", "아냐", "싫어", "싫어요", "취소", "됐어", "됐어요", "말고", "나중에", "그만", "안돼", "안돼요")
    private val rejectCompact = listOf("안해", "하지마", "안할래")

    fun normalize(text: String): String =
        text.lowercase().map { if (it.isLetterOrDigit() || it == ' ') it else ' ' }.joinToString("").trim().replace(Regex("\\s+"), " ")

    private fun compact(text: String) = normalize(text).replace(" ", "")

    fun isReject(text: String): Boolean {
        val n = normalize(text)
        if (n.isEmpty()) return false
        if (n.split(' ').any { it in rejectWords }) return true
        return rejectCompact.any { n.replace(" ", "").contains(it) }
    }

    /** '졸리지 않아요/괜찮아' 계열. 수락·거절보다 먼저 판정한다. */
    fun isNotSleepy(text: String): Boolean {
        val c = compact(text)
        return c.isNotEmpty() && notSleepy.any { c.contains(it) } && !c.contains("안괜찮")
    }

    fun isQuiz(text: String): Boolean = compact(text).let { c -> c.isNotEmpty() && quizWords.any { c.contains(it) } }

    /** 수락: 공백을 뺀 전체가 수락 표현의 연속이어야 한다. [overwriteRoute] 면 경로를 덮어쓰는 요청이라 단음절 수락은 불가. */
    fun isAccept(text: String, overwriteRoute: Boolean = false): Boolean {
        val n = normalize(text)
        if (n.isEmpty() || isReject(text)) return false
        if (text.contains('?') || text.contains('？')) return false
        val c = n.replace(" ", "")
        if (c.length > MAX_COMPACT) return false
        val phrases = if (overwriteRoute) overwrite else accept
        val ok = BooleanArray(c.length + 1).also { it[0] = true }
        for (i in 1..c.length) ok[i] = phrases.any { ph -> i >= ph.length && ok[i - ph.length] && c.regionMatches(i - ph.length, ph, 0, ph.length) }
        return ok[c.length]
    }

    /** 단음절 긍정('네/응')만으로 이뤄졌는지(덮어쓰기 요청에서는 수락 불가임을 알리는 데 사용). */
    fun isPlainYesOnly(text: String): Boolean = isAccept(text, false) && !isAccept(text, true)
}

/** 끝말잇기 퀴즈(순수). */
object Quiz {
    /** 끝 글자로 이을 수 있는 단어(받침/두음법칙 문제가 없는 글자로 끝나는 것만). */
    val WORDS = listOf("기차", "사과", "나무", "바다", "하마", "고구마", "자동차", "비행기", "도서관", "라디오")
    const val SLOW_MS = 7_000L

    fun pick(index: Int): String = WORDS[Math.floorMod(index, WORDS.size)]
    fun question(word: String) = "끝말잇기 퀴즈예요. $word, 다음 말은 무엇일까요?"

    /** [answer] 이 [word] 의 끝 글자로 시작하고 2글자 이상이며 같은 단어가 아니면 정답. */
    fun isCorrect(word: String, answer: String): Boolean {
        val a = VoiceIntent.normalize(answer).replace(" ", "")
        if (a.length < 2 || a == word) return false
        return a.first() == word.last()
    }

    enum class Outcome { CORRECT_FAST, CORRECT_SLOW, WRONG, NO_ANSWER }

    fun outcome(word: String, heard: Heard, responseMs: Long): Outcome = when (heard) {
        is Heard.Text -> when {
            VoiceIntent.normalize(heard.text).isEmpty() -> Outcome.NO_ANSWER
            !isCorrect(word, heard.text) -> Outcome.WRONG
            responseMs > SLOW_MS -> Outcome.CORRECT_SLOW
            else -> Outcome.CORRECT_FAST
        }
        Heard.Failure, Heard.Timeout -> Outcome.NO_ANSWER
    }

    /** 각성이 확인된(정답 + 빠른 응답) 경우만 true. */
    fun alert(o: Outcome) = o == Outcome.CORRECT_FAST
}

/** 음성 청취 결과의 순수 표현. */
sealed interface Heard {
    data class Text(val text: String) : Heard
    data object Failure : Heard
    data object Timeout : Heard
}
