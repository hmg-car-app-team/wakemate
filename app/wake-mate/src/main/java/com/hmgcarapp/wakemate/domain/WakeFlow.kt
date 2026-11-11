package com.hmgcarapp.wakemate.domain

/** 음성 청취 목적. */
enum class Purpose { CHOICE, CONFIRM, QUIZ }

/** 배너에 표시하는 짧은 상태(고정 단어: 주행 중 한 줄에서 잘리지 않게). */
object Status {
    const val PREPARING = "준비 중"
    const val SPEAKING = "말하는 중"
    const val LISTENING = "듣는 중"
    const val WORKING = "공조 조절 중"
    const val NAVIGATING = "내비 요청 중"
    const val NOT_SLEEPY = "괜찮다고 응답"
    const val QUIZ_PASSED = "퀴즈 통과"
    const val QUIZ_FAILED = "퀴즈 실패"
    const val ACCEPTED = "수락"
    const val DECLINED = "거절"
    const val NOT_ACCEPTED = "수락 안 됨"
    const val NO_REPLY = "응답 없음"
    const val VOICE_ONLY = "안내만"
    const val FAILED = "실패"
    const val NO_SHELTER = "쉼터 없음"
    const val PARTIAL = "일부 실패"
    const val NO_LOCATION = "위치 미확인"
    const val DONE = "완료"
}

/** 진행 중이거나 방금 끝난 깨우기 한 번. [finished] 면 결과 표시 중(자동 소거 대기). */
data class Alert(
    val id: Long,
    val stage: Stage,
    val status: String = Status.PREPARING,
    val line: String = "",
    val detail: String? = null,
    val score: Int = 0,
    val practice: Boolean = false,
    val listening: Purpose? = null,
    val after: Purpose? = null,
    val shelter: NearShelter? = null,
    val plan: RoutePlan? = null,
    val sampleData: Boolean = false,
    /** 3단계에서 현재 위치를 확인하지 못해 쉼터를 찾지 못한 경우(데이터 없음과 구분). */
    val noLocation: Boolean = false,
    val quizWord: String? = null,
    val introDone: Boolean = false,
    val hvacSummary: String? = null,
    val escalateAfter: Boolean = false,
    /** 공조 결과에 따른 최종 상태(null = 기본). */
    val finalStatus: String? = null,
    val finished: Boolean = false,
    /** 1단계에서 '외기로 바꿔드릴까요?' 를 물었는지(듣는 중 '응' 은 퀴즈가 아니라 외기 전환). */
    val ventOffer: Boolean = false,
) {
    /** 3단계 쉼터 한 줄(배너·주행 화면 공통). 가상 샘플이면 그 사실을 함께 적는다(실데이터는 이름만). */
    val shelterLine: String? get() = shelter?.let { 
        if (sampleData) it.shelter.name + " · 가상 샘플"
        else listOf(it.shelter.kindLabel, it.shelter.name, it.shelter.directionLabel).filter { p -> p.isNotEmpty() }.joinToString(" ") + " · 스냅샷"
    }
}

data class ReplaceConfirm(val shelterId: String)

data class WakeState(
    /** 기본 true(fail-closed). */
    val driving: Boolean = true,
    /** 마지막 위험 갱신 시각(단조 시계 ms). */
    val nowMs: Long = 0L,
    val score: Int = 0,
    val episode: Stage = Stage.NONE,
    val lastAlertAtMs: Long? = null,
    val noneSinceMs: Long? = null,
    val snoozeUntilMs: Long? = null,
    /** 휴지를 건 시점의 최고 단계(에피소드가 초기화돼도 유지): 이 단계 이하는 휴지 중 억제. */
    val snoozeStage: Stage = Stage.NONE,
    val seq: Long = 0L,
    val alert: Alert? = null,
    val quizIdx: Int = 0,
    val voiceReply: Boolean = true,
    val hvacControl: Boolean = true,
    val replaceConfirm: ReplaceConfirm? = null,
    val message: String? = null,
    /** 이번 에피소드 첫 공조 적용 때 읽은 설정 온도(반복 시 누적 하강 방지용 기준). */
    val baseTempC: Float? = null,
    /** 이번 에피소드에서 1단계 외기 전환 제안을 이미 했는지(반복 제안 억제). 휴식·에피소드 초기화 때 해제. */
    val ventOffered: Boolean = false,
) {
    val inFlight: Boolean get() = alert?.finished == false
}

