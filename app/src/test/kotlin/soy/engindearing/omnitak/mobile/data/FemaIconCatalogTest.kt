package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #29 / iOS PR for #13 — locks the FEMA / IC catalog contract so the
 * Android emission matches the iOS one byte-for-byte. Cross-platform
 * round-trip of a SAR marker depends on:
 *   1. Same `Kind.raw` strings (used in iconsetpath segment)
 *   2. Same `cotType` per kind
 *   3. Same `iconsetPath` format (`COT_MAPPING_FEMA/<category>/<kind>`)
 */
class FemaIconCatalogTest {

    @Test fun catalog_has_exactly_twelve_entries_matching_ios() {
        assertEquals(12, FemaIconCatalog.all.size)
    }

    @Test fun every_kind_is_indexed() {
        FemaIconCatalog.Kind.entries.forEach { kind ->
            assertNotNull(
                "missing FemaIcon for $kind",
                FemaIconCatalog.iconFor(kind),
            )
        }
    }

    @Test fun cot_types_are_unique() {
        val types = FemaIconCatalog.all.map { it.cotType }
        assertEquals(
            "duplicate cotType in catalog: ${types.groupingBy { it }.eachCount().filter { it.value > 1 }}",
            types.size,
            types.toSet().size,
        )
    }

    @Test fun iconsetpath_format_is_cot_mapping_fema() {
        FemaIconCatalog.all.forEach { icon ->
            assertTrue(
                "iconsetPath ${icon.iconsetPath} must start with COT_MAPPING_FEMA/",
                icon.iconsetPath.startsWith("COT_MAPPING_FEMA/"),
            )
            // category and kind segments must round-trip back through
            // iconByIconsetPath
            assertEquals(
                "iconsetPath ${icon.iconsetPath} must round-trip via iconByIconsetPath",
                icon.kind,
                FemaIconCatalog.iconByIconsetPath(icon.iconsetPath)?.kind,
            )
        }
    }

    @Test fun ios_parity_specific_mappings() {
        // Spot-check the mappings against the iOS catalog so a renamed
        // Kind on either platform breaks this test loudly.
        val cp = FemaIconCatalog.iconFor(FemaIconCatalog.Kind.COMMAND_POST)!!
        assertEquals("a-f-G-I-U-T", cp.cotType)
        assertEquals("COT_MAPPING_FEMA/incidentCommand/command_post", cp.iconsetPath)

        val lz = FemaIconCatalog.iconFor(FemaIconCatalog.Kind.HELICOPTER_LZ)!!
        assertEquals("a-f-G-I-X-H", lz.cotType)
        assertEquals("COT_MAPPING_FEMA/aviation/helicopter_lz", lz.iconsetPath)

        val ft = FemaIconCatalog.iconFor(FemaIconCatalog.Kind.FIRE_TRUCK)!!
        assertEquals("a-f-G-E-V-F-R", ft.cotType)

        val ch = FemaIconCatalog.iconFor(FemaIconCatalog.Kind.COMMUNICATIONS_HUB)!!
        assertEquals("a-f-G-I-U-C", ch.cotType)
        assertEquals("COT_MAPPING_FEMA/support/communications_hub", ch.iconsetPath)
    }

    @Test fun iconsIn_filters_to_category() {
        val medical = FemaIconCatalog.iconsIn(FemaIconCatalog.Category.MEDICAL)
        assertEquals(2, medical.size)
        assertTrue(medical.all { it.category == FemaIconCatalog.Category.MEDICAL })

        val command = FemaIconCatalog.iconsIn(FemaIconCatalog.Category.INCIDENT_COMMAND)
        assertEquals(2, command.size)
    }

    @Test fun iconByIconsetPath_rejects_non_fema_paths() {
        assertNull(FemaIconCatalog.iconByIconsetPath("COT_MAPPING_2525B/a-f/a-f-G-U-C"))
        assertNull(FemaIconCatalog.iconByIconsetPath(""))
        assertNull(FemaIconCatalog.iconByIconsetPath("COT_MAPPING_FEMA/medical/unknown_kind"))
    }

    @Test fun iconByRaw_matches_kind_raw_values() {
        FemaIconCatalog.all.forEach { icon ->
            assertEquals(icon.kind, FemaIconCatalog.iconByRaw(icon.kind.raw)?.kind)
        }
        assertNull(FemaIconCatalog.iconByRaw("definitely_not_a_kind"))
    }
}
