package com.screenlink.pro

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.Manifest
import android.media.MediaCodec
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Build
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.MotionEvent
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.firebase.firestore.ListenerRegistration
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.screenlink.pro.capture.CaptureService
import com.screenlink.pro.capture.EncodedFrame
import com.screenlink.pro.capture.H264Decoder
import com.screenlink.pro.capture.PlaybackAudioPlayer
import com.screenlink.pro.cloud.CloudSync
import com.screenlink.pro.control.RemoteControlAccessibilityService
import com.screenlink.pro.network.ScreenClient
import com.screenlink.pro.util.*
import com.screenlink.pro.webrtc.SdpObserverAdapter
import com.screenlink.pro.webrtc.WebRtcHostService
import com.screenlink.pro.webrtc.WebRtcInit
import com.screenlink.pro.webrtc.WebRtcSignaling
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

private val Blue = Color(0xFF2563EB)
private val Navy = Color(0xFF0F172A)
private val SoftBlue = Color(0xFFEFF6FF)
private val Green = Color(0xFF16A34A)
private val SoftGreen = Color(0xFFF0FDF4)

class MainActivity : ComponentActivity() {
    private val pendingViewUid = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestAppPermissions()
        if (!RemoteControlAccessibilityService.isEnabled()) {
            try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) { }
        }
        handleViewIntent(intent)
        setContent { ScreenLinkTheme { ScreenLinkApp(pendingViewUid) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
    }

    /** Handles the website's "View this screen" button: screenlinkpro://view?uid=<hostUid> */
    private fun handleViewIntent(intent: Intent?) {
        val uri: Uri = intent?.data ?: return
        if (uri.scheme == "screenlinkpro" && uri.host == "view") {
            uri.getQueryParameter("uid")?.let { pendingViewUid.value = it }
        }
    }

    private fun requestAppPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
            permissions += Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }
        requestPermissions(permissions.toTypedArray(), 100)
    }
}

@Composable
private fun ScreenLinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Blue, onPrimary = Color.White, background = Color(0xFFF8FAFC), surface = Color.White), content = content)
}

