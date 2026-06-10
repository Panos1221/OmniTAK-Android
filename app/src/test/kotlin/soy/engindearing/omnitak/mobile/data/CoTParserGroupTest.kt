package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies the `<__group>` team-color pipeline:
 *  - [TakTeamColor] maps all 14 canonical TAK team names to non-null ARGB values.
 *  - [CoTEvent.displayColor] returns the team color when [CoTEvent.teamName] is
 *    set, and falls back to MIL-STD affiliation color when it is absent.
 *  - [CoTEvent.displayColor] is case-insensitive (teams arrive as "Red" / "red").
 *
 * Note: [CoTParser.parse] is XmlPullParserFactory-backed and runs on the
 * JVM host test runner too. The data-model and color-resolution logic
 * tested here is purely JVM-safe.
 */
class CoTParserGroupTest {

    // --- TakTeamColor palette ---

    private val allTeamNames = listOf(
        "White", "Yellow", "Orange", "Magenta", "Red", "Maroon",
        "Purple", "Dark Blue", "Blue", "Cyan", "Teal", "Green",
        "Dark Green", "Brown",
    )

    @Test fun `all 14 canonical team names resolve to a color`() {
        for (name in allTeamNames) {
            assertNotNull("Expected non-null color for team '$name'", TakTeamColor.forName(name))
        }
    }

    @Test fun `team name lookup is case-insensitive`() {
        assertEquals(TakTeamColor.forName("Red"), TakTeamColor.forName("red"))
        assertEquals(TakTeamColor.forName("Red"), TakTeamColor.forName("RED"))
        assertEquals(TakTeamColor.forName("Dark Blue"), TakTeamColor.forName("dark blue"))
    }

    @Test fun `unknown team name returns null`() {
        assertNull(TakTeamColor.forName("NotATeam"))
        assertNull(TakTeamColor.forName(null))
        assertNull(TakTeamColor.forName(""))
    }

    // --- CoTEvent.displayColor team-override ---

    private fun friendEvent(teamName: String? = null) = CoTEvent(
        uid = "test-uid",
        type = "a-f-G-U",   // Friend ground unit
        lat = 47.65,
        lon = -117.42,
        callsign = "Red-1",
        teamName = teamName,
    )

    @Test fun `displayColor returns team color when teamName is set`() {
        val event = friendEvent(teamName = "Red")
        val expected = TakTeamColor.forName("Red")!!
        assertEquals(
            "Red-team event should display red (#FF0000), not affiliation green",
            expected,
            event.displayColor,
        )
    }

    @Test fun `displayColor falls back to affiliation color when teamName is absent`() {
        val event = friendEvent(teamName = null)
        // Friend affiliation → 0xFF4ADE80
        assertEquals(0xFF4ADE80.toInt(), event.displayColor)
    }

    @Test fun `displayColor for Orange team matches palette`() {
        val event = friendEvent(teamName = "Orange")
        assertEquals(TakTeamColor.forName("Orange"), event.displayColor)
    }

    @Test fun `displayColor for unrecognised team falls back to affiliation`() {
        val event = friendEvent(teamName = "Ultraviolet")
        // Falls back — friend affiliation
        assertEquals(0xFF4ADE80.toInt(), event.displayColor)
    }

    // --- CoTEvent field defaults ---

    @Test fun `teamName and teamRole default to null`() {
        val event = CoTEvent(uid = "u", type = "a-u-G", lat = 0.0, lon = 0.0)
        assertNull(event.teamName)
        assertNull(event.teamRole)
    }
}
