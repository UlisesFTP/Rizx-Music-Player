package fm.rizx.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fm.rizx.player.BuildConfig
import fm.rizx.player.R
import fm.rizx.player.ui.components.clickableScale
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.theme.sg

/**
 * About / license. Preserves the AGPL notice, upstream attribution, and source link required by
 * Nuclear's AGPL-3.0 license (see repo CLAUDE.md licensing section).
 */
@Composable
fun AboutScreen(onBack: () -> Unit, onOpenLicenses: () -> Unit) {
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
                RizxIcons.Back, stringResource(R.string.about_cd_back), tint = c.text,
                modifier = Modifier.size(26.dp).clickableScale(scale = 0.88f, onClick = onBack),
            )
            Text(stringResource(R.string.about_title), style = sg(28, FontWeight.Bold, -0.02f), color = c.text)
        }

        Box(
            Modifier.padding(top = 20.dp).size(64.dp).clip(RectangleShape).background(c.accent),
            contentAlignment = Alignment.Center,
        ) {
            Text("R", style = sg(34, FontWeight.Bold), color = c.onFill)
        }
        Text("Rizx Player", style = sg(22, FontWeight.Bold, -0.01f), color = c.text, modifier = Modifier.padding(top = 12.dp))
        Text(
            stringResource(R.string.about_version_label, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            style = mr(13, FontWeight.Medium), color = c.muted, modifier = Modifier.padding(top = 2.dp),
        )

        Card {
            Label(stringResource(R.string.about_label_based_on))
            Body(stringResource(R.string.about_body_based_on))
        }

        Card {
            Label(stringResource(R.string.about_label_source))
            Body(stringResource(R.string.about_body_source_intro))
            Body("• Rizx Player: github.com/rizx-player/rizx-android")
            Body(stringResource(R.string.about_body_source_nuclear))
        }

        Card {
            Label(stringResource(R.string.about_label_license))
            Body(stringResource(R.string.about_body_license))
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
                .clip(RectangleShape)
                .background(c.elev)
                .border(1.dp, c.line, RectangleShape)
                .clickableScale(scale = 0.98f, onClick = onOpenLicenses)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Label(stringResource(R.string.about_label_open_source_licenses))
                Body(stringResource(R.string.about_body_third_party))
            }
            Icon(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.about_cd_open_licenses), tint = c.muted, modifier = Modifier.size(20.dp))
        }

        Spacer(Modifier.height(60.dp))
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    val c = RizxTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .clip(RectangleShape)
            .background(c.elev)
            .border(1.dp, c.line, RectangleShape)
            .padding(16.dp),
        content = content,
    )
}

@Composable
private fun Label(text: String) {
    Text(
        text.uppercase(),
        style = mr(11, FontWeight.Bold, 0.16f),
        color = RizxTheme.colors.muted,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun Body(text: String) {
    Text(text, style = mr(14, FontWeight.Medium), color = RizxTheme.colors.text2)
}
