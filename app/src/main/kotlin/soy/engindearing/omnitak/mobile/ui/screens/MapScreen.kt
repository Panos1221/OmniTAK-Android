package soy.engindearing.omnitak.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.AddLocation
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import soy.engindearing.omnitak.mobile.ui.theme.TacticalAccent
import soy.engindearing.omnitak.mobile.ui.theme.TacticalBackground
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import soy.engindearing.omnitak.mobile.OmniTAKApp
import soy.engindearing.omnitak.mobile.data.CoTAffiliation
import soy.engindearing.omnitak.mobile.data.CoTEvent
import soy.engindearing.omnitak.mobile.data.Drawing
import soy.engindearing.omnitak.mobile.data.DrawingKind
import soy.engindearing.omnitak.mobile.data.GeoMath
import soy.engindearing.omnitak.mobile.domain.ConnectionState
import soy.engindearing.omnitak.mobile.ui.components.ATAKStatusBar
import soy.engindearing.omnitak.mobile.ui.components.ContactsPanel
import soy.engindearing.omnitak.mobile.ui.components.LayersDialog
import soy.engindearing.omnitak.mobile.ui.components.MarkerEditSheet
import soy.engindearing.omnitak.mobile.ui.components.RadialAction
import soy.engindearing.omnitak.mobile.ui.components.RadialMenu
import soy.engindearing.omnitak.mobile.ui.components.SelfPositionCard
import soy.engindearing.omnitak.mobile.ui.components.TacticalMap
import soy.engindearing.omnitak.mobile.ui.components.styleJsonForProvider
import soy.engindearing.omnitak.mobile.ui.components.ToolEntry
import soy.engindearing.omnitak.mobile.ui.components.ToolsDrawer
import soy.engindearing.omnitak.mobile.ui.components.rememberLocationPermission
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MapScreen(onOpenTab: (String) -> Unit = {}) {
    val app = LocalContext.current.applicationContext as OmniTAKApp
    val active by app.serverManager.activeServer.collectAsState()
    val connState by app.serverManager.connectionState.collectAsState()
    val msgReceived by app.serverManager.messagesReceived.collectAsState()
    val msgSent by app.serverManager.messagesSent.collectAsState()
    val contacts by app.contactStore.contacts.collectAsState()
    // Layers toggle: mesh-origin contacts are persisted because the
    // operator's last choice should survive a process restart. Default
    // visible — matches iOS.
    val userPrefs by app.userPrefsStore.prefs.collectAsState(initial = soy.engindearing.omnitak.mobile.data.UserPrefs())
    val meshNodesVisible = userPrefs.meshNodesLayerVisible
    // GAP-110 — persisted UI toggles. These survive relaunch instead of
    // resetting to defaults each time the user opens the app. Aliases so
    // existing read sites stay terse.
    val gridEnabled = userPrefs.gridEnabled
    val drawingsVisible = userPrefs.drawingsVisible
    val aircraftVisible = userPrefs.aircraftVisible
    val contactsVisible = userPrefs.contactsVisible
    val callsignCardVisible = userPrefs.callsignCardVisible
    val followMeActive = userPrefs.followMeActive
    val prefScope = rememberCoroutineScope()
    fun mutatePref(block: (soy.engindearing.omnitak.mobile.data.UserPrefs) -> soy.engindearing.omnitak.mobile.data.UserPrefs) {
        prefScope.launch { app.userPrefsStore.update(block) }
    }

    val headerLabel = when (val s = connState) {
        is ConnectionState.Connected -> s.serverName
        is ConnectionState.Connecting -> "Connecting…"
        is ConnectionState.Failed -> "Failed"
        ConnectionState.Disconnected -> active?.name ?: "Offline"
    }

    var nowLabel by remember { mutableStateOf(timeLabel()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowLabel = timeLabel()
            delay(15_000L)
        }
    }

    val locationGranted by rememberLocationPermission()
    // GAP-030b — start the FusedLocationProviderClient as soon as we have
    // permission. Idempotent, safe to re-invoke on every recomposition.
    val selfFix by app.locationProvider.fix.collectAsState()
    LaunchedEffect(locationGranted) {
        if (locationGranted) app.locationProvider.start()
    }
    var radialAnchor by remember { mutableStateOf<Offset?>(null) }
    var radialLatLng by remember { mutableStateOf<LatLng?>(null) }
    var markerSheetLatLng by remember { mutableStateOf<LatLng?>(null) }
    var editingMarker by remember { mutableStateOf<CoTEvent?>(null) }
    var recenterTick by remember { mutableStateOf(0) }
    var zoomInTick by remember { mutableStateOf(0) }
    var zoomOutTick by remember { mutableStateOf(0) }
    var measurementActive by remember { mutableStateOf(false) }
    // UAS waypoint-add mode — when true, taps on the map drop mission
    // waypoints instead of any other action. Toggled from the mission
    // banner's Done button and entered via Tools → "Add UAS Waypoints".
    var missionMode by remember { mutableStateOf(false) }
    val mission by app.uasManager.mission.collectAsState()
    // Index of the waypoint currently being edited (tap a pin to open
    // the WaypointEditSheet). null = no sheet. Lives at function scope
    // because both onMapSingleTap (hit-test) and the sheet itself need
    // to see/mutate it.
    var editingWaypointIndex by remember { mutableStateOf<Int?>(null) }
    var measurementPoints by remember { mutableStateOf<List<LatLng>>(emptyList()) }
    var drawingKind by remember { mutableStateOf<DrawingKind?>(null) }
    var drawingPoints by remember { mutableStateOf<List<LatLng>>(emptyList()) }
    var drawingPickerOpen by remember { mutableStateOf(false) }
    // GAP-110 — gridEnabled, drawingsVisible, aircraftVisible, contactsVisible,
    // callsignCardVisible, followMeActive are now read from userPrefs (above)
    // so they survive relaunch.
    var layersSheetOpen by remember { mutableStateOf(false) }
    var teamsPanelOpen by remember { mutableStateOf(false) }
    var panTarget by remember { mutableStateOf<LatLng?>(null) }
    var panTargetTick by remember { mutableStateOf(0) }
    val adsbService = remember { soy.engindearing.omnitak.mobile.data.AdsbService() }
    val rawAircraft by adsbService.aircraft.collectAsState()
    val adsbActive by adsbService.active.collectAsState()
    DisposableEffect(adsbService) { onDispose { adsbService.stop() } }

    // Drone telemetry from UASManager — the connected UAS gets its own
    // DroneLayer (programmatic source/layer added at runtime), which
    // pops visually above ADS-B traffic and self so the drone isn't
    // lost in the generic aircraft circle. We still expose the drone
    // through the regular aircraft list for the historical hooks
    // (lasso, etc.) but the dedicated layer is what the operator sees.
    val droneState by app.uasManager.state.collectAsState()
    val aircraft = rawAircraft
    // Pass the drone separately to TacticalMap → DroneLayer renders it
    // as a distinct cyan ring + callsign label, above the aircraft circle.
    val droneForMap = if (droneState.hasFix()) droneState else null
    val drawings by app.drawingStore.drawings.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Issue #16 — Lasso freehand multi-select.
    // The MapLibreMap reference is captured via TacticalMap.onMapReady
    // so the lasso overlay can project screen pixels to LatLng on
    // drag end. lassoMode is flipped on by the Tools popup → service
    // event; it flips back automatically when LassoOverlay reports
    // the gesture completed (commits selection on the service).
    var lassoMode by remember { mutableStateOf(false) }
    var mapboxMap by remember { mutableStateOf<org.maplibre.android.maps.MapLibreMap?>(null) }
    val lassoService = remember { soy.engindearing.omnitak.mobile.domain.LassoSelectionService.shared }
    val lassoSelection by lassoService.current.collectAsState()
    var lassoActionsOpen by remember { mutableStateOf(false) }
    var contactPickerOpen by remember { mutableStateOf(false) }
    val appContext = LocalContext.current.applicationContext
    // Observe activation requests via a generation counter — every
    // increment means "user picked Lasso Select again." We track the
    // last value we honored so we only flip lassoMode on a fresh
    // edge. StateFlow keeps the latest value alive across MapScreen
    // recompositions, so an emission that lands before the collector
    // subscribed still gets applied as soon as we observe it.
    val activationGen by lassoService.activationGeneration.collectAsState()
    var lastHonoredGen by remember { mutableStateOf(activationGen) }
    LaunchedEffect(activationGen) {
        if (activationGen != lastHonoredGen) {
            lastHonoredGen = activationGen
            // Skip the initial "0" baseline — only react to real increments.
            if (activationGen > 0L) lassoMode = true
        }
    }

    fun toast(msg: String) {
        scope.launch { snackbar.showSnackbar(msg, withDismissAction = true) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TacticalMap(
            modifier = Modifier.fillMaxSize(),
            // Restore the operator's last pan/zoom across bottom-nav
            // switches. Falls back to TacticalMap's own default on first
            // run / fresh process. Issue #7.
            initialCenter = run {
                val lat = app.mapCameraStore.lastTargetLat
                val lon = app.mapCameraStore.lastTargetLon
                if (lat != null && lon != null) LatLng(lat, lon) else LatLng(47.6588, -117.4260)
            },
            initialZoom = app.mapCameraStore.lastZoom ?: 11.0,
            onCameraIdle = { target, zoom ->
                app.mapCameraStore.update(target.latitude, target.longitude, zoom)
            },
            onMapReady = { map -> mapboxMap = map },
            // GAP-101 / GAP-107 — react to the basemap selection from Settings.
            // WMTS_CUSTOM uses the operator-pasted XYZ tile URL.
            styleJson = styleJsonForProvider(userPrefs.mapProvider, userPrefs.customTileUrl),
            onMapLongPress = { latLng, offset ->
                if (measurementActive) return@TacticalMap
                radialLatLng = latLng
                radialAnchor = offset
            },
            onContactTap = { event ->
                if (measurementActive) return@TacticalMap
                editingMarker = event
                markerSheetLatLng = LatLng(event.lat, event.lon)
            },
            onMapSingleTap = onMapSingleTap@ { latLng ->
                // Hit-test existing mission waypoints first so a tap on
                // a pin opens its edit sheet instead of adding a new
                // waypoint on top of it. ~80 m radius covers both
                // missionMode (adding) and view-only modes — pins are
                // 28dp visual, but operator GPS-tap accuracy isn't
                // pixel-perfect on a moving thumb.
                val waypoints = app.uasManager.mission.value.waypoints
                val hitIdx = waypoints.indexOfFirst { wp ->
                    val dLat = wp.latDeg - latLng.latitude
                    val dLon = wp.lonDeg - latLng.longitude
                    val metersPerDegLat = 111_320.0
                    val metersPerDegLon = 111_320.0 * kotlin.math.cos(Math.toRadians(latLng.latitude))
                    kotlin.math.hypot(dLat * metersPerDegLat, dLon * metersPerDegLon) < 80.0
                }
                if (hitIdx >= 0) {
                    editingWaypointIndex = hitIdx
                    return@onMapSingleTap true
                }
                when {
                    missionMode -> {
                        app.uasManager.missionStore.addWaypoint(latLng.latitude, latLng.longitude)
                        true
                    }
                    measurementActive -> {
                        measurementPoints = measurementPoints + latLng
                        true
                    }
                    drawingKind != null -> {
                        drawingPoints = drawingPoints + latLng
                        true
                    }
                    else -> false
                }
            },
            locationEnabled = locationGranted,
            recenterTrigger = recenterTick,
            zoomInTrigger = zoomInTick,
            zoomOutTrigger = zoomOutTick,
            contacts = if (contactsVisible) {
                if (meshNodesVisible) contacts.values
                // Hide mesh-origin contacts — they all share the
                // `MESHTASTIC-` UID prefix produced by
                // `MeshtasticCoTConverter.takUid`.
                else contacts.values.filterNot { it.uid.startsWith("MESHTASTIC-") }
            } else {
                emptyList()
            },
            measurementPoints = measurementPoints,
            drawings = if (drawingsVisible) {
                drawings + buildInProgressDrawing(drawingKind, drawingPoints)
            } else {
                emptyList()
            },
            gridCenter = if (gridEnabled) LatLng(37.42, -122.08) else null,
            aircraft = if (aircraftVisible) aircraft else emptyList(),
            panTarget = panTarget,
            panTargetTick = panTargetTick,
            followMeActive = followMeActive,
            useMilStdSelfSymbol = userPrefs.useMilStdSelfSymbol,
        )

        // Issue #16 — lasso freehand multi-select overlay. Renders
        // ABOVE the map so the dashed orange path stays on top of
        // marker layers (the Android equivalent of the iOS
        // CAShapeLayer fix). Inert when `active = false` — pan/zoom
        // continue to land on TacticalMap.
        // Drawing → LassoDrawing adapter. Drawing.id is a String (TAK
        // UID style); LassoDrawing.id is UUID for parity with the iOS
        // service shape. We project the string onto a deterministic
        // UUID via nameUUIDFromBytes so the same drawing always maps
        // to the same UUID across recompositions and we can reverse
        // the mapping during bulk delete.
        val lassoDrawingIdMap = remember(drawings) {
            drawings.associate {
                java.util.UUID.nameUUIDFromBytes(it.id.toByteArray()) to it.id
            }
        }
        val lassoDrawings = remember(drawings) {
            drawings.map { d ->
                soy.engindearing.omnitak.mobile.domain.LassoDrawing(
                    id = java.util.UUID.nameUUIDFromBytes(d.id.toByteArray()),
                    coordinates = d.points.map { (lat, lon) ->
                        soy.engindearing.omnitak.mobile.domain.LassoLatLng(latitude = lat, longitude = lon)
                    },
                )
            }
        }

        // Link line operator → drone + drone trail. Rendered BEFORE the
        // drone overlay so the cyan marker lands on top of its own tail.
        val opFix by app.locationProvider.fix.collectAsState()
        soy.engindearing.omnitak.mobile.ui.components.UasLinkAndTrail(
            drone = droneState,
            operator = opFix,
            mapboxMap = mapboxMap,
        )

        soy.engindearing.omnitak.mobile.ui.components.DroneOverlay(
            drone = droneForMap,
            mapboxMap = mapboxMap,
        )

        soy.engindearing.omnitak.mobile.ui.components.MissionOverlay(
            mission = mission,
            mapboxMap = mapboxMap,
            onWaypointClick = { idx -> editingWaypointIndex = idx },
        )
        editingWaypointIndex?.let { idx ->
            mission.waypoints.getOrNull(idx)?.let { wp ->
                val homeMsl = droneState.altMslMeters ?: 0.0
                val cruise = app.uasManager.cruiseAlt.value
                val cruiseMsl = cruise.toMsl(homeMsl)
                soy.engindearing.omnitak.mobile.ui.components.WaypointEditSheet(
                    index = idx,
                    waypoint = wp,
                    cruiseHintMsl = cruiseMsl,
                    onApply = { updated -> app.uasManager.missionStore.updateWaypoint(idx, updated) },
                    onDelete = { app.uasManager.missionStore.removeWaypoint(idx) },
                    onDismiss = { editingWaypointIndex = null },
                )
            }
        }

        // -------- UAS HUD: cruise altitude pill + Follow-Me toggle --------
        val cruiseAlt by app.uasManager.cruiseAlt.collectAsState()
        val followActive by app.uasManager.followMeActive.collectAsState()
        var altSheetOpen by remember { mutableStateOf(false) }
        if (droneState.isConnected()) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 90.dp, end = 12.dp),
                contentAlignment = Alignment.TopEnd,
            ) {
                val operatorFix by app.locationProvider.fix.collectAsState()
                val terrainBelowDrone by app.uasManager.terrainBelowDroneMsl.collectAsState()
                androidx.compose.foundation.layout.Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                ) {
                    soy.engindearing.omnitak.mobile.ui.components.UasAltitudePill(
                        cruise = cruiseAlt,
                        onClick = { altSheetOpen = true },
                    )
                    soy.engindearing.omnitak.mobile.ui.components.UasSituationCard(
                        drone = droneState,
                        operator = operatorFix,
                        terrainBelowDroneMsl = terrainBelowDrone,
                    )
                    soy.engindearing.omnitak.mobile.ui.components.UasFollowMePill(
                        active = followActive,
                        onToggle = {
                            scope.launch {
                                if (followActive) {
                                    app.uasManager.stopFollowMe()
                                    toast("Follow-Me OFF — drone parked in LOITER")
                                } else {
                                    val r = app.uasManager.startFollowMe()
                                    val msg = when (r) {
                                        soy.engindearing.omnitak.mobile.domain.UASManager.FollowMeResult.Started ->
                                            "Following you at ${cruiseAlt.meters.toInt()} m above"
                                        soy.engindearing.omnitak.mobile.domain.UASManager.FollowMeResult.NoGpsFix ->
                                            "Need your GPS fix first — open the map a moment"
                                        soy.engindearing.omnitak.mobile.domain.UASManager.FollowMeResult.NotConnected ->
                                            "UAS link down"
                                        is soy.engindearing.omnitak.mobile.domain.UASManager.FollowMeResult.AutopilotNotSupported ->
                                            "Follow-Me requires PX4 (autopilot=${r.autopilot ?: "unknown"})"
                                    }
                                    toast(msg)
                                }
                            }
                        },
                    )
                }
            }
        }
        if (altSheetOpen) {
            soy.engindearing.omnitak.mobile.ui.components.UasAltitudeSheet(
                current = cruiseAlt,
                onApply = { newCruise ->
                    app.uasManager.setCruiseAltitude(newCruise.meters, newCruise.frame)
                },
                onDismiss = { altSheetOpen = false },
            )
        }

        if (missionMode || mission.waypoints.isNotEmpty()) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 90.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                soy.engindearing.omnitak.mobile.ui.components.MissionBanner(
                    missionMode = missionMode,
                    mission = mission,
                    onUploadAndStart = {
                        scope.launch { app.uasManager.uploadAndStartMission() }
                    },
                    onUndo = { app.uasManager.missionStore.undoWaypoint() },
                    onCancel = { app.uasManager.cancelMission() },
                    onExitMissionMode = { missionMode = false },
                )
            }
        }

        // -------- In-flight control bar (armed only) — bottom-center --------
        if (droneState.isConnected() && droneState.armed == true) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 24.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                soy.engindearing.omnitak.mobile.ui.components.UasControlBar(
                    drone = droneState,
                    mission = mission,
                    onLand = {
                        scope.launch { app.uasManager.landHere() }
                        toast("LAND HERE — drone descending at current position")
                    },
                    onPause = {
                        scope.launch { app.uasManager.pauseMission() }
                        toast("Mission PAUSED — hovering in place")
                    },
                    onResume = {
                        scope.launch { app.uasManager.resumeMission() }
                        toast("Mission RESUMED")
                    },
                    onRtl = {
                        scope.launch { app.uasManager.returnToLaunch() }
                        toast("RTL — returning to launch")
                    },
                    onEmergencyStop = {
                        scope.launch { app.uasManager.emergencyStop() }
                        toast("EMERGENCY STOP — motors cut")
                    },
                )
            }
        }

        soy.engindearing.omnitak.mobile.ui.components.LassoOverlay(
            active = lassoMode,
            mapboxMap = mapboxMap,
            markers = (contacts.values).map { c ->
                soy.engindearing.omnitak.mobile.domain.LassoMarker(
                    id = c.uid,
                    coordinate = soy.engindearing.omnitak.mobile.domain.LassoLatLng(
                        latitude = c.lat,
                        longitude = c.lon,
                    ),
                )
            },
            drawings = lassoDrawings,
            onCompleted = { lassoMode = false },
            onCancelled = { lassoMode = false },
        )

        // Issue #16 — selection pill. Surfaces "N selected" whenever
        // there's an active lasso selection. Tap → action sheet.
        if (lassoSelection.totalCount > 0) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 90.dp, end = 16.dp),
                contentAlignment = Alignment.TopEnd,
            ) {
                soy.engindearing.omnitak.mobile.ui.components.LassoSelectionPill(
                    count = lassoSelection.totalCount,
                    onShowActions = { lassoActionsOpen = true },
                )
            }
        }
        if (lassoActionsOpen) {
            // Resolve the selection's UIDs back to the live CoTEvent
            // objects in the contact store. Filters out anything
            // that's already been removed since the lasso closed.
            val selectedEvents = lassoSelection.markerIDs.mapNotNull { contacts[it] }

            soy.engindearing.omnitak.mobile.ui.components.LassoActionsSheet(
                selectionCount = lassoSelection.totalCount,
                onDismiss = { lassoActionsOpen = false },
                onAddToDataPackage = {
                    lassoActionsOpen = false
                    if (selectedEvents.isEmpty()) {
                        toast("Selection is empty")
                    } else {
                        val ctx = appContext
                        scope.launch {
                            val file = withContext(Dispatchers.IO) {
                                soy.engindearing.omnitak.mobile.domain.LassoExporters.writeMissionPackage(
                                    context = ctx,
                                    name = "Lasso selection (${selectedEvents.size})",
                                    events = selectedEvents,
                                )
                            }
                            val shared = soy.engindearing.omnitak.mobile.domain.LassoExporters.shareFile(
                                context = ctx,
                                file = file,
                                mimeType = "application/zip",
                                title = "Lasso data package",
                            )
                            toast(
                                if (shared) "Mission package built · share sheet open"
                                else "Saved package to ${file.parentFile?.name}/${file.name}"
                            )
                        }
                    }
                },
                onExportKML = {
                    lassoActionsOpen = false
                    if (selectedEvents.isEmpty()) {
                        toast("Selection is empty")
                    } else {
                        val ctx = appContext
                        scope.launch {
                            val file = withContext(Dispatchers.IO) {
                                soy.engindearing.omnitak.mobile.domain.LassoExporters.writeKml(
                                    context = ctx,
                                    name = "Lasso selection (${selectedEvents.size})",
                                    events = selectedEvents,
                                )
                            }
                            val shared = soy.engindearing.omnitak.mobile.domain.LassoExporters.shareFile(
                                context = ctx,
                                file = file,
                                mimeType = "application/vnd.google-earth.kml+xml",
                                title = "Lasso KML",
                            )
                            toast(
                                if (shared) "KML built · share sheet open"
                                else "Saved KML to ${file.parentFile?.name}/${file.name}"
                            )
                        }
                    }
                },
                onSendToContacts = {
                    lassoActionsOpen = false
                    if (selectedEvents.isEmpty()) {
                        toast("Selection is empty")
                    } else {
                        contactPickerOpen = true
                    }
                },
                onBulkDelete = {
                    val n = lassoSelection.totalCount
                    val mySelfUid = userPrefs.callsign.takeIf { it.isNotBlank() }
                    val selfFixUid = selfFix?.let { "OMNI-${userPrefs.callsign}" }
                    val targetEvents = selectedEvents
                    // Self-marker guard: never accidentally delete our
                    // own CoT — that would propagate "delete me" to
                    // every peer.
                    val protected = setOfNotNull(mySelfUid, selfFixUid)
                    val toRemove = targetEvents.filterNot { it.uid in protected }

                    // 1) Local removal — ContactStore is the source of
                    //    truth for the map renderer; drop them
                    //    immediately so the operator sees the result.
                    toRemove.forEach { app.contactStore.remove(it.uid) }
                    // 2) Drawings: project selection UUIDs back to the
                    //    drawing.id String via the side-map built when
                    //    we adapted Drawings → LassoDrawings.
                    val deletedDrawings = lassoSelection.drawingIDs
                        .mapNotNull { lassoDrawingIdMap[it] }
                    deletedDrawings.forEach { app.drawingStore.remove(it) }
                    // 3) Broadcast — for each deleted marker, fire a
                    //    t-x-d-d tombstone on the active server so
                    //    other EUDs propagate the removal. Best-effort:
                    //    if the connection is down the local removal
                    //    still stands.
                    scope.launch {
                        val senderUid = mySelfUid ?: "OMNI-${java.util.UUID.randomUUID()}"
                        toRemove.forEach { e ->
                            val xml = soy.engindearing.omnitak.mobile.domain.CotBuilders
                                .buildDeleteEvent(targetUid = e.uid, senderUid = senderUid)
                            runCatching { app.serverManager.sendCoT(xml) }
                        }
                    }
                    lassoService.clear()
                    lassoActionsOpen = false
                    val drawingNote = if (deletedDrawings.isNotEmpty())
                        " + ${deletedDrawings.size} drawing(s)" else ""
                    toast(
                        if (toRemove.size == targetEvents.size)
                            "Deleted ${toRemove.size} marker(s)$drawingNote + broadcast"
                        else
                            "Deleted ${toRemove.size}/$n (self skipped)$drawingNote"
                    )
                },
                onClear = {
                    lassoService.clear()
                    lassoActionsOpen = false
                },
            )
        }
        if (contactPickerOpen) {
            // Snapshot the events the lasso captured AND the rest of
            // ContactStore so the picker has someone to send TO. We
            // exclude the selection itself — sending markers to
            // themselves makes no sense.
            val selected = lassoSelection.markerIDs
            soy.engindearing.omnitak.mobile.ui.components.ContactPickerDialog(
                title = "Send ${lassoSelection.totalCount} item(s) to…",
                candidates = contacts.values.toList(),
                excludeUids = selected,
                onDismiss = { contactPickerOpen = false },
                onConfirm = { destUids ->
                    contactPickerOpen = false
                    if (destUids.isEmpty()) {
                        toast("No recipients selected")
                        return@ContactPickerDialog
                    }
                    val selectedEvents = selected.mapNotNull { contacts[it] }
                    scope.launch {
                        var sent = 0
                        selectedEvents.forEach { e ->
                            val xml = soy.engindearing.omnitak.mobile.domain.CotBuilders
                                .rebuildEvent(e, destUids.toList())
                            if (runCatching { app.serverManager.sendCoT(xml) }
                                    .getOrDefault(false)
                            ) sent++
                        }
                        toast("Sent $sent/${selectedEvents.size} → ${destUids.size} recipient(s)")
                    }
                },
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .align(Alignment.TopCenter),
        ) {
            ATAKStatusBar(
                serverName = headerLabel,
                isConnected = connState is ConnectionState.Connected,
                messagesReceived = msgReceived,
                messagesSent = msgSent,
                gpsAccuracyMeters = selfFix?.accuracyM?.toInt() ?: 0,
                timeLabel = nowLabel,
                // GAP-102 — wire the previously dead status-bar taps to
                // their natural destinations. Server icon goes to the
                // Servers tab (also helps GAP-105 — server auth lives
                // there). Hamburger goes to Settings.
                onServerTap = { onOpenTab("servers") },
                onMenuTap = { onOpenTab("settings") },
            )
        }

        // PPLI self-position card — bottom-right, mirrors iOS layout.
        // GAP-030b — coordinates pull from FusedLocationProviderClient
        // (LocationProvider). Until a fix arrives we show "Acquiring
        // fix…" so users in Germany don't see San Francisco (issue #10).
        if (callsignCardVisible) {
            val fix = selfFix
            SelfPositionCard(
                callsign = userPrefs.callsign,
                coordinateLabel = if (fix != null) {
                    soy.engindearing.omnitak.mobile.data.CoordFormatter.position(
                        fix.lat, fix.lon, userPrefs.coordFormat,
                    )
                } else {
                    "Acquiring fix…"
                },
                altitudeLabel = soy.engindearing.omnitak.mobile.data.CoordFormatter.altitude(
                    fix?.altitudeM ?: 0.0, userPrefs.distanceUnit,
                ),
                speedLabel = soy.engindearing.omnitak.mobile.data.CoordFormatter.speed(
                    fix?.speedKmh ?: 0.0, userPrefs.distanceUnit,
                ),
                accuracyLabel = soy.engindearing.omnitak.mobile.data.CoordFormatter.accuracy(
                    fix?.accuracyM?.toInt() ?: 0, userPrefs.distanceUnit,
                ),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 96.dp),
            )
        }

        ToolsDrawer(
            tools = listOf(
                ToolEntry("draw", Icons.Filled.Brush, "Drawing"),
                ToolEntry("measure", Icons.Filled.Straighten, "Measure"),
                ToolEntry("layers", Icons.Filled.Layers, "Layers"),
                ToolEntry("adsb", Icons.Filled.Flight, if (adsbActive) "ADSB on" else "ADSB"),
                ToolEntry("chat", Icons.Filled.Chat, "Chat"),
                ToolEntry("teams", Icons.Filled.Groups, "Teams"),
                ToolEntry(
                    "nav",
                    Icons.Filled.Navigation,
                    if (followMeActive) "Follow on" else "Follow",
                ),
            ),
            onSelect = { tool ->
                when (tool.id) {
                    "measure" -> {
                        measurementActive = true
                        measurementPoints = emptyList()
                        toast("Measure mode — tap map to add points")
                    }
                    "draw" -> drawingPickerOpen = true
                    "layers" -> layersSheetOpen = true
                    "adsb" -> {
                        if (adsbActive) {
                            adsbService.stop()
                            toast("ADSB off")
                        } else {
                            // Bay Area box until we plumb the live camera
                            // center through — matches the emulator's
                            // default Mountain View GPS so aircraft stay
                            // on-screen during dev.
                            adsbService.start(
                                centerLat = 37.42,
                                centerLon = -122.08,
                                halfWidthDegrees = 2.5,
                            )
                            toast("ADSB on — polling OpenSky every 15s")
                        }
                    }
                    "chat" -> onOpenTab("chat")
                    "teams" -> teamsPanelOpen = true
                    "nav" -> {
                        if (!locationGranted) {
                            toast("Follow-me needs location permission")
                        } else {
                            val next = !followMeActive
                            mutatePref { it.copy(followMeActive = next) }
                            toast(if (next) "Follow me ON" else "Follow me OFF")
                        }
                    }
                }
            },
            modifier = Modifier.align(Alignment.BottomEnd),
        )

        // Map control stack — zoom in / zoom out / center-on-me — at the
        // BottomStart corner so it stays reachable one-handed without
        // opening the tools drawer. Mirrors the iOS map controls layout.
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 110.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            MapControlFab(
                icon = Icons.Filled.Add,
                contentDescription = "Zoom in",
                tint = TacticalAccent,
                onClick = { zoomInTick++ },
            )
            MapControlFab(
                icon = Icons.Filled.Remove,
                contentDescription = "Zoom out",
                tint = TacticalAccent,
                onClick = { zoomOutTick++ },
            )
            MapControlFab(
                icon = Icons.Filled.MyLocation,
                contentDescription = "Center on me",
                tint = if (locationGranted) TacticalAccent else androidx.compose.ui.graphics.Color.Gray,
                enabled = locationGranted,
                onClick = { recenterTick++ },
            )
            // Recenter on the drone — only when UAS is connected with a
            // fix. Tap to jump camera to drone's current position at the
            // operator's current zoom (don't change zoom — pilot may have
            // intentionally zoomed for context).
            if (droneState.isConnected() && droneState.latDeg != null && droneState.lonDeg != null) {
                MapControlFab(
                    icon = Icons.Filled.FlightTakeoff,
                    contentDescription = "Center on drone",
                    tint = androidx.compose.ui.graphics.Color(0xFF00E5FF),
                    onClick = {
                        mapboxMap?.let { m ->
                            val pos = m.cameraPosition
                            m.cameraPosition = org.maplibre.android.camera.CameraPosition.Builder()
                                .target(LatLng(droneState.latDeg!!, droneState.lonDeg!!))
                                .zoom(pos.zoom.coerceAtLeast(15.0))
                                .build()
                        }
                    },
                )
            }
        }

        // Surface a "Fly UAS here" action in the radial menu only when a
        // drone is actually connected — keeps the menu clean for users
        // who never touch a UAS.
        val uasConnected = droneState.isConnected()
        RadialMenu(
            visible = radialAnchor != null,
            anchor = radialAnchor ?: Offset.Zero,
            actions = buildList {
                add(RadialAction("drop", Icons.Filled.Place, "Drop Marker"))
                add(RadialAction("measure", Icons.Filled.Straighten, "Measure"))
                add(RadialAction("nav", Icons.Filled.Navigation, "Navigate"))
                add(RadialAction("layers", Icons.Filled.Layers, "Layers"))
                add(RadialAction("copy", Icons.Filled.LocationOn, "Copy Coords"))
                if (uasConnected) {
                    add(RadialAction("uas_fly_here", Icons.Filled.FlightTakeoff, "Fly UAS Here"))
                    add(RadialAction("uas_waypoint", Icons.Filled.AddLocation, "UAS Waypoint"))
                    add(RadialAction("uas_orbit", Icons.Filled.Refresh, "Orbit Here"))
                }
                add(RadialAction("center", Icons.Filled.Explore, "Center"))
                add(RadialAction("add", Icons.Filled.Add, "Add"))
            },
            onSelect = { action ->
                val ll = radialLatLng
                radialAnchor = null
                radialLatLng = null
                when (action.id) {
                    "drop" -> if (ll != null) markerSheetLatLng = ll
                    "layers" -> layersSheetOpen = true
                    "uas_fly_here" -> if (ll != null) {
                        // MAV_CMD_DO_REPOSITION at the operator's cruise
                        // altitude, after a TAK Terrain safety check.
                        // Result is surfaced as a toast — blocked
                        // commands include the exact clearance number
                        // so the operator knows what to change.
                        scope.launch {
                            val result = app.uasManager.flyTo(ll.latitude, ll.longitude)
                            val msg = when (result) {
                                is soy.engindearing.omnitak.mobile.domain.UASManager.FlyHereResult.Sent ->
                                    if (result.clearance != null)
                                        "UAS → ${"%.4f, %.4f".format(ll.latitude, ll.longitude)} " +
                                            "(${result.targetMsl.toInt()}m MSL, ${result.clearance.toInt()}m AGL)"
                                    else
                                        "UAS → ${"%.4f, %.4f".format(ll.latitude, ll.longitude)} (${result.targetMsl.toInt()}m MSL)"
                                is soy.engindearing.omnitak.mobile.domain.UASManager.FlyHereResult.WouldHitTerrain ->
                                    "BLOCKED: target ${result.targetMsl.toInt()}m would clip terrain at " +
                                        "${result.terrainMsl.toInt()}m (clearance ${result.clearance.toInt()}m). Raise cruise alt."
                                soy.engindearing.omnitak.mobile.domain.UASManager.FlyHereResult.NoGpsFix ->
                                    "UAS has no GPS fix yet — wait for telemetry"
                                soy.engindearing.omnitak.mobile.domain.UASManager.FlyHereResult.NotConnected ->
                                    "UAS link down — reconnect first"
                            }
                            toast(msg)
                        }
                    }
                    "uas_waypoint" -> if (ll != null) {
                        // Seed the mission with this waypoint AND enter
                        // missionMode so further taps keep dropping pins.
                        app.uasManager.missionStore.addWaypoint(ll.latitude, ll.longitude)
                        missionMode = true
                        toast("WP${mission.waypoints.size + 1} dropped — tap more or hit Upload")
                    }
                    "uas_orbit" -> if (ll != null) {
                        // MAV_CMD_DO_ORBIT at cruise altitude, default 50 m radius.
                        // Fast-follow: radius slider on the orbit action.
                        scope.launch { app.uasManager.orbitPoint(ll.latitude, ll.longitude) }
                        toast("Orbiting ${"%.4f, %.4f".format(ll.latitude, ll.longitude)} @ 50 m radius")
                    }
                    else -> {
                        // Respect the operator's coordinate-format pref (Lat/Lon,
                        // DMS, MGRS, UTM) so the "Add @ …" toast matches the
                        // SelfPositionCard readout instead of always lat/lon.
                        val coord = ll?.let {
                            soy.engindearing.omnitak.mobile.data.CoordFormatter.position(
                                it.latitude, it.longitude, userPrefs.coordFormat,
                            )
                        } ?: ""
                        toast("${action.label}${if (coord.isNotEmpty()) " @ $coord" else ""}")
                    }
                }
            },
            onDismiss = {
                radialAnchor = null
                radialLatLng = null
            },
        )

        MarkerEditSheet(
            visible = markerSheetLatLng != null,
            latLng = markerSheetLatLng,
            initialCallsign = editingMarker?.callsign ?: "Marker ${contacts.size + 1}",
            initialAffiliation = editingMarker?.affiliation ?: CoTAffiliation.FRIEND,
            initialAltitude = editingMarker?.hae?.takeIf { it != 0.0 },
            initialRemarks = editingMarker?.remarks ?: "",
            editing = editingMarker != null,
            onSave = { result ->
                val ll = markerSheetLatLng
                if (ll != null) {
                    val uid = editingMarker?.uid ?: "local-${System.currentTimeMillis()}"
                    app.contactStore.ingest(
                        CoTEvent(
                            uid = uid,
                            type = "a-${result.affiliation.code}-G-U-C",
                            lat = ll.latitude,
                            lon = ll.longitude,
                            hae = result.altitudeMeters ?: 0.0,
                            callsign = result.callsign,
                            remarks = result.remarks,
                        )
                    )
                    val verb = if (editingMarker != null) "Updated" else "Saved"
                    toast("$verb ${result.affiliation.name.lowercase()} marker “${result.callsign}”")
                }
                markerSheetLatLng = null
                editingMarker = null
            },
            onDelete = editingMarker?.let {
                {
                    app.contactStore.remove(it.uid)
                    toast("Deleted marker “${it.callsign ?: it.uid}”")
                    markerSheetLatLng = null
                    editingMarker = null
                }
            },
            onPursueWithUas = editingMarker?.takeIf { droneState.isConnected() }?.let { mk ->
                {
                    val uid = mk.uid
                    val callsign = mk.callsign ?: uid.takeLast(6)
                    scope.launch {
                        val r = app.uasManager.startPursueContact(uid, callsign) {
                            // Re-read each tick so we track the contact as it moves.
                            val live = app.contactStore.contacts.value[uid] ?: return@startPursueContact null
                            live.lat to live.lon
                        }
                        val msg = when (r) {
                            soy.engindearing.omnitak.mobile.domain.UASManager.FollowMeResult.Started ->
                                "Pursuing “$callsign” at ${app.uasManager.cruiseAlt.value.meters.toInt()} m above terrain"
                            soy.engindearing.omnitak.mobile.domain.UASManager.FollowMeResult.NoGpsFix ->
                                "Contact has no position fix"
                            soy.engindearing.omnitak.mobile.domain.UASManager.FollowMeResult.NotConnected ->
                                "UAS link down"
                            is soy.engindearing.omnitak.mobile.domain.UASManager.FollowMeResult.AutopilotNotSupported ->
                                "Pursue requires PX4 (autopilot=${r.autopilot ?: "unknown"})"
                        }
                        toast(msg)
                    }
                    markerSheetLatLng = null
                    editingMarker = null
                }
            },
            onDismiss = {
                markerSheetLatLng = null
                editingMarker = null
            },
        )

        if (drawingKind != null) {
            DrawingOverlay(
                kind = drawingKind!!,
                pointCount = drawingPoints.size,
                onUndo = {
                    if (drawingPoints.isNotEmpty()) {
                        drawingPoints = drawingPoints.dropLast(1)
                    }
                },
                onCancel = {
                    drawingKind = null
                    drawingPoints = emptyList()
                },
                onFinish = {
                    val minPts = when (drawingKind!!) {
                        DrawingKind.LINE -> 2
                        DrawingKind.POLYGON -> 3
                        DrawingKind.CIRCLE -> 2
                    }
                    if (drawingPoints.size >= minPts) {
                        app.drawingStore.add(
                            Drawing(
                                id = "draw-${System.currentTimeMillis()}",
                                kind = drawingKind!!,
                                points = drawingPoints.map { it.latitude to it.longitude },
                            )
                        )
                        toast("Saved ${drawingKind!!.name.lowercase()}")
                    } else {
                        toast("Need at least $minPts points")
                    }
                    drawingKind = null
                    drawingPoints = emptyList()
                },
                modifier = Modifier.align(Alignment.TopStart),
            )
        }

        if (drawingPickerOpen) {
            DrawingKindPicker(
                onPick = { kind ->
                    drawingPickerOpen = false
                    drawingKind = kind
                    drawingPoints = emptyList()
                    toast("Drawing ${kind.name.lowercase()} — tap to add points")
                },
                onDismiss = { drawingPickerOpen = false },
            )
        }

        if (measurementActive) {
            MeasurementOverlay(
                points = measurementPoints,
                onUndo = {
                    if (measurementPoints.isNotEmpty()) {
                        measurementPoints = measurementPoints.dropLast(1)
                    }
                },
                onClose = {
                    measurementActive = false
                    measurementPoints = emptyList()
                },
                modifier = Modifier.align(Alignment.TopStart),
            )
        }

        if (layersSheetOpen) {
            LayersDialog(
                gridEnabled = gridEnabled,
                drawingsVisible = drawingsVisible,
                aircraftVisible = aircraftVisible,
                contactsVisible = contactsVisible,
                callsignCardVisible = callsignCardVisible,
                meshNodesVisible = meshNodesVisible,
                onToggleGrid = { v -> mutatePref { it.copy(gridEnabled = v) } },
                onToggleDrawings = { v -> mutatePref { it.copy(drawingsVisible = v) } },
                onToggleAircraft = { v -> mutatePref { it.copy(aircraftVisible = v) } },
                onToggleContacts = { v -> mutatePref { it.copy(contactsVisible = v) } },
                onToggleCallsignCard = { v -> mutatePref { it.copy(callsignCardVisible = v) } },
                onToggleMeshNodes = { v ->
                    scope.launch { app.userPrefsStore.setMeshNodesLayerVisible(v) }
                },
                onDismiss = { layersSheetOpen = false },
            )
        }

        if (teamsPanelOpen) {
            ContactsPanel(
                contacts = contacts.values.toList(),
                onSelect = { c ->
                    panTarget = LatLng(c.lat, c.lon)
                    panTargetTick += 1
                    if (followMeActive) mutatePref { it.copy(followMeActive = false) }
                    teamsPanelOpen = false
                    toast("Panning to ${c.callsign ?: c.uid}")
                },
                onDismiss = { teamsPanelOpen = false },
            )
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

private fun buildInProgressDrawing(kind: DrawingKind?, points: List<LatLng>): List<Drawing> {
    if (kind == null || points.isEmpty()) return emptyList()
    return listOf(
        Drawing(
            id = "__in_progress__",
            kind = kind,
            points = points.map { it.latitude to it.longitude },
            colorHex = "#FFC107",  // amber while drafting
        )
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DrawingKindPicker(
    onPick: (DrawingKind) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = soy.engindearing.omnitak.mobile.ui.theme.TacticalSurface,
        title = { androidx.compose.material3.Text("Drawing tool", color = MaterialTheme.colorScheme.onBackground) },
        text = {
            androidx.compose.foundation.layout.Column {
                listOf(
                    DrawingKind.LINE to "Line — connected segments",
                    DrawingKind.POLYGON to "Polygon — closed shape",
                    DrawingKind.CIRCLE to "Circle — center + edge",
                ).forEach { (kind, label) ->
                    androidx.compose.material3.TextButton(
                        onClick = { onPick(kind) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        androidx.compose.material3.Text(
                            label,
                            color = TacticalAccent,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                androidx.compose.material3.Text("Cancel", color = TacticalAccent)
            }
        },
    )
}

@Composable
private fun DrawingOverlay(
    kind: DrawingKind,
    pointCount: Int,
    onUndo: () -> Unit,
    onCancel: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier
            .padding(top = 76.dp, start = 12.dp, end = 12.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .background(TacticalBackground.copy(alpha = 0.9f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Text(
            "${kind.name.lowercase().replaceFirstChar { it.uppercase() }} · $pointCount pt",
            color = TacticalAccent,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
        )
        androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
        androidx.compose.material3.TextButton(onClick = onUndo, enabled = pointCount > 0) {
            androidx.compose.material3.Text("Undo", color = TacticalAccent)
        }
        androidx.compose.material3.TextButton(onClick = onCancel) {
            androidx.compose.material3.Text("Cancel", color = TacticalAccent)
        }
        androidx.compose.material3.TextButton(onClick = onFinish) {
            androidx.compose.material3.Text("Save", color = TacticalAccent)
        }
    }
}

@Composable
private fun MeasurementOverlay(
    points: List<LatLng>,
    onUndo: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var totalMeters = 0.0
    for (i in 1 until points.size) {
        totalMeters += GeoMath.haversineMeters(
            points[i - 1].latitude, points[i - 1].longitude,
            points[i].latitude, points[i].longitude,
        )
    }
    val bearing = if (points.size >= 2) {
        val a = points[points.size - 2]
        val b = points[points.size - 1]
        GeoMath.bearingDegrees(a.latitude, a.longitude, b.latitude, b.longitude)
    } else null

    androidx.compose.foundation.layout.Row(
        modifier = modifier
            .padding(top = 76.dp, start = 12.dp, end = 12.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .background(TacticalBackground.copy(alpha = 0.9f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Text(
            buildString {
                append("${points.size} pt · ")
                append(GeoMath.formatDistance(totalMeters))
                if (bearing != null) append(" · ${GeoMath.formatBearing(bearing)}")
            },
            color = TacticalAccent,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
        )
        androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
        androidx.compose.material3.TextButton(onClick = onUndo, enabled = points.isNotEmpty()) {
            androidx.compose.material3.Text("Undo", color = TacticalAccent)
        }
        androidx.compose.material3.TextButton(onClick = onClose) {
            androidx.compose.material3.Text("Done", color = TacticalAccent)
        }
    }
}

private fun timeLabel(): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

/**
 * Standard tactical map control button — translucent dark disc with a
 * tactical-accent glyph. Used for zoom +/− and center-on-me; sized to
 * the same 48dp pip so the column reads as a single control stack.
 */
@Composable
private fun MapControlFab(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(TacticalBackground.copy(alpha = 0.9f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint)
    }
}
