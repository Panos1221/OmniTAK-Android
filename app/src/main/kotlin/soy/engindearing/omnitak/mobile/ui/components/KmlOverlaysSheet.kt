package soy.engindearing.omnitak.mobile.ui.components

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import soy.engindearing.omnitak.mobile.OmniTAKApp
import java.io.File

/**
 * "Map Overlays" — import KML/KMZ and manage imported vector overlays
 * (toggle, zoom-to-fit, delete). Backed by KmlVectorOverlayStore so even a
 * ~50k-feature statewide file imports and toggles smoothly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KmlOverlaysSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as OmniTAKApp
    val store = app.kmlOverlayStore
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState()

    val overlays by store.overlays.collectAsState()
    val importing by store.isImporting.collectAsState()
    val status by store.status.collectAsState()
    val error by store.lastError.collectAsState()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val name = queryDisplayName(context, uri) ?: "overlay.kml"
                val tmp = File(context.cacheDir, name)
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    }
                }
                store.importKml(tmp, name)
                // Jump the map to the freshly imported overlay so it's
                // immediately visible (large overlays are often far from the
                // current view).
                store.overlays.value.lastOrNull()?.let { KmlOverlayEvents.requestZoom(it) }
                tmp.delete()
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Color(0xFF0F1115)) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                "Map Overlays",
                color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { picker.launch(arrayOf("*/*")) }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.FileDownload, contentDescription = null, tint = Color(0xFF4FA8FF))
                Spacer(Modifier.width(14.dp))
                Text("Import KML / KMZ", color = Color(0xFF4FA8FF), fontSize = 16.sp)
            }

            if (importing) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(status.ifEmpty { "Importing…" }, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                }
            }
            error?.let { Text(it, color = Color(0xFFFF6B6B), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }

            Text(
                "Big files render as one GPU vector layer and stay smooth. Overlays show on the 2D / terrain map.",
                color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

            if (overlays.isEmpty()) {
                Text(
                    "No overlays yet.",
                    color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                )
            } else {
                overlays.forEach { overlay ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        Box(
                            modifier = Modifier.size(14.dp).clip(CircleShape)
                                .background(runCatching { Color(android.graphics.Color.parseColor(overlay.colorHex)) }.getOrDefault(Color.Magenta)),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(overlay.name, color = Color.White, fontSize = 16.sp, maxLines = 1)
                            Text("${overlay.featureCount} features", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                        }
                        Icon(
                            Icons.Filled.MyLocation, contentDescription = "Zoom to",
                            tint = Color(0xFF4FA8FF),
                            modifier = Modifier.size(22.dp).clickable {
                                KmlOverlayEvents.requestZoom(overlay)
                                onDismiss()
                            },
                        )
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = overlay.visible, onCheckedChange = { store.setVisible(overlay.id, it) })
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Filled.Delete, contentDescription = "Delete",
                            tint = Color(0xFFFF3B30),
                            modifier = Modifier.size(22.dp).clickable { store.remove(overlay.id) },
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(start = 20.dp), color = Color.White.copy(alpha = 0.06f))
                }
            }
        }
    }
}

private fun queryDisplayName(context: android.content.Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
