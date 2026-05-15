package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import soy.engindearing.omnitak.mobile.data.CoTAffiliation
import soy.engindearing.omnitak.mobile.data.CoTEvent

/**
 * Issue #16 — picker for "Send Lasso Selection to Contacts." Shows
 * every contact currently in `ContactStore` as a tappable row with a
 * checkbox; tapping confirm hands the chosen UIDs back to the caller
 * so the send path can route the selected CoTs via `<dest>` elements.
 *
 * Excludes the user's own callsign / self-marker if present and any
 * UIDs that are members of the lasso selection itself (we don't want
 * to send markers to themselves).
 *
 * Visual: standard Material 3 AlertDialog with a vertically-scrolling
 * list. Each row shows callsign (or UID fallback) + affiliation tag.
 */
@Composable
fun ContactPickerDialog(
    title: String,
    candidates: List<CoTEvent>,
    excludeUids: Set<String> = emptySet(),
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    val filtered = remember(candidates, excludeUids) {
        candidates.filterNot { it.uid in excludeUids }
            .sortedBy { it.callsign?.lowercase() ?: it.uid.lowercase() }
    }
    val checked: SnapshotStateList<Boolean> = remember(filtered) {
        MutableList(filtered.size) { false }.toMutableStateList()
    }
    val anyChecked = checked.any { it }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (filtered.isEmpty()) {
                Text(
                    "No contacts available to send to. Wait for at least one peer to appear on the map.",
                    color = Color.White.copy(alpha = 0.7f),
                )
            } else {
                val scroll = rememberScrollState()
                Column(
                    modifier = Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(scroll),
                ) {
                    filtered.forEachIndexed { i, contact ->
                        ContactRow(
                            contact = contact,
                            isSelected = checked[i],
                            onToggle = { checked[i] = !checked[i] },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val uids = filtered.filterIndexed { i, _ -> checked[i] }
                        .map { it.uid }
                        .toSet()
                    onConfirm(uids)
                },
                enabled = anyChecked,
            ) { Text(if (anyChecked) "Send to ${checked.count { it }}" else "Send") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        containerColor = Color(0xFF0F1115),
    )
}

@Composable
private fun ContactRow(
    contact: CoTEvent,
    isSelected: Boolean,
    onToggle: () -> Unit,
) {
    val tint = when (contact.affiliation) {
        CoTAffiliation.FRIEND -> Color(0xFF00FF66)
        CoTAffiliation.HOSTILE -> Color(0xFFFF4040)
        CoTAffiliation.NEUTRAL -> Color(0xFFD0AF66)
        CoTAffiliation.UNKNOWN -> Color(0xFFFFCC00)
        else -> Color.White.copy(alpha = 0.6f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            if (isSelected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
            contentDescription = null,
            tint = if (isSelected) Color(0xFFFF9500) else Color.White.copy(alpha = 0.5f),
        )
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(tint),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                contact.callsign?.takeIf { it.isNotBlank() } ?: contact.uid,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "${contact.affiliation.name.lowercase()} · ${contact.uid.take(12)}…",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
