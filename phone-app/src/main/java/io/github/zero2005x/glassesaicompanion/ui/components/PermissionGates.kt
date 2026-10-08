package io.github.zero2005x.glassesaicompanion.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import io.github.zero2005x.glassesaicompanion.R

/**
 * Returns an action that runs [onGranted] once the microphone permission is available.
 *
 * The permission is requested in context, at the moment the user starts a recording, instead
 * of at app launch. If the user declines, nothing is started and a short explanation is shown.
 */
@Composable
fun rememberMicrophoneGate(onGranted: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val currentOnGranted by rememberUpdatedState(onGranted)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            currentOnGranted()
        } else {
            Toast.makeText(context, R.string.microphone_permission_denied, Toast.LENGTH_LONG).show()
        }
    }
    return remember(context, launcher) {
        {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) currentOnGranted() else launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
