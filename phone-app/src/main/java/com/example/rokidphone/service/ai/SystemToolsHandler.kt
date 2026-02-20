package com.example.rokidphone.service.ai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Local Android system tool handlers for Gemini function calling.
 *
 * Permission reminder:
 * - Add READ_CALENDAR in AndroidManifest.xml for check_schedule.
 * - Add CALL_PHONE in AndroidManifest.xml if you change make_call from ACTION_DIAL to ACTION_CALL.
 */
class SystemToolsHandler(private val context: Context) {

    companion object {
        private const val TAG = "SystemToolsHandler"
    }

    suspend fun handleCheckSchedule(call: GeminiFunctionCall): ToolResult = withContext(Dispatchers.IO) {
        if (!hasPermission(Manifest.permission.READ_CALENDAR)) {
            return@withContext ToolResult.failure(
                call.id,
                "Calendar permission missing. Please declare and grant READ_CALENDAR."
            )
        }

        return@withContext try {
            val events = queryTodayEvents()
            ToolResult.success(
                call.id,
                JSONObject().apply {
                    put("date", todayDateString())
                    put("count", events.length())
                    put("events", events)
                    put("message", if (events.length() == 0) "No events found for today." else "Fetched today's schedule successfully.")
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "check_schedule failed", e)
            ToolResult.failure(call.id, "Failed to read calendar: ${e.message}")
        }
    }

    suspend fun handleMakeCall(call: GeminiFunctionCall): ToolResult = withContext(Dispatchers.Main) {
        val args = call.args
        val phoneNumber = args.optString("phone_number").ifBlank {
            args.optString("phoneNumber").ifBlank { "" }
        }
        val contactName = args.optString("contact_name").ifBlank {
            args.optString("contactName").ifBlank { "" }
        }

        val resolvedNumber = when {
            phoneNumber.isNotBlank() -> sanitizePhoneNumber(phoneNumber)
            contactName.isNotBlank() -> resolvePhoneNumberByContactName(contactName)
            else -> null
        }

        if (resolvedNumber.isNullOrBlank()) {
            return@withContext ToolResult.failure(
                call.id,
                "No valid phone number found. Provide phone_number or a resolvable contact_name."
            )
        }

        return@withContext try {
            val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$resolvedNumber")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(dialIntent)

            ToolResult.success(
                call.id,
                JSONObject().apply {
                    put("message", "Dialer opened successfully.")
                    put("phone_number", resolvedNumber)
                    if (contactName.isNotBlank()) put("contact_name", contactName)
                    put("action", "ACTION_DIAL")
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "make_call failed", e)
            ToolResult.failure(call.id, "Failed to start dialer: ${e.message}")
        }
    }

    private fun queryTodayEvents(): JSONArray {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startMs = calendar.timeInMillis
        calendar.add(Calendar.DAY_OF_MONTH, 1)
        val endMs = calendar.timeInMillis

        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_LOCATION
        )

        val selection = "${CalendarContract.Events.DTSTART} < ? AND ${CalendarContract.Events.DTEND} > ?"
        val selectionArgs = arrayOf(endMs.toString(), startMs.toString())
        val sortOrder = "${CalendarContract.Events.DTSTART} ASC"

        val events = JSONArray()
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val titleIndex = cursor.getColumnIndex(CalendarContract.Events.TITLE)
            val startIndex = cursor.getColumnIndex(CalendarContract.Events.DTSTART)
            val endIndex = cursor.getColumnIndex(CalendarContract.Events.DTEND)
            val locationIndex = cursor.getColumnIndex(CalendarContract.Events.EVENT_LOCATION)

            while (cursor.moveToNext()) {
                val title = if (titleIndex >= 0) cursor.getString(titleIndex) else "(No title)"
                val start = if (startIndex >= 0) cursor.getLong(startIndex) else 0L
                val end = if (endIndex >= 0) cursor.getLong(endIndex) else 0L
                val location = if (locationIndex >= 0) cursor.getString(locationIndex) else ""

                events.put(JSONObject().apply {
                    put("title", title ?: "(No title)")
                    put("start_time_ms", start)
                    put("end_time_ms", end)
                    put("location", location ?: "")
                })
            }
        }

        return events
    }

    private fun resolvePhoneNumberByContactName(contactName: String): String? {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            return null
        }

        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$contactName%")

        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            if (cursor.moveToFirst() && numberIndex >= 0) {
                val raw = cursor.getString(numberIndex)
                return sanitizePhoneNumber(raw)
            }
        }

        return null
    }

    private fun sanitizePhoneNumber(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val sanitized = value.trim().replace(" ", "").replace("-", "")
        return if (sanitized.isBlank()) null else sanitized
    }

    private fun todayDateString(): String {
        val now = Calendar.getInstance()
        val year = now.get(Calendar.YEAR)
        val month = now.get(Calendar.MONTH) + 1
        val day = now.get(Calendar.DAY_OF_MONTH)
        return String.format("%04d-%02d-%02d", year, month, day)
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}
