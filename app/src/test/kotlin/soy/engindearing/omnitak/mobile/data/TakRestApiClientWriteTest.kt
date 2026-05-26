package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #30 slice 1 — pure-format tests for the Mission Authoring write
 * endpoints. The wire-level send is integration-tested against the
 * local tak57 docker; this slice locks in the encoding contract.
 */
class TakRestApiClientWriteTest {

    // ── extractHash — three known Marti response shapes ─────────────

    @Test fun extractHash_from_canonical_url() {
        val hash = TakRestApiClient.extractHash(
            "https://server.example.com:8443/Marti/sync/content?hash=ABC123DEF456"
        )
        assertEquals("ABC123DEF456", hash)
    }

    @Test fun extractHash_from_url_with_extra_query_params() {
        val hash = TakRestApiClient.extractHash(
            "https://server/Marti/sync/content?foo=bar&hash=DEADBEEF&baz=qux"
        )
        assertEquals("DEADBEEF", hash)
    }

    @Test fun extractHash_from_token_form_older_marti() {
        // Older Marti builds occasionally return the bare `hash=...`
        // token without the full URL prefix. iOS supports this form
        // case-insensitively in the same fallback branch.
        val hash = TakRestApiClient.extractHash("hash=A1B2C3")
        assertEquals("A1B2C3", hash)
    }

    @Test fun extractHash_handles_whitespace() {
        val hash = TakRestApiClient.extractHash(
            "   https://server/Marti/sync/content?hash=WHITESPACE   \n"
        )
        assertEquals("WHITESPACE", hash)
    }

    @Test fun extractHash_returns_null_on_garbage() {
        assertNull(TakRestApiClient.extractHash(""))
        assertNull(TakRestApiClient.extractHash("server error"))
        assertNull(TakRestApiClient.extractHash("https://server/Marti/sync/content"))
    }

    // ── urlSegment — mission names with awkward characters ──────────

    @Test fun urlSegment_encodes_spaces_as_percent_twenty() {
        // Spaces must be %20 (not '+') inside path segments — '+' is
        // legal in query strings only, and Marti's route parser doesn't
        // decode it back to space in the path.
        assertEquals("SAR%20Op%20Alpha", TakRestApiClient.urlSegment("SAR Op Alpha"))
    }

    @Test fun urlSegment_encodes_reserved_characters() {
        assertEquals("op%2Falpha", TakRestApiClient.urlSegment("op/alpha"))
        assertEquals("op%26b", TakRestApiClient.urlSegment("op&b"))
        assertEquals("op%3Falpha", TakRestApiClient.urlSegment("op?alpha"))
    }

    @Test fun urlSegment_unicode_round_trip() {
        assertEquals("%E6%97%A5%E6%9C%AC", TakRestApiClient.urlSegment("日本"))
    }

    // ── encodeQuery — list-of-pairs to `?k=v&k=v` ───────────────────

    @Test fun encodeQuery_empty_returns_empty_string() {
        assertEquals("", TakRestApiClient.encodeQuery(emptyList()))
    }

    @Test fun encodeQuery_preserves_pair_order_and_encodes_values() {
        val q = TakRestApiClient.encodeQuery(
            listOf("creatorUid" to "ANDROID-1", "tool" to "public", "description" to "hello world")
        )
        assertEquals("?creatorUid=ANDROID-1&tool=public&description=hello+world", q)
    }

    @Test fun encodeQuery_repeats_keys_for_group_lists() {
        // Marti accepts repeated `group=` params for multi-group missions.
        val q = TakRestApiClient.encodeQuery(
            listOf("group" to "TEAM-A", "group" to "TEAM-B", "group" to "TEAM-C")
        )
        assertEquals("?group=TEAM-A&group=TEAM-B&group=TEAM-C", q)
    }

    // ── jsonEscape — protect against quote/control injection ────────

    @Test fun jsonEscape_passes_through_safe_strings() {
        assertEquals("ABC123", TakRestApiClient.jsonEscape("ABC123"))
    }

    @Test fun jsonEscape_escapes_quotes_and_backslash() {
        assertEquals("""foo\"bar""", TakRestApiClient.jsonEscape("""foo"bar"""))
        assertEquals("""foo\\bar""", TakRestApiClient.jsonEscape("""foo\bar"""))
    }

    @Test fun jsonEscape_escapes_control_characters() {
        assertEquals("a\\nb", TakRestApiClient.jsonEscape("a\nb"))
        assertEquals("a\\u0001b", TakRestApiClient.jsonEscape("ab"))
    }

    // ── buildMultipartPackage — wire shape Marti expects ────────────

    @Test fun buildMultipartPackage_contains_required_headers_and_payload() {
        val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x42, 0x42, 0x42)
        val body = TakRestApiClient.buildMultipartPackage(
            boundary = "test-boundary",
            filename = "search-area.zip",
            zipBytes = zip,
        )
        val text = String(body, Charsets.UTF_8)
        assertTrue("boundary delimiter", text.contains("--test-boundary\r\n"))
        assertTrue(
            "Content-Disposition with assetfile name + filename",
            text.contains("""Content-Disposition: form-data; name="assetfile"; filename="search-area.zip""""),
        )
        assertTrue(
            "zip content-type header",
            text.contains("Content-Type: application/x-zip-compressed\r\n"),
        )
        assertTrue("body ends with boundary terminator", text.endsWith("--test-boundary--\r\n"))
        // Sanity: the zip magic bytes are in the body.
        assertTrue(
            "zip payload present",
            body.toList().windowed(zip.size).any { it.toByteArray().contentEquals(zip) },
        )
    }

    @Test fun buildMultipartPackage_strips_quotes_from_filename() {
        // Defensive — operator-supplied filenames shouldn't break the
        // Content-Disposition header.
        val body = TakRestApiClient.buildMultipartPackage(
            boundary = "b",
            filename = """bad"name.zip""",
            zipBytes = byteArrayOf(),
        )
        val text = String(body, Charsets.UTF_8)
        assertFalse(
            "filename should have its embedded quote stripped",
            text.contains("""filename="bad"name.zip""""),
        )
        assertTrue(
            "filename written sans embedded quote",
            text.contains("""filename="badname.zip""""),
        )
    }

    // ── MissionBbox — query-string format Marti expects ─────────────

    @Test fun missionBbox_query_format_no_spaces() {
        val bbox = MissionBbox(
            minLat = 47.6,
            minLon = -117.5,
            maxLat = 47.7,
            maxLon = -117.4,
        )
        assertEquals("47.6,-117.5,47.7,-117.4", bbox.queryValue)
    }

    private fun List<Byte>.toByteArray(): ByteArray = ByteArray(size) { this[it] }
}
