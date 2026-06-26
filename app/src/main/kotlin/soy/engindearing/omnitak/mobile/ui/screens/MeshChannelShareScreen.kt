package soy.engindearing.omnitak.mobile.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import soy.engindearing.omnitak.mobile.OmniTAKApp
import soy.engindearing.omnitak.mobile.data.MeshChannel
import soy.engindearing.omnitak.mobile.data.MeshChannelImport
import soy.engindearing.omnitak.mobile.data.MeshChannelShare
import soy.engindearing.omnitak.mobile.data.MeshCoreChannel
import soy.engindearing.omnitak.mobile.data.MeshFramework
import soy.engindearing.omnitak.mobile.data.MeshShareTransport
import soy.engindearing.omnitak.mobile.data.ProfileQrGenerator
import soy.engindearing.omnitak.mobile.data.RebroadcastMode
import soy.engindearing.omnitak.mobile.data.UserPrefs

/**
 * #172 — Meshtastic/MeshCore channel settings: list channels, share one as a
 * URL + QR, paste/scan a share link to join, and push rebroadcast scope to the
 * radio. The active transport follows [UserPrefs.selectedMeshFramework] (the
 * same selector the mesh screen uses).
 *
 * Channels are held in screen-local state — there is no persisted channel
 * roster yet — seeded from whatever the operator imports or creates here.
 * "Apply" pushes a channel to the connected radio via the clean-room encoders
 * ([soy.engindearing.omnitak.mobile.data.AdminMessageSerializer.buildSetChannel]
 * / [soy.engindearing.omnitak.mobile.data.MeshCoreFrameCodec.buildSetChannel]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshChannelShareScreen(onBack: () -> Unit = {}) {
    val app = LocalContext.current.applicationContext as OmniTAKApp
    val scope = rememberCoroutineScope()
    val prefs by app.userPrefsStore.prefs.collectAsState(initial = UserPrefs())
    val transport = prefs.selectedMeshFramework

    var status by remember { mutableStateOf<String?>(null) }
    var joinText by remember { mutableStateOf("") }
    var shareUrl by remember { mutableStateOf<String?>(null) }

    // Screen-local channel rosters, one per transport.
    val meshtasticChannels = remember { mutableListOf<MeshChannel>().toMutableStateList() }
    val meshcoreChannels = remember { mutableListOf<MeshCoreChannel>().toMutableStateList() }

    fun join() {
        val parsed = MeshChannelShare.parse(joinText.trim())
        when (parsed) {
            is MeshChannelImport.Meshtastic -> {
                parsed.channels.forEach { ch ->
                    if (meshtasticChannels.none { it.name == ch.name }) meshtasticChannels.add(ch)
                }
                status = "Imported ${parsed.channels.size} Meshtastic channel(s)"
            }
            is MeshChannelImport.MeshCore -> {
                val ch = parsed.channel
                if (meshcoreChannels.none { it.name == ch.name }) meshcoreChannels.add(ch)
                status = "Imported MeshCore channel \"${ch.name}\""
            }
            null -> status = "Not a recognizable channel link"
        }
        joinText = ""
    }

    fun shareMeshtastic(ch: MeshChannel) {
        shareUrl = MeshChannelShare.shareURL(MeshShareTransport.MESHTASTIC, meshtastic = listOf(ch))
    }

    fun shareMeshcore(ch: MeshCoreChannel) {
        shareUrl = MeshChannelShare.shareURL(MeshShareTransport.MESHCORE, meshcore = ch)
    }

    fun applyMeshtastic(ch: MeshChannel, index: Int) {
        scope.launch {
            val ok = app.meshtastic.applyChannel(ch, index)
            status = if (ok) "Applied \"${ch.name}\" to radio" else "Apply failed — no radio connected"
        }
    }

    fun applyMeshcore(ch: MeshCoreChannel, index: Int) {
        scope.launch {
            val ok = app.meshcore.applyChannel(ch, index)
            status = if (ok) "Applied \"${ch.name}\" to radio" else "Apply failed — no radio connected"
        }
    }

    fun applyRebroadcast(mode: RebroadcastMode) {
        scope.launch {
            val ok = app.meshtastic.applyRebroadcastMode(mode)
            status = if (ok) "Rebroadcast set to ${mode.label}" else "Set rebroadcast failed — no radio"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mesh Channels") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Transport: ${if (transport == MeshFramework.MESHTASTIC) "Meshtastic" else "MeshCore"}",
                style = MaterialTheme.typography.labelLarge,
            )

            // Join: paste a share link.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Scan / paste to join", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = joinText,
                        onValueChange = { joinText = it },
                        label = { Text("meshtastic.org/e/#… or meshcore://channel/add?…") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Button(onClick = { join() }, enabled = joinText.isNotBlank()) {
                        Text("Join channel")
                    }
                }
            }

            // Channel list for the active transport.
            Text("Channels", style = MaterialTheme.typography.titleMedium)
            if (transport == MeshFramework.MESHTASTIC) {
                if (meshtasticChannels.isEmpty()) {
                    Text("No channels yet — join or create one above.", color = MaterialTheme.colorScheme.outline)
                }
                meshtasticChannels.forEachIndexed { index, ch ->
                    ChannelRow(
                        name = ch.name.ifEmpty { "(unnamed)" },
                        subtitle = "${ch.psk.size}-byte PSK · index $index",
                        onShare = { shareMeshtastic(ch) },
                        onApply = { applyMeshtastic(ch, index) },
                    )
                }
            } else {
                if (meshcoreChannels.isEmpty()) {
                    Text("No channels yet — join or create one above.", color = MaterialTheme.colorScheme.outline)
                }
                meshcoreChannels.forEachIndexed { index, ch ->
                    ChannelRow(
                        name = ch.name.ifEmpty { "(unnamed)" },
                        subtitle = if (ch.secret.isEmpty()) "public · index $index" else "${ch.secret.size}-byte secret · index $index",
                        onShare = { shareMeshcore(ch) },
                        onApply = { applyMeshcore(ch, index) },
                    )
                }
            }

            // Share output: URL + QR.
            shareUrl?.let { url ->
                HorizontalDivider()
                Text("Share link", style = MaterialTheme.typography.titleMedium)
                Text(url, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                val bmp = remember(url) { ProfileQrGenerator.renderQr(url, sizePx = 512) }
                bmp?.let {
                    Spacer(Modifier.height(8.dp))
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Channel QR",
                        modifier = Modifier
                            .size(240.dp)
                            .align(Alignment.CenterHorizontally),
                    )
                }
            }

            // Rebroadcast scope (Meshtastic only — admin set_config).
            if (transport == MeshFramework.MESHTASTIC) {
                HorizontalDivider()
                Text("Rebroadcast scope", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Limit which channels this radio relays for the mesh.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RebroadcastMode.entries.forEach { mode ->
                        OutlinedButton(onClick = { applyRebroadcast(mode) }) {
                            Text(mode.label)
                        }
                    }
                }
            }

            status?.let {
                HorizontalDivider()
                Text(it, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ChannelRow(
    name: String,
    subtitle: String,
    onShare: () -> Unit,
    onApply: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            OutlinedButton(onClick = onShare) { Text("Share") }
            Button(onClick = onApply) { Text("Apply") }
        }
    }
}
