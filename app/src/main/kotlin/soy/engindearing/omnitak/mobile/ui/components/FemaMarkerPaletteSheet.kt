package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maplibre.android.geometry.LatLng
import soy.engindearing.omnitak.mobile.data.FemaIconCatalog
import soy.engindearing.omnitak.mobile.ui.theme.TacticalAccent
import soy.engindearing.omnitak.mobile.ui.theme.TacticalBackground
import soy.engindearing.omnitak.mobile.ui.theme.TacticalSurface

/** Result handed back when the operator picks a FEMA icon and taps Drop. */
data class FemaMarkerSelection(
    val icon: FemaIconCatalog.FemaIcon,
    val name: String,
    val remarks: String,
)

/**
 * FEMA / IC marker palette. Modal bottom sheet — never compresses the
 * full-screen map (per `feedback_omnitak_fullscreen_map`).
 *
 * 5 category sections, each a small grid of icon tiles. Picking a tile
 * reveals the name + remarks fields and the Drop button. Drop hands
 * the selection back; caller is responsible for ingesting the marker.
 *
 * Closes the UI half of #29; the catalog is in [FemaIconCatalog].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FemaMarkerPaletteSheet(
    visible: Boolean,
    latLng: LatLng?,
    defaultName: String = "",
    onConfirm: (FemaMarkerSelection) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var picked by remember { mutableStateOf<FemaIconCatalog.FemaIcon?>(null) }
    var name by remember(picked) { mutableStateOf(picked?.label ?: defaultName) }
    var remarks by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = TacticalBackground,
        scrimColor = Color.Black.copy(alpha = 0.35f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                "FEMA / IC Markers",
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                "SAR / DISASTER RESPONSE",
                color = Color(0xFFFFEB3B),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                "Tap an icon, then Drop. Round-trips over CoT as a friendly ground installation with a FEMA usericon.",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            latLng?.let {
                Text(
                    rememberCoordText(it.latitude, it.longitude),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(8.dp))

            FemaIconCatalog.Category.entries.forEach { category ->
                CategorySection(
                    category = category,
                    picked = picked,
                    onPick = { picked = it },
                )
                Spacer(Modifier.height(12.dp))
            }

            picked?.let { selected ->
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Marker name") },
                    singleLine = true,
                    colors = paletteFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = remarks,
                    onValueChange = { remarks = it },
                    label = { Text("Remarks (optional)") },
                    singleLine = false,
                    minLines = 2,
                    colors = paletteFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        onConfirm(
                            FemaMarkerSelection(
                                icon = selected,
                                name = name.ifBlank { selected.label },
                                remarks = remarks,
                            )
                        )
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TacticalAccent,
                        contentColor = TacticalBackground,
                    ),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Text(
                        "Drop ${selected.label}",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CategorySection(
    category: FemaIconCatalog.Category,
    picked: FemaIconCatalog.FemaIcon?,
    onPick: (FemaIconCatalog.FemaIcon) -> Unit,
) {
    val icons = FemaIconCatalog.iconsIn(category)
    if (icons.isEmpty()) return

    Text(
        category.displayName.uppercase(),
        color = category.accent,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 6.dp),
    )

    // Single row of tiles per category — FlowRow would be ideal but
    // sticking to a LazyVerticalGrid with fixed cell count keeps the
    // tile sizing predictable across category counts.
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        userScrollEnabled = false,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height((((icons.size + 2) / 3) * 100).dp),
    ) {
        items(icons, key = { it.kind.name }) { icon ->
            IconTile(icon = icon, isPicked = picked?.kind == icon.kind, onPick = onPick)
        }
    }
}

@Composable
private fun IconTile(
    icon: FemaIconCatalog.FemaIcon,
    isPicked: Boolean,
    onPick: (FemaIconCatalog.FemaIcon) -> Unit,
) {
    val border = if (isPicked) BorderStroke(2.dp, icon.category.accent) else null
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (isPicked) icon.category.accent.copy(alpha = 0.18f) else TacticalSurface)
            .then(if (border != null) Modifier.border(border, RoundedCornerShape(10.dp)) else Modifier)
            .clickable { onPick(icon) }
            .padding(8.dp)
            .fillMaxWidth()
            .height(86.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .padding(top = 4.dp),
        ) {
            Icon(
                imageVector = icon.image,
                contentDescription = icon.label,
                tint = icon.category.accent,
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = icon.label,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

@Composable
private fun paletteFieldColors() = TextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.colorScheme.onBackground,
    unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
    focusedContainerColor = TacticalSurface,
    unfocusedContainerColor = TacticalSurface,
    focusedIndicatorColor = TacticalAccent,
    unfocusedIndicatorColor = TacticalAccent.copy(alpha = 0.4f),
    focusedLabelColor = TacticalAccent,
    unfocusedLabelColor = TacticalAccent.copy(alpha = 0.6f),
    cursorColor = TacticalAccent,
)
