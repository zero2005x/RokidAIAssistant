package io.github.zero2005x.glassesaicompanion.ui.onboarding

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.zero2005x.glassesaicompanion.R

/**
 * First-launch notice. The user must tap "I understand" to continue.
 *
 * Four short cards: this is an unofficial app, glasses are optional, where the data goes, and
 * respect for other people's privacy. Nothing here asks for a permission.
 */
@Composable
fun OnboardingScreen(
    onAccept: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val guideUrl = stringResource(R.string.onboarding_glasses_guide_url)

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.onboarding_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                text = stringResource(R.string.onboarding_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OnboardingCard(
                icon = Icons.Default.Info,
                title = stringResource(R.string.onboarding_unofficial_title),
                body = stringResource(R.string.onboarding_unofficial_body)
            )

            OnboardingCard(
                icon = Icons.Default.Visibility,
                title = stringResource(R.string.onboarding_glasses_title),
                body = stringResource(R.string.onboarding_glasses_body)
            ) {
                TextButton(
                    onClick = {
                        // A plain web page; opening it never needs a permission.
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, guideUrl.toUri()))
                        } catch (_: ActivityNotFoundException) {
                            // No browser installed: nothing to do, the notice stays readable.
                        }
                    }
                ) {
                    Text(stringResource(R.string.onboarding_glasses_guide_button))
                }
            }

            OnboardingCard(
                icon = Icons.Default.Shield,
                title = stringResource(R.string.onboarding_data_title),
                body = stringResource(R.string.onboarding_data_body)
            )

            OnboardingCard(
                icon = Icons.Default.Groups,
                title = stringResource(R.string.onboarding_people_title),
                body = stringResource(R.string.onboarding_people_body)
            )

            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onAccept,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.onboarding_accept))
            }
        }
    }
}

@Composable
private fun OnboardingCard(
    icon: ImageVector,
    title: String,
    body: String,
    extra: @Composable () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() }
                )
            }
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
            extra()
        }
    }
}