/** 사용자 조작(터치) 이벤트: 주행 중에는 reducer 가 무시한다. 음성 수락과 시스템 이벤트는 해당 없음. */
sealed interface UserEvent

sealed interface WakeEvent {
    data class Options(val voiceReply: Boolean, val hvacControl: Boolean) : WakeEvent
    data class RiskUpdated(val nowMs: Long, val score: Int, val stage: Stage) : WakeEvent
    data class DriveChanged(val driving: Boolean) : WakeEvent
    /** 휴식 감지: 이번 깨우기 에피소드를 끝낸다. */
    data object Rested : WakeEvent
    data class ShelterPlanned(val id: Long, val near: NearShelter?, val plan: RoutePlan?, val sampleData: Boolean, val locationKnown: Boolean = true) : WakeEvent
    data class VoiceSpoken(val id: Long, val ok: Boolean) : WakeEvent
    data class VoiceHeard(val id: Long, val purpose: Purpose, val heard: Heard, val responseMs: Long) : WakeEvent
    data class HvacApplied(val id: Long, val results: List<ItemResult>, val planned: HvacTargets, val connected: Boolean, val baseTempC: Float? = null, val ventOnly: Boolean = false) : WakeEvent
    data class NaviResult(val id: Long?, val status: NaviStatus?) : WakeEvent
    data class ResultSpoken(val id: Long) : WakeEvent
    data class AlertFailed(val id: Long) : WakeEvent
    data class ExpiryElapsed(val id: Long) : WakeEvent
    /** 시연: 단계를 강제로 시작(주행 가드 대상 아님: 시스템 주도). */
    data class ForceStage(val stage: Stage) : WakeEvent
    /** 시연(adb): 듣는 중인 질문에 대한 답을 주입(STT 결과가 오지 않는 환경 대비). */
    data class SimHeard(val text: String) : WakeEvent
    data class Notify(val text: String) : WakeEvent
    data object MessageConsumed : WakeEvent
    data object AppReleased : WakeEvent

    // ---- 사용자 조작(주행 중 무시) ----
    data object Dismiss : WakeEvent, UserEvent
    data object StartQuizPractice : WakeEvent, UserEvent
    data class ReplaceAsked(val shelterId: String) : WakeEvent, UserEvent
    data object ReplaceConfirmed : WakeEvent, UserEvent
    data object ReplaceCancelled : WakeEvent, UserEvent
    /** 정차 화면 버튼: 1단계 외기 전환 제안에 대한 답(주행 중 무시). */
    data object VentAccept : WakeEvent, UserEvent
    data object VentDecline : WakeEvent, UserEvent
    data class ManualNavi(val shelterId: String, val action: NaviAction) : WakeEvent, UserEvent
}

sealed interface WakeEffect {
    data object CancelVoice : WakeEffect
    data class Beep(val id: Long, val stage: Stage) : WakeEffect
    data class Speak(val id: Long, val text: String) : WakeEffect
    data class Listen(val id: Long, val purpose: Purpose) : WakeEffect
    data class PlanShelter(val id: Long) : WakeEffect
    /** [ventOnly] 면 외기 순환만 바꾼다(1단계 제안 수락). 아니면 2단계 계획 전체. */
    data class ApplyHvac(val id: Long, val ventOnly: Boolean = false) : WakeEffect
    data class StartNavi(val id: Long?, val shelterId: String, val action: NaviAction) : WakeEffect
    data class SpeakResult(val id: Long, val text: String) : WakeEffect
    data class ScheduleExpiry(val id: Long, val delayMs: Long) : WakeEffect
    data class ClearNaviFinished(val reqId: Long?) : WakeEffect
    data object ApplyNotSleepy : WakeEffect
    data class RecordQuiz(val outcome: Quiz.Outcome, val responseMs: Long) : WakeEffect
    data class Warn(val text: String) : WakeEffect
}

