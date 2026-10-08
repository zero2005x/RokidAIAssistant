package io.github.zero2005x.glassesaicompanion.service.stt

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.core.app.ApplicationProvider
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class SttCredentialsRepositoryTest {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var builder: MasterKey.Builder

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Replace only the keystore boundary; exercise real preference persistence.
        prefs = context.getSharedPreferences("test_encrypted_stt", 0)
        prefs.edit().clear().commit()
        builder = mockk()
        val key = mockk<MasterKey>()
        mockkConstructor(MasterKey.Builder::class)
        every { anyConstructed<MasterKey.Builder>().setKeyScheme(any()) } returns builder
        every { builder.build() } returns key
        mockkStatic(EncryptedSharedPreferences::class)
        every { EncryptedSharedPreferences.create(context, any<String>(), key, any(), any()) } returns prefs
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `save reload switch and clear preserve expected credentials`() {
        val repository = SttCredentialsRepository(context)
        assertEquals(SttCredentials(), repository.getCredentials())
        val credentials = SttCredentials(
            selectedProvider = SttProvider.GOOGLE_CLOUD_STT.name,
            gcpProjectId = "test-project", gcpApiKey = "test-key",
            gcpUseServiceAccount = true, gcpServiceAccountJson = "test-json",
            deepgramApiKey = "test-deepgram", awsSessionToken = "test-session"
        )
        repository.saveCredentials(credentials)
        assertEquals(credentials, repository.credentialsFlow.value)
        assertEquals(credentials, SttCredentialsRepository(context).getCredentials())
        repository.setSelectedProvider(SttProvider.DEEPGRAM)
        assertEquals(credentials.copy(selectedProvider = SttProvider.DEEPGRAM.name),
            SttCredentialsRepository(context).getCredentials())
        repository.clearAll()
        assertEquals(SttCredentials(), repository.credentialsFlow.value)
        assertEquals(SttCredentials(), SttCredentialsRepository(context).getCredentials())
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun `failed save does not publish credentials that were not persisted`() {
        val failingPrefs = mockk<SharedPreferences>()
        every { failingPrefs.getString(any(), any()) } answers { secondArg() }
        every { failingPrefs.getBoolean(any(), any()) } answers { secondArg() }
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { failingPrefs.edit() } returns editor
        every { editor.commit() } returns false
        every { EncryptedSharedPreferences.create(context, any<String>(), any<MasterKey>(), any(), any()) } returns failingPrefs
        val repository = SttCredentialsRepository(context)
        assertThrows(IllegalStateException::class.java) {
            repository.saveCredentials(SttCredentials(deepgramApiKey = "not-saved"))
        }
        assertEquals(SttCredentials(), repository.credentialsFlow.value)
    }

    @Test
    fun `failed clear retains current observable credentials`() {
        val repository = SttCredentialsRepository(context)
        val credentials = SttCredentials(deepgramApiKey = "keep-me")
        repository.saveCredentials(credentials)
        val spyPrefs = spyk(prefs)
        val editor = mockk<SharedPreferences.Editor>()
        every { spyPrefs.edit() } returns editor
        every { editor.clear() } returns editor
        every { editor.commit() } returns false
        every { EncryptedSharedPreferences.create(context, any<String>(), any<MasterKey>(), any(), any()) } returns spyPrefs
        val reloaded = SttCredentialsRepository(context)
        assertThrows(IllegalStateException::class.java) { reloaded.clearAll() }
        assertEquals(credentials, reloaded.credentialsFlow.value)
        assertEquals("keep-me", prefs.getString("deepgram_api_key", null))
    }

    @Test
    fun `keystore failure is explicit and does not create plaintext credentials`() {
        val failure = IllegalStateException("keystore unavailable")
        every { builder.build() } throws failure
        val thrown = assertThrows(IllegalStateException::class.java) { SttCredentialsRepository(context) }
        assertSame(failure, thrown.cause)
        verify(exactly = 0) { EncryptedSharedPreferences.create(context, any<String>(), any<MasterKey>(), any(), any()) }
        assertTrue(context.getSharedPreferences("rokid_stt_credentials", 0).all.isEmpty())
    }
}
