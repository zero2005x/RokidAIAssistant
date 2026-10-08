package io.github.zero2005x.glassesaicompanion.data.db

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class RecordingConvertersTest {
    private val converters = RecordingConverters()

    @Test
    fun `all recording sources survive database round trip`() {
        RecordingSource.entries.forEach {
            assertEquals(it.name, converters.fromRecordingSource(it))
            assertEquals(it, converters.toRecordingSource(converters.fromRecordingSource(it)))
        }
    }

    @Test
    fun `all processing statuses survive database round trip`() {
        RecordingStatus.entries.forEach {
            assertEquals(it.name, converters.fromRecordingStatus(it))
            assertEquals(it, converters.toRecordingStatus(converters.fromRecordingStatus(it)))
        }
    }

    @Test
    fun `legacy or corrupt database values use safe defaults`() {
        listOf("", "LEGACY", "phone", "completed").forEach {
            assertEquals(RecordingSource.PHONE, converters.toRecordingSource(it))
            assertEquals(RecordingStatus.ERROR, converters.toRecordingStatus(it))
        }
    }
}