/** 문구(음성 전문 / 배너 한 줄). */
object WakeText {
    fun s1(score: Int, voiceReply: Boolean, quiz: Boolean = true) =
        "졸음 위험지수 ${score}점이에요. 환기하고 쉬어 가세요." +
            if (voiceReply) " 졸리지 않으면 괜찮아, 퀴즈를 원하면 퀴즈라고 말씀하세요." else ""

    const val VENT_ASK = "졸음 위험이 올라가고 있어요. 공기를 외기로 바꿔드릴까요?"

    fun s2(score: Int, hvac: Boolean) =
        "졸음 위험지수 ${score}점으로 높아요. " + if (hvac) "외기 순환과 바람 세기를 조절해 볼게요." else "창문을 열어 환기하고 가까운 쉼터에서 쉬어 가세요."

    /** 0.05km(50m) 미만이면 '바로 근처예요', 그 외 '약 N킬로미터예요'(소수 한 자리). */
    fun distanceSpeech(km: Double): String = if (km < 0.05) "바로 근처예요" else "약 ${"%.1f".format(km)}킬로미터예요"

    fun s3(score: Int, near: NearShelter?, sample: Boolean, plan: RoutePlan?, voiceReply: Boolean, locationKnown: Boolean = true): String {
        if (!locationKnown) return "졸음 위험지수 ${score}점으로 매우 높아요. 현재 위치를 확인하지 못했어요. 가까운 휴게소나 안전한 곳에서 쉬어 가세요."
        if (near == null) return "졸음 위험지수 ${score}점으로 매우 높아요. 근처 졸음쉼터 정보를 찾지 못했어요. 가까운 휴게소나 안전한 곳에서 쉬어 가세요."
        val dist = distanceSpeech(near.distanceKm)
        val head = if (sample) "졸음 위험지수 ${score}점으로 매우 높아요. 가까운 ${near.shelter.kindLabel}, ${near.shelter.name}, $dist. 시연용 샘플 데이터예요."
        else {
            val dir = near.shelter.directionLabel.let { if (it.isEmpty()) "" else ", $it" }
            "졸음 위험지수 ${score}점으로 매우 높아요. 가까운 ${near.shelter.kindLabel}, ${near.shelter.spokenName}$dir, $dist. " +
                "진행 방향은 반영하지 않으니 방향을 확인하세요. 공공데이터 스냅샷이라 실시간 정보는 아니에요."
        }
        return head + if (voiceReply && plan != null) " ${plan.ask}" else " 안전한 곳에서 쉬어 가세요."
    }

    fun line(stage: Stage, score: Int) = "졸음 ${stage.label} · 위험지수 $score"
    const val NOT_SLEEPY_ACK = "알겠어요. 기준을 조금 높여 둘게요."
    const val QUIZ_PASS = "정답이에요. 정신이 맑으시네요."
    const val QUIZ_FAIL = "조금 더 주의가 필요해요."
    const val NOT_HEARD = "잘 듣지 못했어요."
}

object AlertPolicy {
    const val MIN_GAP_MS = 60_000L
    const val REPEAT_MS = 5 * 60_000L
    const val EPISODE_RESET_MS = 3 * 60_000L
    const val QUIZ_SNOOZE_MS = 5 * 60_000L
    const val TTL_MS = 25_000L

    /** 주행 중에만, 진행 중인 깨우기가 없고, 휴지 중이 아니며, 더 높은 단계이거나 같은 단계가 [REPEAT_MS] 이상 이어질 때. */
    fun shouldFire(s: WakeState, stage: Stage, nowMs: Long): Boolean {
        if (!s.driving || stage == Stage.NONE || s.inFlight) return false
        // 퀴즈 통과 휴지는 같은 단계·낮은 단계에만 적용한다(더 높은 단계로의 상승은 항상 허용)
        if (s.snoozeUntilMs != null && nowMs < s.snoozeUntilMs && stage.n <= s.snoozeStage.n) return false
        val last = s.lastAlertAtMs
        if (last != null && nowMs - last < MIN_GAP_MS) return false
        // 더 높은 단계로 오르면 바로, 아니면 마지막 경고 뒤 REPEAT_MS 가 지났을 때 현재 단계로 반복(에피소드 최고 단계보다 낮아도)
        return stage.n > s.episode.n || (last != null && nowMs - last >= REPEAT_MS)
    }
}

