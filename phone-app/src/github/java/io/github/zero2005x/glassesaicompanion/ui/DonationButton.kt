package io.github.zero2005x.glassesaicompanion.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * GitHub flavor only: a link to the developer's Ko-fi page.
 *
 * The Google Play flavor does not contain this code at all (see the `play` source set), so no
 * donation link can be reached or found in that build.
 */
@Composable
fun DonationButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Button(
        onClick = {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://ko-fi.com/liangtinglin"))
            context.startActivity(intent)
        },
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFFFF5E5B)
        )
    ) {
        Icon(
            imageVector = Icons.Default.LocalCafe,
            contentDescription = null,
            tint = Color.White
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Support me on Ko-fi",
            color = Color.White
        )
    }
}