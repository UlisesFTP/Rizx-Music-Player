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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fm.rizx.player.R
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
        Text(stringResource(R.string.plugins_title), style = sg(28, FontWeight.Bold, -0.02f), color = c.text, modifier = Modifier.padding(top = 12.dp))
        Text(stringResource(R.string.plugins_subtitle), style = mr(13, FontWeight.Medium), color = c.muted, modifier = Modifier.padding(top = 2.dp))

        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tab(stringResource(R.string.plugins_tab_installed), !storeTab) { storeTab = false }
            Tab(stringResource(R.string.plugins_tab_store), storeTab) { storeTab = true }
        }

        var sideloadOpen by remember { mutableStateOf(false) }
        var registryOpen by remember { mutableStateOf(false) }

        if (storeTab) {
            // state.storeError is sourced from the ViewModel; left as a raw literal there (not localized here).
            state.storeError?.let { Text(it, style = mr(12, FontWeight.Medium), color = c.muted, modifier = Modifier.padding(top = 12.dp)) }
            // Bundled with the app: no network, no URL to paste, and nothing published anywhere. Absent
            // when the build carries none, which is the case for a clone of the public repository.
            if (state.bundled.isNotEmpty()) {
                Section(stringResource(R.string.plugins_section_bundled))
                state.bundled.forEach { row -> StoreRow(row, onInstall = { vm.installBundled(row.id) }) }
            }

            // Grouped by category rather than one flat list of fifteen. The category used to be glued to
            // the name on each row, where it read as part of it; as a heading it does the job it was for.
            Section(stringResource(R.string.plugins_section_nuclear_store))
            state.store.groupBy { it.category }.toSortedMap().forEach { (category, rows) ->
                CategoryLabel(category)
                rows.forEach { row -> StoreRow(row, onInstall = { vm.install(row.id) }) }
            }

            Section(stringResource(R.string.plugins_section_open))
            ActionRow(
                title = stringResource(R.string.plugins_sideload_title),
                subtitle = stringResource(R.string.plugins_sideload_subtitle),
                busy = state.sideloadBusy,
            ) { sideloadOpen = true }
            ActionRow(
                title = stringResource(R.string.plugins_add_registry),
                subtitle = stringResource(R.string.plugins_add_registry_subtitle),
            ) { registryOpen = true }
            state.registries.forEach { url ->
                RegistryRow(url, onRemove = { vm.removeRegistry(url) })
            }
        } else {
            if (state.installedPlugins.isNotEmpty()) {
                Section(stringResource(R.string.plugins_section_installed_plugins))
                state.installedPlugins.forEach { p ->
                    InstalledPluginRow(
                        p,
                        onToggle = { vm.setPluginEnabled(p.id, it) },
                        onUninstall = { vm.uninstall(p.id) },
                        onUpdate = { vm.update(p.id) },
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

        if (sideloadOpen) {
            UrlInputDialog(
                title = stringResource(R.string.plugins_sideload_title),
                hint = stringResource(R.string.plugins_sideload_hint),
                confirmLabel = stringResource(R.string.plugins_install),
                onConfirm = { vm.installFromUrl(it) },
                onDismiss = { sideloadOpen = false },
            )
        }
        if (registryOpen) {
            UrlInputDialog(
                title = stringResource(R.string.plugins_add_registry),
                hint = stringResource(R.string.plugins_add_registry_hint),
                confirmLabel = stringResource(R.string.plugins_action_add),
                onConfirm = { vm.addRegistry(it) },
                onDismiss = { registryOpen = false },
            )
        }
    }
}

/** One URL in, one action out — sideload and add-registry share it. */
@Composable
private fun UrlInputDialog(
    title: String,
    hint: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                singleLine = true,
                placeholder = { Text(hint) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(url); onDismiss() }, enabled = url.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ActionRow(title: String, subtitle: String, busy: Boolean = false, onClick: () -> Unit) {
    val c = RizxTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RectangleShape).background(c.elev).border(1.dp, c.line, RectangleShape)
            .clickableScale(scale = 0.99f) { if (!busy) onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = mr(15, FontWeight.SemiBold), color = c.text)
            Text(
                if (busy) stringResource(R.string.plugins_installing) else subtitle,
                style = mr(12, FontWeight.Medium), color = c.muted, modifier = Modifier.padding(top = 2.dp),
            )
        }
        Icon(Icons.Filled.Add, title, tint = c.text2, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun RegistryRow(url: String, onRemove: () -> Unit) {
    val c = RizxTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RectangleShape).background(c.inset).border(1.dp, c.line, RectangleShape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(url, style = mr(12, FontWeight.Medium), color = c.text2, modifier = Modifier.weight(1f), maxLines = 1)
        Icon(
            Icons.Filled.Close, stringResource(R.string.plugins_remove_registry), tint = c.text2,
            modifier = Modifier.size(18.dp).clickableScale(scale = 0.86f, onClick = onRemove),
        )
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
                if (row.singleActive) Text(if (row.active) stringResource(R.string.plugins_active) else stringResource(R.string.plugins_tap_to_use), style = mr(12, FontWeight.Medium), color = c.muted)
            }
        }
        if (row.singleActive) {
            if (row.active) Icon(RizxIcons.Check, stringResource(R.string.plugins_active), tint = c.redAccent, modifier = Modifier.size(22.dp))
        } else {
            Switch(checked = row.enabled, onCheckedChange = onToggle)
        }
    }
}

