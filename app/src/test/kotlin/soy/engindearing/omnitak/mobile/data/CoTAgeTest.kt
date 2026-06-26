package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #178 — point-age bucketing + relative-time formatting. Pure functions of two
 * epoch-millis values, so these run on a fixed clock with no Android deps.
 *
 * Buckets: fresh < 1m, aging 1–5m, stale > 5m.
 */
class CoTAgeTest {

    private val MIN = 60_000L

    // ── buckets ───────────────────────────────────────────────────────────────

    @Test fun fresh_under_one_minute() {
        assertEquals(CoTAge.Bucket.FRESH, CoTAge.bucket(0L))
        assertEquals(CoTAge.Bucket.FRESH, CoTAge.bucket(30_000L))
        assertEquals(CoTAge.Bucket.FRESH, CoTAge.bucket(MIN)) // 60s is the inclusive edge
    }

    @Test fun aging_between_one_and_five_minutes() {
        assertEquals(CoTAge.Bucket.AGING, CoTAge.bucket(MIN + 1))
        assertEquals(CoTAge.Bucket.AGING, CoTAge.bucket(4 * MIN))
        assertEquals(CoTAge.Bucket.AGING, CoTAge.bucket(5 * MIN)) // 5m is the inclusive edge
    }

    @Test fun stale_over_five_minutes() {
        assertEquals(CoTAge.Bucket.STALE, CoTAge.bucket(5 * MIN + 1))
        assertEquals(CoTAge.Bucket.STALE, CoTAge.bucket(60 * MIN))
    }

    @Test fun negative_age_from_clock_skew_is_treated_as_fresh() {
        // A producer clock ahead of ours yields a "future" timestamp — don't crash.
        assertEquals(CoTAge.Bucket.FRESH, CoTAge.bucket(-10_000L))
    }

    @Test fun bucketOf_uses_now_minus_received() {
        val now = 1_000_000L
        assertEquals(CoTAge.Bucket.FRESH, CoTAge.bucketOf(now - 10_000L, now))
        assertEquals(CoTAge.Bucket.STALE, CoTAge.bucketOf(now - 10 * MIN, now))
    }

    // ── opacity fade ──────────────────────────────────────────────────────────

    @Test fun alpha_fades_monotonically_with_age() {
        val fresh = CoTAge.alpha(0L)
        val aging = CoTAge.alpha(2 * MIN)
        val stale = CoTAge.alpha(10 * MIN)
        assertTrue("fresh should be fully opaque", fresh == CoTAge.ALPHA_FRESH)
        assertTrue("aging dimmer than fresh", aging < fresh)
        assertTrue("stale dimmer than aging", stale < aging)
    }

    // ── relative time ─────────────────────────────────────────────────────────

    @Test fun relative_just_now_under_ten_seconds() {
        val now = 1_000_000L
        assertEquals("just now", CoTAge.relative(now - 5_000L, now))
        assertEquals("just now", CoTAge.relative(now, now))
    }

    @Test fun relative_seconds_under_a_minute() {
        val now = 1_000_000L
        assertEquals("30s ago", CoTAge.relative(now - 30_000L, now))
    }

    @Test fun relative_minutes_under_an_hour() {
        val now = 10_000_000L
        // The tester's exact "~4 min old" case.
        assertEquals("4m ago", CoTAge.relative(now - 4 * MIN, now))
        assertEquals("59m ago", CoTAge.relative(now - 59 * MIN, now))
    }

    @Test fun relative_over_an_hour_collapses_to_gt_1h() {
        val now = 10_000_000L
        assertEquals(">1h", CoTAge.relative(now - 90 * MIN, now))
    }

    @Test fun relative_future_timestamp_reads_just_now() {
        val now = 1_000_000L
        assertEquals("just now", CoTAge.relative(now + 5_000L, now))
    }

    @Test fun relative_null_when_never_received() {
        assertNull(CoTAge.relative(0L, 1_000_000L))
        assertNull(CoTAge.relative(-1L, 1_000_000L))
    }

    // ── short label (map overlay) ──────────────────────────────────────────────

    @Test fun short_label_buckets_compactly() {
        val now = 10_000_000L
        assertEquals("<1m", CoTAge.shortLabel(now - 30_000L, now))
        assertEquals("3m", CoTAge.shortLabel(now - 3 * MIN, now))
        assertEquals(">1h", CoTAge.shortLabel(now - 90 * MIN, now))
        assertNull(CoTAge.shortLabel(0L, now))
    }
}
