package com.example.rokidphone.service.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for SystemToolsHandler.
 *
 * Uses Robolectric so Android Context / ContentResolver are available.
 * Permissions are NOT granted by default → all permission-gated paths return failure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SystemToolsHandlerTest {

    private lateinit var context: Context
    private lateinit var handler: SystemToolsHandler

    @Before
    fun setUp() {
        // Replace the real Main dispatcher with an unconfined test dispatcher so
        // withContext(Dispatchers.Main) inside handleMakeCall doesn't deadlock.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        handler = SystemToolsHandler(context)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ==================== handleCheckSchedule ====================

    @Test
    fun `handleCheckSchedule - returns failure when READ_CALENDAR permission not granted`() = runTest {
        val call = GeminiFunctionCall(id = "sch-1", name = "check_schedule", args = JSONObject())

        val result = handler.handleCheckSchedule(call)

        assertThat(result.success).isFalse()
        assertThat(result.toolCallId).isEqualTo("sch-1")
        assertThat(result.errorMessage).contains("permission")
    }

    @Test
    fun `handleCheckSchedule - failure result toolCallId matches call id`() = runTest {
        val call = GeminiFunctionCall(id = "my-sched-id", name = "check_schedule", args = JSONObject())

        val result = handler.handleCheckSchedule(call)

        assertThat(result.toolCallId).isEqualTo("my-sched-id")
    }

    // ==================== handleMakeCall — no valid number ====================

    @Test
    fun `handleMakeCall - empty args returns failure with no-phone message`() = runTest {
        val call = GeminiFunctionCall(id = "call-1", name = "make_call", args = JSONObject())

        val result = handler.handleMakeCall(call)

        assertThat(result.success).isFalse()
        assertThat(result.errorMessage).contains("No valid phone number")
    }

    @Test
    fun `handleMakeCall - blank phone_number returns failure`() = runTest {
        val args = JSONObject().apply { put("phone_number", "   ") }
        val call = GeminiFunctionCall(id = "call-2", name = "make_call", args = args)

        val result = handler.handleMakeCall(call)

        assertThat(result.success).isFalse()
        assertThat(result.errorMessage).contains("No valid phone number")
    }

    @Test
    fun `handleMakeCall - blank contact_name and no phone returns failure`() = runTest {
        val args = JSONObject().apply {
            put("contact_name", "")
            put("phone_number", "")
        }
        val call = GeminiFunctionCall(id = "call-3", name = "make_call", args = args)

        val result = handler.handleMakeCall(call)

        assertThat(result.success).isFalse()
    }

    @Test
    fun `handleMakeCall - failure toolCallId matches call id`() = runTest {
        val call = GeminiFunctionCall(id = "no-phone-id", name = "make_call", args = JSONObject())

        val result = handler.handleMakeCall(call)

        assertThat(result.toolCallId).isEqualTo("no-phone-id")
    }

    // ==================== handleMakeCall — contact_name without READ_CONTACTS permission ====================

    @Test
    fun `handleMakeCall - contact_name without READ_CONTACTS permission returns failure`() = runTest {
        val args = JSONObject().apply { put("contact_name", "Alice") }
        val call = GeminiFunctionCall(id = "call-c", name = "make_call", args = args)

        // READ_CONTACTS not granted → resolvePhoneNumberByContactName returns null
        val result = handler.handleMakeCall(call)

        assertThat(result.success).isFalse()
        assertThat(result.errorMessage).contains("No valid phone number")
    }

    @Test
    fun `handleMakeCall - contactName alternative key without permission returns failure`() = runTest {
        val args = JSONObject().apply { put("contactName", "Bob") }
        val call = GeminiFunctionCall(id = "call-cn", name = "make_call", args = args)

        val result = handler.handleMakeCall(call)

        assertThat(result.success).isFalse()
    }

    // ==================== handleMakeCall — with a phone number (dial path) ====================

    @Test
    fun `handleMakeCall - valid phone_number opens dialer successfully`() = runTest {
        val args = JSONObject().apply { put("phone_number", "0912345678") }
        val call = GeminiFunctionCall(id = "call-ok", name = "make_call", args = args)

        // Robolectric shadows startActivity → no UI is shown, call returns success
        val result = handler.handleMakeCall(call)

        assertThat(result.success).isTrue()
        assertThat(result.result.getString("phone_number")).isEqualTo("0912345678")
        assertThat(result.result.getString("action")).isEqualTo("ACTION_DIAL")
    }

    @Test
    fun `handleMakeCall - phone number with spaces and dashes is sanitized`() = runTest {
        val args = JSONObject().apply { put("phone_number", "09 28-123-456") }
        val call = GeminiFunctionCall(id = "call-san", name = "make_call", args = args)

        val result = handler.handleMakeCall(call)

        assertThat(result.success).isTrue()
        // Spaces and dashes removed
        assertThat(result.result.getString("phone_number")).isEqualTo("0928123456")
    }

    @Test
    fun `handleMakeCall - alternative arg phoneNumber is accepted`() = runTest {
        val args = JSONObject().apply { put("phoneNumber", "0987654321") }
        val call = GeminiFunctionCall(id = "call-alt", name = "make_call", args = args)

        val result = handler.handleMakeCall(call)

        assertThat(result.success).isTrue()
        assertThat(result.result.getString("phone_number")).isEqualTo("0987654321")
    }

    @Test
    fun `handleMakeCall - success result includes message and action fields`() = runTest {
        val args = JSONObject().apply { put("phone_number", "1234567890") }
        val call = GeminiFunctionCall(id = "call-msg", name = "make_call", args = args)

        val result = handler.handleMakeCall(call)

        assertThat(result.success).isTrue()
        assertThat(result.result.getString("message")).contains("Dialer")
        assertThat(result.result.getString("action")).isEqualTo("ACTION_DIAL")
    }

    @Test
    fun `handleMakeCall - contact_name is included in success result when provided with phone_number`() = runTest {
        val args = JSONObject().apply {
            put("phone_number", "0912000000")
            put("contact_name", "TestUser")
        }
        val call = GeminiFunctionCall(id = "call-named", name = "make_call", args = args)

        val result = handler.handleMakeCall(call)

        assertThat(result.success).isTrue()
        assertThat(result.result.getString("contact_name")).isEqualTo("TestUser")
    }
}
