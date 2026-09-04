package com.bipss.app.ai

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "ChatbotEngine"

sealed class ChatbotResult {
    data class Success(val text: String) : ChatbotResult()
    data class Failure(val reason: String) : ChatbotResult()
}

/**
 * Wraps MediaPipe LlmInference for on-device AI chat.
 * Initialization is deferred until the model file is available (post-download).
 */
class ChatbotEngine(
    private val context:   Context,
    private val prefsRepo: com.bipss.app.data.UserPreferencesRepository
) {
    private var llmInference: LlmInference? = null

    /** True if the engine is initialized and ready to generate responses */
    val isModelAvailable: Boolean get() = llmInference != null

    /**
     * Initializes LlmInference from the given model file path.
     * Fails gracefully and persists the failure flag so UI can fall back to TemplatedResponder.
     */
    suspend fun initialize(modelPath: String) = withContext(Dispatchers.IO) {
        try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(1024)
                .setTemperature(0.7f)
                .build()
            llmInference = LlmInference.createFromOptions(context, options)
            prefsRepo.setChatbotEngineFailed(false)
            Log.i(TAG, "LlmInference initialized from $modelPath")
        } catch (e: Exception) {
            Log.e(TAG, "LlmInference init failed: ${e.message}")
            prefsRepo.setChatbotEngineFailed(true)
            llmInference = null
        }
    }

    /**
     * Generates a response for the given prompt.
     * Returns [ChatbotResult.Failure] if the engine is not initialized.
     */
    suspend fun generateResponse(prompt: String): ChatbotResult = withContext(Dispatchers.IO) {
        val engine = llmInference ?: return@withContext ChatbotResult.Failure("Model not loaded")
        try {
            val response = engine.generateResponse(prompt)
            ChatbotResult.Success(response?.trim() ?: "I couldn't generate a response.")
        } catch (e: Exception) {
            Log.e(TAG, "Inference error: ${e.message}")
            ChatbotResult.Failure(e.message ?: "Inference failed")
        }
    }
}
