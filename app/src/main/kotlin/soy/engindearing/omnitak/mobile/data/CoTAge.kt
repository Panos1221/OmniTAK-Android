package soy.engindearing.omnitak.mobile.data

/**
 * #178 — Point-age formatting + staleness bucketing for received CoT events.
 *
 * Field tester: "Sometimes I take my guys' position for granted not knowing the
 * info is ~4 min old." This turns the wall-clock gap between now and when a
 * point was last received into (a) a short relative-time label for the detail
 * sheet and (b) a coarse freshness bucket the map overlay fades a stale point by.
 *
 * Everything here is a pure function of two epoch-millis values so it unit-tests
 * without a clock — callers pass `System.currentTimeMillis()` as `nowMs`.
 */
object CoTAge {
    /** Freshness buckets a received point falls into as it ages. */
    enum class Bucket { FRESH, AGING, STALE }

    // Thresholds (constants, per the spec): fresh < 1m, aging 1–5m, stale > 5m.
    const val FRESH_MAX_MS: Long = 60_000L          // 1 minute
    const val AGING_MAX_MS: Long = 5 * 60_000L      // 5 minutes

    /** Map-opacity (alpha 0..1) applied to a point by its freshness bucket. */
    const val ALPHA_FRESH: Float = 1.0f
    const val ALPHA_AGING: Float = 0.7f
    const val ALPHA_STALE: Float = 0.4f

    /**
     * Bucket for an age in millis. Negative ages (clock skew — a future
     * timestamp) are treated as fresh rather than throwing.
     */
    fun bucket(ageMs: Long): Bucket = when {
        ageMs <= FRESH_MAX_MS -> Bucket.FRESH
        ageMs <= AGING_MAX_MS -> Bucket.AGING
        else -> Bucket.STALE
    }

    /** Convenience: bucket from a received-at timestamp relative to [nowMs]. */
    fun bucketOf(receivedAtMs: Long, nowMs: Long): Bucket = bucket(nowMs - receivedAtMs)

    /** Map opacity for an age, by bucket. */
    fun alpha(ageMs: Long): Float = when (bucket(ageMs)) {
        Bucket.FRESH -> ALPHA_FRESH
        Bucket.AGING -> ALPHA_AGING
        Bucket.STALE -> ALPHA_STALE
    }

    /**
     * Short relative-time label for [receivedAtMs] as of [nowMs]:
     *   "just now"  (< 10s)
     *   "Ns ago"    (< 1m)
     *   "Nm ago"    (< 1h)
     *   ">1h"       (>= 1h)
     * A future timestamp (clock skew) reads "just now". Returns null when
     * [receivedAtMs] is non-positive (we never received it / unknown).
     */
    fun relative(receivedAtMs: Long, nowMs: Long): String? {
        if (receivedAtMs <= 0L) return null
        val age = nowMs - receivedAtMs
        return when {
            age < 10_000L -> "just now"
            age < 60_000L -> "${age / 1_000L}s ago"
            age < 60 * 60_000L -> "${age / 60_000L}m ago"
            else -> ">1h"
        }
    }

    /** Compact label for the on-map overlay (no "ago"): "<1m", "Nm", ">1h". */
    fun shortLabel(receivedAtMs: Long, nowMs: Long): String? {
        if (receivedAtMs <= 0L) return null
        val age = nowMs - receivedAtMs
        return when {
            age < 60_000L -> "<1m"
            age < 60 * 60_000L -> "${age / 60_000L}m"
            else -> ">1h"
        }
    }
}
