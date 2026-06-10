package soy.engindearing.omnitak.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import soy.engindearing.omnitak.mobile.OmniTAKApp
import soy.engindearing.omnitak.mobile.ui.theme.TacticalAccent
import soy.engindearing.omnitak.plugin.PluginRegistry

/**
 * Detail page for a single plugin, reached from the Settings → Plugins row a
 * plugin registers, or from [PluginsListScreen]. Shows metadata, the
 * enable/disable toggle (persisted to the `plugin_<id>_enabled` SharedPreferences
 * flag), and the plugin's own [settingsContent].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginDetailScreen(pluginId: String, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as OmniTAKApp
    val plugin = remember(pluginId) { PluginRegistry.all().firstOrNull { it.pluginId == pluginId } }
    val prefs = remember { app.pluginPrefs() }
    var enabled by remember {
        mutableStateOf(prefs.getBoolean(PluginRegistry.enabledKey(pluginId), true))
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(plugin?.displayName ?: "Plugin") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = TacticalAccent,
                ),
            )
        },
    ) { inner ->
        if (plugin == null) {
            Column(modifier = Modifier.fillMaxSize().padding(inner).padding(16.dp)) {
                Text("Plugin not found: $pluginId", color = MaterialTheme.colorScheme.error)
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Enabled", color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        if (enabled) "Plugin is active" else "Plugin is disabled",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { on ->
                        enabled = on
                        prefs.edit().putBoolean(PluginRegistry.enabledKey(pluginId), on).apply()
                        val host = app.pluginHost
                        if (on) {
                            PluginRegistry.activate(
                                pluginId = pluginId,
                                host = host.asPluginHost(),
                                onActivating = { id, activate -> host.withActivating(id) { activate() } },
                            )
                        } else {
                            PluginRegistry.deactivate(pluginId)
                            host.clearForPlugin(pluginId)
                        }
                    },
                )
            }

            MetaRow("Version", plugin.pluginVersion)
            MetaRow("Author", plugin.pluginAuthor)
            MetaRow("ID", plugin.pluginId)
            Spacer(Modifier.height(8.dp))
            Text(
                plugin.pluginDescription,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodyMedium,
            )

            // Host-process / no-sandbox disclosure, per the SDK security model.
            Spacer(Modifier.height(8.dp))
            Text(
                "Runs in the app process with the app's permissions — no sandbox.",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(12.dp))
            // The plugin's own settings UI (if any). Only meaningful when the
            // plugin is enabled, but we render it regardless so the operator
            // can see what it offers.
            if (enabled) {
                plugin.settingsContent()?.invoke()
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(value, color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.bodySmall)
    }
}
