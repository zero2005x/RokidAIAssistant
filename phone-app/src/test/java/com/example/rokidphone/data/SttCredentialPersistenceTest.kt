package com.example.rokidphone.data

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.example.rokidphone.service.stt.SttProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The per-provider speech-to-text credentials must reach storage and come back.
 *
 * They are edited on the settings screen as part of [ApiSettings] but were never
 * written to preferences, so every key was lost when the process died and the user
 * had to retype it.
 *
 * The Android keystore is unavailable under Robolectric, so [SettingsRepository]
 * falls back to a per-instance in-memory store and a second repository cannot see
 * the first one's writes. These tests therefore drive both halves against the
 * repository's own preferences object: saveSettings must write every key, and
 * loadSettings must read every key back from it.
 */
@RunWith(RobolectricTestRunner::class)
class SttCredentialPersistenceTest {

    private lateinit var context: Context
    private lateinit var repository: SettingsRepository

    /** The preferences the repository actually writes to, encrypted or in-memory. */
    private val prefs: SharedPreferences
        get() = SettingsRepository::class.java.getDeclaredField("prefs")
            .apply { isAccessible = true }
            .get(repository) as SharedPreferences

    /** Re-reads settings from those preferences, which is what a restart does. */
    private fun reloadFromStorage(): ApiSettings =
        SettingsRepository::class.java.getDeclaredMethod("loadSettings")
            .apply { isAccessible = true }
            .invoke(repository) as ApiSettings

    /** Every speech-to-text credential, each with a value of its own. */
    private fun ApiSettings.withAllSttCredentials() = copy(
        sttProvider = SttProvider.DEEPGRAM,
        deepgramApiKey = "deepgram-key",
        assemblyaiApiKey = "assembly-key",
        gcpProjectId = "gcp-project",
        gcpApiKey = "gcp-key",
        gcpServiceAccountJson = """{"type":"service_account"}""",
        gcpUseServiceAccount = true,
        azureSpeechKey = "azure-key",
        azureSpeechRegion = "eastus",
        awsAccessKeyId = "aws-id",
        awsSecretAccessKey = "aws-secret",
        awsRegion = "eu-west-1",
        ibmApiKey = "ibm-key",
        ibmServiceUrl = "https://watson.example",
        iflytekAppId = "iflytek-app",
        iflytekApiKey = "iflytek-key",
        iflytekApiSecret = "iflytek-secret",
        huaweiAk = "huawei-ak",
        huaweiSk = "huawei-sk",
        huaweiRegion = "cn-south-1",
        huaweiProjectId = "huawei-project",
        volcengineAk = "volc-ak",
        volcangineSk = "volc-sk",
        volcengineAppId = "volc-app",
        aliyunAccessKeyId = "aliyun-id",
        aliyunAccessKeySecret = "aliyun-secret",
        aliyunAppKey = "aliyun-app",
        tencentSecretId = "tencent-id",
        tencentSecretKey = "tencent-secret",
        tencentAppId = "tencent-app",
        tencentEngineModelType = "16k_en",
        baiduAsrApiKey = "baidu-asr-key",
        baiduAsrSecretKey = "baidu-asr-secret",
        revaiAccessToken = "revai-token",
        speechmaticsApiKey = "speechmatics-key",
        otteraiApiKey = "otter-key"
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = SettingsRepository(context)
        repository.saveSettings(repository.getSettings().withAllSttCredentials())
    }

