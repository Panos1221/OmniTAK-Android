package soy.engindearing.omnitak.mobile.ui.components

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView

/**
 * Picture-in-picture live video for the drone camera. RTSP via
 * Media3 ExoPlayer + the RtspMediaSource. The URL is operator-supplied
 * (Settings → UAS → Video URL or per-session field on the Quick
 * Connect screen).
 *
 * Layout:
 *  - Compact (default): 160 × 90 dp 16:9 box, bottom-right of the map.
 *  - Expanded: 320 × 180 dp. Tap fullscreen icon to toggle.
 *  - Close icon: tears down the player and dismisses the PIP for this
 *    session (caller is responsible for re-showing on URL change).
 *
 * Player is paused / released on the host's onStop and resumed on
 * onStart so the RTSP socket doesn't leak when the operator backgrounds
 * the app.
 *
 * NOTE: forces RTSP TCP transport. UDP/RTP works on a LAN but breaks
 * through SSH tunnels (no UDP forwarding) and through many home
 * routers (inter-VLAN UDP block) — TCP is the universally compatible
 * choice, ~10 ms more latency, fine for ops video.
 */
@Composable
fun UasVideoPip(
    rtspUrl: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (rtspUrl.isBlank()) return
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var expanded by remember { mutableStateOf(false) }

    // Build the player once per URL; rebuild on URL change.
    val player = remember(rtspUrl) {
        ExoPlayer.Builder(context).build().apply {
            val factory = RtspMediaSource.Factory()
                .setForceUseRtpTcp(true) // see note above — TCP-only RTP
                .setTimeoutMs(8_000)
            val mediaSource = factory.createMediaSource(MediaItem.fromUri(rtspUrl))
            setMediaSource(mediaSource)
            prepare()
            playWhenReady = true
        }
    }

    // Release the player when the URL changes or the composable leaves
    // the composition. ExoPlayer holds native resources — the leak
    // shows up as a "Player still active in onCleared" log.
    DisposableEffect(player) {
        onDispose { player.release() }
    }

    // Pause when the host goes to background so the RTSP socket doesn't
    // chew battery; resume on foreground.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> player.pause()
                Lifecycle.Event.ON_START -> player.play()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val widthDp = if (expanded) 320.dp else 160.dp
    Box(
        modifier = modifier
            .width(widthDp)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    this.player = player
                }
            },
            update = { it.player = player },
        )

        // Live label (top-left) so the operator can tell at a glance
        // that this is the drone camera vs a map inset.
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFFFF3B30))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                text = "LIVE",
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        // Action row (top-right): expand/collapse, close.
        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(28.dp)) {
                Icon(
                    if (expanded) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    contentDescription = "Toggle size",
                    tint = Color.White,
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}