/**
 * 깨우기 상태 전이(순수, 시계 없음: 시각은 이벤트로 들어온다). 컨트롤러는 효과를 실행하고 결과를 이벤트로 되먹일 뿐 상태를 직접 바꾸지 않는다.
 * 졸음 경고(음성·경고음)는 주행 중에도 시스템이 시작하지만, 사용자 터치 조작([UserEvent])은 주행 중 무시한다.
 */
object WakeFlow {
    private val NONE: List<WakeEffect> = emptyList()

    fun reduce(s: WakeState, e: WakeEvent): Pair<WakeState, List<WakeEffect>> {
        if (e is UserEvent && s.driving) return s to NONE
        return when (e) {
            is WakeEvent.Options -> s.copy(voiceReply = e.voiceReply, hvacControl = e.hvacControl) to NONE
            is WakeEvent.RiskUpdated -> risk(s, e)
            is WakeEvent.DriveChanged -> drive(s, e.driving)
            WakeEvent.Rested -> s.copy(episode = Stage.NONE, snoozeUntilMs = null, snoozeStage = Stage.NONE, lastAlertAtMs = null, noneSinceMs = null, baseTempC = null, ventOffered = false) to NONE
            is WakeEvent.ForceStage -> fire(s, e.stage)
            is WakeEvent.ShelterPlanned -> planned(s, e)
            is WakeEvent.VoiceSpoken -> spoken(s, e)
            is WakeEvent.VoiceHeard -> heard(s, e)
            is WakeEvent.SimHeard -> s.alert?.takeIf { it.status == Status.LISTENING && it.listening != null && !it.finished }
                ?.let { a -> heard(s, WakeEvent.VoiceHeard(a.id, a.listening!!, Heard.Text(e.text), 1_000L)) } ?: (s to listOf(WakeEffect.Warn("SIM 무시: 듣는 중인 질문 없음")))
            WakeEvent.VentAccept -> ventAnswer(s, "응")
            WakeEvent.VentDecline -> ventAnswer(s, "아니")
            is WakeEvent.HvacApplied -> hvac(s, e)
            is WakeEvent.NaviResult -> {
                val a = s.alert
                if (e.id != null && a != null && a.id == e.id && !a.finished) {
                    val text = NaviText.spoken(e.status)
                    val st = if (e.status is NaviStatus.Success) Status.ACCEPTED else Status.FAILED
                    s.copy(alert = a.copy(status = st, detail = text)) to listOf(WakeEffect.SpeakResult(e.id, text))
                } else s to NONE
            }
            is WakeEvent.ResultSpoken -> resultSpoken(s, e.id)
            is WakeEvent.AlertFailed -> if (s.alert?.let { it.id == e.id && !it.finished } == true) finish(s, Status.FAILED) else s to NONE
            is WakeEvent.ExpiryElapsed -> if (s.alert?.let { it.id == e.id && it.finished } == true) s.copy(alert = null) to listOf(WakeEffect.ClearNaviFinished(null)) else s to NONE
            WakeEvent.Dismiss -> if (s.alert?.finished == true) s.copy(alert = null) to listOf(WakeEffect.ClearNaviFinished(null)) else s to NONE
            WakeEvent.StartQuizPractice -> if (s.inFlight) s to NONE else practice(s)
            is WakeEvent.ReplaceAsked -> s.copy(replaceConfirm = ReplaceConfirm(e.shelterId)) to NONE
            WakeEvent.ReplaceConfirmed -> s.replaceConfirm?.let { s.copy(replaceConfirm = null) to listOf(WakeEffect.StartNavi(null, it.shelterId, NaviAction.REQUEST_ROUTE)) } ?: (s to NONE)
            WakeEvent.ReplaceCancelled -> s.copy(replaceConfirm = null) to NONE
            is WakeEvent.ManualNavi -> s to listOf(WakeEffect.StartNavi(null, e.shelterId, e.action))
            is WakeEvent.Notify -> (if (s.driving) s else s.copy(message = e.text)) to NONE
            WakeEvent.MessageConsumed -> s.copy(message = null) to NONE
            WakeEvent.AppReleased -> s to listOf(WakeEffect.CancelVoice)
        }
    }

