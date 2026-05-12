package soy.engindearing.omnitak.mobile.data.symbology

/**
 * Maps CoT types to MIL-STD-2525 SIDC codes and resolves the SVG
 * asset path for the matching symbol.
 *
 * Mirrors the iOS `MilStdIconService` so both platforms agree on
 * which SIDC any given CoT type renders as. The hardcoded catalogue
 * here is a Phase A floor that matches the current iOS list verbatim;
 * Phase C replaces it with a YAML load from the shared canonical
 * `cot_types.yaml` so the two platforms can't drift.
 *
 * ## Lookup rules
 * 1. Exact match on the full CoT type.
 * 2. Progressive truncation — strip the trailing `-segment`
 *    repeatedly until a known entry is found or we hit < 3 chars.
 *    Lets a specific type like `a-f-G-U-C-I-X` fall back to its
 *    parent `a-f-G-U-C-I`.
 * 3. Per-affiliation fallback SIDC. Always returns *something*; the
 *    map should never render a missing icon.
 */
object MilStdIconService {

    /** SVG asset directory under `app/src/main/assets/`. */
    private const val ASSETS_DIR = "milstd"

    private val fallbackSidc: Map<Affiliation, String> = mapOf(
        Affiliation.FRIENDLY to "SFGPU------",
        Affiliation.HOSTILE to "SHGPU------",
        Affiliation.NEUTRAL to "SNGPU------",
        Affiliation.UNKNOWN to "SUGPU------",
    )

    private val defaultDefinitions: List<CoTTypeDefinition> = listOf(
        // Friendly Ground Units
        CoTTypeDefinition("a-f-G-U", "SFGPU------.svg", "Friendly Ground - Generic", "Generic friendly marker", "friendly"),
        CoTTypeDefinition("a-f-G-U-C-I", "SFGPUCI----.svg", "Friendly Infantry", "Friendly ground infantry unit", "friendly"),
        CoTTypeDefinition("a-f-G-U-C-A", "SFGPUCA----.svg", "Friendly Armor", "Friendly ground armored unit", "friendly"),
        CoTTypeDefinition("a-f-G-U-C-S", "SFGPUCS----.svg", "Friendly Combat Support", "Friendly combat support unit", "friendly"),
        CoTTypeDefinition("a-f-G-U-U-L-C", "SFGPUULC----.svg", "Law Enforcement", "Police or law enforcement", "friendly"),
        CoTTypeDefinition("a-f-G-U-S-M", "SFGPUSM----.svg", "Medical", "Ambulance or medical vehicle", "friendly"),
        CoTTypeDefinition("a-f-G-E-V-U", "SFGPEVU----.svg", "Utility Vehicle", "Utility or service vehicle", "friendly"),
        CoTTypeDefinition("a-f-G-E-V-M", "SFGPEVM----.svg", "Civilian Vehicle", "Civilian motor vehicle", "friendly"),
        CoTTypeDefinition("a-f-G-E-V-F", "SFGPEVF----.svg", "Full Track Vehicle", "Full track armored vehicle", "friendly"),
        CoTTypeDefinition("a-f-G-E-V-L", "SFGPEVL----.svg", "Light Vehicle", "Light armored vehicle", "friendly"),
        CoTTypeDefinition("a-f-F-G-S", "SFFP-------.svg", "Special Forces", "Special operations forces", "friendly"),

        // Friendly Air Units
        CoTTypeDefinition("a-f-A", "SFAP-------.svg", "Friendly Air", "Friendly air unit", "friendly"),
        CoTTypeDefinition("a-f-A-M-F", "SFAP-------.svg", "Fixed Wing", "Friendly fixed-wing aircraft", "friendly"),
        CoTTypeDefinition("a-f-A-M-h", "SFAPMh------.svg", "Rotary Wing", "Friendly helicopter", "friendly"),
        CoTTypeDefinition("a-f-A-C-F", "SFAPACF----.svg", "Fighter Aircraft", "Fighter/interceptor aircraft", "friendly"),
        CoTTypeDefinition("a-f-A-C-R", "SFAPACR----.svg", "Reconnaissance Aircraft", "Reconnaissance aircraft", "friendly"),

        // Friendly Maritime
        CoTTypeDefinition("a-f-S", "SFSP-------.svg", "Friendly Maritime", "Friendly naval vessel", "friendly"),

        // Hostile Units
        CoTTypeDefinition("a-h-G-U", "SHGPU------.svg", "Hostile Ground - Generic", "Generic hostile marker", "hostile"),
        CoTTypeDefinition("a-h-G-U-C-I", "SHGPUCI----.svg", "Hostile Infantry", "Hostile infantry unit", "hostile"),
        CoTTypeDefinition("a-h-G-U-C-A", "SHGPUCA----.svg", "Hostile Armor", "Hostile armored vehicle", "hostile"),
        CoTTypeDefinition("a-h-G-U-C-C", "SHGPUCC----.svg", "Hostile Cavalry", "Hostile cavalry/recon unit", "hostile"),
        CoTTypeDefinition("a-h-G-E-V-C", "SHGPEVC----.svg", "Hostile Civil Vehicle", "Hostile civilian vehicle", "hostile"),

        // Neutral Units
        CoTTypeDefinition("a-n-G-U", "SNGPU------.svg", "Neutral Ground", "Neutral ground unit", "neutral"),
        CoTTypeDefinition("a-n-A-M-F", "SNAP-------.svg", "Neutral Air", "Neutral aircraft", "neutral"),
        CoTTypeDefinition("a-n-S", "SNSP-------.svg", "Neutral Maritime", "Neutral vessel", "neutral"),

        // Unknown Units
        CoTTypeDefinition("a-u-G-U", "SUGPU------.svg", "Unknown Ground", "Unknown ground unit", "unknown"),
        CoTTypeDefinition("a-u-G-U-C-I", "SUGPUCI----.svg", "Unknown Infantry", "Unknown infantry unit", "unknown"),
        CoTTypeDefinition("a-u-A", "SUA---------.svg", "Unknown Air", "Unknown aircraft", "unknown"),
    )

