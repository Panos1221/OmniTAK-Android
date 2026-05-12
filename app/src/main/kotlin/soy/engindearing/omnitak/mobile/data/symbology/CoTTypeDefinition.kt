package soy.engindearing.omnitak.mobile.data.symbology

/**
 * One entry in the canonical CoT-type → MIL-STD-2525 SIDC catalogue.
 *
 * Mirrors the Swift `CoTTypeDefinition` on iOS so the YAML/JSON
 * catalogue introduced in Phase C can deserialise into either shape
 * without per-platform schema drift.
 *
 * @property value CoT type string, e.g. `a-f-G-U-C-I` (friendly infantry).
 * @property sidc SIDC asset filename, e.g. `SFGPUCI----.svg`.
 * @property label Short human label for menus.
 * @property description Longer human description for tooltips.
 * @property category Affiliation bucket as raw string ("friendly" /
 *   "hostile" / "neutral" / "unknown"); the [Affiliation] enum
 *   resolves this.
 */
data class CoTTypeDefinition(
    val value: String,
    val sidc: String,
    val label: String,
    val description: String,
    val category: String,
) {
    /** SIDC code with the `.svg` suffix stripped. */
    val sidcCode: String
        get() = sidc.removeSuffix(".svg")

    /** Affiliation resolved from the [category] raw string. */
    val affiliation: Affiliation
        get() = Affiliation.fromRaw(category)
}