    @Test
    fun `every credential is written to storage under its own key`() {
        val stored = mapOf(
            "stt_deepgram_api_key" to "deepgram-key",
            "stt_assemblyai_api_key" to "assembly-key",
            "stt_gcp_project_id" to "gcp-project",
            "stt_gcp_api_key" to "gcp-key",
            "stt_gcp_service_account_json" to """{"type":"service_account"}""",
            "stt_azure_speech_key" to "azure-key",
            "stt_azure_speech_region" to "eastus",
            "stt_aws_access_key_id" to "aws-id",
            "stt_aws_secret_access_key" to "aws-secret",
            "stt_aws_region" to "eu-west-1",
            "stt_ibm_api_key" to "ibm-key",
            "stt_ibm_service_url" to "https://watson.example",
            "stt_iflytek_app_id" to "iflytek-app",
            "stt_iflytek_api_key" to "iflytek-key",
            "stt_iflytek_api_secret" to "iflytek-secret",
            "stt_huawei_ak" to "huawei-ak",
            "stt_huawei_sk" to "huawei-sk",
            "stt_huawei_region" to "cn-south-1",
            "stt_huawei_project_id" to "huawei-project",
            "stt_volcengine_ak" to "volc-ak",
            "stt_volcengine_sk" to "volc-sk",
            "stt_volcengine_app_id" to "volc-app",
            "stt_aliyun_access_key_id" to "aliyun-id",
            "stt_aliyun_access_key_secret" to "aliyun-secret",
            "stt_aliyun_app_key" to "aliyun-app",
            "stt_tencent_secret_id" to "tencent-id",
            "stt_tencent_secret_key" to "tencent-secret",
            "stt_tencent_app_id" to "tencent-app",
            "stt_tencent_engine_model_type" to "16k_en",
            "stt_baidu_asr_api_key" to "baidu-asr-key",
            "stt_baidu_asr_secret_key" to "baidu-asr-secret",
            "stt_revai_access_token" to "revai-token",
            "stt_speechmatics_api_key" to "speechmatics-key",
            "stt_otterai_api_key" to "otter-key"
        )

        for ((key, expected) in stored) {
            assertThat(prefs.getString(key, null)).isEqualTo(expected)
        }
        assertThat(prefs.getBoolean("stt_gcp_use_service_account", false)).isTrue()
        assertThat(prefs.getString("stt_provider", null)).isEqualTo(SttProvider.DEEPGRAM.name)
    }

    @Test
    fun `every credential is read back from storage`() {
        val reloaded = reloadFromStorage()

        assertThat(reloaded.sttProvider).isEqualTo(SttProvider.DEEPGRAM)
        assertThat(reloaded.deepgramApiKey).isEqualTo("deepgram-key")
        assertThat(reloaded.assemblyaiApiKey).isEqualTo("assembly-key")
        assertThat(reloaded.gcpProjectId).isEqualTo("gcp-project")
        assertThat(reloaded.gcpApiKey).isEqualTo("gcp-key")
        assertThat(reloaded.gcpServiceAccountJson).isEqualTo("""{"type":"service_account"}""")
        assertThat(reloaded.gcpUseServiceAccount).isTrue()
        assertThat(reloaded.azureSpeechKey).isEqualTo("azure-key")
        assertThat(reloaded.azureSpeechRegion).isEqualTo("eastus")
        assertThat(reloaded.awsAccessKeyId).isEqualTo("aws-id")
        assertThat(reloaded.awsSecretAccessKey).isEqualTo("aws-secret")
        assertThat(reloaded.awsRegion).isEqualTo("eu-west-1")
        assertThat(reloaded.ibmApiKey).isEqualTo("ibm-key")
        assertThat(reloaded.ibmServiceUrl).isEqualTo("https://watson.example")
    }

