package soy.engindearing.omnitak.mobile.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import soy.engindearing.omnitak.mobile.domain.ConnectionState
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.coroutineContext

/**
 * One TAK server connection. Pure JVM networking — no JNI/Rust needed.
 *
 * Lifecycle:
 *   Disconnected → Connecting → Connected → (read loop) → Disconnected
 *                             ↳ Failed (timeout / socket error)
 *
 * Mirrors the iOS DirectTCPSender behavior shipped in the 2.11.0 batch:
 *   - 15s connect timeout so "Connecting…" never sticks forever (iOS #40)
 *   - Single-shot state transitions guarded by coroutine cancellation
 */
class TAKConnection(
    private val server: TAKServer,
    private val certVault: CertVault? = null,
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectJob: Job? = null
    private var socket: Socket? = null

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    // Raw CoT-XML stream from the server. Parsing into CoT events
    // arrives in a later slice; for now callers can log/inspect.
    private val _received = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val received: SharedFlow<String> = _received.asSharedFlow()

    fun connect() {
        if (connectJob?.isActive == true) return
        _state.value = ConnectionState.Connecting(server.name)

        connectJob = scope.launch {
            try {
                val sock = withTimeout(CONNECT_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        if (server.useTLS) openTlsSocket() else openPlainSocket()
                    }
                }
                socket = sock
                _state.value = ConnectionState.Connected(server.name, server.useTLS)
                Log.i(TAG, "Connected to ${server.host}:${server.port} (tls=${server.useTLS})")
                readLoop(sock)
            } catch (_: TimeoutCancellationException) {
                Log.w(TAG, "Connect timed out after ${CONNECT_TIMEOUT_MS / 1000}s")
                _state.value = ConnectionState.Failed("Connection timed out")
                cleanup()
            } catch (t: Throwable) {
                Log.w(TAG, "Connect failed: ${t.javaClass.simpleName}: ${t.message}")
                _state.value = ConnectionState.Failed(t.message ?: t.javaClass.simpleName)
                cleanup()
            }
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        cleanup()
        _state.value = ConnectionState.Disconnected
    }

    /**
     * Fire-and-forget CoT XML send. Returns false if no socket is open
     * or if the write fails. Suspends on [Dispatchers.IO] so a UI-thread
     * caller (eg. ChatScreen's send button) doesn't trip
     * NetworkOnMainThreadException.
     */
    suspend fun send(xml: String): Boolean {
        val sock = socket ?: return false
        if (_state.value !is ConnectionState.Connected) return false
        return withContext(Dispatchers.IO) {
            try {
                val out = sock.getOutputStream()
                out.write(xml.toByteArray(Charsets.UTF_8))
                out.flush()
                true
            } catch (t: Throwable) {
                Log.w(TAG, "send failed: ${t.javaClass.simpleName}: ${t.message}")
                false
            }
        }
    }

    private fun openPlainSocket(): Socket {
        val s = Socket()
        s.tcpNoDelay = true
        s.soTimeout = READ_TIMEOUT_MS
        s.connect(InetSocketAddress(server.host, server.port), CONNECT_TIMEOUT_MS.toInt())
        return s
    }

    /**
     * Mutual-TLS when the operator imported a `.p12` for this server, plain
     * TLS otherwise. TAK servers default to mTLS — without a client cert the
     * server closes the connection at handshake (PEER_DID_NOT_RETURN_A_CERTIFICATE).
     *
     * Server-side trust resolves in this order (#38 / GAP-106):
     *   1. CA pin written during Quick Connect enrollment (preferred).
     *   2. System trust store — for legacy `.p12` imports without a pin.
     * No trust-all path remains.
     */
    private fun openTlsSocket(): Socket {
        val keyManagers = loadClientKeyManagers()
        if (keyManagers == null) {
            Log.w(TAG, "⚠ TLS without client cert — server may reject. Import a .p12 if mTLS is required.")
        } else {
            Log.i(TAG, "TLS with client cert ${server.certificateName} (mTLS)")
        }
        val trustManager = loadServerTrustManager()
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(keyManagers, arrayOf<TrustManager>(trustManager), SecureRandom())
        val s = ctx.socketFactory.createSocket() as SSLSocket
        s.tcpNoDelay = true
        s.soTimeout = READ_TIMEOUT_MS
        s.connect(InetSocketAddress(server.host, server.port), CONNECT_TIMEOUT_MS.toInt())
        s.startHandshake()
        return s
    }

    /**
     * Build the server-trust X509TrustManager for this connection.
     *
     * Prefers the enrollment-time CA pin (TAKServer.caCertificateName →
     * CertVault → CaTrust.trustManagerFor). Falls back to the system
     * trust store when no pin exists or the pin file fails to load —
     * legacy `.p12` imports from before Quick Connect existed end up on
     * this branch.
     *
     * Never returns a trust-all manager. If a server presents a cert
     * the pinned CA didn't sign (or the system bundle doesn't trust),
     * the handshake fails — that's the point.
     */
    private fun loadServerTrustManager(): X509TrustManager {
        val caName = server.caCertificateName
        val vault = certVault
        if (caName != null && vault != null) {
            val bytes = vault.read(caName)
            if (bytes != null) {
                val chain = runCatching { CaTrust.decodePemChain(bytes) }.getOrElse {
                    Log.w(TAG, "CA pin '$caName' parse failed: ${it.javaClass.simpleName}: ${it.message}")
                    emptyList()
                }
                if (chain.isNotEmpty()) {
                    Log.i(TAG, "TLS trust: pinned CA(s) from '$caName' (${chain.size} cert)")
                    return CaTrust.trustManagerFor(chain)
                }
                Log.w(TAG, "CA pin '$caName' present but yielded no certs — falling back to system trust")
            } else {
                Log.w(TAG, "CA pin '$caName' missing from vault — falling back to system trust")
            }
        }
        Log.i(TAG, "TLS trust: system trust store (no CA pin for this server)")
        return CaTrust.systemTrustManager()
    }

    /**
     * Build a KeyManager array from the operator-imported `.p12`. Returns
     * null when the server has no cert configured or the import failed —
     * both cases fall through to anonymous TLS (which most TAK servers
     * will reject, but we leave the option for non-mTLS deployments).
     */
    private fun loadClientKeyManagers(): Array<KeyManager>? {
        val name = server.certificateName ?: return null
        val pass = server.certificatePassword ?: return null
        val vault = certVault ?: run {
            Log.w(TAG, "Cert configured but no vault available — skipping client auth.")
            return null
        }
        val bytes = vault.read(name) ?: run {
            Log.w(TAG, "Cert '$name' missing from vault — skipping client auth.")
            return null
        }
        return runCatching {
            val ks = KeyStore.getInstance("PKCS12")
            ByteArrayInputStream(bytes).use { ks.load(it, pass.toCharArray()) }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, pass.toCharArray())
            kmf.keyManagers
        }.onFailure {
            Log.w(TAG, "Cert load failed: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()
    }

    private suspend fun readLoop(sock: Socket) {
        val reader: BufferedReader = sock.getInputStream().bufferedReader(Charsets.UTF_8)
        val buffer = StringBuilder()
        try {
            while (coroutineContext.isActive) {
                val ch = reader.read()
                if (ch == -1) break
                buffer.append(ch.toChar())
                if (buffer.endsWith(EVENT_CLOSE_TAG) || buffer.length > 64 * 1024) {
                    _received.tryEmit(buffer.toString())
                    buffer.clear()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Read loop ended: ${t.javaClass.simpleName}: ${t.message}")
        }
        if (_state.value is ConnectionState.Connected) {
            _state.value = ConnectionState.Disconnected
        }
        cleanup()
    }

    private fun cleanup() {
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        private const val TAG = "TAKConnection"
        const val CONNECT_TIMEOUT_MS = 15_000L
        private const val READ_TIMEOUT_MS = 0  // 0 = infinite, we handle cancellation via coroutine
        private const val EVENT_CLOSE_TAG = "</event>"
    }
}
