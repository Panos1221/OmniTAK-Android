package soy.engindearing.omnitak.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import soy.engindearing.omnitak.mobile.OmniTAKApp
import soy.engindearing.omnitak.mobile.domain.AggregatedMission
import soy.engindearing.omnitak.mobile.domain.AggregatedPackage
import soy.engindearing.omnitak.mobile.domain.MissionServerStatus
import soy.engindearing.omnitak.mobile.domain.ServerSyncSession
import soy.engindearing.omnitak.mobile.ui.theme.HostileRed
import soy.engindearing.omnitak.mobile.ui.theme.TacticalAccent
import soy.engindearing.omnitak.mobile.ui.theme.TacticalBackground
import soy.engindearing.omnitak.mobile.ui.theme.TacticalSurface

/**
 * Multi-server Mission Sync UI, bound to [MissionSyncManager] (no stubs).
 * Shows every enabled server's live status and an aggregated list of missions
 * + data packages across all of them. Android counterpart to iOS's
 * MissionSyncView.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MissionSyncScreen(onBack: () -> Unit = {}) {
    val app = LocalContext.current.applicationContext as OmniTAKApp
    val manager = app.missionSyncManager
    val sessions by manager.sessions.collectAsState()
    val isRefreshing by manager.isRefreshing.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { manager.refreshAll() }

    val onlineCount = sessions.count { it.status.isOnline }
    val allMissions = sessions.flatMap { s ->
        s.missions.map { AggregatedMission(s.serverId, s.serverName, it) }
    }
    val allPackages = sessions.flatMap { s ->
        s.dataPackages.map { AggregatedPackage(s.serverId, s.serverName, it) }
    }

    Scaffold(
        containerColor = TacticalBackground,
        topBar = {
            TopAppBar(
                title = { Text("Mission Sync", color = MaterialTheme.colorScheme.onBackground) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { scope.launch { manager.refreshAll() } },
                        enabled = !isRefreshing,
                    ) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = TacticalAccent,
                            )
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = TacticalAccent)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TacticalBackground,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { inner: PaddingValues ->
        if (sessions.isEmpty()) {
            EmptyMissionSync(Modifier.padding(inner))
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                SectionHeader("SERVERS", "$onlineCount/${sessions.size} online")
            }
            items(sessions, key = { it.serverId }) { s ->
                ServerStatusRow(s, onTap = { scope.launch { manager.refresh(s.serverId) } })
            }

            if (allMissions.isNotEmpty()) {
                item { SectionHeader("MISSIONS", "${allMissions.size}") }
                items(allMissions, key = { it.id }) { m -> MissionRow(m) }
            }

            if (allPackages.isNotEmpty()) {
                item { SectionHeader("DATA PACKAGES", "${allPackages.size}") }
                items(allPackages, key = { it.id }) { p -> PackageRow(p) }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, trailing: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        Text(
            trailing,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun ServerStatusRow(s: ServerSyncSession, onTap: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(TacticalSurface)
            .clickable(onClick = onTap)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(s.status)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                s.serverName,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                statusLine(s),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (s.status.isOnline) {
            Text(
                "${s.missions.size}m · ${s.dataPackages.size}p",
                color = TacticalAccent,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun StatusDot(status: MissionServerStatus) {
    Box(modifier = Modifier.size(14.dp), contentAlignment = Alignment.Center) {
        when (status) {
            is MissionServerStatus.Checking -> CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 2.dp,
                color = TacticalAccent,
            )
            is MissionServerStatus.Online -> Dot(Color(0xFF4CAF50))
            is MissionServerStatus.Offline -> Dot(HostileRed)
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color),
    )
}

private fun statusLine(s: ServerSyncSession): String = when (val st = s.status) {
    is MissionServerStatus.Checking -> "${s.host} — checking…"
    is MissionServerStatus.Online -> s.host
    is MissionServerStatus.Offline -> "${s.host} — ${st.reason}"
}

@Composable
private fun MissionRow(item: AggregatedMission) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(TacticalSurface)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.mission.name,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            ServerBadge(item.serverName)
        }
        val desc = item.mission.description
        if (!desc.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                desc,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
            )
        }
        if (item.mission.contentCount > 0) {
            Spacer(Modifier.height(2.dp))
            val n = item.mission.contentCount
            Text(
                "$n item${if (n == 1) "" else "s"}",
                color = TacticalAccent,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun PackageRow(item: AggregatedPackage) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(TacticalSurface)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Inventory2,
            contentDescription = null,
            tint = TacticalAccent,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.pkg.name,
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            if (item.pkg.size > 0) {
                Text(
                    formatBytes(item.pkg.size),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        ServerBadge(item.serverName)
    }
}

@Composable
private fun ServerBadge(name: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(TacticalAccent.copy(alpha = 0.85f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            name,
            color = Color.Black,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun EmptyMissionSync(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.Sync,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = TacticalAccent.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "No servers enabled",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Enable a TLS TAK server with a client certificate in Servers, then " +
                "refresh. Every enabled server syncs here at once.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    return String.format("%.1f GB", mb / 1024.0)
}
