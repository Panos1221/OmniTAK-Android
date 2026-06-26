package soy.engindearing.omnitak.mobile.ui.components

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import soy.engindearing.omnitak.mobile.ui.theme.TacticalAccent
import soy.engindearing.omnitak.mobile.ui.theme.TacticalBackground

data class ToolEntry(
    val id: String,
    val icon: ImageVector,
    val label: String,
    val enabled: Boolean = true,
)

/**
 * Expanding tool rail anchored to the bottom-right of the map. Tapping
 * the main FAB toggles the icon stack (drawing, measure, layers, chat,
 * etc.). [onSelect] fires with the tool id.
 *
 * Collapse-by-default behind the FAB, so the map owns the screen until
 * the operator taps to reveal the tools — and every tool stays one tap
 * away in both orientations.
 *
 * #182 — orientation aware. Portrait keeps the familiar vertical stack
 * that grows upward from the FAB. Landscape (the plate-carrier
 * orientation on a Pixel 9/10 Pro) has little vertical room, so the same
 * stack used to overrun the short map and draw under the status bar.
 * There the expanded tools flow into a compact horizontal rail that
 * grows sideways (wrapping into extra rows if needed) and uses tighter
 * pip sizing, leaving the map its height.
 *
 * The drawer only renders itself when its host allows — callers place it
 * in a Box on top of the map.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolsDrawer(
    tools: List<ToolEntry>,
    onSelect: (ToolEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val toolSize = ToolsDrawerLayout.toolSizeDp(isLandscape).dp
    val fabSize = ToolsDrawerLayout.fabSizeDp(isLandscape).dp
    val spacing = ToolsDrawerLayout.spacingDp(isLandscape).dp

    val fab: @Composable () -> Unit = {
        // Main FAB — always visible; swaps to a close icon when expanded.
        Box(
            modifier = Modifier
                .size(fabSize)
                .clip(CircleShape)
                .background(TacticalAccent)
                .clickable { expanded = !expanded },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (expanded) Icons.Filled.Close else Icons.Filled.Menu,
                contentDescription = if (expanded) "Close tools" else "Open tools",
                tint = TacticalBackground,
            )
        }
    }

    if (ToolsDrawerLayout.useHorizontalRail(isLandscape)) {
        // Landscape: the FAB pins to the bottom-right; the expanded tools
        // flow leftward in a wrapping rail above it. maxItemsInEachRow caps
        // the rail width (using the screen width) so it can't stretch
        // edge-to-edge and re-eat the map; extra tools wrap to a new row.
        val screenWidthDp = LocalConfiguration.current.screenWidthDp
        // Leave the right third for the map; pad both ends out of the rail box.
        val railWidthDp = (screenWidthDp * 2) / 3
        val perRow = ToolsDrawerLayout.toolsPerRow(
            availableWidthDp = railWidthDp,
            toolSizeDp = ToolsDrawerLayout.toolSizeDp(isLandscape),
            spacingDp = ToolsDrawerLayout.spacingDp(isLandscape),
        )
        Column(
            modifier = modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp, top = 12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(120)) + scaleIn(tween(120)),
                exit = fadeOut(tween(120)) + scaleOut(tween(120)),
            ) {
                FlowRow(
                    modifier = Modifier.widthIn(max = railWidthDp.dp),
                    horizontalArrangement = Arrangement.spacedBy(spacing, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(spacing),
                    maxItemsInEachRow = perRow,
                ) {
                    tools.forEach { tool ->
                        ToolIcon(tool, size = toolSize) {
                            expanded = false
                            onSelect(tool)
                        }
                    }
                }
            }
            fab()
        }
    } else {
        // Portrait: unchanged — vertical stack growing upward from the FAB.
        Column(
            modifier = modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            tools.forEach { tool ->
                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn(tween(120)) + scaleIn(tween(120)),
                    exit = fadeOut(tween(120)) + scaleOut(tween(120)),
                ) {
                    ToolIcon(tool, size = toolSize) {
                        expanded = false
                        onSelect(tool)
                    }
                }
            }
            fab()
        }
    }
}

@Composable
private fun ToolIcon(tool: ToolEntry, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(TacticalBackground.copy(alpha = 0.9f))
            .clickable(enabled = tool.enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = tool.icon,
            contentDescription = tool.label,
            tint = if (tool.enabled) TacticalAccent else Color.Gray,
        )
    }
}