@Composable
private fun ScreenLinkApp(pendingViewUid: MutableState<String?>) {
    var page by rememberSaveable { mutableStateOf("home") }
    var viewerHostUid by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingViewUid.value) {
        pendingViewUid.value?.let { uid -> viewerHostUid = uid; page = "online_viewer"; pendingViewUid.value = null }
    }
    when (page) {
        "home" -> HomeScreen { page = it }
        "host" -> HostScreen { page = "home" }
        "viewer" -> ViewerScreen { page = "home" }
        "account" -> AccountScreen { page = "home" }
        "online_host" -> OnlineHostScreen(onBack = { page = "home" }, onNeedsLogin = { page = "account" })
        "online_viewer" -> {
            val uid = viewerHostUid
            if (uid != null) OnlineViewerScreen(hostUid = uid, onBack = { page = "home" })
            else HomeScreen { page = it }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(title: String, onBack: () -> Unit, scrollable: Boolean = true, fullScreen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(topBar = {
        if (!fullScreen) TopAppBar(title = { Text(title, fontWeight = FontWeight.Bold) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
        })
    }, containerColor = if (fullScreen) Color.Black else Color.White) { padding ->
        val contentModifier = Modifier.fillMaxSize().then(if (fullScreen) Modifier else Modifier.padding(padding).padding(horizontal = 22.dp)).let { base -> if (scrollable && !fullScreen) base.verticalScroll(rememberScrollState()) else base }
        Column(contentModifier, content = content)
    }
}

@Composable
private fun HomeScreen(navigate: (String) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        IconButton(onClick = { navigate("account") }, modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)) {
            Icon(Icons.Default.AccountCircle, "Internet account", tint = if (CloudSync.isLoggedIn()) Blue else Color(0xFF94A3B8))
        }
        Column(Modifier.fillMaxSize().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(92.dp).background(SoftBlue, RoundedCornerShape(28.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Cast, null, Modifier.size(48.dp), tint = Blue) }
            Spacer(Modifier.height(22.dp))
            Text("ScreenLink Pro", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = Navy)
            Spacer(Modifier.height(8.dp))
            Text("Screen sharing — on local Wi‑Fi or over the internet", color = Color(0xFF64748B), fontSize = 15.sp)
            Spacer(Modifier.height(40.dp))

            Text("LOCAL (same Wi‑Fi / hotspot)", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
            Spacer(Modifier.height(8.dp))
            Button({ navigate("host") }, Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(16.dp)) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(10.dp)); Text("Share my screen", fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(14.dp))
            OutlinedButton({ navigate("viewer") }, Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(16.dp)) { Icon(Icons.Default.Visibility, null); Spacer(Modifier.width(10.dp)); Text("View another screen", fontWeight = FontWeight.SemiBold) }

            Spacer(Modifier.height(30.dp))
            Text("ONLINE (over the internet, needs an account)", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
            Spacer(Modifier.height(8.dp))
            Button(
                { navigate("online_host") }, Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Green)
            ) { Icon(Icons.Default.Public, null); Spacer(Modifier.width(10.dp)); Text("Go live online", fontWeight = FontWeight.SemiBold) }
            Text("View a live device from screenlink-pro.web.app", color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun AccountScreen(onBack: () -> Unit) {
    var loggedIn by remember { mutableStateOf(CloudSync.isLoggedIn()) }
    var email by remember { mutableStateOf(CloudSync.currentEmail() ?: "") }
    var mode by rememberSaveable { mutableStateOf("login") }
    var emailInput by rememberSaveable { mutableStateOf("") }
    var passwordInput by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    AppScaffold("Internet account", onBack) {
        if (loggedIn) {
            Spacer(Modifier.height(6.dp))
            Text("Signed in as", color = Color(0xFF64748B), fontSize = 13.sp)
            Text(email, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(10.dp))
            Text(
                "Each account is its own device profile on the website. Anyone logged in to screenlink-pro.web.app can see which accounts are live right now and view them — this account included.",
                color = Color(0xFF64748B), fontSize = 13.sp
            )
            Spacer(Modifier.height(28.dp))
            OutlinedButton({ CloudSync.signOut(); loggedIn = false; email = ""; emailInput = ""; passwordInput = "" }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
                Text("Sign out")
            }
        } else {
            Spacer(Modifier.height(6.dp))
            Text(if (mode == "login") "Log in" else "Create an account", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = Navy)
            Spacer(Modifier.height(6.dp))
            Text(
                "Needed only for \"Go live online\" — viewing/being viewed over the internet from the website. Local sharing works fine without this.",
                color = Color(0xFF64748B), fontSize = 13.sp
            )
            Spacer(Modifier.height(20.dp))
            if (error != null) { ErrorCard(error!!); Spacer(Modifier.height(12.dp)) }
            OutlinedTextField(value = emailInput, onValueChange = { emailInput = it; error = null }, label = { Text("Email") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(value = passwordInput, onValueChange = { passwordInput = it; error = null }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    error = null; loading = true
                    val onResult: (Boolean, String?) -> Unit = { ok, message ->
                        loading = false
                        if (ok) { loggedIn = true; email = emailInput.trim() } else error = message ?: "Something went wrong. Please try again."
                    }
                    val trimmedEmail = emailInput.trim()
                    if (mode == "login") CloudSync.logIn(trimmedEmail, passwordInput, onResult)
                    else CloudSync.signUp(trimmedEmail, passwordInput, onResult)
                },
                modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(15.dp),
                enabled = !loading && emailInput.isNotBlank() && passwordInput.length >= 6
            ) {
                Text(if (loading) "Please wait…" else if (mode == "login") "Log in" else "Create account", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(14.dp))
            TextButton(onClick = { mode = if (mode == "login") "signup" else "login"; error = null }, modifier = Modifier.fillMaxWidth()) {
                Text(if (mode == "login") "New here? Create an account" else "Already have an account? Log in")
            }
        }
    }
}

/**
 * The internet-sharing counterpart to HostScreen. No local Wi-Fi/IP requirement — starts
 * WebRtcHostService, which captures the screen as a WebRTC video track and marks this account
 * live on the website regardless of whether a local network is available (mobile data is fine).
 * Video only for now; system audio over the internet is a separate future addition.
 */
@Composable
private fun OnlineHostScreen(onBack: () -> Unit, onNeedsLogin: () -> Unit) {
    val context = LocalContext.current
    val loggedIn = CloudSync.isLoggedIn()
    var live by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val service = Intent(context, WebRtcHostService::class.java).apply {
                action = WebRtcHostService.START
                putExtra(WebRtcHostService.RESULT, result.resultCode)
                putExtra(WebRtcHostService.DATA, result.data)
            }
            try { ContextCompat.startForegroundService(context, service); live = true; error = null }
            catch (e: Exception) { live = false; error = "Could not start sharing on this phone. Please allow screen capture and try again." }
        }
    }
    AppScaffold("Go live online", { if (live) context.startService(Intent(context, WebRtcHostService::class.java).setAction(WebRtcHostService.STOP)); onBack() }) {
        if (!loggedIn) {
            Spacer(Modifier.height(6.dp))
            Text("You need an account for online sharing", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Spacer(Modifier.height(8.dp))
            Text("Log in or create a free account, then come back here to go live.", color = Color(0xFF64748B), fontSize = 13.sp)
            Spacer(Modifier.height(20.dp))
            Button(onNeedsLogin, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(15.dp)) { Text("Log in / create account", fontWeight = FontWeight.Bold) }
        } else {
            Spacer(Modifier.height(6.dp))
            Text("Signed in as ${CloudSync.currentEmail()}", color = Color(0xFF64748B), fontSize = 13.sp)
            Spacer(Modifier.height(20.dp))
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = if (live) SoftGreen else SoftBlue)) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (live) Icons.Default.Public else Icons.Default.CloudOff, null, Modifier.size(40.dp), tint = if (live) Green else Blue)
                    Spacer(Modifier.height(10.dp))
                    Text(if (live) "You're live" else "Not sharing online yet", fontWeight = FontWeight.Bold, color = if (live) Green else Blue)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (live) "Anyone logged in to screenlink-pro.web.app can view this screen right now (video only, no audio yet)."
                        else "Start sharing to appear live on screenlink-pro.web.app — no Wi‑Fi needed, mobile data works.",
                        color = Color(0xFF64748B), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp)
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            if (error != null) { ErrorCard(error!!); Spacer(Modifier.height(12.dp)) }
            if (!live) Button({ projectionLauncher.launch((context.getSystemService(MediaProjectionManager::class.java)).createScreenCaptureIntent()) }, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text("Go live", fontWeight = FontWeight.Bold) }
            else OutlinedButton({ context.startService(Intent(context, WebRtcHostService::class.java).setAction(WebRtcHostService.STOP)); live = false }, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(15.dp)) { Text("Stop sharing") }
        }
    }
}

/**
 * Receives another account's live screen over the internet via WebRTC, opened either from the
 * home screen's "Go live online" area (future: a browse list) or from the website's "View"
 * button through the screenlinkpro://view deep link. Reached without a local network.
 */
@Composable
private fun OnlineViewerScreen(hostUid: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var status by rememberSaveable { mutableStateOf("Connecting…") }
    var connected by rememberSaveable { mutableStateOf(false) }
    val eglBase = remember { EglBase.create() }
    val rendererState = remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    val remoteTrackState = remember { mutableStateOf<VideoTrack?>(null) }

    fun attachIfReady() {
        val renderer = rendererState.value ?: return
        val track = remoteTrackState.value ?: return
        try { track.addSink(renderer) } catch (_: Exception) {}
    }

    DisposableEffect(hostUid) {
        val viewerUid = CloudSync.currentUid()
        if (viewerUid == null) {
            status = "Please log in first."
            return@DisposableEffect onDispose {}
        }
        WebRtcInit.ensure(context)
        val factory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .createPeerConnectionFactory()
        val rtcConfig = PeerConnection.RTCConfiguration(WebRtcSignaling.iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        var answerListener: ListenerRegistration? = null
        var candidatesListener: ListenerRegistration? = null
        val handler = Handler(Looper.getMainLooper())

        val pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) { WebRtcSignaling.addViewerCandidate(hostUid, candidate) }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                handler.post {
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED -> { connected = true; status = "Live" }
                        PeerConnection.IceConnectionState.FAILED, PeerConnection.IceConnectionState.DISCONNECTED -> status = "Connection lost — the host may have stopped sharing."
                        else -> {}
                    }
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onAddStream(stream: MediaStream?) {
                val track = stream?.videoTracks?.firstOrNull() ?: return
                handler.post { remoteTrackState.value = track; attachIfReady() }
            }
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                val track = receiver?.track() as? VideoTrack ?: return
                handler.post { remoteTrackState.value = track; attachIfReady() }
            }
        })

        if (pc == null) {
            status = "Couldn't start the connection on this device."
        } else {
            pc.createOffer(object : SdpObserverAdapter() {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    if (sdp == null) return
                    pc.setLocalDescription(SdpObserverAdapter(), sdp)
                    WebRtcSignaling.sendOffer(hostUid, viewerUid, sdp) { ok ->
                        if (!ok) handler.post { status = "Couldn't reach the host. Check your connection and try again." }
                    }
                }
            }, MediaConstraints())

            answerListener = WebRtcSignaling.listenForAnswer(hostUid) { answer ->
                handler.post { try { pc.setRemoteDescription(SdpObserverAdapter(), answer) } catch (_: Exception) {} }
            }
            candidatesListener = WebRtcSignaling.listenForHostCandidates(hostUid) { candidate ->
                handler.post { try { pc.addIceCandidate(candidate) } catch (_: Exception) {} }
            }
        }

        onDispose {
            answerListener?.remove()
            candidatesListener?.remove()
            try { pc?.close() } catch (_: Exception) {}
            try { factory.dispose() } catch (_: Exception) {}
        }
    }

    DisposableEffect(Unit) { onDispose { try { eglBase.release() } catch (_: Exception) {} } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
            SurfaceViewRenderer(c).apply {
                init(eglBase.eglBaseContext, null)
                setMirror(false)
                rendererState.value = this
                attachIfReady()
            }
        })
        if (!connected) Box(Modifier.align(Alignment.Center).background(Color(0xAA000000), RoundedCornerShape(14.dp)).padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text(status, color = Color.White, fontSize = 14.sp)
        }
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).padding(10.dp)) {
            Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
        }
    }
}

