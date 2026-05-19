package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonPin
import androidx.compose.material.icons.filled.Stop
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
 * Follow-Me toggle chip. Lives under the cruise altitude pill in the
 * map HUD when a UAS is connected.
 *
 * Inactive: dark "Follow Me" tap to engage.
 * Active: red "FOLLOWING · STOP" — operator can hit it from anywhere
 * to disengage and put the drone back in AUTO.LOITER.
 */
@Composable
fun UasFollowMePill(
    active: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (active) Color(0xFFFF3B30).copy(alpha = 0.9f) else Color.Black.copy(alpha = 0.78f)
    val fg = if (active) Color.White else Color(0xFF00E5FF)
    val label = if (active) "FOLLOWING · STOP" else "Follow Me"
    val icon = if (active) Icons.Filled.Stop else Icons.Filled.PersonPin

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.padding(end = 2.dp))
        Text(
            text = label,
            color = if (active) Color.White else Color.White,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