    private fun risk(s0: WakeState, e: WakeEvent.RiskUpdated): Pair<WakeState, List<WakeEffect>> {
        var s = s0.copy(nowMs = e.nowMs, score = e.score)
        // 정상 구간이 길게 이어지면 에피소드를 끝낸다(다음 상승 때 1단계부터)
        s = if (e.stage == Stage.NONE) {
            val since = s.noneSinceMs ?: e.nowMs
            s.copy(noneSinceMs = since, episode = if (e.nowMs - since >= AlertPolicy.EPISODE_RESET_MS) Stage.NONE else s.episode,
                baseTempC = if (e.nowMs - since >= AlertPolicy.EPISODE_RESET_MS) null else s.baseTempC,
                ventOffered = if (e.nowMs - since >= AlertPolicy.EPISODE_RESET_MS) false else s.ventOffered)
        } else s.copy(noneSinceMs = null)
        return if (AlertPolicy.shouldFire(s, e.stage, e.nowMs)) fire(s, e.stage) else s to NONE
    }

    private fun drive(s: WakeState, driving: Boolean): Pair<WakeState, List<WakeEffect>> {
        if (s.driving == driving) return s to NONE
        // 주행 진입: 떠 있던 토스트·덮어쓰기 팝업 폐기
        return if (driving) s.copy(driving = true, message = null, replaceConfirm = null) to NONE else s.copy(driving = false) to NONE
    }

    /** 새 깨우기 시작: 이전 음성 흐름을 끊는다. */
    private fun fire(s: WakeState, stage: Stage): Pair<WakeState, List<WakeEffect>> {
        if (stage == Stage.NONE) return s to NONE
        val id = s.seq + 1
        val base = Alert(id, stage, line = WakeText.line(stage, s.score), score = s.score)
        val ns = s.copy(
            seq = id, alert = base, episode = if (stage.n > s.episode.n) stage else s.episode,
            lastAlertAtMs = s.nowMs, replaceConfirm = null,
        )
        val common = listOf(WakeEffect.CancelVoice, WakeEffect.ClearNaviFinished(null))
        return when (stage) {
            Stage.S1 -> {
                // 음성 응답이 켜져 있고 공조 제어를 쓰며 이번 에피소드에서 아직 제안하지 않았으면 외기 전환을 묻는다(반복 제안 안 함)
                val offer = s.voiceReply && s.hvacControl && !s.ventOffered
                val text = if (offer) WakeText.VENT_ASK else WakeText.s1(s.score, s.voiceReply)
                ns.copy(ventOffered = s.ventOffered || offer,
                    alert = base.copy(status = Status.SPEAKING, ventOffer = offer, after = if (s.voiceReply) Purpose.CHOICE else null)) to
                    (common + WakeEffect.Speak(id, text))
            }
            Stage.S2 -> {
                val text = WakeText.s2(s.score, s.hvacControl)
                ns.copy(alert = base.copy(status = Status.SPEAKING)) to
                    (common + WakeEffect.Beep(id, Stage.S2) + WakeEffect.Speak(id, text) + (if (s.hvacControl) listOf(WakeEffect.ApplyHvac(id)) else NONE))
            }
            else -> ns.copy(alert = base.copy(status = Status.PREPARING)) to (common + WakeEffect.Beep(id, Stage.S3) + WakeEffect.PlanShelter(id))
        }
    }

    private fun practice(s: WakeState): Pair<WakeState, List<WakeEffect>> {
        val id = s.seq + 1
        val word = Quiz.pick(s.quizIdx)
        val a = Alert(id, Stage.S1, Status.SPEAKING, "퀴즈 연습", practice = true, quizWord = word, after = Purpose.QUIZ)
        return s.copy(seq = id, alert = a, quizIdx = s.quizIdx + 1) to listOf(WakeEffect.CancelVoice, WakeEffect.Speak(id, Quiz.question(word)))
    }

