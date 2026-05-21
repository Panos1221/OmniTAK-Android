package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Top-of-map banner for autopilot WARNING-or-worse STATUSTEXT.
 * Auto-dismisses after [autoDismissMs] so the operator's view isn't
 * permanently obscured by stale alerts.
 *
 * MAV_SEVERITY ordinals (lower = worse):
 *   0 EMERGENCY · 1 ALERT · 2 CRITICAL · 3 ERROR · 4 WARNING
 */
@Composable
fun UasAlertBanner(
    alert: Triple<Int, String, Long>?,
    autoDismissMs: Long = 6_000L,
    modifier: Modifier = Modifier,
) {
    if (alert == null) return

    // Stable key per alert (timestamp) so the auto-dismiss timer
    // re-arms on each new alert instead of carrying over.
    val (sev, text, ts) = alert
    var visible by remember(ts) { mutableStateOf(true) }
    LaunchedEffect(ts) {
        delay(autoDismissMs)
        visible = false
    }
    if (!visible) return

    val (bg, fg, label) = when {
        sev <= 2 -> Triple(Color(0xFFFF3B30).copy(alpha = 0.92f), Color.White, "CRITICAL")
        sev == 3 -> Triple(Color(0xFFFF6B35).copy(alpha = 0.92f), Color.White, "ERROR")
        else     -> Triple(Color(0xFFFFC107).copy(alpha = 0.92f), Color.Black, "WARNING")
    }
    val icon = if (sev <= 3) Icons.Filled.Error else Icons.Filled.Warning

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = label, tint = fg)
        Text(
            label,
            color = fg,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            text = text,
            color = fg,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
