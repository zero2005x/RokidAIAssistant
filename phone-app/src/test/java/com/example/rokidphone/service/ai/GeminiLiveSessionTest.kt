package com.example.rokidphone.service.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for GeminiLiveSession.
 * Tests state guard clauses and pure-logic helpers that can be exercised
 * without an active WebSocket/audio connection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GeminiLiveSessionTest {

    private lateinit var session: GeminiLiveSession

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        session = GeminiLiveSession(
            context = context,
            apiKey = "test-key",
            modelId = "test-model",
            systemPrompt = "Be helpful"
        )
    }

    // ==================== Initial State ====================

    @Test
    fun `initial sessionState is IDLE`() {
        assertThat(session.sessionState.value)
            .isEqualTo(GeminiLiveSession.SessionState.IDLE)
    }

    @Test
    fun `initial errorMessage is null`() {
        assertThat(session.errorMessage.value).isNull()
    }

    // ==================== Guard clauses when state is IDLE ====================

    @Test
    fun `stop when IDLE does not change sessionState`() {
        // stop() logs a warning and returns early when IDLE
        session.stop()

        assertThat(session.sessionState.value)
            .isEqualTo(GeminiLiveSession.SessionState.IDLE)
    }

    @Test
    fun `pause when not ACTIVE does not change sessionState`() {
        // Must be ACTIVE to pause, IDLE fails the guard
        session.pause()

        assertThat(session.sessionState.value)
            .isEqualTo(GeminiLiveSession.SessionState.IDLE)
    }

    @Test
    fun `resume when not PAUSED does not change sessionState`() {
        // Must be PAUSED to resume, IDLE fails the guard
        session.resume()

        assertThat(session.sessionState.value)
            .isEqualTo(GeminiLiveSession.SessionState.IDLE)
    }

    @Test
    fun `sendVideoFrame when not ACTIVE is silently ignored`() {
        // No exception should be thrown
        session.sendVideoFrame(ByteArray(100))

        assertThat(session.sessionState.value)
            .isEqualTo(GeminiLiveSession.SessionState.IDLE)
    }

    // ==================== getDefaultToolDeclarations ====================

    @Test
    fun `getDefaultToolDeclarations returns four declarations`() {
        val declarations: List<JSONObject> = session.getDefaultToolDeclarations()
        assertThat(declarations).hasSize(4)
    }

    @Test
    fun `getDefaultToolDeclarations contains function declarations`() {
        val declarations = session.getDefaultToolDeclarations()
        for (decl in declarations) {
            assertThat(decl.has("function_declarations")).isTrue()
        }
    }

    // ==================== VadConfig ====================

    @Test
    fun `VadConfig default values match expected constants`() {
        val vadConfig = GeminiLiveSession.VadConfig()
        assertThat(vadConfig.startSensitivity)
            .isEqualTo(GeminiLiveService.StartOfSpeechSensitivity.START_SENSITIVITY_HIGH)
        assertThat(vadConfig.endSensitivity)
            .isEqualTo(GeminiLiveService.EndOfSpeechSensitivity.END_SENSITIVITY_LOW)
        assertThat(vadConfig.silenceDurationMs).isEqualTo(500)
        assertThat(vadConfig.activityHandling)
            .isEqualTo(GeminiLiveService.ActivityHandling.START_OF_ACTIVITY_INTERRUPTS)
    }

    // ==================== getToolCallRouter before start ====================

    @Test
    fun `getToolCallRouter returns null before session starts`() {
        assertThat(session.getToolCallRouter()).isNull()
    }
}