/** A category heading inside the store — quieter than [Section], which separates whole areas. */
@Composable
private fun CategoryLabel(category: String) {
    Text(
        category.replaceFirstChar { it.uppercase() },
        style = code(11),
        color = RizxTheme.colors.muted,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
    )
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
            // The name on its own line. It used to share one string with the category — "Bandcamp
            // Dashboard · dashboard" — which read as part of the name and pushed the real name onto a
            // second line on every long entry. The category is now the section this row sits under.
            Text(row.displayName, style = mr(15, FontWeight.SemiBold), color = c.text)
            if (row.description.isNotBlank()) {
                Text(row.description, style = mr(12, FontWeight.Medium), color = c.muted, modifier = Modifier.padding(top = 2.dp))
            }
        }
        val (label, actionable) = when (row.status) {
            StoreStatus.AVAILABLE -> stringResource(R.string.plugins_install) to true
            StoreStatus.INSTALLING -> stringResource(R.string.plugins_installing) to false
            StoreStatus.INSTALLED -> stringResource(R.string.plugins_installed) to false
            StoreStatus.ERROR -> stringResource(R.string.action_retry) to true
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
private fun InstalledPluginRow(
    row: InstalledPluginRow,
    onToggle: (Boolean) -> Unit,
    onUninstall: () -> Unit,
    onUpdate: () -> Unit,
) {
    val c = RizxTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RectangleShape).background(c.elev)
            .border(1.dp, if (row.quarantined) c.redAccent else c.line, RectangleShape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("${row.name}  ·  v${row.version}", style = mr(15, FontWeight.SemiBold), color = c.text)
            if (row.quarantined) {
                Text(
                    stringResource(R.string.plugins_quarantined) + if (row.lastError.isNotBlank()) " · ${row.lastError}" else "",
                    style = mr(12, FontWeight.Medium), color = c.redAccent, modifier = Modifier.padding(top = 2.dp),
                )
            } else {
                Text(stringResource(R.string.plugins_plugin_category, row.category), style = mr(12, FontWeight.Medium), color = c.muted, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (row.canUpdate) {
            Icon(
                Icons.Filled.Refresh, stringResource(R.string.plugins_update), tint = c.text2,
                modifier = Modifier.size(22.dp).clickableScale(scale = 0.86f, onClick = onUpdate),
            )
        }
        Icon(
            Icons.Filled.DeleteOutline, stringResource(R.string.plugins_uninstall), tint = c.text2,
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
        is ProviderHealth.Down -> stringResource(R.string.plugins_health_down) to c.redAccent
        ProviderHealth.Unknown -> "…" to c.muted
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Text(text, style = mr(12, FontWeight.Medium), color = c.muted)
    }
}
