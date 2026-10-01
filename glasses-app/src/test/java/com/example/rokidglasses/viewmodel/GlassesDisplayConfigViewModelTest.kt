package com.example.rokidglasses.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.rokidcommon.protocol.GlassesDisplayConfig
import com.example.rokidcommon.protocol.GlassesDisplayMetrics
import com.example.rokidcommon.protocol.GlassesFont
import com.example.rokidcommon.protocol.Message
import com.example.rokidcommon.protocol.MessageType
import com.example.rokidglasses.R
import com.example.rokidglasses.sdk.CxrServiceManager
import com.example.rokidglasses.sdk.UnifiedCameraManager
import com.example.rokidglasses.service.BluetoothClientState
import com.example.rokidglasses.service.BluetoothSppClient
import com.example.rokidglasses.testutil.returnsSuccess
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The display configuration the phone pushes to the glasses, the measured text layout the
 * renderer reports back, the display metrics sent to the phone, and the fallback
 * pagination used before the first layout pass.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GlassesDisplayConfigViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val fromPhone = MutableSharedFlow<Message>(extraBufferCapacity = 32)
    private val bluetoothState = MutableStateFlow(BluetoothClientState.DISCONNECTED)
    private val deviceName = MutableStateFlow<String?>(null)
    private lateinit var context: Context

    private fun string(id: Int) = context.getString(id)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        mockkObject(CxrServiceManager.Companion)
        every { CxrServiceManager.isSdkAvailable() } returns false
        mockkConstructor(BluetoothSppClient::class, UnifiedCameraManager::class)
        every { anyConstructed<BluetoothSppClient>().messageFlow } returns fromPhone
        every { anyConstructed<BluetoothSppClient>().connectionState } returns bluetoothState
        every { anyConstructed<BluetoothSppClient>().connectedDeviceName } returns deviceName
        every { anyConstructed<BluetoothSppClient>().getPairedDevices() } returns emptyList()
        coEvery { anyConstructed<BluetoothSppClient>().sendMessage(any()) } returns true
        coEvery { anyConstructed<UnifiedCameraManager>().initialize() } returnsSuccess Unit
        every { anyConstructed<UnifiedCameraManager>().getCameraTypeName() } returns "Camera2 API"
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun newModel() = GlassesViewModel(context)

    private suspend fun TestScope.receive(message: Message) {
        advanceUntilIdle()
        fromPhone.emit(message)
        advanceUntilIdle()
    }

    private val sentence = "The quick brown fox jumps over the lazy dog. "

    // ==================== Stored configuration ====================

    @Test
    fun `a saved display configuration is restored at startup`() = scope.runTest {
        val saved = GlassesDisplayConfig(fontSizeSp = 30, font = GlassesFont.MONOSPACE,
            widthPercent = 60, heightPercent = 50, leftPercent = 20, topPercent = 30)
        context.getSharedPreferences("glasses_display", Context.MODE_PRIVATE)
            .edit().putString("config", saved.toJson()).commit()

        val model = newModel()

        assertThat(model.uiState.value.displayConfig).isEqualTo(saved)
    }

    @Test
    fun `a new configuration from the phone is applied, saved and re-paginates the answer`() = scope.runTest {
        val model = newModel()
        receive(Message(type = MessageType.AI_RESPONSE_TEXT, payload = sentence.repeat(10)))
        model.nextPage()
        val pagesBefore = model.uiState.value.totalPages
        assertThat(model.uiState.value.currentPage).isEqualTo(1)

        val larger = GlassesDisplayConfig(fontSizeSp = 36)
        receive(Message(type = MessageType.SYSTEM_CONFIG, payload = larger.toJson()))

        val state = model.uiState.value
        assertThat(state.displayConfig).isEqualTo(larger.normalized())
        // A bigger font fits less text per page, and reading restarts from the first page.
        assertThat(state.totalPages).isGreaterThan(pagesBefore)
        assertThat(state.currentPage).isEqualTo(0)
        assertThat(state.isPaginated).isTrue()
        val stored = context.getSharedPreferences("glasses_display", Context.MODE_PRIVATE)
            .getString("config", null)
        assertThat(GlassesDisplayConfig.fromJson(stored)).isEqualTo(larger.normalized())
    }

    @Test
    fun `a configuration change leaves status messages and unreadable payloads alone`() = scope.runTest {
        val model = newModel()
        advanceUntilIdle()
        val original = model.uiState.value.displayConfig
        val greeting = model.uiState.value.displayText

        // Nothing has been answered yet, so there is no response to re-paginate.
        receive(Message(type = MessageType.SYSTEM_CONFIG, payload = GlassesDisplayConfig(fontSizeSp = 30).toJson()))
        assertThat(model.uiState.value.displayConfig.fontSizeSp).isEqualTo(30)
        assertThat(model.uiState.value.displayText).isEqualTo(greeting)

        // While a new question is being processed the on-screen text is a status, not an answer.
        receive(Message(type = MessageType.AI_RESPONSE_TEXT, payload = sentence.repeat(10)))
        receive(Message(type = MessageType.AI_PROCESSING, payload = "Thinking"))
        receive(Message(type = MessageType.SYSTEM_CONFIG, payload = GlassesDisplayConfig(fontSizeSp = 34).toJson()))
        assertThat(model.uiState.value.displayConfig.fontSizeSp).isEqualTo(34)
        assertThat(model.uiState.value.displayText).isEqualTo("Thinking")

        // A payload that is not a configuration changes nothing.
        receive(Message(type = MessageType.SYSTEM_CONFIG, payload = "garbage"))
        receive(Message(type = MessageType.SYSTEM_CONFIG, payload = null))
        assertThat(model.uiState.value.displayConfig.fontSizeSp).isEqualTo(34)
        assertThat(original.fontSizeSp).isNotEqualTo(34)
    }

    // ==================== Measured layout ====================

    @Test
    fun `a measured layout re-flows the answer and keeps the reading position`() = scope.runTest {
        val model = newModel()
        // Three-digit numbers: no two stretches of the answer are alike, so a page can be located.
        val answer = (0 until 100).joinToString("") { "%03d".format(it) }
        receive(Message(type = MessageType.AI_RESPONSE_TEXT, payload = answer))
        model.nextPage()
        val readSoFar = answer.indexOf(model.uiState.value.displayText)
        assertThat(readSoFar).isEqualTo(120)

        model.updateTextLayout { text -> text.chunked(50) }

        val state = model.uiState.value
        assertThat(state.totalPages).isEqualTo(6)
        // The page that contains where the reader had got to.
        assertThat(state.currentPage).isEqualTo(readSoFar / 50)
        assertThat(state.displayText).isEqualTo(answer.chunked(50)[state.currentPage])
        assertThat(state.hintText).isEqualTo(string(R.string.swipe_for_more))

        // Later answers use the measured layout straight away.
        receive(Message(type = MessageType.AI_RESPONSE_TEXT, payload = "x".repeat(120)))
        assertThat(model.uiState.value.totalPages).isEqualTo(3)
    }

    @Test
    fun `a layout that fits the whole answer on one page ends pagination`() = scope.runTest {
        val model = newModel()
        receive(Message(type = MessageType.AI_RESPONSE_TEXT, payload = sentence.repeat(10)))
        assertThat(model.uiState.value.isPaginated).isTrue()

        model.updateTextLayout { text -> listOf(text) }

        assertThat(model.uiState.value.isPaginated).isFalse()
        assertThat(model.uiState.value.totalPages).isEqualTo(1)
        assertThat(model.uiState.value.hintText).isEqualTo(string(R.string.tap_continue))
    }

    @Test
    fun `a layout report is ignored unless an answer is on screen`() = scope.runTest {
        val model = newModel()
        val initial = model.uiState.value

        // No answer yet.
        model.updateTextLayout { text -> listOf(text) }
        assertThat(model.uiState.value).isEqualTo(initial)
        // A configuration message drops the measured layout again.
        receive(Message(type = MessageType.SYSTEM_CONFIG, payload = GlassesDisplayConfig().toJson()))

        // The answer was dismissed, so there is nothing left to re-flow.
        receive(Message(type = MessageType.AI_RESPONSE_TEXT, payload = sentence.repeat(10)))
        model.dismissPagination()
        val dismissed = model.uiState.value
        model.updateTextLayout { text -> text.chunked(10) }
        assertThat(model.uiState.value).isEqualTo(dismissed)

        // Something else replaced the answer on screen (here, what the user just said).
        receive(Message(type = MessageType.AI_RESPONSE_TEXT, payload = sentence.repeat(10)))
        receive(Message(type = MessageType.USER_TRANSCRIPT, payload = "next question"))
        val replaced = model.uiState.value
        assertThat(replaced.displayText).isNotEqualTo(sentence.repeat(10))
        model.updateTextLayout { text -> text.chunked(10) }
        assertThat(model.uiState.value).isEqualTo(replaced)

        // A new question is being processed.
        receive(Message(type = MessageType.AI_RESPONSE_TEXT, payload = sentence.repeat(10)))
        receive(Message(type = MessageType.AI_PROCESSING, payload = "Thinking"))
        val processing = model.uiState.value
        model.updateTextLayout { text -> text.chunked(10) }
        assertThat(model.uiState.value).isEqualTo(processing)
    }

    // ==================== Display metrics ====================

    @Test
    fun `display metrics are sent to the phone when connected and when it asks`() = scope.runTest {
        val model = newModel()
        val metrics = GlassesDisplayMetrics(480, 640, 1.5f, 1.2f)
        advanceUntilIdle()

        // Not known yet: a request from the phone has nothing to answer with.
        bluetoothState.value = BluetoothClientState.CONNECTED
        advanceUntilIdle()
        receive(Message(type = MessageType.DISPLAY_METRICS))
        coVerify(exactly = 0) {
            anyConstructed<BluetoothSppClient>().sendMessage(match { it.type == MessageType.DISPLAY_METRICS })
        }

        // Known and connected: sent straight away, and again whenever the phone asks.
        model.updateDisplayMetrics(metrics)
        advanceUntilIdle()
        receive(Message(type = MessageType.DISPLAY_METRICS))
        coVerify(exactly = 2) {
            anyConstructed<BluetoothSppClient>().sendMessage(
                match { it.type == MessageType.DISPLAY_METRICS && it.payload == metrics.toJson() }
            )
        }
    }

    @Test
    fun `display metrics wait for a connection and are sent once it is made`() = scope.runTest {
        val model = newModel()
        advanceUntilIdle()

        model.updateDisplayMetrics(GlassesDisplayMetrics(480, 640, 1f, 1f))
        advanceUntilIdle()
        coVerify(exactly = 0) {
            anyConstructed<BluetoothSppClient>().sendMessage(match { it.type == MessageType.DISPLAY_METRICS })
        }

        bluetoothState.value = BluetoothClientState.CONNECTED
        advanceUntilIdle()
        coVerify(exactly = 1) {
            anyConstructed<BluetoothSppClient>().sendMessage(match { it.type == MessageType.DISPLAY_METRICS })
        }
    }

    // ==================== Fallback pagination ====================

    private suspend fun TestScope.pagesOf(model: GlassesViewModel, text: String): List<String> {
        receive(Message(type = MessageType.AI_RESPONSE_TEXT, payload = text))
        val pages = mutableListOf(model.uiState.value.displayText)
        repeat(model.uiState.value.totalPages - 1) {
            model.nextPage()
            pages += model.uiState.value.displayText
        }
        return pages
    }

    @Test
    fun `an empty answer is one blank page`() = scope.runTest {
        val model = newModel()

        val pages = pagesOf(model, "")

        assertThat(pages).containsExactly("")
        assertThat(model.uiState.value.isPaginated).isFalse()
    }

    @Test
    fun `a line break budget keeps lists readable`() = scope.runTest {
        val model = newModel()
        val list = "line\n".repeat(10)

        val pages = pagesOf(model, list)

        // Three explicit lines per page, nothing dropped.
        assertThat(pages.first()).isEqualTo("line\nline\nline\n")
        assertThat(pages).hasSize(4)
        assertThat(pages.joinToString("")).isEqualTo(list)
    }

    @Test
    fun `a page never ends in the middle of an emoji`() = scope.runTest {
        val model = newModel()
        val text = "A".repeat(119) + "😀" + "B".repeat(100)

        val pages = pagesOf(model, text)

        assertThat(pages.first().last().isHighSurrogate()).isFalse()
        assertThat(pages.joinToString("")).isEqualTo(text)
    }
}
