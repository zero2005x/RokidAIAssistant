package com.example.rokidglasses.service

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.rokidglasses.MainActivity
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController

/**
 * Lifecycle, foreground notification and wake handling for [WakeWordService].
 * The microphone loop itself needs real audio hardware and is not exercised here.
 */
@RunWith(RobolectricTestRunner::class)
class WakeWordServiceTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private var controller: ServiceController<WakeWordService>? = null

    /** [WakeWordService.isRunning] is process-wide state, so never leak it between tests. */
    @After
    fun tearDown() {
        controller?.destroy()
        controller = null
        assertThat(WakeWordService.isRunning).isFalse()
    }

    private fun grantMicrophone() =
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)

    private fun create(): WakeWordService {
        val created = Robolectric.buildService(WakeWordService::class.java)
        controller = created
        return created.create().get()
    }

    private fun invoke(service: WakeWordService, name: String, vararg types: Class<*>): Any? =
        WakeWordService::class.java.getDeclaredMethod(name, *types)
            .apply { isAccessible = true }
            .invoke(service, *arrayOf<Any>())

    @Test
    fun `without the microphone permission the service stops instead of listening`() {
        val service = create()

        assertThat(WakeWordService.isRunning).isFalse()
        assertThat(shadowOf(service).isStoppedBySelf).isTrue()
    }

    @Test
    fun `with the permission granted the service starts listening`() {
        grantMicrophone()

        val service = create()

        assertThat(WakeWordService.isRunning).isTrue()
        assertThat(shadowOf(service).isStoppedBySelf).isFalse()
    }

    @Test
    fun `a low priority notification channel is created for the foreground notice`() {
        grantMicrophone()
        create()

        val manager = application.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel("wake_word_channel")

        assertThat(channel).isNotNull()
        assertThat(channel.importance).isEqualTo(NotificationManager.IMPORTANCE_LOW)
        assertThat(channel.canShowBadge()).isFalse()
        assertThat(channel.description).isNotEmpty()
    }

    @Test
    fun `the service runs in the foreground with an ongoing notification`() {
        grantMicrophone()
        val service = create()

        val notification = shadowOf(service).lastForegroundNotification

        assertThat(notification).isNotNull()
        assertThat(shadowOf(service).lastForegroundNotificationId).isEqualTo(2001)
        assertThat(shadowOf(notification).isOngoing).isTrue()
        assertThat(shadowOf(notification).contentTitle.toString()).isNotEmpty()
        assertThat(notification.contentIntent).isNotNull()
    }

    @Test
    fun `the service is sticky and cannot be bound`() {
        grantMicrophone()
        val service = create()

        assertThat(service.onStartCommand(null, 0, 1)).isEqualTo(android.app.Service.START_STICKY)
        assertThat(service.onBind(null)).isNull()
    }

    @Test
    fun `destroying the service clears the running flag`() {
        grantMicrophone()
        create()
        assertThat(WakeWordService.isRunning).isTrue()

        controller!!.destroy()

        assertThat(WakeWordService.isRunning).isFalse()
        // The @After destroy must stay harmless once the service is already gone.
        controller = null
    }

    @Test
    fun `a wake brings the main activity to the front as a new task`() {
        grantMicrophone()
        val service = create()

        invoke(service, "triggerWakeUp")

        val started = shadowOf(application).nextStartedActivity!!
        assertThat(started.component!!.className).isEqualTo(MainActivity::class.java.name)
        assertThat(started.getBooleanExtra("wake_up", false)).isTrue()
        for (flag in listOf(
            Intent.FLAG_ACTIVITY_NEW_TASK,
            Intent.FLAG_ACTIVITY_CLEAR_TOP,
            Intent.FLAG_ACTIVITY_SINGLE_TOP
        )) {
            assertThat(started.flags and flag).isEqualTo(flag)
        }
    }

    @Test
    fun `amplitude is the mean absolute value of the samples that were read`() {
        grantMicrophone()
        val service = create()
        val method = WakeWordService::class.java
            .getDeclaredMethod("calculateAmplitude", ShortArray::class.java, Int::class.java)
            .apply { isAccessible = true }

        // Only the first `count` samples count; the tail is whatever the last read left behind.
        val buffer = shortArrayOf(100, -300, 500, 30_000)
        assertThat(method.invoke(service, buffer, 3)).isEqualTo(300)

        // The most negative sample must not overflow into a positive average.
        assertThat(method.invoke(service, shortArrayOf(-32_768), 1)).isEqualTo(32_768)
    }

    @Test
    fun `releasing the recorder is safe when nothing was ever started`() {
        grantMicrophone()
        val service = create()

        // No AudioRecord exists in a JVM test, so this must not throw.
        invoke(service, "releaseAudioRecord")
        invoke(service, "releaseAudioRecord")
    }
}