@Composable
private fun HostScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var code by rememberSaveable { mutableStateOf(Pairing.generate()) }
    var wifiName by rememberSaveable { mutableStateOf("") }
    var wifiPassword by rememberSaveable { mutableStateOf("") }
    val wifiStore = remember { SavedWifiStore(context) }
    var savedWifi by remember { mutableStateOf(wifiStore.list()) }
    var sharing by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val ip = remember { NetworkInfo.addresses().firstOrNull() }
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val service = Intent(context, CaptureService::class.java).apply { action = CaptureService.START; putExtra(CaptureService.RESULT, result.resultCode); putExtra(CaptureService.DATA, result.data); putExtra(CaptureService.CODE, code) }
            try { ContextCompat.startForegroundService(context, service); sharing = true; error = null } catch (e: Exception) { sharing = false; error = "Could not start sharing on this phone. Please allow screen capture and try again." }
        }
    }
    val qrBitmap = remember(ip, code, wifiName, wifiPassword) { ip?.let { QrPairing.createBitmap(QrPairing.payload(it, 47821, code, wifiName.trim(), wifiPassword), 560) } }
    AppScaffold("Share my screen (local)", { if (sharing) context.startService(Intent(context, CaptureService::class.java).setAction(CaptureService.STOP)); onBack() }) {
        Text("Connect both phones to the same Wi‑Fi or hotspot.", color = Color(0xFF64748B))
        Spacer(Modifier.height(20.dp))
        if (ip == null) ErrorCard("No local network found. Connect to Wi‑Fi first — or use \"Go live online\" from the home screen instead, which works over mobile data.")
        OutlinedTextField(value = wifiName, onValueChange = { wifiName = it }, label = { Text("Wi‑Fi / hotspot name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(value = wifiPassword, onValueChange = { wifiPassword = it }, label = { Text("Wi‑Fi password (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = {
                if (wifiName.isNotBlank()) {
                    wifiStore.save(SavedWifi(wifiName.trim(), wifiPassword))
                    savedWifi = wifiStore.list()
                }
            }, enabled = wifiName.isNotBlank()) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(6.dp)); Text("Save hotspot") }
        }
        if (savedWifi.isNotEmpty()) {
            Text("Saved hotspots", color = Color(0xFF64748B), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            savedWifi.forEach { profile ->
                Card(Modifier.fillMaxWidth().padding(vertical = 3.dp), shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { wifiName = profile.name; wifiPassword = profile.password }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Wifi, null); Spacer(Modifier.width(8.dp)); Text(profile.name, maxLines = 1)
                        }
                        IconButton(onClick = { wifiStore.remove(profile.name); savedWifi = wifiStore.list() }) { Icon(Icons.Default.DeleteOutline, "Remove saved hotspot", tint = Color(0xFFDC2626)) }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = SoftBlue)) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (sharing) "Sharing is active" else "Scan this QR code", color = Blue, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))
                qrBitmap?.let { Image(it.asImageBitmap(), "Pairing QR code", Modifier.size(220.dp)) }
                Spacer(Modifier.height(12.dp))
                Text("Pairing code", color = Color(0xFF64748B), fontSize = 13.sp)
                Text(code, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.ExtraBold, letterSpacing = 4.sp)
                Text("or enter this code manually", color = Color(0xFF64748B), fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(20.dp))
        if (error != null) {
            ErrorCard(error!!)
            Spacer(Modifier.height(12.dp))
        }
        if (!sharing) Button({ projectionLauncher.launch((context.getSystemService(MediaProjectionManager::class.java)).createScreenCaptureIntent()) }, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(15.dp), enabled = ip != null) { Text("Start sharing", fontWeight = FontWeight.Bold) }
        else OutlinedButton({ context.startService(Intent(context, CaptureService::class.java).setAction(CaptureService.STOP)); sharing = false; code = Pairing.generate() }, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(15.dp)) { Text("Stop sharing") }
    }
}

