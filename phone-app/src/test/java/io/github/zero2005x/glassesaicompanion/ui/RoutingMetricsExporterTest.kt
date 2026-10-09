package io.github.zero2005x.glassesaicompanion.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.zero2005x.glassesaicompanion.data.db.AppDatabase
import io.github.zero2005x.glassesaicompanion.data.db.RoutingMetricEntity
import io.github.zero2005x.glassesaicompanion.data.db.RoutingMetricSource
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class RoutingMetricsExporterTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        mockkObject(AppDatabase.Companion)
        every { AppDatabase.getInstance(any()) } returns database
    }

    @After
    fun tearDown() {
        database.close()
        unmockkAll()
    }

    private fun csvFile() = File(File(context.cacheDir, "exports"), RoutingMetricsExporter.FILE_NAME)

    @Test
    fun exportWritesTheHeaderAndOneLinePerRow() = runBlocking<Unit> {
        database.routingMetricDao().insert(
            RoutingMetricEntity(
                receivedAt = 0, source = RoutingMetricSource.PHONE_CHAT, routingEnabled = false,
                decisionBackend = null, decisionLatencyMs = null, tier = null, reasonCode = "disabled",
                provider = "GEMINI", modelId = "m", totalMs = 5, answerChars = 3, glassesPageCount = null
            )
        )

        // The share intent itself needs a real FileProvider grant, which Robolectric cannot
        // resolve; the CSV the user would share is what matters here.
        RoutingMetricsExporter.createShareIntent(context)

        val lines = csvFile().readText().trimEnd().lines()
        assertThat(lines).hasSize(2)
        assertThat(lines[0]).startsWith("time,source,routing_enabled")
        assertThat(lines[1]).startsWith("1970-01-01T00:00:00Z,phone_chat,false")
    }

    @Test
    fun anEmptyTableStillExportsTheHeader() = runBlocking<Unit> {
        RoutingMetricsExporter.createShareIntent(context)

        assertThat(csvFile().readText().trimEnd().lines()).hasSize(1)
    }

    @Test
    fun aDatabaseFailureReturnsNullInsteadOfThrowing() = runBlocking<Unit> {
        every { AppDatabase.getInstance(any()) } throws java.io.IOException("unavailable")

        assertThat(RoutingMetricsExporter.createShareIntent(context)).isNull()
    }
}