    private fun planned(s: WakeState, e: WakeEvent.ShelterPlanned): Pair<WakeState, List<WakeEffect>> {
        val a = s.alert ?: return s to NONE
        if (a.id != e.id || a.finished) return s to NONE
        val text = WakeText.s3(a.score, e.near, e.sampleData, e.plan, s.voiceReply, e.locationKnown)
        val after = if (e.near != null && e.plan != null && s.voiceReply) Purpose.CONFIRM else null
        return s.copy(alert = a.copy(status = Status.SPEAKING, shelter = e.near, plan = e.plan, sampleData = e.sampleData, noLocation = !e.locationKnown, after = after)) to listOf(WakeEffect.Speak(e.id, text))
    }

    private fun spoken(s: WakeState, e: WakeEvent.VoiceSpoken): Pair<WakeState, List<WakeEffect>> {
        val a = s.alert ?: return s to NONE
        if (a.id != e.id || a.finished || a.status != Status.SPEAKING) return s to NONE
        if (a.stage == Stage.S2 && !a.practice) {
            // 공조 결과가 이미 있으면 이어서 알리고, 없으면 결과가 오기를 기다린다. 공조를 쓰지 않으면 여기서 끝.
            val na = a.copy(introDone = true)
            return when {
                !s.hvacControl -> finish(s.copy(alert = na), Status.VOICE_ONLY)
                a.hvacSummary != null -> s.copy(alert = na.copy(status = Status.SPEAKING)) to listOf(WakeEffect.SpeakResult(a.id, a.hvacSummary))
                else -> s.copy(alert = na.copy(status = Status.WORKING)) to NONE
            }
        }
        // 퀴즈 질문 TTS 가 실패했으면 운전자는 질문을 못 들은 것이다: 청취하지 않고 STT 실패와 같게 처리(상승·공조·기록 없음)
        if (a.after == Purpose.QUIZ && !e.ok)
            return s.copy(alert = a.copy(status = Status.FAILED, detail = WakeText.NOT_HEARD, after = null)) to listOf(WakeEffect.SpeakResult(a.id, WakeText.NOT_HEARD))
        val purpose = a.after ?: return finish(s, if (a.stage == Stage.S3 && a.shelter == null) (if (a.noLocation) Status.NO_LOCATION else Status.NO_SHELTER) else Status.VOICE_ONLY)
        return s.copy(alert = a.copy(status = Status.LISTENING, listening = purpose, after = null)) to listOf(WakeEffect.Listen(a.id, purpose))
    }

    private fun hvac(s0: WakeState, e: WakeEvent.HvacApplied): Pair<WakeState, List<WakeEffect>> {
        // 기준 온도는 알림 id 와 무관하게 기억한다(교체된 알림의 쓰기도 실제로 적용되므로)
        val s = s0.copy(baseTempC = s0.baseTempC ?: e.baseTempC)
        val a = s.alert ?: return s to NONE
        if (a.id != e.id || a.finished) return s to NONE
        val summary = HvacReport.summary(e.results, e.planned, e.connected, e.ventOnly)
        val fin = when {
            !e.connected -> Status.FAILED
            e.results.isEmpty() && !e.planned.isEmpty -> Status.FAILED
            e.results.isNotEmpty() && e.results.all { !it.ok } -> Status.FAILED
            HvacReport.hasFailure(e.results) -> Status.PARTIAL
            else -> null
        }
        val na = a.copy(hvacSummary = summary, detail = summary, finalStatus = fin)
        // 안내 문구를 다 말한 뒤에만 결과를 말한다(둘이 겹쳐 앞 문장이 끊기지 않게)
        return if (a.introDone) s.copy(alert = na.copy(status = Status.SPEAKING)) to listOf(WakeEffect.SpeakResult(a.id, summary))
        else s.copy(alert = na) to NONE
    }

