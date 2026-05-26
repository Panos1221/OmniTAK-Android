package soy.engindearing.omnitak.mobile.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.util.Log
import androidx.core.content.ContextCompat
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import soy.engindearing.omnitak.mobile.R
import soy.engindearing.omnitak.mobile.data.CoTAffiliation
import soy.engindearing.omnitak.mobile.data.CoTEvent
import soy.engindearing.omnitak.mobile.data.symbology.MilStdIconCache

/**
 * Renders the contacts overlay using MapLibre's Annotation API
 * (`map.addMarker(...)`) instead of GeoJsonSource + Circle/Symbol
 * layers.
 *
 * History: tried CircleLayer with inline-style geojson source,
 * then programmatic recreation, then SymbolLayer with bitmap icons
 * registered via `style.addImage`. None of those produced visible
 * pixels on real hardware (Adreno 610 was the worst case) even
 * though `style.layers` and `style.getImage` confirmed everything
 * was registered. MapLibre-Android 11.x has a paint pipeline that
 * silently drops circle/symbol layers on a subset of GL drivers.
 *
 * The Annotation API uses MapLibre's bundled `org.maplibre.annotations.points`
 * overlay, which is rendered via a native code path that works
 * across the same drivers — same path LocationComponent uses for
 * its foreground self-marker.
 *
 * Each contact's [CoTEvent.type] resolves to a MIL-STD-2525 SIDC via
 * [MilStdIconCache], which loads the matching SVG from
 * `assets/milstd/`. The pre-Phase-B tinted-dot path remains as a
 * runtime fallback — if the SVG pipeline throws (asset missing,
 * AndroidSVG parse error, OEM GL quirk), markers degrade gracefully
 * to a coloured dot keyed off affiliation, matching the prior
 * visual.
 */
object ContactLayer {
    private const val TAG = "ContactLayer"

    private val markers = mutableMapOf<String, Marker>()

    // Affiliation-coloured fallback icons. Populated lazily on first
    // tinted-dot draw — only happens when the SVG pipeline fails.
    private var fallbackFriend: Icon? = null
    private var fallbackHostile: Icon? = null
    private var fallbackNeutral: Icon? = null
    private var fallbackUnknown: Icon? = null

    fun update(map: MapLibreMap, context: Context, contacts: Collection<CoTEvent>) {
        // Build a UID set for quick membership checks.
        val incomingByUid = contacts.associateBy { it.uid }

        // Remove markers for contacts that have been deleted.
        val toRemove = markers.keys - incomingByUid.keys
        toRemove.forEach { uid ->
            markers[uid]?.let { map.removeMarker(it) }
            markers.remove(uid)
        }

        // Add or update markers for incoming contacts.
        contacts.forEach { c ->
            val existing = markers[c.uid]
            val ll = LatLng(c.lat, c.lon)
            val icon = iconFor(context, c)
            if (existing == null) {
                val marker = map.addMarker(
                    MarkerOptions()
                        .position(ll)
                        .title(c.callsign ?: c.uid)
                        .icon(icon)
                )
                markers[c.uid] = marker
            } else {
                // In-place mutation on PPLI ticks. MapLibre 11.8's
                // Marker.setPosition / setIcon both call
                // map.updateMarker(this) internally, so the annotation
                // view is preserved across updates — fixes the
                // remove+readd flicker the previous workaround caused
                // (port of iOS PR D #1 / closes #23).
                if (existing.position != ll) existing.position = ll
                if (existing.icon != icon) existing.icon = icon
                val newTitle = c.callsign ?: c.uid
                if (existing.title != newTitle) existing.title = newTitle
            }
        }
    }

    private fun iconFor(context: Context, contact: CoTEvent): Icon =
        try {
            MilStdIconCache.iconFor(context, contact.type)
        } catch (t: Throwable) {
            Log.w(TAG, "MIL-STD icon resolution failed for ${contact.type}; using affiliation fallback", t)
            fallbackIconFor(context, contact.affiliation)
        }

    private fun fallbackIconFor(context: Context, affiliation: CoTAffiliation): Icon {
        ensureFallbackIcons(context)
        return when (affiliation) {
            CoTAffiliation.FRIEND -> fallbackFriend!!
            CoTAffiliation.HOSTILE -> fallbackHostile!!
            CoTAffiliation.NEUTRAL -> fallbackNeutral!!
            else -> fallbackUnknown!!
        }
    }

    private fun ensureFallbackIcons(context: Context) {
        if (fallbackFriend != null) return
        val factory = IconFactory.getInstance(context)
        fallbackFriend = factory.fromBitmap(tintedDot(context, 0xFF4ADE80.toInt()))
        fallbackHostile = factory.fromBitmap(tintedDot(context, 0xFFF44336.toInt()))
        fallbackNeutral = factory.fromBitmap(tintedDot(context, 0xFFFFC107.toInt()))
        fallbackUnknown = factory.fromBitmap(tintedDot(context, 0xFFB39DDB.toInt()))
    }

    private fun tintedDot(context: Context, colorArgb: Int): Bitmap {
        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_contact_dot)!!.mutate()
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 72
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 72
        drawable.setBounds(0, 0, w, h)
        drawable.colorFilter = PorterDuffColorFilter(colorArgb, PorterDuff.Mode.SRC_IN)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawable.draw(Canvas(bmp))
        return bmp
    }

    /** Stable color for previewing an affiliation outside the map. */
    fun previewColor(affiliation: CoTAffiliation): Int = when (affiliation) {
        CoTAffiliation.FRIEND -> 0xFF4ADE80.toInt()
        CoTAffiliation.HOSTILE -> 0xFFF44336.toInt()
        CoTAffiliation.NEUTRAL -> 0xFFFFC107.toInt()
        else -> 0xFFB39DDB.toInt()
    }
}
