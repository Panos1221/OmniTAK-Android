package soy.engindearing.omnitak.mobile.ui.components

import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import org.maplibre.android.maps.MapLibreMap
import soy.engindearing.omnitak.mobile.domain.LassoDrawing
import soy.engindearing.omnitak.mobile.domain.LassoLatLng
import soy.engindearing.omnitak.mobile.domain.LassoMarker
import soy.engindearing.omnitak.mobile.domain.LassoSelectionService

/**
 * Freehand-multi-select overlay. Rendered as a Compose sibling above
 * [TacticalMap] inside a Box. When [active] is true the Canvas
 * captures pointer input — drag traces a dashed orange polyline,
 * release runs [LassoSelectionService.performLasso] against the
 * supplied [markers] and [drawings], and commits the result.
 *
 * Mirrors the iOS implementation (Features/Drawing/Services/LassoSelectionService
 * + the CAShapeLayer in MapViewController) — Compose Canvas plays the
 * role of CAShapeLayer here: it draws on a layer above the map's
 * surface and is immune to whatever redraw cycle MapLibre uses, so
 * the freehand path tracks the finger frame-for-frame without
 * flicker.
 *
 * When [active] is false the overlay's `Modifier.pointerInput` block
 * is short-circuited so the underlying TacticalMap keeps owning
 * pan/zoom gestures. We still render an (empty) Canvas to keep the
 * layout slot stable across mode toggles.
 */
@Composable
fun LassoOverlay(
    active: Boolean,
    mapboxMap: MapLibreMap?,
    markers: List<LassoMarker>,
    drawings: List<LassoDrawing> = emptyList(),
    onCompleted: () -> Unit,
    onCancelled: () -> Unit = onCompleted,
    service: LassoSelectionService = LassoSelectionService.shared,
    modifier: Modifier = Modifier,
) {
    // View-space points accumulated during the current drag. Cleared
    // on each new gesture so a fresh swipe never inherits old
    // vertices.
    val points = remember { mutableStateListOf<Offset>() }

    // Drop any stale stroke if we leave lasso mode without finishing.
    LaunchedEffect(active) {
        if (!active) points.clear()
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (active) Modifier.pointerInput(active) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            points.clear()
                            points.add(offset)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            points.add(change.position)
                        },
                        onDragEnd = {
                            commitLasso(
                                points = points,
                                mapboxMap = mapboxMap,
                                markers = markers,
                                drawings = drawings,
                                service = service,
                            )
                            points.clear()
                            onCompleted()
                        },
                        onDragCancel = {
                            points.clear()
                            onCancelled()
                        },
                    )
                } else Modifier,
            ),
    ) {
        if (active && points.size >= 2) {
            val path = Path().apply {
                moveTo(points[0].x, points[0].y)
                for (p in points.drop(1)) lineTo(p.x, p.y)
            }
            drawPath(
                path = path,
                color = Color(0xFFFF9500), // iOS systemOrange — matches lasso brand
                style = Stroke(
                    width = 14f, // ~3.3dp at 420dpi; visible without dominating
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(24f, 14f)),
                ),
            )
        }
    }
}

private fun commitLasso(
    points: SnapshotStateList<Offset>,
    mapboxMap: MapLibreMap?,
    markers: List<LassoMarker>,
    drawings: List<LassoDrawing>,
    service: LassoSelectionService,
) {
    val map = mapboxMap ?: return
    if (points.size < 3) return // degenerate — fewer than 3 vertices means no enclosed area
    val polygon = points.map { pt ->
        val ll = map.projection.fromScreenLocation(PointF(pt.x, pt.y))
        LassoLatLng(latitude = ll.latitude, longitude = ll.longitude)
    }
    val selection = service.performLasso(
        polygon = polygon,
        markers = markers,
        drawings = drawings,
    )
    service.applySelection(selection)
}
