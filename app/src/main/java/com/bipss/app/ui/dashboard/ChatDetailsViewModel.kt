package com.bipss.app.ui.dashboard

import com.bipss.app.localization.AppText
import com.bipss.app.R

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bipss.app.ai.ChatbotEngine
import com.bipss.app.ai.ChatbotResult
import com.bipss.app.ai.CoachCard
import com.bipss.app.ai.MorningDose
import com.bipss.app.ai.TemplatedResponder
import com.bipss.app.data.MetricTypes
import com.bipss.app.data.SensorRepository
import com.bipss.app.data.SleepRepository
import com.bipss.app.data.UserPreferencesRepository
import com.bipss.app.data.environmental.DisasterAlertRepository
import com.bipss.app.domain.AlertRanker
import com.bipss.app.domain.ChatMessage
import com.bipss.app.domain.DataMode
import com.bipss.app.domain.RiskScore
import com.bipss.app.domain.RiskScoreCalculator
import com.bipss.app.domain.Sender
import com.bipss.app.domain.environmental.EnvironmentalSnapshot
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ChatDetailsUiState(
    val messages: List<ChatMessage> = listOf(
        ChatMessage(
            content = AppText.inEnglish { AppText.text(R.string.copy_7b7035e05c35, "%1\$s! I can explain your readings and suggest basic next steps ", com.bipss.app.ai.GreetingHelper.getGreeting()) +
                AppText.text(R.string.copy_e490186bfc76, "when you feel unwell. Tell me what's happening, or ask about a score or your water log.") },
            sender  = Sender.AI
        )
    ),
    val inputText:     String  = "",
    val isAiTyping:    Boolean = false,
    val waterLoggedAt: Long?   = null,
    /** False → replies come from the deterministic TemplatedResponder. */
    val aiModelReady:  Boolean = false,
    val waterTodayMl: Int? = null,
    /** The morning briefing. Empty until there is something real to report. */
    val morningDose:   List<CoachCard> = emptyList(),

    // Live context, read from the repositories rather than borrowed from another
    // view model - see the note on the constructor.
    val heartRate:  Int?   = null,
    val spO2:       Int?   = null,
    val skinTempC:  Float? = null,
    val riskScores: List<RiskScore> = emptyList(),
    val env:        EnvironmentalSnapshot = EnvironmentalSnapshot.EMPTY
)

/**
 * Backs the Coach tab.
 *
 * This deliberately depends on repositories rather than on DashboardViewModel.
 * Koin resolves a view model requested with `get()` as a **new instance**, not
 * the one bound to the dashboard's ViewModelStore, so the previous wiring
 * handed the coach a freshly-constructed dashboard whose state was still empty.
 * The visible symptoms were a briefing with a single card and "you logged water
 * 0 times" while the Home screen showed a logged drink - the coach was
 * describing an empty object, not the user.
 */
