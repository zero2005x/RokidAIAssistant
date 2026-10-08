package io.github.zero2005x.glassesaicompanion.service

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Google Play flavor: Edge TTS is not shipped.
 *
 * The GitHub flavor talks to an undocumented Microsoft Edge "read aloud" endpoint, which is not
 * an officially supported API. This stub keeps the call sites compiling and always reports
 * failure, so callers fall back to the Android system TTS engine. The Play settings UI never
 * offers Edge TTS (see DistributionRules), so this is only a safety net.
 */
@Suppress("UNUSED_PARAMETER")
class EdgeTtsClient(
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    companion object {
        const val VOICE_XIAOXIAO = "zh-CN-XiaoxiaoNeural"
    }

    suspend fun synthesize(
        text: String,
        voice: String = VOICE_XIAOXIAO,
        rate: String = "+0%",
        pitch: String = "+0Hz",
        volume: String = "+0%"
    ): Result<ByteArray> =
        Result.failure(UnsupportedOperationException("Edge TTS is not available in this build"))
}