    private val cotTypeMap: Map<String, CoTTypeDefinition> =
        defaultDefinitions.associateBy { it.value }

    private val sidcToCotMap: Map<String, CoTTypeDefinition> =
        defaultDefinitions.associateBy { it.sidcCode }

    /**
     * Resolve the SIDC code for a CoT type. Exact match → progressive
     * truncation → per-affiliation fallback. Never returns null.
     */
    fun getSidc(cotType: String): String {
        cotTypeMap[cotType]?.let { return it.sidcCode }

        var searchType = cotType
        while (searchType.length > 3) {
            cotTypeMap[searchType]?.let { return it.sidcCode }
            val lastHyphen = searchType.lastIndexOf('-')
            if (lastHyphen < 0) break
            searchType = searchType.substring(0, lastHyphen)
        }

        val affiliation = Affiliation.fromCotType(cotType)
        return fallbackSidc[affiliation] ?: "SUGPU------"
    }

    /** Asset-relative SVG filename for a CoT type (e.g. `SFGPUCI----.svg`). */
    fun getSvgFileName(cotType: String): String = "${getSidc(cotType)}.svg"

    /** Asset-relative path callers feed to `AssetManager.open(...)`. */
    fun getAssetPath(cotType: String): String = "$ASSETS_DIR/${getSvgFileName(cotType)}"

    /** Lookup by exact CoT type; null when the type isn't in the catalogue. */
    fun getDefinition(cotType: String): CoTTypeDefinition? = cotTypeMap[cotType]

    /** Lookup by SIDC code (without `.svg`); null when not found. */
    fun getDefinitionBySidc(sidcCode: String): CoTTypeDefinition? = sidcToCotMap[sidcCode]

    fun getAffiliation(cotType: String): Affiliation = Affiliation.fromCotType(cotType)

    fun getBattleDimension(cotType: String): BattleDimension =
        BattleDimension.fromCotType(cotType)

    /** Every definition in the catalogue, in insertion order. */
    fun getAllDefinitions(): List<CoTTypeDefinition> = defaultDefinitions

    /** Definitions filtered by affiliation, in catalogue order. */
    fun getDefinitions(forAffiliation: Affiliation): List<CoTTypeDefinition> =
        defaultDefinitions.filter { it.affiliation == forAffiliation }
}
