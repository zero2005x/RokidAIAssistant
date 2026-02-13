package com.example.rokidphone.service.stt

import com.example.rokidphone.data.ApiSettings
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SttServiceFactoryTest {

    private fun fullyConfiguredApiSettings(): ApiSettings {
        return ApiSettings(
            geminiApiKey = "gemini-key",
            openaiApiKey = "openai-key",
            groqApiKey = "groq-key"
        )
    }

    private fun fullyConfiguredCredentials(selected: SttProvider): SttCredentials {
        return SttCredentials(
            selectedProvider = selected.name,
            deepgramApiKey = "deepgram-key",
            assemblyaiApiKey = "assembly-key",
            gcpProjectId = "gcp-project",
            gcpApiKey = "gcp-key",
            azureSpeechKey = "azure-key",
            azureSpeechRegion = "eastus",
            awsAccessKeyId = "aws-ak",
            awsSecretAccessKey = "aws-sk",
            ibmApiKey = "ibm-key",
            ibmServiceUrl = "https://example.ibm.com",
            iflytekAppId = "iflytek-app",
            iflytekApiKey = "iflytek-key",
            iflytekApiSecret = "iflytek-secret",
            huaweiAk = "huawei-ak",
            huaweiSk = "huawei-sk",
            huaweiProjectId = "huawei-project",
            volcengineAk = "volc-ak",
            volcangineSk = "volc-sk",
            volcengineAppId = "volc-app",
            aliyunAccessKeyId = "aliyun-ak",
            aliyunAccessKeySecret = "aliyun-sk",
            aliyunAppKey = "aliyun-app",
            tencentSecretId = "tencent-id",
            tencentSecretKey = "tencent-key",
            tencentAppId = "tencent-app",
            baiduAsrApiKey = "baidu-asr-key",
            baiduAsrSecretKey = "baidu-asr-secret",
            revaiAccessToken = "rev-token",
            speechmaticsApiKey = "speechmatics-key",
            otteraiApiKey = "otter-key"
        )
    }

    @Test
    fun `createService with valid Gemini credentials returns GeminiSttAdapter`() {
        // 測試：GEMINI provider 應建立 Gemini adapter
        val service = SttServiceFactory.createService(
            sttCredentials = SttCredentials(selectedProvider = SttProvider.GEMINI.name),
            apiSettings = fullyConfiguredApiSettings()
        )

        assertThat(service).isNotNull()
        assertThat(service!!.provider).isEqualTo(SttProvider.GEMINI)
        assertThat(service.javaClass.simpleName).contains("GeminiSttAdapter")
    }

    @Test
    fun `createService with blank API key returns null`() {
        // 測試：provider 選擇後若必要憑證為空應回傳 null
        val blankApiSettings = ApiSettings()

        for (provider in SttProvider.entries) {
            val service = SttServiceFactory.createService(
                sttCredentials = SttCredentials(selectedProvider = provider.name),
                apiSettings = blankApiSettings
            )
            assertThat(service).isNull()
        }
    }

    @Test
    fun `every SttProvider enum value has a factory case`() {
        // 測試：所有 SttProvider 都有 factory 分支且可建立
        val settings = fullyConfiguredApiSettings()

        for (provider in SttProvider.entries) {
            val service = SttServiceFactory.createService(
                sttCredentials = fullyConfiguredCredentials(provider),
                apiSettings = settings
            )
            assertThat(service).isNotNull()
            assertThat(service!!.provider).isEqualTo(provider)
        }
    }

    @Test
    fun `getImplementedProviders returns all 18 providers`() {
        // 測試：已實作 STT provider 數量應為 18
        val implemented = SttServiceFactory.getImplementedProviders()
        assertThat(implemented.size).isEqualTo(18)
        assertThat(implemented).containsAtLeastElementsIn(SttProvider.entries)
    }

    @Test
    fun `getPlannedProviders returns empty list`() {
        // 測試：目前無未實作 provider
        assertThat(SttServiceFactory.getPlannedProviders()).isEmpty()
    }
}