    private fun heard(s: WakeState, e: WakeEvent.VoiceHeard): Pair<WakeState, List<WakeEffect>> {
        val a = s.alert ?: return s to NONE
        if (a.id != e.id || a.finished || a.status != Status.LISTENING) return s to NONE
        val h = e.heard
        return when (e.purpose) {
            Purpose.CHOICE -> if (a.ventOffer) ventChoice(s, a, h) else when {
                h !is Heard.Text -> finish(s, if (h == Heard.Timeout) Status.NO_REPLY else Status.FAILED)
                VoiceIntent.isNotSleepy(h.text) ->
                    s.copy(alert = a.copy(status = Status.NOT_SLEEPY, detail = WakeText.NOT_SLEEPY_ACK, listening = null)) to
                        listOf(WakeEffect.ApplyNotSleepy, WakeEffect.SpeakResult(a.id, WakeText.NOT_SLEEPY_ACK))
                VoiceIntent.isQuiz(h.text) || VoiceIntent.isAccept(h.text) -> {
                    val word = Quiz.pick(s.quizIdx)
                    s.copy(quizIdx = s.quizIdx + 1, alert = a.copy(status = Status.SPEAKING, quizWord = word, after = Purpose.QUIZ, listening = null)) to
                        listOf(WakeEffect.Speak(a.id, Quiz.question(word)))
                }
                VoiceIntent.normalize(h.text).isEmpty() -> finish(s, Status.NO_REPLY)
                else -> finish(s, Status.DECLINED)
            }
            Purpose.CONFIRM -> {
                val near = a.shelter; val plan = a.plan
                if (h !is Heard.Text) finish(s, if (h == Heard.Timeout) Status.NO_REPLY else Status.FAILED)
                else if (near == null || plan == null) finish(s, Status.FAILED)
                else if (VoiceIntent.isAccept(h.text, plan.canOverwrite))
                    s.copy(alert = a.copy(status = Status.NAVIGATING, listening = null)) to listOf(WakeEffect.StartNavi(a.id, near.shelter.id, plan.action))
                else if (plan.canOverwrite && VoiceIntent.isPlainYesOnly(h.text)) finish(s, Status.NOT_ACCEPTED) // 경로를 바꾸는 요청은 '네/응' 만으로 수락하지 않는다
                else if (VoiceIntent.normalize(h.text).isEmpty()) finish(s, Status.NO_REPLY)
                else finish(s, Status.DECLINED)
            }
            Purpose.QUIZ -> {
                val word = a.quizWord ?: return finish(s, Status.FAILED)
                // 인식 오류(기술적 실패)는 졸음 판단 근거가 아니다: 안내만 하고 단계 상승·공조 없이 끝낸다(침묵 타임아웃만 무응답 처리)
                if (h == Heard.Failure) return s.copy(alert = a.copy(status = Status.FAILED, detail = WakeText.NOT_HEARD, listening = null)) to listOf(WakeEffect.SpeakResult(a.id, WakeText.NOT_HEARD))
                val o = Quiz.outcome(word, h, e.responseMs)
                val pass = Quiz.alert(o)
                val text = if (pass) WakeText.QUIZ_PASS else WakeText.QUIZ_FAIL
                val snooze = if (pass && !a.practice) s.nowMs + AlertPolicy.QUIZ_SNOOZE_MS else s.snoozeUntilMs
                s.copy(
                    snoozeUntilMs = snooze,
                    snoozeStage = if (pass && !a.practice) s.episode else s.snoozeStage,
                    alert = a.copy(status = if (pass) Status.QUIZ_PASSED else Status.QUIZ_FAILED, detail = quizDetail(o, e.responseMs), listening = null, escalateAfter = !pass && !a.practice),
                ) to listOf(WakeEffect.RecordQuiz(o, e.responseMs), WakeEffect.SpeakResult(a.id, text))
            }
        }
    }

