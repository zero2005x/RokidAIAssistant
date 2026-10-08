package io.github.zero2005x.glassesaicompanion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rokidcommon.protocol.GlassesDisplayConfig
import com.example.rokidcommon.protocol.GlassesDisplayLayout
import com.example.rokidcommon.protocol.GlassesDisplayMetrics
import com.example.rokidcommon.protocol.GlassesFont
import io.github.zero2005x.glassesaicompanion.R

/** Scale device pixels to preview pixels before laying out fonts and padding. */
@Composable
internal fun GlassesDisplayPreview(config: GlassesDisplayConfig, metrics: GlassesDisplayMetrics?) {
    val device = metrics ?: GlassesDisplayMetrics(480, 640, 1f, 1f)
    val phoneDensity = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val ratio = device.widthPx.toFloat() / device.heightPx
        val height = minOf(maxWidth / ratio, 320.dp)
        val width = height * ratio
        val scale = with(phoneDensity) { width.toPx() } / device.widthPx
        Box(Modifier.size(width, height).background(Color.Black).clipToBounds()) {
            CompositionLocalProvider(LocalDensity provides Density(device.density * scale,
                device.fontScale * config.fontSizeSp / GlassesDisplayLayout.BASE_FONT_SP.toFloat())) {
                val viewportWidth = (device.widthPx / device.density * config.widthPercent / 100f).dp
                val viewportHeight = (device.heightPx / device.density * config.heightPercent / 100f).dp
                val family = if (config.font == GlassesFont.MONOSPACE) FontFamily.Monospace else FontFamily.Default
                Column(Modifier.offset(
                    (device.widthPx / device.density * config.leftPercent / 100f).dp,
                    (device.heightPx / device.density * config.topPercent / 100f).dp)
                    .size(viewportWidth, viewportHeight).background(Color(0xFF17212B)).clipToBounds()
                    .padding(GlassesDisplayLayout.PADDING_DP.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(max = viewportHeight * GlassesDisplayLayout.HEADER_FRACTION)
                        .horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                        Spacer(Modifier.width(1.dp))
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.glasses_preview_connected), color = Color.White,
                                fontSize = 12.sp, fontFamily = family)
                            Text(stringResource(R.string.record_phone), color = Color.White.copy(alpha = 0.5f),
                                fontSize = 10.sp, fontFamily = family)
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = GlassesDisplayLayout.PADDING_DP.dp),
                        contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.glasses_preview_text), color = Color.White,
                            fontSize = GlassesDisplayLayout.BASE_FONT_SP.sp, lineHeight = GlassesDisplayLayout.LINE_HEIGHT_SP.sp,
                            fontFamily = family, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()))
                    }
                    Text(stringResource(R.string.glasses_preview_hint), color = Color.LightGray,
                        fontSize = 14.sp, fontFamily = family, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().heightIn(max = viewportHeight * GlassesDisplayLayout.HINT_FRACTION)
                            .verticalScroll(rememberScrollState()))
                }
            }
        }
    }
    if (metrics != null) Text(stringResource(R.string.glasses_preview_metrics, device.widthPx, device.heightPx))
}
