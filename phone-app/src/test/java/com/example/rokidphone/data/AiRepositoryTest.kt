package com.example.rokidphone.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.example.rokidphone.R
import com.example.rokidphone.service.ai.AiServiceFactory
import com.example.rokidphone.service.ai.AiServiceProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class AiRepositoryTest {

    private lateinit var context: Context
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val aiService = mockk<AiServiceProvider>(relaxed = true)
    private var settings = ApiSettings(aiProvider = AiProvider.OPENAI, openaiApiKey = "k")
    private lateinit var repository: AiRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        mockkObject(AiServiceFactory)
        every { AiServiceFactory.createService(any()) } returns aiService
        every { settingsRepository.getSettings() } answers { settings }
        repository = AiRepository::class.java
            .getDeclaredConstructor(Context::class.java, SettingsRepository::class.java)
            .apply { isAccessible = true }
            .newInstance(context, settingsRepository)
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `each analysis mode sends its own prompt`() = runTest {
        val prompt = slot<String>()
        coEvery { aiService.analyzeImage(any(), capture(prompt)) } returns "answer"

        val expected = mapOf(
            AiRepository.AnalysisMode.DESCRIPTION to R.string.image_analysis_prompt,
            AiRepository.AnalysisMode.OCR to R.string.image_ocr_prompt,
            AiRepository.AnalysisMode.TRANSLATE to R.string.image_translate_prompt,
            AiRepository.AnalysisMode.SUMMARY to R.string.image_summary_prompt
        )
        for ((mode, resource) in expected) {
            repository.analyzeImage(byteArrayOf(1), mode)
            assertThat(prompt.captured).isEqualTo(context.getString(resource))
        }

        repository.analyzeImage(byteArrayOf(1), AiRepository.AnalysisMode.CUSTOM, "count the cats")
        assertThat(prompt.captured).isEqualTo("count the cats")
        // A CUSTOM request with no prompt falls back to the description prompt.
        repository.analyzeImage(byteArrayOf(1), AiRepository.AnalysisMode.CUSTOM, null)
        assertThat(prompt.captured).isEqualTo(context.getString(R.string.image_analysis_prompt))
    }

    @Test
    fun `a successful analysis returns the description`() = runTest {
        coEvery { aiService.analyzeImage(any(), any()) } returns "a red bicycle"

        val result = repository.analyzeImage(byteArrayOf(1, 2))

        assertThat(result).isInstanceOf(ImageAnalysisResult.Success::class.java)
        assertThat((result as ImageAnalysisResult.Success).description).isEqualTo("a red bicycle")
    }

    @Test
    fun `a provider failure is reported with its message and cause`() = runTest {
        val cause = IOException("upstream down")
        coEvery { aiService.analyzeImage(any(), any()) } throws cause

        val result = repository.analyzeImage(byteArrayOf(1)) as ImageAnalysisResult.Error

        assertThat(result.message).isEqualTo("upstream down")
        assertThat(result.exception).isSameInstanceAs(cause)
    }

    @Test
    fun `convenience wrappers map results onto Result`() = runTest {
        coEvery { aiService.analyzeImage(any(), any()) } returns "text"

        assertThat(repository.describeImage(byteArrayOf(1)).getOrNull()).isEqualTo("text")
        assertThat(repository.recognizeText(byteArrayOf(1)).getOrNull()).isEqualTo("text")
        assertThat(repository.translateImage(byteArrayOf(1)).getOrNull()).isEqualTo("text")

        coEvery { aiService.analyzeImage(any(), any()) } throws IOException("nope")
        assertThat(repository.describeImage(byteArrayOf(1)).exceptionOrNull())
            .hasMessageThat().isEqualTo("nope")
        assertThat(repository.recognizeText(byteArrayOf(1)).exceptionOrNull())
            .hasMessageThat().isEqualTo("nope")
        assertThat(repository.translateImage(byteArrayOf(1)).exceptionOrNull())
            .hasMessageThat().isEqualTo("nope")
    }

    @Test
    fun `base64 input is decoded before analysis and bad input is reported`() = runTest {
        mockkStatic(Base64::class)
        // Run the class initializer before recording: MockK replays an every {} block
        // and rejects it when the two rounds see different call counts.
        Base64.encodeToString(byteArrayOf(1), Base64.NO_WRAP)
        every { Base64.decode(any<String>(), any()) } returns byteArrayOf(7, 8)
        val sent = slot<ByteArray>()
        coEvery { aiService.analyzeImage(capture(sent), any()) } returns "ok"

        assertThat(repository.analyzeImageBase64("Zm9v")).isInstanceOf(ImageAnalysisResult.Success::class.java)
        assertThat(sent.captured).isEqualTo(byteArrayOf(7, 8))

        every { Base64.decode(any<String>(), any()) } throws IllegalArgumentException("bad base64")
        val failure = repository.analyzeImageBase64("!!!") as ImageAnalysisResult.Error
        assertThat(failure.message).isEqualTo(context.getString(R.string.image_decode_failed))
    }

    @Test
    fun `bitmap input is encoded as JPEG before analysis and failures are reported`() = runTest {
        val bitmap = mockk<Bitmap>(relaxed = true)
        every { bitmap.compress(Bitmap.CompressFormat.JPEG, any(), any()) } answers {
            thirdArg<java.io.OutputStream>().write(byteArrayOf(9)); true
        }
        val sent = slot<ByteArray>()
        coEvery { aiService.analyzeImage(capture(sent), any()) } returns "ok"

        assertThat(repository.analyzeImage(bitmap)).isInstanceOf(ImageAnalysisResult.Success::class.java)
        assertThat(sent.captured).isEqualTo(byteArrayOf(9))

        every { bitmap.compress(any(), any(), any()) } throws IllegalStateException("recycled")
        val failure = repository.analyzeImage(bitmap) as ImageAnalysisResult.Error
        assertThat(failure.message).isEqualTo(context.getString(R.string.image_convert_failed))
    }

    @Test
    fun `undecodable and already small images are sent unchanged`() = runTest {
        mockkStatic(BitmapFactory::class)
        val original = ByteArray(16) { it.toByte() }
        val sent = slot<ByteArray>()
        coEvery { aiService.analyzeImage(capture(sent), any()) } returns "ok"

        // Bounds decode fails: -1 dimensions must not be treated as "small enough".
        every { BitmapFactory.decodeByteArray(any(), any(), any(), any()) } answers {
            arg<BitmapFactory.Options>(3).outWidth = -1
            arg<BitmapFactory.Options>(3).outHeight = -1
            null
        }
        repository.analyzeImage(original)
        assertThat(sent.captured).isEqualTo(original)

        // Already within the limit.
        every { BitmapFactory.decodeByteArray(any(), any(), any(), any()) } answers {
            arg<BitmapFactory.Options>(3).outWidth = 800
            arg<BitmapFactory.Options>(3).outHeight = 600
            null
        }
        repository.analyzeImage(original)
        assertThat(sent.captured).isEqualTo(original)
    }

    @Test
    fun `oversized images are downsampled by a power of two and recompressed`() = runTest {
        mockkStatic(BitmapFactory::class)
        val scaled = mockk<Bitmap>(relaxed = true)
        every { scaled.compress(Bitmap.CompressFormat.JPEG, 85, any()) } answers {
            thirdArg<java.io.OutputStream>().write(byteArrayOf(5, 5)); true
        }
        val sampleSizes = mutableListOf<Int>()
        every { BitmapFactory.decodeByteArray(any(), any(), any(), any()) } answers {
            val options = arg<BitmapFactory.Options>(3)
            if (options.inJustDecodeBounds) {
                options.outWidth = 4000
                options.outHeight = 2000
                null
            } else {
                sampleSizes += options.inSampleSize
                scaled
            }
        }
        val sent = slot<ByteArray>()
        coEvery { aiService.analyzeImage(capture(sent), any()) } returns "ok"

        repository.analyzeImage(ByteArray(64))

        assertThat(sampleSizes).containsExactly(4) // 4000 / 4 <= 1024
        assertThat(sent.captured).isEqualTo(byteArrayOf(5, 5))
        io.mockk.verify { scaled.recycle() }
    }

    @Test
    fun `service availability and provider details come from the settings`() {
        settings = ApiSettings(aiProvider = AiProvider.OPENAI, openaiApiKey = "sk-123", aiModelId = "gpt-4")
        assertThat(repository.isServiceAvailable()).isTrue()
        assertThat(repository.getCurrentProviderName()).isEqualTo("OPENAI")
        assertThat(repository.getCurrentModelId()).isEqualTo("gpt-4")

        settings = ApiSettings(aiProvider = AiProvider.OPENAI, openaiApiKey = "  ")
        assertThat(repository.isServiceAvailable()).isFalse()
    }

    @Test
    fun `the service is rebuilt from the current settings on every analysis`() = runTest {
        coEvery { aiService.analyzeImage(any(), any()) } returns "ok"
        repository.analyzeImage(byteArrayOf(1))
        settings = settings.copy(aiProvider = AiProvider.GEMINI, geminiApiKey = "g")
        repository.analyzeImage(byteArrayOf(1))

        coVerify(exactly = 2) { aiService.analyzeImage(any(), any()) }
        io.mockk.verify(exactly = 2) { AiServiceFactory.createService(any()) }
    }
}