    /** 1단계 외기 전환 제안에 대한 답: 긍정이면 외기 전환만 실행, 퀴즈 요청은 퀴즈, 그 밖(부정·무응답·실패)은 아무것도 바꾸지 않고 끝낸다. */
    private fun ventChoice(s: WakeState, a: Alert, h: Heard): Pair<WakeState, List<WakeEffect>> {
        if (h !is Heard.Text) return finish(s, if (h == Heard.Timeout) Status.NO_REPLY else Status.FAILED)
        val bareOk = VoiceIntent.normalize(h.text).replace(" ", "") in setOf("괜찮아", "괜찮아요", "괜찮습니다") // 제안에 대한 사양('괜찮아요')이므로 졸음 보정이 아니다
        // '네 괜찮아요' 처럼 수락어가 앞서고 '괜찮아'가 붙은 복합 발화는 수락이다('아니/안 괜찮아'는 거절, 단독 '괜찮아요'는 거절)
        val acceptOk = !bareOk && !VoiceIntent.isReject(h.text) && VoiceIntent.isAcceptThenOk(h.text)
        return when {
            VoiceIntent.isReject(h.text) || bareOk -> finish(s, Status.DECLINED)
            acceptOk -> s.copy(alert = a.copy(status = Status.WORKING, listening = null, introDone = true)) to listOf(WakeEffect.ApplyHvac(a.id, ventOnly = true))
            VoiceIntent.isNotSleepy(h.text) ->
                s.copy(alert = a.copy(status = Status.NOT_SLEEPY, detail = WakeText.NOT_SLEEPY_ACK, listening = null)) to
                    listOf(WakeEffect.ApplyNotSleepy, WakeEffect.SpeakResult(a.id, WakeText.NOT_SLEEPY_ACK))
            VoiceIntent.isQuiz(h.text) -> {
                val word = Quiz.pick(s.quizIdx)
                s.copy(quizIdx = s.quizIdx + 1, alert = a.copy(status = Status.SPEAKING, quizWord = word, after = Purpose.QUIZ, listening = null)) to
                    listOf(WakeEffect.Speak(a.id, Quiz.question(word)))
            }
            VoiceIntent.isAccept(h.text) ->
                s.copy(alert = a.copy(status = Status.WORKING, listening = null, introDone = true)) to listOf(WakeEffect.ApplyHvac(a.id, ventOnly = true))
            VoiceIntent.normalize(h.text).isEmpty() -> finish(s, Status.NO_REPLY)
            else -> finish(s, Status.DECLINED)
        }
    }

    /** 화면 버튼 답: 듣는 중인 외기 전환 제안에만 적용한다(음성 응답과 같은 경로). */
    private fun ventAnswer(s: WakeState, word: String): Pair<WakeState, List<WakeEffect>> {
        val a = s.alert?.takeIf { it.ventOffer && it.status == Status.LISTENING && !it.finished } ?: return s to NONE
        return heard(s, WakeEvent.VoiceHeard(a.id, Purpose.CHOICE, Heard.Text(word), 1_000L))
    }

    private fun quizDetail(o: Quiz.Outcome, ms: Long) = when (o) {
        Quiz.Outcome.CORRECT_FAST -> "정답 · 응답 ${"%.1f".format(ms / 1000.0)}초"
        Quiz.Outcome.CORRECT_SLOW -> "정답이지만 응답이 느렸어요(${"%.1f".format(ms / 1000.0)}초)"
        Quiz.Outcome.WRONG -> "오답"
        Quiz.Outcome.NO_ANSWER -> "응답 없음"
    }

    private fun resultSpoken(s: WakeState, id: Long): Pair<WakeState, List<WakeEffect>> {
        val a = s.alert ?: return s to NONE
        if (a.id != id || a.finished) return s to NONE
        val (fin, fx) = finish(s, a.finalStatus ?: a.status.takeIf { it != Status.SPEAKING && it != Status.NAVIGATING && it != Status.WORKING } ?: Status.DONE)
        // 퀴즈 실패(연습 아님)는 곧바로 2단계로 올린다. 이미 2단계 이상을 거쳤으면 올리지 않는다.
        if (a.escalateAfter && s.driving && s.episode.n < Stage.S2.n) {
            val (n2, fx2) = fire(fin, Stage.S2)
            return n2 to (fx + fx2)
        }
        return fin to fx
    }

    private fun finish(s: WakeState, status: String): Pair<WakeState, List<WakeEffect>> {
        val a = s.alert ?: return s to NONE
        val na = a.copy(status = status, finished = true, listening = null, after = null)
        return s.copy(alert = na) to listOf(WakeEffect.ScheduleExpiry(a.id, AlertPolicy.TTL_MS))
    }
}
