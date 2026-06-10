package soy.engindearing.omnitak.mobile.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import soy.engindearing.omnitak.mobile.OmniTAKApp
import soy.engindearing.omnitak.mobile.ui.theme.TacticalAccent
import soy.engindearing.omnitak.plugin.PluginRegistry

/**
 * Top-level Plugins list. Reads straight from [PluginRegistry.all], shows each
 * plugin with an enable toggle (the `plugin_<id>_enabled` SharedPreferences
 * flag), and rows into each plugin's [PluginDetailScreen] (which renders its
 * settingsContent). Mirrors the iOS Plugins list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsListScreen(onBack: () -> Unit, onOpenPlugin: (String) -> Unit) {
    val app = LocalContext.current.applicationContext as OmniTAKApp
    val prefs = remember { app.pluginPrefs() }
    val plugins = remember { PluginRegistry.all() }
    val enabledState = remember {
        mutableStateMapOf<String, Boolean>().apply {
            plugins.forEach { put(it.pluginId, prefs.getBoolean(PluginRegistry.enabledKey(it.pluginId), true)) }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Plugins", color = MaterialTheme.colorScheme.onBackground) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Bundled, compile-time plugins. They run in the app process with " +
                    "the app's permissions (no sandbox). No code is ever downloaded.",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))

            if (plugins.isEmpty()) {
                Text(
                    "No plugins installed.",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }

            plugins.forEach { plugin ->
                val enabled = enabledState[plugin.pluginId] ?: true
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onOpenPlugin(plugin.pluginId) }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Extension,
                        contentDescription = null,
                        tint = TacticalAccent,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.size(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(plugin.displayName, color = MaterialTheme.colorScheme.onBackground)
                        Text(
                            "v${plugin.pluginVersion} · ${plugin.pluginAuthor}",
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { on ->
                            enabledState[plugin.pluginId] = on
                            prefs.edit().putBoolean(PluginRegistry.enabledKey(plugin.pluginId), on).apply()
                            val host = app.pluginHost
                            if (on) {
                                PluginRegistry.activate(
                                    pluginId = plugin.pluginId,
                                    host = host.asPluginHost(),
                                    onActivating = { id, activate -> host.withActivating(id) { activate() } },
                                )
                            } else {
                                PluginRegistry.deactivate(plugin.pluginId)
                                host.clearForPlugin(plugin.pluginId)
                            }
                        },
                    )
                    Spacer(Modifier.size(8.dp))
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