class ChatDetailsViewModel(
    private val chatbotEngine: ChatbotEngine,
    private val prefsRepo:     UserPreferencesRepository,
    private val sleepRepo:     SleepRepository,
    private val sensorRepo:    SensorRepository,
    private val envRepo:       DisasterAlertRepository,
    private val calculator:    RiskScoreCalculator,
    private val waterRepo:     com.bipss.app.data.WaterRepository,
    private val scoring:       com.bipss.app.data.ScoringRepository,
    private val cycleRepo:     com.bipss.app.data.CycleRepository,
    private val dailyRepo:     com.bipss.app.data.DailyActivityRepository,
    private val vo2Repo:       com.bipss.app.data.Vo2Repository,
    private val bioAgeRepo:    com.bipss.app.data.BioAgeRepository,
    private val stageRepo:     com.bipss.app.data.SleepStageRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatDetailsUiState())
    val uiState: StateFlow<ChatDetailsUiState> = _uiState.asStateFlow()
    private val liveReadings = com.bipss.app.ai.CoachReadings()
    private var symptomTopic: com.bipss.app.ai.SymptomCoach.Topic? = null
    private var dailyTopic: String? = null

    init {
        _uiState.update { it.copy(aiModelReady = chatbotEngine.isModelAvailable) }
        observeSensors()
        refresh()
    }

    private fun observeSensors() {
        viewModelScope.launch {
            sensorRepo.sensorEvents.collect { reading ->
                liveReadings.accept(reading)
                publishVitals()
            }
        }
        viewModelScope.launch {
            sensorRepo.bandConnected.collect { publishVitals() }
        }
        viewModelScope.launch {
            while (true) { publishVitals(); kotlinx.coroutines.delay(5_000) }
        }
        viewModelScope.launch {
            waterRepo.todayTotals.collect { total -> _uiState.update { it.copy(waterTodayMl = total.totalMl) } }
        }
    }

    private fun publishVitals() {
        val fresh = liveReadings.snapshot(System.currentTimeMillis(), sensorRepo.bandConnected.value)
        _uiState.update { it.copy(heartRate = fresh.heartRate, spO2 = fresh.spO2, skinTempC = fresh.skinTempC) }
    }

    /** Rebuilds the briefing and the chat context from current stored state. */
    fun refresh() {
        viewModelScope.launch { refreshContext() }
    }

    private suspend fun refreshContext() = AppText.inEnglishSuspend {
        publishVitals()
        val env = envRepo.getCachedSnapshot() ?: EnvironmentalSnapshot.EMPTY
        val s = _uiState.value

        val profile = prefsRepo.userProfile.first()
        val greeting = com.bipss.app.ai.GreetingHelper.getGreeting(profile?.name)

        // The same inputs the dashboard uses, so the two never disagree.
        val inputs = scoring.snapshot()
        val scores = calculator.computeAll(
            hr              = s.heartRate,
            spo2            = s.spO2,
            skinTempC       = s.skinTempC,
            personal        = inputs.personal,
            env             = env.takeIf { it.fetchedAtMs > 0L },
            hoursSinceWater = waterRepo.hoursSinceLastLog(),
            sleepScore      = sleepRepo.lastNightScore(),
            recovery        = inputs.recovery,
            strain          = inputs.strain,
            ali             = inputs.ali,
            dataMode        = if (s.heartRate != null) DataMode.FULL else DataMode.ENVIRONMENT_ONLY
        )

        val topAlert = AlertRanker.rank(
            alerts       = env.activeAlerts,
            riskScores   = scores,
            userDistrict = env.districtName,
            userState    = env.stateName,
            userLat      = env.latitude,
            userLon      = env.longitude
        ).let { AlertRanker.topRelevant(it) }.firstOrNull()

        val cards = MorningDose.build(
            sleep      = sleepRepo.lastNight()?.takeIf {
                com.bipss.app.domain.time.LocalDay.epochDay(it.endMs) == com.bipss.app.domain.time.LocalDay.epochDay(System.currentTimeMillis())
            },
            sleepScore = sleepRepo.lastNightScore(),
            scores     = scores,
            env        = env,
            topAlert   = topAlert,
            // The previous local calendar day - not "today so far", and not
            // a UTC day that ends at 05:30 IST.
            waterLoggedYesterday = waterRepo.yesterdayTotals().drinks
        )

        _uiState.update { state ->
            val firstMessage = state.messages.firstOrNull()
            val newMessages = if (firstMessage != null && firstMessage.sender == Sender.AI && state.messages.size == 1) {
                listOf(firstMessage.copy(content = AppText.text(R.string.copy_7ecba64efa4b, "%1\$s! I can explain your readings and suggest basic next steps when you feel unwell. Tell me what's happening, or ask about a score or your water log.", greeting)))
            } else {
                state.messages
            }

            state.copy(
                morningDose = cards,
                riskScores = scores,
                env = env,
                messages = newMessages
            )
        }
    }

    fun onInputChange(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isBlank() || _uiState.value.isAiTyping) return

        val userMsg = ChatMessage(content = text, sender = Sender.USER)
        _uiState.update { it.copy(messages = it.messages + userMsg, inputText = "", isAiTyping = true) }

        val interpretation = com.bipss.app.localization.CoachLanguage.canonicalize(text)
        viewModelScope.launch {
            // Symptom guidance is available immediately, even if a repository or model fails.
            // A chosen question starts a new topic, rather than being mistaken
            // for an answer about the duration of a previous symptom.
            val symptom = AppText.inEnglish { com.bipss.app.ai.SymptomCoach.respond(interpretation,
                if (com.bipss.app.ai.HistoryCoach.isKnownRequest(interpretation)) null else symptomTopic) }
            symptomTopic = symptom?.topic
            val response = AppText.inEnglishSuspend { try {
                val waterCommand = com.bipss.app.ai.DailyCoach.waterCommand(interpretation)
                val activityCommand = com.bipss.app.ai.DailyCoach.activityCommand(interpretation)
                if (symptom != null) symptom.text
                else if (waterCommand != null) {
                    waterRepo.log(waterCommand)
                    AppText.text(R.string.copy_5edcabc0c388, "%1\$s mL logged. You can edit or remove it in Hydration.", waterCommand)
                } else if (activityCommand != null) {
                    dailyRepo.logActivity(activityCommand.first, activityCommand.second, "")
                    AppText.text(R.string.copy_ac96e0752e3c, "Logged %1\$s minutes of %2\$s. You can review or remove it in Activity.", activityCommand.second, activityCommand.first.lowercase())
                } else if (com.bipss.app.ai.HistoryCoach.habitCommand(text) != null) {
                    val name = com.bipss.app.ai.HistoryCoach.habitCommand(text)!!
                    dailyRepo.setHabit(name)
                    AppText.text(R.string.copy_0c952d00a577, "Your weekly habit is ‘%1\$s’. Say ‘Mark my habit done’ after completing it, or ‘Review my habit’ to check progress.", name)
                } else if (com.bipss.app.ai.HistoryCoach.normalize(interpretation) in listOf("mark my habit done", "undo today s habit", "undo todays habit")) {
                    val done = com.bipss.app.ai.HistoryCoach.normalize(interpretation) == "mark my habit done"
                    if (dailyRepo.markHabit(done)) if (done) AppText.text(R.string.copy_2888151cbb5a, "Today's habit is marked done. You can undo it with ‘Undo today's habit’.") else AppText.text(R.string.copy_09ed01c27912, "Today's completion was removed.")
                    else AppText.text(R.string.copy_57470ff7418c, "Choose a habit first: ‘Set my weekly habit: Walk for 10 minutes’.")
                } else if (com.bipss.app.ai.HistoryCoach.normalize(interpretation) == "clear my habit") {
                    dailyRepo.clearHabit(); AppText.text(R.string.copy_912c42b1a8fb, "Your habit and its completion marks were cleared.")
                } else {
                    refreshContext()
                    generateResponse(interpretation, _uiState.value)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) {
                "I couldn't read your latest data. Please try again, or tell me what you're feeling and when it started."
            }
            }
            val aiMsg = ChatMessage(content = response, sender = Sender.AI)
            _uiState.update { it.copy(messages = it.messages + aiMsg, isAiTyping = false) }
        }
    }

    private suspend fun generateResponse(question: String, s: ChatDetailsUiState): String {
        val daily = dailyRepo.data.first()
        val profile = prefsRepo.userProfile.first()
        val today = com.bipss.app.domain.time.LocalDay.epochDay(System.currentTimeMillis())
        // Cycle details are fetched only for an explicit request, never sent to a model.
        if (com.bipss.app.ai.HistoryCoach.isCycleRequest(question)) {
            val periods = cycleRepo.periods.first().filter { it.startDay <= today }
            val notes = cycleRepo.notesBetween(today - 29, today).first()
            return buildString {
                val last = periods.maxByOrNull { it.startDay }
                append(last?.let { "Your latest logged period started ${java.time.LocalDate.ofEpochDay(it.startDay)}" +
                    (it.endDay?.let { end -> " and ended ${java.time.LocalDate.ofEpochDay(end)}." } ?: "; no end is logged.") }
                    ?: "No period start is logged.")
                append(if (notes.isEmpty()) " No cycle notes in the last 30 days." else "\nYour notes from the last 30 days:\n" + notes.toSortedMap().entries.joinToString("\n") { "${java.time.LocalDate.ofEpochDay(it.key)}: ${it.value}" })
                append("\nYou can edit these in Cycle tracking. Notes describe what you recorded; they don't establish a cause or diagnosis.")
            }
        }
        val sessions = sleepRepo.sessionsNewestLast()
        val vo2Latest = vo2Repo.latest.first()
        val bioAgeNow = bioAgeRepo.snapshot.first().result
        val history = com.bipss.app.ai.HistoryCoach.Context(today, daily, sessions.map { s ->
            val bed = java.time.Instant.ofEpochMilli(s.startMs).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
            val wake = java.time.Instant.ofEpochMilli(s.endMs).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
            com.bipss.app.ai.HistoryCoach.Sleep(com.bipss.app.domain.time.LocalDay.epochDay(s.endMs),
                (s.totalSleepMs / 60000).toInt(), bed.hour * 60 + bed.minute, wake.hour * 60 + wake.minute, s.awakenings)
        }, vo2 = vo2Latest?.value, vo2Sigma = vo2Latest?.sigma,
            bioAge = bioAgeNow?.bioAge, bioAgeDelta = bioAgeNow?.yearsDelta, stages = stageRepo.records.first().lastOrNull())
        com.bipss.app.ai.HistoryCoach.respond(question, history)?.let { return it }
        val sleep = sessions.filter { com.bipss.app.domain.time.LocalDay.epochDay(it.endMs) == today }.maxByOrNull { it.totalSleepMs }
        val now = java.time.LocalTime.now()
        val context = com.bipss.app.ai.DailyCoach.Context(
            daily.bandSteps[today], daily.goal, s.waterTodayMl ?: 0,
            prefsRepo.waterGoalMl.first() ?: profile?.let { com.bipss.app.domain.DailyWellness.waterStartingGoal(it.weightKg, it.age) },
            waterRepo.yesterdayTotals().totalMl, sleep?.let { (it.totalSleepMs / 60_000).toInt() },
            daily.activities.filter { com.bipss.app.domain.time.LocalDay.epochDay(it.atMs) in (today - 6)..today }.sumOf { it.minutes },
            profile?.let { com.bipss.app.domain.DailyWellness.bmi(it.heightCm, it.weightKg, it.age) },
            daily.bedtimeMinutes, daily.wakeMinutes, now.hour * 60 + now.minute,
            s.env.aqiValue.takeUnless { s.env.isStale }, s.env.uvIndex.takeUnless { s.env.isStale },
            s.riskScores.firstOrNull { it.type == com.bipss.app.domain.RiskScoreType.RECOVERY && it.hasNumber }?.score)
        val dailyReply = com.bipss.app.ai.DailyCoach.respond(question, context, dailyTopic)
        dailyTopic = dailyReply?.topic
        if (dailyReply != null) return dailyReply.text
        // null is emitted as JSON null, never as a stand-in number: the model
        // must be able to tell "no reading" from "a reading of zero".
        val contextJson = buildString {
            append("{\"heartRate\":${s.heartRate},\"spO2\":${s.spO2},")
            append("\"skinTempC\":${s.skinTempC},")
            append("\"waterTodayMl\":${s.waterTodayMl},")
            append("\"ambientTempC\":${s.env.ambientTempC},")
            append("\"aqiValue\":${s.env.aqiValue},")
            append("\"aqiSource\":\"${s.env.aqiSource.name}\",")
            append("\"scores\":[")
            // Experimental indicators stay out of the model's context: it would
            // treat them as risks. Every other score carries its fidelity.
            s.riskScores.filter { it.hasNumber && it.fidelity != com.bipss.app.domain.Fidelity.EXPERIMENTAL }
                .forEachIndexed { i, sc ->
                    if (i > 0) append(",")
                    append("{\"type\":\"${sc.type.displayName}\",\"value\":\"${sc.valueWithScale}\",")
                    append("\"level\":\"${sc.levelWord}\",\"fidelity\":\"${sc.fidelity.name}\"}")
                }
            append("]}")
        }

        // The system instruction is not part of the user's question. It is
        // application-controlled text that constrains the model's behavior.
        // The context JSON is trusted sensor data, not user input — it cannot
        // be prompt-injected because it is serialized from typed fields.
        val prompt = buildString {
            append("System: You are a health-data reporter for the BIPSS wearable app. ")
            append("Rules you MUST follow:\n")
            append("1. You are NOT a doctor. Never name a medical condition or prescribe treatment.\n")
            append("2. A null value in the context means the sensor is offline or data is unavailable. ")
            append("Say 'no data available' — NEVER guess or estimate a number.\n")
            append("3. Only reference values that appear in the context JSON below. Do not invent readings.\n")
            append("4. If a risk score's level is high, do not reassure the user that everything is fine.\n")
            append("5. For medical symptoms or concerns, recommend consulting a healthcare professional.\n")
            append("6. Keep responses concise and use plain language.\n")
            append("7. Scores are calculated from the readings listed; never present one as a medical measurement.\n\n")
            append("Context: $contextJson\n")
            append("User: $question\n")
            append("Assistant:")
        }

        return if (chatbotEngine.isModelAvailable) {
            when (val result = chatbotEngine.generateResponse(prompt)) {
                is ChatbotResult.Success -> validateAiOutput(result.text, s, question)
                is ChatbotResult.Failure -> templateFallback(question, s)
            }
        } else {
            templateFallback(question, s)
        }
    }

    /**
     * Phase 8: Post-generation safety net.
     * Even with a strict system prompt, an LLM might hallucinate a number (e.g.,
     * "Your heart rate is 72" when the sensor is offline). This scans the output
     * for numbers and compares them against the actual null state.
     */
    private fun validateAiOutput(text: String, s: ChatDetailsUiState, question: String): String {
        val lowerText = text.lowercase()

        // If the AI mentions a metric that is null, but includes digits, it's hallucinating.
        val numberRegex = Regex("\\d+")
        val hasNumbers = numberRegex.containsMatchIn(text)

        if (hasNumbers) {
            val hallucinations = mutableListOf<String>()

            if (s.heartRate == null && (lowerText.contains("heart rate") || lowerText.contains("hr"))) {
                hallucinations.add("heart rate")
            }
            if (s.spO2 == null && (lowerText.contains("oxygen") || lowerText.contains("spo2"))) {
                hallucinations.add("blood oxygen")
            }
            if (s.skinTempC == null && lowerText.contains("skin temp")) {
                hallucinations.add("skin temperature")
            }

            if (hallucinations.isNotEmpty()) {
                return templateFallback(question, s)
            }
        }

        return text
    }

    private fun templateFallback(q: String, s: ChatDetailsUiState) =
        TemplatedResponder.respond(q, s.riskScores, s.heartRate, s.spO2, s.skinTempC, s.waterTodayMl)

    fun logWater(amountMl: Int) {
        viewModelScope.launch {
            waterRepo.log(amountMl)
            _uiState.update { it.copy(waterLoggedAt = System.currentTimeMillis()) }
            val msg = ChatMessage(
                content = "$amountMl mL logged. You can correct it in Home's Hydration card.",
                sender  = Sender.AI
            )
            _uiState.update { it.copy(messages = it.messages + msg) }
            refresh()
        }
    }
}
