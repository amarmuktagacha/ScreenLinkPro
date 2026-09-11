package com.screenlink.pro

import android.app.Activity
import android.content.Intent
import android.Manifest
import android.media.MediaCodec
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Build
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.screenlink.pro.capture.CaptureService
import com.screenlink.pro.capture.EncodedFrame
import com.screenlink.pro.capture.H264Decoder
import com.screenlink.pro.network.ScreenClient
import com.screenlink.pro.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

private val Blue = Color(0xFF2563EB)
private val Navy = Color(0xFF0F172A)
private val SoftBlue = Color(0xFFEFF6FF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestAppPermissions()
        setContent { ScreenLinkTheme { ScreenLinkApp() } }
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
private fun ScreenLinkApp() {
    var page by rememberSaveable { mutableStateOf("home") }
    when (page) {
        "home" -> HomeScreen { page = it }
        "host" -> HostScreen { page = "home" }
        "viewer" -> ViewerScreen { page = "home" }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(title: String, onBack: () -> Unit, scrollable: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(title, fontWeight = FontWeight.Bold) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
        })
    }) { padding ->
        val contentModifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 22.dp).let { base -> if (scrollable) base.verticalScroll(rememberScrollState()) else base }
        Column(contentModifier, content = content)
    }
}

@Composable
private fun HomeScreen(navigate: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(92.dp).background(SoftBlue, RoundedCornerShape(28.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Cast, null, Modifier.size(48.dp), tint = Blue) }
        Spacer(Modifier.height(22.dp))
        Text("ScreenLink Pro", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = Navy)
        Spacer(Modifier.height(8.dp))
        Text("Fast, private screen sharing on your local Wi‑Fi", color = Color(0xFF64748B), fontSize = 15.sp)
        Spacer(Modifier.height(48.dp))
        Button({ navigate("host") }, Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(16.dp)) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(10.dp)); Text("Share my screen", fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(14.dp))
        OutlinedButton({ navigate("viewer") }, Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(16.dp)) { Icon(Icons.Default.Visibility, null); Spacer(Modifier.width(10.dp)); Text("View another screen", fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(26.dp))
        Text("Encrypted only by your private local network connection", color = Color(0xFF94A3B8), fontSize = 12.sp)
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
            try { ContextCompat.startForegroundService(context, service); sharing = true; error = null } catch (e: Exception) { error = "Could not start sharing. Please try again." }
        }
    }
    val qrBitmap = remember(ip, code, wifiName, wifiPassword) { ip?.let { QrPairing.createBitmap(QrPairing.payload(it, 47821, code, wifiName.trim(), wifiPassword), 560) } }
    AppScaffold("Share your screen", { if (sharing) context.startService(Intent(context, CaptureService::class.java).setAction(CaptureService.STOP)); onBack() }) {
        Text("Connect both phones to the same Wi‑Fi or hotspot.", color = Color(0xFF64748B))
        Spacer(Modifier.height(20.dp))
        if (ip == null) ErrorCard("No local network found. Connect to Wi‑Fi first.")
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
    val wifi = remember { WifiConnector(context) }
    fun connectToHost(targetHost: String = host, targetPort: Int = port, targetCode: String = code) {
        client.onConnected = { w, h -> size = w to h; connected = true }
        client.onFrame = { data, flags ->
            val frame = EncodedFrame(data, flags)
            if ((flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) latestConfig.set(frame)
            if ((flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) latestKeyFrame.set(frame)
            decoder?.feed(data, flags) ?: pendingFrames.offer(frame)
        }
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
    DisposableEffect(Unit) { onDispose { decoder?.stop(); client.close(); wifi.disconnect() } }
    AppScaffold("View a screen", onBack, scrollable = false) {
        if (!connected) {
            Text("Scan the host QR code for instant pairing, or enter details manually.", color = Color(0xFF64748B))
            Spacer(Modifier.height(18.dp))
            Button({ scanLauncher.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Point at the host QR code").setBeepEnabled(false).setOrientationLocked(false)) }, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(15.dp)) { Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.width(10.dp)); Text("Scan QR code", fontWeight = FontWeight.Bold) }
            if (wifiStatus.isNotBlank()) { Spacer(Modifier.height(10.dp)); Text(wifiStatus, color = Color(0xFF2563EB), fontSize = 13.sp) }
            Spacer(Modifier.height(22.dp)); Divider(); Spacer(Modifier.height(18.dp))
            OutlinedTextField(value = host, onValueChange = { value: String -> host = value.trim() }, label = { Text("Host IP address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp)); OutlinedTextField(value = code, onValueChange = { value: String -> code = Pairing.normalize(value) }, label = { Text("6-digit pairing code") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(22.dp)); Button({ connectToHost() }, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(15.dp), enabled = host.isNotBlank() && Pairing.valid(code)) { Text("Connect", fontWeight = FontWeight.Bold) }
        } else {
            Text("Connected to $host", color = Color(0xFF16A34A), fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(14.dp))
            AndroidView(modifier = Modifier.fillMaxWidth().aspectRatio(if (size.first > 1 && size.second > 1) size.first.toFloat() / size.second.toFloat() else 0.56f), factory = { c -> SurfaceView(c).apply { holder.addCallback(object : SurfaceHolder.Callback { override fun surfaceCreated(h: SurfaceHolder) { try { decoder = H264Decoder(h.surface, size.first, size.second).also { it.start() }; latestConfig.get()?.let { decoder?.feed(it.bytes, it.flags) }; latestKeyFrame.get()?.let { decoder?.feed(it.bytes, it.flags) }; while (true) { val frame = pendingFrames.poll() ?: break; decoder?.feed(frame.bytes, frame.flags) } } catch (_: Exception) {} }; override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h2: Int) {}; override fun surfaceDestroyed(h: SurfaceHolder) { decoder?.stop(); decoder = null; pendingFrames.clear() } }) } })
            Spacer(Modifier.height(16.dp)); OutlinedButton({ decoder?.stop(); client.close(); wifi.disconnect(); connected = false }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(15.dp)) { Text("Disconnect") }
        }
    }
}

@Composable private fun ErrorCard(message: String) { Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)), shape = RoundedCornerShape(14.dp)) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.ErrorOutline, null, tint = Color(0xFFDC2626)); Spacer(Modifier.width(10.dp)); Text(message, color = Color(0xFF991B1B), fontSize = 13.sp) } } }
