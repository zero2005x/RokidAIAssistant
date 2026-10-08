package io.github.zero2005x.glassesaicompanion.data.log

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * End-to-end check of the promise "secrets never leave the app in a log": a synthetic key is
 * logged in every way the app could, and none of the places a log can be read or exported may
 * still contain it.
 */
@RunWith(RobolectricTestRunner::class)
class LogManagerRedactionTest {

    private val key = "sk-" + "test-LEAK-123456"
    private lateinit var manager: LogManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        manager = LogManager.getInstance(context)
        manager.clearAllLogs()
    }

    @Test
    fun `a key in a message is masked in the viewer buffer`() {
        manager.d("Test", "request used $key for auth")

        val stored = manager.getFilteredLogs(LogFilter()).joinToString("\n") { it.message }

        assertThat(stored).doesNotContain(key)
        assertThat(stored).contains("request used")
    }

    @Test
    fun `header query and json forms are all masked in the exported string`() {
        manager.d("Test", "Authorization: Bearer $key")
        manager.d("Test", "GET https://example.test/v1?key=$key")
        manager.d("Test", """{"api_key":"$key"}""")
        manager.d("Test", "x-api-key: $key")

        val exported = manager.exportLogsAsString(LogFilter())

        assertThat(exported).doesNotContain(key)
        assertThat(exported).contains("***")
    }

    @Test
    fun `a key inside an exception is masked in the exported string`() {
        manager.e("Test", "request failed", IllegalStateException("401 for key $key"))

        val exported = manager.exportLogsAsString(LogFilter())

        assertThat(exported).doesNotContain(key)
        assertThat(exported).contains("IllegalStateException")
    }

    @Test
    fun `a key is masked in the exported file`() = runTest {
        manager.w("Test", "bad credential $key")

        val file = manager.exportLogs(LogFilter())

        assertThat(file).isNotNull()
        val text = file!!.readText()
        assertThat(text).doesNotContain(key)
        assertThat(text).contains("bad credential")
    }

    @Test
    fun `the export header uses the app name`() {
        manager.i("Test", "hello")

        assertThat(manager.exportLogsAsString(LogFilter())).startsWith("=== ")
    }
}