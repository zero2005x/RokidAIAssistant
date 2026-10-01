package com.example.rokidphone.service.ai

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowContentResolver
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Covers the permission-granted branches of [SystemToolsHandler], where the handler
 * reads the calendar and contacts providers. The sibling SystemToolsHandlerTest covers
 * the denied-permission and argument-validation branches.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SystemToolsProviderTest {

    /** Serves one prepared cursor, or fails the query when [cursor] is null. */
    private class StubProvider : ContentProvider() {
        var cursor: Cursor? = null
        var error: RuntimeException? = null
        override fun onCreate() = true
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?
        ): Cursor? = error?.let { throw it } ?: cursor
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    private lateinit var context: Context
    private lateinit var handler: SystemToolsHandler
    private val calendarProvider = StubProvider()
    private val contactsProvider = StubProvider()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.READ_CONTACTS)
        ShadowContentResolver.registerProviderInternal(
            CalendarContract.Events.CONTENT_URI.authority, calendarProvider
        )
        ShadowContentResolver.registerProviderInternal(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI.authority, contactsProvider
        )
        handler = SystemToolsHandler(context)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun call(id: String, vararg args: Pair<String, String>) = GeminiFunctionCall(
        id = id, name = "tool", args = JSONObject().apply { args.forEach { put(it.first, it.second) } }
    )

    @Test
    fun `today's events are returned with their title, times and location`() = runTest {
        calendarProvider.cursor = MatrixCursor(
            arrayOf(
                CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND, CalendarContract.Events.EVENT_LOCATION
            )
        ).apply {
            addRow(arrayOf<Any?>("Standup", 1_000L, 2_000L, "Room 1"))
            // A row with no title or location must not break the response.
            addRow(arrayOf<Any?>(null, 3_000L, 4_000L, null))
        }

        val result = handler.handleCheckSchedule(call("sched"))

        assertThat(result.success).isTrue()
        assertThat(result.result.getInt("count")).isEqualTo(2)
        assertThat(result.result.getString("message")).contains("successfully")
        assertThat(result.result.getString("date"))
            .isEqualTo(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date()))

        val first = result.result.getJSONArray("events").getJSONObject(0)
        assertThat(first.getString("title")).isEqualTo("Standup")
        assertThat(first.getLong("start_time_ms")).isEqualTo(1_000L)
        assertThat(first.getLong("end_time_ms")).isEqualTo(2_000L)
        assertThat(first.getString("location")).isEqualTo("Room 1")

        val second = result.result.getJSONArray("events").getJSONObject(1)
        assertThat(second.getString("title")).isEqualTo("(No title)")
        assertThat(second.getString("location")).isEmpty()
    }

    @Test
    fun `an empty day reports zero events rather than an error`() = runTest {
        calendarProvider.cursor = MatrixCursor(arrayOf(CalendarContract.Events.TITLE))

        val result = handler.handleCheckSchedule(call("sched"))

        assertThat(result.success).isTrue()
        assertThat(result.result.getInt("count")).isEqualTo(0)
        assertThat(result.result.getString("message")).contains("No events")
    }

    @Test
    fun `a calendar provider failure is reported as a tool failure`() = runTest {
        calendarProvider.error = IllegalStateException("provider died")

        val result = handler.handleCheckSchedule(call("sched"))

        assertThat(result.success).isFalse()
        assertThat(result.errorMessage).contains("provider died")
    }

    @Test
    fun `a contact name resolves to its sanitized number and opens the dialer`() = runTest {
        contactsProvider.cursor = MatrixCursor(
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        ).apply { addRow(arrayOf<Any?>("09 12-345-678")) }

        val result = handler.handleMakeCall(call("call", "contact_name" to "Alice"))

        assertThat(result.success).isTrue()
        assertThat(result.result.getString("phone_number")).isEqualTo("0912345678")
        assertThat(result.result.getString("contact_name")).isEqualTo("Alice")
    }

    @Test
    fun `an unknown or unusable contact yields no number`() = runTest {
        contactsProvider.cursor = MatrixCursor(arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER))
        assertThat(handler.handleMakeCall(call("call", "contact_name" to "Nobody")).success).isFalse()

        // Contact found, but the stored number is blank.
        contactsProvider.cursor = MatrixCursor(
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        ).apply { addRow(arrayOf<Any?>("   ")) }
        val blank = handler.handleMakeCall(call("call", "contact_name" to "Blank"))
        assertThat(blank.success).isFalse()
        assertThat(blank.errorMessage).contains("No valid phone number")
    }
}
