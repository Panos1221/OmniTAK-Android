package soy.engindearing.omnitak.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Unit specs for the lasso multi-select. Mirrors the 6 iOS specs at
 * `OmniTAKMobileSpecs/Tests/LassoCoreTests/LassoSelectionTests.swift`
 * so logic stays in lockstep across the two clients.
 *
 *   1. testPointInLassoSelectsMarker — headline from the issue
 *   2. testEmptyPolygonSelectsNothing — degenerate polygon no-op
 *   3. testMarkerOnPolygonEdgeIsSelectedDeterministically — edge stability
 *   4. testDrawingCentroidInsidePolygonSelectsDrawing — drawing inclusion
 *   5. testSelectionPersistsAcrossEmptyLasso — applySelection is sticky
 *   6. testConcavePolygonExcludesCarveOut — PNPOLY handles concavity
 */
class LassoSelectionServiceTest {

    // MARK: - Helpers

    /** Square polygon centered on (0,0) with the given half-extent. */
    private fun squarePolygon(half: Double): List<LassoLatLng> = listOf(
        LassoLatLng(half, -half),
        LassoLatLng(half, half),
        LassoLatLng(-half, half),
        LassoLatLng(-half, -half),
    )

    // MARK: - Headline test from the issue brief

    @Test
    fun testPointInLassoSelectsMarker() {
        val service = LassoSelectionService()
        val poly = squarePolygon(1.0) // ±1 around origin

        val inside = LassoMarker("alpha", LassoLatLng(0.0, 0.0))
        val outside = LassoMarker("bravo", LassoLatLng(5.0, 5.0))

        val result = service.performLasso(
            polygon = poly,
            markers = listOf(inside, outside),
            drawings = emptyList(),
        )

        assertEquals(setOf("alpha"), result.markerIDs)
        assertTrue(result.drawingIDs.isEmpty())
        assertEquals(1, result.totalCount)
    }

    // MARK: - Edge cases

    @Test
    fun testEmptyPolygonSelectsNothing() {
        val service = LassoSelectionService()
        val marker = LassoMarker("alpha", LassoLatLng(0.0, 0.0))

        // 0, 1, 2 vertices are all degenerate — no enclosed area.
        for (vertexCount in 0..2) {
            val poly = squarePolygon(1.0).take(vertexCount)
            val result = service.performLasso(
                polygon = poly,
                markers = listOf(marker),
                drawings = emptyList(),
            )
            assertTrue(
                "degenerate polygon with $vertexCount vertex(es) should select nothing",
                result.isEmpty,
            )
        }
    }

    @Test
    fun testMarkerOnPolygonEdgeIsSelectedDeterministically() {
        // A marker positioned exactly on the polygon's east edge.
        // Whether ray-casting considers it inside or outside doesn't
        // matter — what matters is the answer is STABLE across runs
        // (no random NaN / float drift).
        val poly = squarePolygon(1.0)
        val onEdge = LassoMarker("edge", LassoLatLng(0.0, 1.0))

        val first = LassoSelectionService.pointInPolygon(onEdge.coordinate, poly)
        for (i in 0..99) {
            val again = LassoSelectionService.pointInPolygon(onEdge.coordinate, poly)
            assertEquals("edge classification must be deterministic on iteration $i", first, again)
        }
    }

    // MARK: - Drawings (per issue: include drawings if centroid is inside)

    @Test
    fun testDrawingCentroidInsidePolygonSelectsDrawing() {
        val service = LassoSelectionService()
        val poly = squarePolygon(2.0) // ±2 around origin

        val drawing = LassoDrawing(
            id = UUID.randomUUID(),
            coordinates = listOf(
                LassoLatLng(0.0, 0.0),
                LassoLatLng(0.5, 0.5),
                LassoLatLng(-0.5, 0.5),
            ),
        )
        // Drawing whose centroid is far outside.
        val outsideDrawing = LassoDrawing(
            id = UUID.randomUUID(),
            coordinates = listOf(
                LassoLatLng(10.0, 10.0),
                LassoLatLng(11.0, 11.0),
            ),
        )

        val result = service.performLasso(
            polygon = poly,
            markers = emptyList(),
            drawings = listOf(drawing, outsideDrawing),
        )

        assertEquals(setOf(drawing.id), result.drawingIDs)
        assertFalse(outsideDrawing.id in result.drawingIDs)
    }

    // MARK: - Selection state survives across no-op lassos

    @Test
    fun testSelectionPersistsAcrossEmptyLasso() {
        val service = LassoSelectionService()
        val ctx = SelectionContext(markerIDs = setOf("alpha", "bravo"))
        service.applySelection(ctx)
        assertEquals(2, service.current.value.totalCount)

        // A subsequent degenerate lasso must NOT clobber the
        // committed state — only an explicit clear() does.
        val empty = service.performLasso(
            polygon = emptyList(),
            markers = emptyList(),
            drawings = emptyList(),
        )
        assertTrue(empty.isEmpty)
        // Service's stored selection is unchanged.
        assertEquals(2, service.current.value.totalCount)
    }

    // MARK: - Convex / concave polygons both work

    @Test
    fun testConcavePolygonExcludesCarveOut() {
        // C-shape — outer square with a horizontal slot bitten out of
        // the east side. A marker in the bite is OUTSIDE the polygon
        // even though it's inside the bounding box.
        //
        //        +-----+
        //        |     |
        //        |  +--+
        //        |  |
        //        |  +--+
        //        |     |
        //        +-----+
        val poly = listOf(
            LassoLatLng(2.0, -2.0),
            LassoLatLng(2.0, 2.0),
            LassoLatLng(0.5, 2.0),
            LassoLatLng(0.5, 0.0),
            LassoLatLng(-0.5, 0.0),
            LassoLatLng(-0.5, 2.0),
            LassoLatLng(-2.0, 2.0),
            LassoLatLng(-2.0, -2.0),
        )

        val inBite = LassoMarker("bite", LassoLatLng(0.0, 1.5))    // inside bbox, outside polygon
        val inBody = LassoMarker("body", LassoLatLng(0.0, -1.5))   // inside both

        val service = LassoSelectionService()
        val result = service.performLasso(
            polygon = poly,
            markers = listOf(inBite, inBody),
            drawings = emptyList(),
        )

        assertFalse("marker in concave carve-out should NOT be selected", "bite" in result.markerIDs)
        assertTrue("marker in the body of the C should be selected", "body" in result.markerIDs)
    }

    // MARK: - Quick sanity for centroid

    @Test
    fun testCentroidIsArithmeticMean() {
        val pts = listOf(
            LassoLatLng(1.0, 1.0),
            LassoLatLng(3.0, 5.0),
            LassoLatLng(-4.0, 0.0),
        )
        val c = LassoSelectionService.centroid(pts)
        assertNotNull(c)
        assertEquals(0.0, c!!.latitude, 1e-9)
        assertEquals(2.0, c.longitude, 1e-9)
    }
}
