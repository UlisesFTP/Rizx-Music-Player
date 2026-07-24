package fm.rizx.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fm.rizx.player.R
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.ui.navigation.Routes
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.brutalShadow
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.util.rememberRizxHaptics

private data class NavTab(val route: String, val labelRes: Int, val icon: ImageVector)

private val navTabs = listOf(
    NavTab(Routes.HOME, R.string.nav_home, RizxIcons.Home),
    NavTab(Routes.SEARCH, R.string.nav_search, RizxIcons.Search),
    NavTab(Routes.LIBRARY, R.string.nav_library, RizxIcons.Library),
    NavTab(Routes.SETTINGS, R.string.nav_settings, RizxIcons.Settings),
)

@Composable
fun RizxBottomNav(
    currentRoute: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = RizxTheme.colors
    val activeTab = Routes.activeTab(currentRoute)
    val haptics = rememberRizxHaptics()
    Row(
        modifier
            .fillMaxWidth()
            .height(62.dp)
            .brutalShadow(c.shadowHard, offset = 4.dp)
            .clip(RectangleShape)
            .background(c.navBg)
            .border(1.5.dp, c.hardLine, RectangleShape),
    ) {
        navTabs.forEach { tab ->
            val active = tab.route == activeTab
            val tint by animateColorAsState(if (active) c.accent else c.dim, tween(220), label = "navTint")
            val ind by animateFloatAsState(if (active) 1f else 0f, tween(220), label = "navInd")
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickableScale(scale = 0.9f, haptic = false) {
                        haptics.select()
                        onSelect(tab.route)
                    },
                contentAlignment = Alignment.Center,
            ) {
                // Dot-matrix active indicator (3 dots) that fades + scales in.
                Row(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 7.dp)
                        .graphicsLayer { alpha = ind; scaleX = 0.5f + 0.5f * ind },
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    repeat(3) { Box(Modifier.size(3.dp).background(c.redAccent)) }
                }
                val label = stringResource(tab.labelRes)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(tab.icon, label, tint = tint, modifier = Modifier.size(23.dp))
                    Text(
                        label.uppercase(),
                        style = mr(9, if (active) FontWeight.Bold else FontWeight.Medium, 0.1f),
                        color = tint,
                    )
                }
            }
        }
    }
}
