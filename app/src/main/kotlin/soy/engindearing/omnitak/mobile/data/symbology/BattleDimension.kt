package soy.engindearing.omnitak.mobile.data.symbology

/**
 * MIL-STD-2525 battle dimension (a-X-Y-... in CoT type strings where Y
 * is the third segment). Drives the SIDC third-character code and the
 * frame shape — air gets an arc-top frame, ground a rectangle, sea a
 * rounded rectangle, etc.
 */
enum class BattleDimension(val sidcChar: Char) {
    AIR('A'),
    GROUND('G'),
    SEA('S'),
    SUBSURFACE('U'),
    SPACE('P'),
    OTHER('X');

    val displayName: String
        get() = when (this) {
            AIR -> "Air"
            GROUND -> "Ground"
            SEA -> "Sea/Surface"
            SUBSURFACE -> "Subsurface"
            SPACE -> "Space"
            OTHER -> "Other"
        }

    companion object {
        /**
         * Resolve a battle dimension from a CoT type string. CoT types
         * are dash-segmented; the third segment carries the dimension
         * (A / G / S / U / P / X). Anything malformed falls back to OTHER.
         */
        fun fromCotType(cotType: String): BattleDimension {
            if (cotType.length < 5) return OTHER
            return when (cotType[4]) {
                'A', 'a' -> AIR
                'G', 'g' -> GROUND
                'S', 's' -> SEA
                'U', 'u' -> SUBSURFACE
                'P', 'p' -> SPACE
                else -> OTHER
            }
        }
    }
}
