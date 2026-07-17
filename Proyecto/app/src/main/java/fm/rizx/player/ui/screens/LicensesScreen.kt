package fm.rizx.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fm.rizx.player.ui.components.clickableScale
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.theme.sg

/**
 * Open-source dependency license report (About → Open-source licenses). Surfaces
 * [LicenseData] to satisfy the AGPL attribution / third-party acknowledgement obligation (spec 014).
 */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val c = RizxTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                RizxIcons.Back, "Back", tint = c.text,
                modifier = Modifier.size(26.dp).clickableScale(scale = 0.88f, onClick = onBack),
            )
            Text("Open-source licenses", style = sg(24, FontWeight.Bold, -0.02f), color = c.text)
        }

        Text(
            "Rizx Player is licensed under AGPL-3.0. It bundles the open-source libraries below, " +
                "each under its own license.",
            style = mr(14, FontWeight.Medium),
            color = c.text2,
            modifier = Modifier.padding(top = 14.dp),
        )

        Section("Bundled in the app")
        LicenseData.runtime.forEach { LicenseRow(it.name, it.version, it.license) }

        Section("Test-only (not shipped)")
        LicenseData.testOnly.forEach { LicenseRow(it.name, it.version, it.license) }

        Section("License texts")
        LinkBody("Apache-2.0 — apache.org/licenses/LICENSE-2.0")
        LinkBody("EPL-1.0 — eclipse.org/legal/epl-v10.html")
        LinkBody("AGPL-3.0 (this app) — gnu.org/licenses/agpl-3.0.html")

        Spacer(Modifier.height(60.dp))
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title.uppercase(),
        style = mr(11, FontWeight.Bold, 0.16f),
        color = RizxTheme.colors.muted,
        modifier = Modifier.padding(top = 22.dp, bottom = 8.dp),
    )
}

@Composable
private fun LicenseRow(name: String, version: String, license: String) {
    val c = RizxTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RectangleShape)
            .background(c.elev)
            .border(1.dp, c.line, RectangleShape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = mr(14, FontWeight.SemiBold), color = c.text)
            Text(version, style = mr(12, FontWeight.Medium), color = c.muted, modifier = Modifier.padding(top = 1.dp))
        }
        Text(license, style = mr(12, FontWeight.Bold, 0.02f), color = c.text2)
    }
}

@Composable
private fun LinkBody(text: String) {
    Text(text, style = mr(13, FontWeight.Medium), color = RizxTheme.colors.text2, modifier = Modifier.padding(bottom = 4.dp))
}
