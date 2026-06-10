package soy.engindearing.omnitak.mobile.ui.components

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import soy.engindearing.omnitak.mobile.BuildConfig
import soy.engindearing.omnitak.mobile.data.CoTEvent

/**
 * Photoreal Cesium 3D globe map engine, hosted in a WebView. Mirrors the
 * iOS Cesium scene — same `cesium_scene.html` bridge (OmniBridge.setEntities
 * etc.) and the same tap / long-press / camera events posted back to native
 * via `window.OmniBridgeNative`.
 *
 * Renders contacts (including dropped pins) and the operator's own position
 * as MIL-STD-affiliation billboards. Long-press surfaces the same radial /
 * drop flow the MapLibre map uses (the menu overlay is engine-agnostic);
 * tapping a contact opens its edit sheet. MapLibre-specific overlays
 * (drone, FAA, lasso, drawings) stay on the 2D / terrain engines for now.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CesiumMapView(
    contacts: List<CoTEvent>,
    selfLat: Double?,
    selfLon: Double?,
    selfCallsign: String,
    onLongPress: (LatLng, Offset) -> Unit,
    onContactTap: (CoTEvent) -> Unit,
    onCameraChanged: (LatLng, Double) -> Unit,
    modifier: Modifier = Modifier,
    // Tick counters from the on-screen map controls. Incrementing one fires
    // the matching camera command on the globe. Mirror the TacticalMap
    // (2D) wiring so the +/- zoom and "center on me" buttons work on 3D too.
    zoomInTrigger: Int = 0,
    zoomOutTrigger: Int = 0,
    recenterTrigger: Int = 0,
) {
    val density = LocalDensity.current.density
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val ready = remember { mutableStateOf(false) }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    val contactsState = rememberUpdatedState(contacts)
    val selfLatState = rememberUpdatedState(selfLat)
    val selfLonState = rememberUpdatedState(selfLon)
    val selfCallsignState = rememberUpdatedState(selfCallsign)
    val onLongPressState = rememberUpdatedState(onLongPress)
    val onContactTapState = rememberUpdatedState(onContactTap)
    val onCameraState = rememberUpdatedState(onCameraChanged)

    fun pushEntities() {
        val wv = webViewRef.value ?: return
        if (!ready.value) return
        val json = buildCesiumEntitiesJson(
            contactsState.value, selfLatState.value, selfLonState.value, selfCallsignState.value,
        )
        wv.evaluateJavascript("window.OmniBridge.setEntities($json);", null)
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                webViewRef.value = this
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                setBackgroundColor(android.graphics.Color.BLACK)
                webViewClient = WebViewClient()
                webChromeClient = WebChromeClient()
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onReady() {
                            mainHandler.post {
                                ready.value = true
                                pushEntities()
                            }
                        }

                        @JavascriptInterface
                        fun onMapEvent(json: String) {
                            val o = runCatching { JSONObject(json) }.getOrNull() ?: return
                            val event = o.optString("event")
                            val lat = o.optDouble("lat")
                            val lon = o.optDouble("lon")
                            if (lat.isNaN() || lon.isNaN()) return
                            mainHandler.post {
                                when (event) {
                                    "tap" -> {
                                        val uid = if (o.isNull("uid")) null else o.optString("uid")
                                        if (!uid.isNullOrEmpty() && uid != "__self__") {
                                            contactsState.value.firstOrNull { it.uid == uid }
                                                ?.let { onContactTapState.value(it) }
                                        }
                                    }
                                    "longpress" -> {
                                        val sx = (o.optDouble("screenX", 0.0) * density).toFloat()
                                        val sy = (o.optDouble("screenY", 0.0) * density).toFloat()
                                        onLongPressState.value(LatLng(lat, lon), Offset(sx, sy))
                                    }
                                    "camerachanged" -> {
                                        onCameraState.value(LatLng(lat, lon), o.optDouble("zoom", 11.0))
                                    }
                                }
                            }
                        }
                    },
                    "OmniBridgeNative",
                )
                val html = ctx.assets.open("cesium_scene.html")
                    .bufferedReader().use { it.readText() }
                    // Ion token is injected here from BuildConfig (fed by the
                    // gitignored local.properties) so the committed asset in
                    // this public repo never carries a literal token. Empty
                    // token = tokenless Cesium (degraded Ion imagery), not a
                    // crash.
                    .replace("__CESIUM_ION_TOKEN__", BuildConfig.CESIUM_ION_TOKEN)
                loadDataWithBaseURL("https://cesium.com/", html, "text/html", "UTF-8", null)
            }
        },
        update = { pushEntities() },
    )

    // Map-control buttons → globe camera. The JS guards no-op until the
    // viewer exists, but gate on `ready` too so a stray early tick is dropped.
    // Skip the initial 0 so the first composition doesn't fire a command.
    DisposableEffect(zoomInTrigger) {
        if (zoomInTrigger > 0 && ready.value) {
            webViewRef.value?.evaluateJavascript("window.OmniBridge.zoomIn();", null)
        }
        onDispose { }
    }
    DisposableEffect(zoomOutTrigger) {
        if (zoomOutTrigger > 0 && ready.value) {
            webViewRef.value?.evaluateJavascript("window.OmniBridge.zoomOut();", null)
        }
        onDispose { }
    }
    DisposableEffect(recenterTrigger) {
        if (recenterTrigger > 0 && ready.value) {
            val lat = selfLatState.value
            val lon = selfLonState.value
            if (lat != null && lon != null && !lat.isNaN() && !lon.isNaN()) {
                webViewRef.value?.evaluateJavascript(
                    "window.OmniBridge.centerOnSelf({lat:$lat,lon:$lon});", null,
                )
            }
        }
        onDispose { }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef.value?.destroy()
            webViewRef.value = null
        }
    }
}

