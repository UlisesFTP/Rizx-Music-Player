package fm.rizx.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Switch
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fm.rizx.player.domain.provider.ProviderHealth
import fm.rizx.player.domain.provider.ProviderKind
import fm.rizx.player.ui.components.clickableScale
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.ui.plugins.InstalledPluginRow
import fm.rizx.player.ui.plugins.PluginRow
import fm.rizx.player.ui.plugins.PluginsViewModel
import fm.rizx.player.ui.plugins.StoreRow
import fm.rizx.player.ui.plugins.StoreStatus
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.code
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.theme.sg

@Composable
fun PluginsScreen(vm: PluginsViewModel = hiltViewModel()) {
    val c = RizxTheme.colors
    val state by vm.state.collectAsStateWithLifecycle()
    var storeTab by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
    ) {
        Text("Plugins", style = sg(28, FontWeight.Bold, -0.02f), color = c.text, modifier = Modifier.padding(top = 12.dp))
        Text("Built-in providers Rizx searches, streams, and gets charts from.", style = mr(13, FontWeight.Medium), color = c.muted, modifier = Modifier.padding(top = 2.dp))

        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tab("Installed", !storeTab) { storeTab = false }
            Tab("Store", storeTab) { storeTab = true }
        }

        if (storeTab) {
            state.storeError?.let { Text(it, style = mr(12, FontWeight.Medium), color = c.muted, modifier = Modifier.padding(top = 12.dp)) }
            Section("Nuclear plugin store")
            state.store.forEach { row -> StoreRow(row, onInstall = { vm.install(row.id) }) }
        } else {
            if (state.installedPlugins.isNotEmpty()) {
                Section("Installed plugins")
                state.installedPlugins.forEach { p ->
                    InstalledPluginRow(
                        p,
                        onToggle = { vm.setPluginEnabled(p.id, it) },
                        onUninstall = { vm.uninstall(p.id) },
                    )
                }
            }
            ProviderKind.entries.forEach { kind ->
                val rows = state.rows.filter { it.kind == kind }
                if (rows.isNotEmpty()) {
                    Section(kind.name.lowercase().replaceFirstChar { it.uppercase() })
                    rows.forEach { row ->
                        InstalledRow(
                            row,
                            onSelect = { vm.setActive(row.kind, row.id) },
                            onToggle = { vm.setEnabled(row.id, it) },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(60.dp))
    }
}

@Composable
private fun Tab(label: String, active: Boolean, onClick: () -> Unit) {
    val c = RizxTheme.colors
    Text(
        label, style = mr(13, FontWeight.Bold), color = if (active) c.onFill else c.text2,
        modifier = Modifier
            .clip(RectangleShape)
            .background(if (active) c.fill else c.elev)
            .border(1.dp, c.line, RectangleShape)
            .clickableScale(scale = 0.95f, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun Section(title: String) =
    Text(title.uppercase(), style = code(11, FontWeight.Bold), color = RizxTheme.colors.muted, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))

@Composable
private fun InstalledRow(row: PluginRow, onSelect: () -> Unit, onToggle: (Boolean) -> Unit) {
    val c = RizxTheme.colors
    val selectable = row.singleActive && !row.active
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RectangleShape)
            .background(c.elev)
            .border(1.dp, if (row.singleActive && row.active) c.accent else c.line, RectangleShape)
            .then(if (selectable) Modifier.clickableScale(scale = 0.99f, onClick = onSelect) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(row.name, style = mr(15, FontWeight.SemiBold), color = c.text)
                Text("v${row.version}", style = mr(11, FontWeight.Bold, 0.02f), color = c.muted)
            }
            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HealthBadge(row.health)
                if (row.singleActive) Text(if (row.active) "Active" else "Tap to use", style = mr(12, FontWeight.Medium), color = c.muted)
            }
        }
        if (row.singleActive) {
            if (row.active) Icon(RizxIcons.Check, "Active", tint = c.redAccent, modifier = Modifier.size(22.dp))
        } else {
            Switch(checked = row.enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun StoreRow(row: StoreRow, onInstall: () -> Unit) {
    val c = RizxTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RectangleShape).background(c.elev).border(1.dp, c.line, RectangleShape).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("${row.displayName}  ·  ${row.category}", style = mr(15, FontWeight.SemiBold), color = c.text)
            if (row.description.isNotBlank()) {
                Text(row.description, style = mr(12, FontWeight.Medium), color = c.muted, modifier = Modifier.padding(top = 2.dp))
            }
        }
        val (label, actionable) = when (row.status) {
            StoreStatus.AVAILABLE -> "Install" to true
            StoreStatus.INSTALLING -> "Installing…" to false
            StoreStatus.INSTALLED -> "Installed" to false
            StoreStatus.ERROR -> "Retry" to true
        }
        Text(
            label,
            style = mr(12, FontWeight.Bold),
            color = if (actionable) c.onFill else c.muted,
            modifier = Modifier
                .clip(RectangleShape)
                .background(if (actionable) c.fill else c.inset)
                .clickableScale(scale = 0.94f) { if (actionable) onInstall() }
                .padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun InstalledPluginRow(row: InstalledPluginRow, onToggle: (Boolean) -> Unit, onUninstall: () -> Unit) {
    val c = RizxTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RectangleShape).background(c.elev).border(1.dp, c.line, RectangleShape).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("${row.name}  ·  v${row.version}", style = mr(15, FontWeight.SemiBold), color = c.text)
            Text("Plugin · ${row.category}", style = mr(12, FontWeight.Medium), color = c.muted, modifier = Modifier.padding(top = 2.dp))
        }
        Icon(
            Icons.Filled.DeleteOutline, "Uninstall", tint = c.text2,
            modifier = Modifier.size(22.dp).clickableScale(scale = 0.86f, onClick = onUninstall),
        )
        Switch(checked = row.enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun HealthBadge(health: ProviderHealth) {
    val c = RizxTheme.colors
    val (text, dot) = when (health) {
        is ProviderHealth.Ok -> "${health.latencyMs} ms" to c.accent
        is ProviderHealth.Down -> "Down" to c.redAccent
        ProviderHealth.Unknown -> "…" to c.muted
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Text(text, style = mr(12, FontWeight.Medium), color = c.muted)
    }
}
