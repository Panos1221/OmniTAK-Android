package soy.engindearing.omnitak.mobile.data.symbology

import androidx.compose.ui.graphics.Color

/**
 * MIL-STD-2525 affiliation (a-X-... in CoT type strings where X is the
 * second segment). Drives the SIDC second character and the frame
 * colour applied to the symbol.
 */
enum class Affiliation(val raw: String) {
    FRIENDLY("friendly"),
    HOSTILE("hostile"),
    NEUTRAL("neutral"),
    UNKNOWN("unknown");

    /** SIDC second-character code for this affiliation. */
    val sidcChar: Char
        get() = when (this) {
            FRIENDLY -> 'F'
            HOSTILE -> 'H'
            NEUTRAL -> 'N'
            UNKNOWN -> 'U'
        }

    /** Display label for UI surfaces. */
    val displayName: String
        get() = raw.replaceFirstChar { it.uppercase() }

    /** Fill colour for the symbol body (matches iOS palette). */
    val color: Color
        get() = when (this) {
            FRIENDLY -> Color(0xFF007AFF) // blue
            HOSTILE -> Color(0xFFFF3B30) // red
            NEUTRAL -> Color(0xFF34C759) // green
            UNKNOWN -> Color(0xFFFFCC00) // yellow
        }

    /** Frame colour (slightly desaturated variant; matches iOS). */
    val frameColor: Color
        get() = when (this) {
            FRIENDLY -> Color(red = 0.0f, green = 0.6f, blue = 1.0f, alpha = 1.0f)
            HOSTILE -> Color(red = 1.0f, green = 0.2f, blue = 0.2f, alpha = 1.0f)
            NEUTRAL -> Color(red = 0.0f, green = 0.8f, blue = 0.4f, alpha = 1.0f)
            UNKNOWN -> Color(red = 1.0f, green = 0.85f, blue = 0.0f, alpha = 1.0f)
        }

    companion object {
        /**
         * Resolve an affiliation from a CoT type string. CoT types are
         * dash-segmented; the second segment carries the affiliation
         * (f / h / n / u). Anything malformed falls back to UNKNOWN.
         */
        fun fromCotType(cotType: String): Affiliation {
            if (cotType.length < 3) return UNKNOWN
            return when (cotType[2]) {
                'f', 'F' -> FRIENDLY
                'h', 'H' -> HOSTILE
                'n', 'N' -> NEUTRAL
                'u', 'U' -> UNKNOWN
                else -> UNKNOWN
            }
        }

        /** Resolve by stored category string (matches iOS `category` field). */
        fun fromRaw(raw: String): Affiliation =
            values().firstOrNull { it.raw == raw } ?: UNKNOWN
    }
}
