package com.bipss.app.ui.dashboard

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
            content = "${com.bipss.app.ai.GreetingHelper.getGreeting()}! I'm your BIPSS health assistant. Ask me about your heart rate, " +
                "risk scores, or anything related to your current health readings.",
            sender  = Sender.AI
        )
    ),
    val inputText:     String  = "",
    val isAiTyping:    Boolean = false,
    val waterLoggedAt: Long?   = null,
    /** False → replies come from the deterministic TemplatedResponder. */
    val aiModelReady:  Boolean = false,
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
    private val waterRepo:     com.bipss.app.data.WaterRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatDetailsUiState())
    val uiState: StateFlow<ChatDetailsUiState> = _uiState.asStateFlow()

    init {
        _uiState.update { it.copy(aiModelReady = chatbotEngine.isModelAvailable) }
        observeSensors()
        refresh()
    }

    private fun observeSensors() {
        viewModelScope.launch {
            sensorRepo.sensorEvents.collect { reading ->
                _uiState.update {
                    it.copy(
                        heartRate = reading.heartRate ?: it.heartRate,
                        spO2      = reading.spO2      ?: it.spO2,
                        skinTempC = reading.skinTempC ?: it.skinTempC
                    )
                }
            }
        }
    }

    /** Rebuilds the briefing and the chat context from current stored state. */
    fun refresh() {
        viewModelScope.launch {
            val env = envRepo.getCachedSnapshot() ?: EnvironmentalSnapshot.EMPTY
            val s = _uiState.value

            val profile = prefsRepo.userProfile.first()
            val greeting = com.bipss.app.ai.GreetingHelper.getGreeting(profile?.name)

            val baseStats = sensorRepo.baselineStatsDao.getByType(MetricTypes.HR)
            val scores = calculator.computeAll(
                hr              = s.heartRate,
                spo2            = s.spO2,
                skinTempC       = s.skinTempC,
                hrvRmssd        = null,
                baselineHR      = baseStats?.mean,
                baselineHRStd   = baseStats?.stdDev,
                baselineDays    = prefsRepo.daysOfData(),
                env             = env.takeIf { it.fetchedAtMs > 0L },
                hoursSinceWater = waterRepo.hoursSinceLastLog(),
                sleepScore      = sleepRepo.lastNightScore(),
                recoveryScore   = null,
                strainScore     = null,
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
                sleep      = sleepRepo.lastNight(),
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
                    listOf(firstMessage.copy(content = "$greeting! I'm your BIPSS health assistant. Ask me about your heart rate, risk scores, or anything related to your current health readings."))
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
    }

    fun onInputChange(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isBlank()) return

        val userMsg = ChatMessage(content = text, sender = Sender.USER)
        _uiState.update { it.copy(messages = it.messages + userMsg, inputText = "", isAiTyping = true) }

        viewModelScope.launch {
            val response = generateResponse(text, _uiState.value)
            val aiMsg = ChatMessage(content = response, sender = Sender.AI)
            _uiState.update { it.copy(messages = it.messages + aiMsg, isAiTyping = false) }
        }
    }

    private suspend fun generateResponse(question: String, s: ChatDetailsUiState): String {
        // null is emitted as JSON null, never as a stand-in number: the model
        // must be able to tell "no reading" from "a reading of zero".
        val contextJson = buildString {
            append("{\"heartRate\":${s.heartRate},\"spO2\":${s.spO2},")
            append("\"skinTempC\":${s.skinTempC},")
            append("\"ambientTempC\":${s.env.ambientTempC},")
            append("\"aqiValue\":${s.env.aqiValue},")
            append("\"scores\":[")
            s.riskScores.filter { it.hasNumber }.forEachIndexed { i, sc ->
                if (i > 0) append(",")
                append("{\"type\":\"${sc.type.displayName}\",\"score\":${sc.score},\"level\":\"${sc.level}\"}")
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
            append("1. You are NOT a doctor. Never diagnose conditions or prescribe treatment.\n")
            append("2. A null value in the context means the sensor is offline or data is unavailable. ")
            append("Say 'no data available' — NEVER guess or estimate a number.\n")
            append("3. Only reference values that appear in the context JSON below. Do not invent readings.\n")
            append("4. If a risk score says HIGH, do not reassure the user that everything is fine.\n")
            append("5. For medical symptoms or concerns, recommend consulting a healthcare professional.\n")
            append("6. Keep responses concise and use plain language.\n\n")
            append("Context: $contextJson\n")
            append("User: $question\n")
            append("Assistant:")
        }

        return if (chatbotEngine.isModelAvailable) {
            when (val result = chatbotEngine.generateResponse(prompt)) {
                is ChatbotResult.Success -> validateAiOutput(result.text, s)
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
    private fun validateAiOutput(text: String, s: ChatDetailsUiState): String {
        var validatedText = text
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
                val metrics = hallucinations.joinToString(" and ")
                validatedText += "\n\n(System Correction: The wearable is not currently providing data for $metrics. Any numbers mentioned above for these metrics are incorrect.)"
            }
        }

        return validatedText
    }

    private fun templateFallback(q: String, s: ChatDetailsUiState) =
        TemplatedResponder.respond(q, s.riskScores, s.heartRate, s.spO2, s.skinTempC)

    fun logWater(amountMl: Int) {
        viewModelScope.launch {
            waterRepo.log(amountMl)
            _uiState.update { it.copy(waterLoggedAt = System.currentTimeMillis()) }
            val msg = ChatMessage(
                content = "${amountMl}mL of water logged. Staying hydrated helps lower your Heat Stress risk score.",
                sender  = Sender.AI
            )
            _uiState.update { it.copy(messages = it.messages + msg) }
            refresh()
        }
    }
}