@Composable
private fun ViewerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var host by rememberSaveable { mutableStateOf("") }; var code by rememberSaveable { mutableStateOf("") }; var port by rememberSaveable { mutableStateOf(47821) }; var wifiName by rememberSaveable { mutableStateOf("") }; var wifiPassword by rememberSaveable { mutableStateOf("") }; var wifiStatus by rememberSaveable { mutableStateOf("") }; var connected by rememberSaveable { mutableStateOf(false) }; var size by remember { mutableStateOf(0 to 0) }; var decoder by remember { mutableStateOf<H264Decoder?>(null) }
    val client = remember { ScreenClient() }
    val pendingFrames = remember { ConcurrentLinkedQueue<EncodedFrame>() }
    val latestConfig = remember { AtomicReference<EncodedFrame?>(null) }
    val latestKeyFrame = remember { AtomicReference<EncodedFrame?>(null) }
    val audioPlayer = remember { PlaybackAudioPlayer() }
    val wifi = remember { WifiConnector(context) }
    val view = LocalView.current
    LaunchedEffect(connected) {
        if (connected) try { audioPlayer.start() } catch (_: Exception) { } else audioPlayer.stop()
        val activity = context as? Activity
        if (activity != null) {
            val controller = WindowInsetsControllerCompat(activity.window, view)
            if (connected) {
                WindowCompat.setDecorFitsSystemWindows(activity.window, false)
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
                WindowCompat.setDecorFitsSystemWindows(activity.window, true)
            }
        }
    }
    fun connectToHost(targetHost: String = host, targetPort: Int = port, targetCode: String = code) {
        client.onConnected = { w, h -> size = w to h; connected = true }
        client.onVideoSizeChanged = { w, h -> Handler(Looper.getMainLooper()).post { size = w to h; (context as? Activity)?.requestedOrientation = if (w > h) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT; decoder?.stop(); decoder = null } }
        client.onFrame = { data, flags ->
            val frame = EncodedFrame(data, flags)
            if ((flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) latestConfig.set(frame)
            if ((flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) latestKeyFrame.set(frame)
            decoder?.feed(data, flags) ?: pendingFrames.offer(frame)
        }
        client.onAudio = { packet -> audioPlayer.feed(packet) }
        client.onError = { message -> Handler(Looper.getMainLooper()).post { wifiStatus = message } }
        client.connect(targetHost, targetPort, targetCode)
    }
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        QrPairing.parse(result.contents ?: "")?.let { info ->
            host = info.host; port = info.port; code = info.code; wifiName = info.ssid; wifiPassword = info.password
            if (info.ssid.isNotBlank()) {
                wifiStatus = "Requesting Wi‑Fi connection…"
                wifi.connect(info.ssid, info.password) { ok, message -> Handler(Looper.getMainLooper()).post { wifiStatus = message; if (ok) connectToHost(info.host, info.port, info.code) } }
            } else connectToHost(info.host, info.port, info.code)
        }
    }
    DisposableEffect(Unit) { onDispose { decoder?.stop(); audioPlayer.stop(); client.close(); wifi.disconnect(); (context as? Activity)?.let { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED; WindowInsetsControllerCompat(it.window, view).show(WindowInsetsCompat.Type.systemBars()); WindowCompat.setDecorFitsSystemWindows(it.window, true) } } }
    BackHandler(enabled = connected) { decoder?.stop(); audioPlayer.stop(); client.close(); wifi.disconnect(); connected = false; (context as? Activity)?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    if (connected) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { c -> SurfaceView(c).apply {
                var downX = 0f; var downY = 0f; var downAt = 0L
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; downAt = System.currentTimeMillis(); true }
                        MotionEvent.ACTION_UP -> {
                            val width = view.width.coerceAtLeast(1); val height = view.height.coerceAtLeast(1)
                            val x = (downX / width).coerceIn(0f, 1f); val y = (downY / height).coerceIn(0f, 1f)
                            val endX = (event.x / width).coerceIn(0f, 1f); val endY = (event.y / height).coerceIn(0f, 1f)
                            val moved = kotlin.math.hypot(event.x - downX, event.y - downY) > 18f
                            client.sendTouch(if (moved) 1 else 0, x, y, endX, endY, System.currentTimeMillis() - downAt); true
                        }
                        else -> true
                    }
                }
                holder.addCallback(object : SurfaceHolder.Callback {
                    fun startDecoder(h: SurfaceHolder) { try { if (decoder == null && h.surface.isValid) { decoder = H264Decoder(h.surface, size.first, size.second).also { it.start() }; latestConfig.get()?.let { decoder?.feed(it.bytes, it.flags) }; latestKeyFrame.get()?.let { decoder?.feed(it.bytes, it.flags) }; while (true) { val frame = pendingFrames.poll() ?: break; decoder?.feed(frame.bytes, frame.flags) } } } catch (_: Exception) {} }
                    override fun surfaceCreated(h: SurfaceHolder) { startDecoder(h) }
                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h2: Int) { startDecoder(h) }
                    override fun surfaceDestroyed(h: SurfaceHolder) { decoder?.stop(); decoder = null }
                })
            } })
        }
    } else AppScaffold("View a screen", onBack, scrollable = false) {
            Text("Scan the host QR code for instant pairing, or enter details manually.", color = Color(0xFF64748B))
            Spacer(Modifier.height(18.dp))
            Button({ scanLauncher.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Point at the host QR code").setBeepEnabled(false).setOrientationLocked(false)) }, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(15.dp)) { Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.width(10.dp)); Text("Scan QR code", fontWeight = FontWeight.Bold) }
            if (wifiStatus.isNotBlank()) { Spacer(Modifier.height(10.dp)); Text(wifiStatus, color = Color(0xFF2563EB), fontSize = 13.sp) }
            Spacer(Modifier.height(22.dp)); Divider(); Spacer(Modifier.height(18.dp))
            OutlinedTextField(value = host, onValueChange = { value: String -> host = value.trim() }, label = { Text("Host IP address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp)); OutlinedTextField(value = code, onValueChange = { value: String -> code = Pairing.normalize(value) }, label = { Text("6-digit pairing code") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(22.dp)); Button({ connectToHost() }, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(15.dp), enabled = host.isNotBlank() && Pairing.valid(code)) { Text("Connect", fontWeight = FontWeight.Bold) }
    }
}

@Composable private fun ErrorCard(message: String) { Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)), shape = RoundedCornerShape(14.dp)) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.ErrorOutline, null, tint = Color(0xFFDC2626)); Spacer(Modifier.width(10.dp)); Text(message, color = Color(0xFF991B1B), fontSize = 13.sp) } } }
