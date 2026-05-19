package soy.engindearing.omnitak.mobile.domain

import soy.engindearing.omnitak.mobile.data.GeoMath
import soy.engindearing.omnitak.mobile.data.uas.TerrainSampler
import soy.engindearing.omnitak.mobile.data.uas.Waypoint

/**
 * Pre-upload AGL sanity check for a mission. For each waypoint and
 * for sampled points along each leg, fetches the bare-earth elevation
 * from TAK Terrain and computes how much clearance the planned flight
 * altitude actually buys.
 *
 * Operator-facing question: "if I send this mission, will the drone
 * fly into anything?" — answered as GO / WARN / NO-GO with a per-row
 * breakdown so the operator can see *which* leg is the problem and
 * how much to raise cruise to fix it.
 */
object MissionRehearsal {

    enum class Verdict { GO, WARN, NO_GO }

    /** One step in the mission rehearsal — either a waypoint itself or
     *  an interpolated sample on the leg leading up to it. */
    data class Sample(
        val label: String,            // "WP 3" or "WP 2→3 @ 240m"
        val latDeg: Double,
        val lonDeg: Double,
        val plannedAltMsl: Double,
        val terrainMsl: Double?,      // null = terrain fetch failed (treated as unknown)
        val clearanceM: Double?,      // plannedAltMsl - terrainMsl
    )

    data class Result(
        val samples: List<Sample>,
        val worstClearance: Double?,  // min over all samples with known terrain
        val unknownTerrainCount: Int, // samples we couldn't fetch
        val verdict: Verdict,
        val verdictReason: String,
    )

    /**
     * Run the rehearsal. [legSpacingMeters] = how often to sample
     * between consecutive waypoints (default 50 m — fine enough to
     * catch ridge spikes, coarse enough to keep ~30 samples for a
     * typical 1.5 km mission so the cached TAK tiles serve everything
     * after the first fetch).
     *
     * Verdict thresholds:
     *  - **NO_GO** when worst clearance < [minClearanceMeters]
     *  - **WARN** when worst clearance < 2 × [minClearanceMeters]
     *  - **GO** otherwise
     */
    suspend fun run(
        waypoints: List<Waypoint>,
        terrain: TerrainSampler,
        legSpacingMeters: Double = 50.0,
        minClearanceMeters: Double = 10.0,
    ): Result {
        if (waypoints.isEmpty()) {
            return Result(emptyList(), null, 0, Verdict.NO_GO, "Mission is empty")
        }
        val samples = mutableListOf<Sample>()
        for (i in waypoints.indices) {
            val wp = waypoints[i]
            // Sample the waypoint itself first.
            val terrainAtWp = runCatching { terrain.sampleMeters(wp.latDeg, wp.lonDeg) }.getOrNull()
            samples += Sample(
                label = "WP ${i + 1}",
                latDeg = wp.latDeg,
                lonDeg = wp.lonDeg,
                plannedAltMsl = wp.altMslMeters,
                terrainMsl = terrainAtWp,
                clearanceM = terrainAtWp?.let { wp.altMslMeters - it },
            )
            // Then sample interpolated points on the leg INTO this WP.
            // Skip on the first leg (no previous WP). Linear lat/lon
            // interp is fine over the short legs operators draw.
            if (i > 0) {
                val prev = waypoints[i - 1]
                val legM = GeoMath.haversineMeters(prev.latDeg, prev.lonDeg, wp.latDeg, wp.lonDeg)
                val steps = (legM / legSpacingMeters).toInt().coerceIn(0, 30)
                for (s in 1 until steps) {
                    val f = s.toDouble() / steps
                    val lat = prev.latDeg + (wp.latDeg - prev.latDeg) * f
                    val lon = prev.lonDeg + (wp.lonDeg - prev.lonDeg) * f
                    val alt = prev.altMslMeters + (wp.altMslMeters - prev.altMslMeters) * f
                    val t = runCatching { terrain.sampleMeters(lat, lon) }.getOrNull()
                    samples += Sample(
                        label = "WP $i→${i + 1} @ ${(legM * f).toInt()}m",
                        latDeg = lat,
                        lonDeg = lon,
                        plannedAltMsl = alt,
                        terrainMsl = t,
                        clearanceM = t?.let { alt - it },
                    )
                }
            }
        }

        val knownClearances = samples.mapNotNull { it.clearanceM }
        val worst = knownClearances.minOrNull()
        val unknownCount = samples.count { it.terrainMsl == null }

        val verdict = when {
            worst == null -> Verdict.WARN
            worst < minClearanceMeters -> Verdict.NO_GO
            worst < minClearanceMeters * 2 -> Verdict.WARN
            else -> Verdict.GO
        }
        val reason = when {
            worst == null -> "No terrain data — DEM fetch failed for all samples"
            worst < minClearanceMeters -> {
                val worstSample = samples.first { it.clearanceM == worst }
                "${worstSample.label} only ${worst.toInt()} m above terrain (need ≥${minClearanceMeters.toInt()} m)"
            }
            worst < minClearanceMeters * 2 -> "Worst clearance ${worst.toInt()} m — under the safe margin, fly with caution"
            else -> "All samples clear by ≥${worst.toInt()} m"
        }
        return Result(samples, worst, unknownCount, verdict, reason)
    }
}