    @Test
    fun `the china region credentials are read back from storage`() {
        val reloaded = reloadFromStorage()

        assertThat(reloaded.iflytekAppId).isEqualTo("iflytek-app")
        assertThat(reloaded.iflytekApiKey).isEqualTo("iflytek-key")
        assertThat(reloaded.iflytekApiSecret).isEqualTo("iflytek-secret")
        assertThat(reloaded.huaweiAk).isEqualTo("huawei-ak")
        assertThat(reloaded.huaweiSk).isEqualTo("huawei-sk")
        assertThat(reloaded.huaweiRegion).isEqualTo("cn-south-1")
        assertThat(reloaded.huaweiProjectId).isEqualTo("huawei-project")
        assertThat(reloaded.volcengineAk).isEqualTo("volc-ak")
        assertThat(reloaded.volcangineSk).isEqualTo("volc-sk")
        assertThat(reloaded.volcengineAppId).isEqualTo("volc-app")
        assertThat(reloaded.aliyunAccessKeyId).isEqualTo("aliyun-id")
        assertThat(reloaded.aliyunAccessKeySecret).isEqualTo("aliyun-secret")
        assertThat(reloaded.aliyunAppKey).isEqualTo("aliyun-app")
        assertThat(reloaded.tencentSecretId).isEqualTo("tencent-id")
        assertThat(reloaded.tencentSecretKey).isEqualTo("tencent-secret")
        assertThat(reloaded.tencentAppId).isEqualTo("tencent-app")
        assertThat(reloaded.tencentEngineModelType).isEqualTo("16k_en")
        assertThat(reloaded.baiduAsrApiKey).isEqualTo("baidu-asr-key")
        assertThat(reloaded.baiduAsrSecretKey).isEqualTo("baidu-asr-secret")
    }

    @Test
    fun `the remaining western providers are read back from storage`() {
        val reloaded = reloadFromStorage()

        assertThat(reloaded.revaiAccessToken).isEqualTo("revai-token")
        assertThat(reloaded.speechmaticsApiKey).isEqualTo("speechmatics-key")
        assertThat(reloaded.otteraiApiKey).isEqualTo("otter-key")
    }

    @Test
    fun `clearing a credential is stored rather than leaving the old value behind`() {
        repository.saveSettings(repository.getSettings().copy(deepgramApiKey = ""))

        assertThat(prefs.getString("stt_deepgram_api_key", null)).isEmpty()
        assertThat(reloadFromStorage().deepgramApiKey).isEmpty()
        // Its neighbours are untouched.
        assertThat(reloadFromStorage().assemblyaiApiKey).isEqualTo("assembly-key")
    }

    @Test
    fun `a stored credential reaches the service factory`() {
        // toSttCredentials is what SttServiceFactory is built from.
        val credentials = reloadFromStorage().toSttCredentials()

        assertThat(credentials.selectedProvider).isEqualTo(SttProvider.DEEPGRAM.name)
        assertThat(credentials.deepgramApiKey).isEqualTo("deepgram-key")
        assertThat(credentials.tencentSecretId).isEqualTo("tencent-id")
        assertThat(credentials.speechmaticsApiKey).isEqualTo("speechmatics-key")
    }

    @Test
    fun `a missing setting falls back to its default`() {
        val readString = SettingsRepository::class.java.getDeclaredMethod(
            "string", SharedPreferences::class.java, String::class.java, String::class.java
        ).apply { isAccessible = true }

        // SharedPreferences.getString is nullable, and some implementations return
        // null rather than echoing the default back. The fallback has to hold.
        val nullReturning = mockk<SharedPreferences> {
            every { getString(any(), any()) } returns null
        }
        assertThat(readString.invoke(repository, nullReturning, "absent", "fallback"))
            .isEqualTo("fallback")

        // A stored value is returned untouched.
        val storing = mockk<SharedPreferences> {
            every { getString(any(), any()) } returns "stored"
        }
        assertThat(readString.invoke(repository, storing, "present", "fallback"))
            .isEqualTo("stored")
    }

    @Test
    fun `a fresh install keeps the non-empty regional defaults`() {
        // A repository with nothing stored yet.
        val fresh = SettingsRepository(context).getSettings()

        assertThat(fresh.awsRegion).isEqualTo("us-east-1")
        assertThat(fresh.huaweiRegion).isEqualTo("cn-north-4")
        assertThat(fresh.tencentEngineModelType).isEqualTo("16k_zh")
        assertThat(fresh.deepgramApiKey).isEmpty()
        assertThat(fresh.gcpUseServiceAccount).isFalse()
    }
}
