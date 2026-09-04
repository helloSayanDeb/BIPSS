package com.bipss.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bipss.app.ai.ChatbotEngine
import com.bipss.app.ai.ChatbotResult
import com.bipss.app.ai.TemplatedResponder
import com.bipss.app.data.UserPreferencesRepository
import com.bipss.app.domain.ChatMessage
import com.bipss.app.domain.RiskScore
import com.bipss.app.domain.Sender
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ChatDetailsUiState(
    val messages:      List<ChatMessage> = listOf(
        ChatMessage(
            content = "Hello! I'm your BIPSS health assistant. Ask me about your heart rate, " +
                      "risk scores, or anything related to your current health readings.",
            sender  = Sender.AI
        )
    ),
    val inputText:     String  = "",
    val isAiTyping:    Boolean = false,
    val waterLoggedAt: Long?   = null,
    /** False → replies come from the deterministic TemplatedResponder. */
    val aiModelReady:  Boolean = false
)

class ChatDetailsViewModel(
    private val chatbotEngine:  ChatbotEngine,
    private val dashboardVm:    DashboardViewModel,
    private val prefsRepo:      UserPreferencesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatDetailsUiState())
    val uiState: StateFlow<ChatDetailsUiState> = _uiState.asStateFlow()

    init {
        _uiState.update { it.copy(aiModelReady = chatbotEngine.isModelAvailable) }
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
            val dashState  = dashboardVm.uiState.value
            val response   = generateResponse(text, dashState.riskScores, dashState)
            val aiMsg      = ChatMessage(content = response, sender = Sender.AI)
            _uiState.update { it.copy(messages = it.messages + aiMsg, isAiTyping = false) }
        }
    }

    private suspend fun generateResponse(
        question:   String,
        riskScores: List<RiskScore>,
        dashState:  DashboardUiState
    ): String {
        // Build structured JSON context for the LLM
        val contextJson = buildString {
            append("{\"heartRate\":${dashState.heartRate},\"spO2\":${dashState.spO2},")
            append("\"skinTempC\":${dashState.skinTempC},")
            append("\"ambientTempC\":${dashState.env.ambientTempC},")
            append("\"aqiValue\":${dashState.env.aqiValue},")
            append("\"scores\":[")
            riskScores.forEachIndexed { i, s ->
                append("{\"type\":\"${s.type.displayName}\",\"score\":${s.score},\"level\":\"${s.level}\"}")
                if (i < riskScores.size - 1) append(",")
            }
            append("]}")
        }
        val prompt = "Context: $contextJson\nUser: $question\nAssistant:"

        return if (chatbotEngine.isModelAvailable) {
            when (val result = chatbotEngine.generateResponse(prompt)) {
                is ChatbotResult.Success -> result.text
                is ChatbotResult.Failure -> templateFallback(question, riskScores, dashState)
            }
        } else {
            templateFallback(question, riskScores, dashState)
        }
    }

    private fun templateFallback(q: String, scores: List<RiskScore>, d: DashboardUiState) =
        TemplatedResponder.respond(q, scores, d.heartRate, d.spO2, d.skinTempC)

    fun logWater() {
        viewModelScope.launch {
            prefsRepo.logWaterIntake()
            _uiState.update { it.copy(waterLoggedAt = System.currentTimeMillis()) }
            val msg = ChatMessage(
                content = "Water intake logged. Staying hydrated helps lower your Heat Stress risk score.",
                sender  = Sender.AI
            )
            _uiState.update { it.copy(messages = it.messages + msg) }
        }
    }
}
