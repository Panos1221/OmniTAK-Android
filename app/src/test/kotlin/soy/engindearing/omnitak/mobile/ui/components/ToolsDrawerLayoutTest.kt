package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure layout decisions for the map tools drawer (#182). The Compose UI
 * itself isn't unit-testable, but the orientation-driven sizing and the
 * horizontal-rail wrapping math are, so they're pinned here.
 */
class ToolsDrawerLayoutTest {

    @Test fun portrait_keeps_vertical_stack() {
        assertFalse(ToolsDrawerLayout.useHorizontalRail(isLandscape = false))
    }

    @Test fun landscape_uses_horizontal_rail() {
        assertTrue(ToolsDrawerLayout.useHorizontalRail(isLandscape = true))
    }

    @Test fun landscape_pips_are_smaller_than_portrait() {
        assertTrue(
            "landscape FAB must be no larger than portrait",
            ToolsDrawerLayout.fabSizeDp(true) <= ToolsDrawerLayout.fabSizeDp(false),
        )
        assertTrue(
            "landscape tool pip must be no larger than portrait",
            ToolsDrawerLayout.toolSizeDp(true) <= ToolsDrawerLayout.toolSizeDp(false),
        )
        assertTrue(
            "landscape spacing must be no larger than portrait",
            ToolsDrawerLayout.spacingDp(true) <= ToolsDrawerLayout.spacingDp(false),
        )
    }

    @Test fun toolsPerRow_fits_as_many_as_the_width_allows() {
        // 40dp pips + 8dp gaps: n pips + (n-1) gaps must fit in the width.
        // 5 pips = 200 + 4*8 = 232; 6 pips = 240 + 5*8 = 280.
        assertEquals(5, ToolsDrawerLayout.toolsPerRow(232, 40, 8))
        assertEquals(5, ToolsDrawerLayout.toolsPerRow(279, 40, 8))
        assertEquals(6, ToolsDrawerLayout.toolsPerRow(280, 40, 8))
    }

    @Test fun toolsPerRow_never_returns_less_than_one() {
        // A tool must never be orphaned off-screen even with a tiny width.
        assertEquals(1, ToolsDrawerLayout.toolsPerRow(0, 40, 8))
        assertEquals(1, ToolsDrawerLayout.toolsPerRow(10, 40, 8))
        assertEquals(1, ToolsDrawerLayout.toolsPerRow(-5, 40, 8))
    }

    @Test fun toolsPerRow_guards_against_zero_pip_size() {
        assertEquals(1, ToolsDrawerLayout.toolsPerRow(200, 0, 8))
    }

    @Test fun narrow_landscape_rail_wraps_a_full_tool_set_into_rows() {
        // On a narrow landscape rail (2/3 of a ~640dp-wide compact device),
        // a 10-tool set must wrap into more than one short row rather than
        // span one edge-to-edge row that re-eats the map.
        val railWidth = (640 * 2) / 3 // ≈ 426dp
        val perRow = ToolsDrawerLayout.toolsPerRow(
            availableWidthDp = railWidth,
            toolSizeDp = ToolsDrawerLayout.toolSizeDp(true),
            spacingDp = ToolsDrawerLayout.spacingDp(true),
        )
        assertTrue("rail should fit several tools per row", perRow >= 3)
        assertTrue("a 10-tool set should wrap into multiple rows here", perRow < 10)
    }
}
