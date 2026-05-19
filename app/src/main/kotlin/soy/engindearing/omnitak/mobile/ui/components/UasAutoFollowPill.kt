package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Auto-follow toggle: when on, the map camera re-pans to the drone
 * every 2 s. Useful for "put the phone down, watch the bird" — operator
 * doesn't need to keep dragging the map back as the drone moves.
 *
 * Standard pattern in DJI Pilot / Auterion. Inactive = dark, active =
 * cyan fill (matches the cruise pill's interaction style).
 */
@Composable
fun UasAutoFollowPill(
    active: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (active) Color(0xFF00E5FF) else Color.Black.copy(alpha = 0.78f)
    val fg = if (active) Color.Black else Color.White
    val icon = if (active) Icons.Filled.CenterFocusStrong else Icons.Filled.CenterFocusWeak

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Icon(icon, contentDescription = "Auto-follow", tint = fg)
        Text(
            text = if (active) "FOLLOWING CAMERA" else "Auto-follow",
            color = fg,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
