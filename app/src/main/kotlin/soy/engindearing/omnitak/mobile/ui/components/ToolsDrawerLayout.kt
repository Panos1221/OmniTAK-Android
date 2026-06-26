package soy.engindearing.omnitak.mobile.ui.components

/**
 * Pure layout logic for the map [ToolsDrawer], split out so it can be
 * unit-tested without a Compose harness (UI layout itself isn't unit
 * testable; this captures the orientation/size decisions that are).
 *
 * #182 — on a plate carrier (Pixel 9/10 Pro) landscape is the most likely
 * orientation, and the vertical tool rail used to grow tall enough to eat
 * most of the (short) landscape map. In landscape we keep the drawer
 * collapsed behind its FAB and, when expanded, flow the tools into a
 * horizontal rail (multiple short rows) instead of one tall column — so
 * the map keeps its vertical space and every tool stays one tap away.
 */
object ToolsDrawerLayout {

    /** Compact pip + FAB sizing for landscape; the roomier portrait sizing otherwise. */
    const val FAB_SIZE_PORTRAIT_DP = 56
    const val FAB_SIZE_LANDSCAPE_DP = 48
    const val TOOL_SIZE_PORTRAIT_DP = 48
    const val TOOL_SIZE_LANDSCAPE_DP = 40
    const val SPACING_PORTRAIT_DP = 12
    const val SPACING_LANDSCAPE_DP = 8

    /**
     * True when the expanded tool stack should flow horizontally (a rail
     * that grows sideways, wrapping into extra rows) rather than stacking
     * straight up. Landscape only — portrait keeps the familiar vertical
     * stack that's never been a height problem.
     */
    fun useHorizontalRail(isLandscape: Boolean): Boolean = isLandscape

    fun fabSizeDp(isLandscape: Boolean): Int =
        if (isLandscape) FAB_SIZE_LANDSCAPE_DP else FAB_SIZE_PORTRAIT_DP

    fun toolSizeDp(isLandscape: Boolean): Int =
        if (isLandscape) TOOL_SIZE_LANDSCAPE_DP else TOOL_SIZE_PORTRAIT_DP

    fun spacingDp(isLandscape: Boolean): Int =
        if (isLandscape) SPACING_LANDSCAPE_DP else SPACING_PORTRAIT_DP

    /**
     * Max tools per row of the horizontal landscape rail. Caps the rail's
     * width to a fraction of the screen so it can't span edge-to-edge and
     * re-eat the map; tools beyond the cap wrap onto the next (upward) row.
     *
     * [availableWidthDp] is the usable width the rail may occupy (already
     * net of side padding); [toolSizeDp]/[spacingDp] are the pip metrics.
     * Always returns at least 1 so a tool is never orphaned off-screen.
     */
    fun toolsPerRow(availableWidthDp: Int, toolSizeDp: Int, spacingDp: Int): Int {
        if (availableWidthDp <= 0 || toolSizeDp <= 0) return 1
        val per = (availableWidthDp + spacingDp) / (toolSizeDp + spacingDp)
        return per.coerceAtLeast(1)
    }
}
