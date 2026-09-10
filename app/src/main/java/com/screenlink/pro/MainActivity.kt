package com.screenlink.pro

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.screenlink.pro.capture.H264Decoder
import com.screenlink.pro.network.ScreenClient
import com.screenlink.pro.util.*

private val Blue = Color(0xFF2563EB)
private val Navy = Color(0xFF0F172A)
private val SoftBlue = Color(0xFFEFF6FF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ScreenLinkTheme { ScreenLinkApp() } }
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

@Composable
private fun AppScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(title, fontWeight = FontWeight.Bold) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
        })
    }) { padding -> Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 22.dp), content = content) }
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
    var sharing by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val ip = remember { NetworkInfo.addresses().firstOrNull() }
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val service = Intent(context, CaptureService::class.java).apply { action = CaptureService.START; putExtra(CaptureService.RESULT, result.resultCode); putExtra(CaptureService.DATA, result.data); putExtra(CaptureService.CODE, code) }
            try { ContextCompat.startForegroundService(context, service); sharing = true; error = null } catch (e: Exception) { error = "Could not start sharing. Please try again." }
        }
    }
    val qrBitmap = remember(ip, code) { ip?.let { QrPairing.createBitmap(QrPairing.payload(it, 47821, code), 560) } }
    AppScaffold("Share your screen", { if (sharing) context.startService(Intent(context, CaptureService::class.java).setAction(CaptureService.STOP)); onBack() }) {
        Text("Connect both phones to the same Wi‑Fi or hotspot.", color = Color(0xFF64748B))
        Spacer(Modifier.height(20.dp))
        if (ip == null) ErrorCard("No local network found. Connect to Wi‑Fi first.")
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
        error?.let { ErrorCard(it); Spacer(Modifier.height(12.dp)) }
        if (!sharing) Button({ projectionLauncher.launch((context.getSystemService(MediaProjectionManager::class.java)).createScreenCaptureIntent()) }, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(15.dp), enabled = ip != null) { Text("Start sharing", fontWeight = FontWeight.Bold) }
        else OutlinedButton({ context.startService(Intent(context, CaptureService::class.java).setAction(CaptureService.STOP)); sharing = false; code = Pairing.generate() }, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(15.dp)) { Text("Stop sharing") }
    }
}

@Composable
private fun ViewerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var host by rememberSaveable { mutableStateOf("") }; var code by rememberSaveable { mutableStateOf("") }; var port by rememberSaveable { mutableStateOf(47821) }; var connected by rememberSaveable { mutableStateOf(false) }; var size by remember { mutableStateOf(0 to 0) }; var decoder by remember { mutableStateOf<H264Decoder?>(null) }
    val client = remember { ScreenClient() }
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result -> QrPairing.parse(result.contents ?: "")?.let { host = it.host; port = it.port; code = it.code } }
    DisposableEffect(Unit) { onDispose { decoder?.stop(); client.close() } }
    AppScaffold("View a screen", onBack) {
        if (!connected) {
            Text("Scan the host QR code for instant pairing, or enter details manually.", color = Color(0xFF64748B))
            Spacer(Modifier.height(18.dp))
            Button({ scanLauncher.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Point at the host QR code").setBeepEnabled(false).setOrientationLocked(false)) }, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(15.dp)) { Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.width(10.dp)); Text("Scan QR code", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(22.dp)); HorizontalDivider(); Spacer(Modifier.height(18.dp))
            OutlinedTextField(value = host, onValueChange = { value: String -> host = value.trim() }, label = { Text("Host IP address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp)); OutlinedTextField(value = code, onValueChange = { value: String -> code = Pairing.normalize(value) }, label = { Text("6-digit pairing code") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(22.dp)); Button({ client.onConnected = { w, h -> size = w to h; connected = true }; client.onFrame = { decoder?.feed(it) }; client.connect(host, port, code) }, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(15.dp), enabled = host.isNotBlank() && Pairing.valid(code)) { Text("Connect", fontWeight = FontWeight.Bold) }
        } else {
            Text("Connected to $host", color = Color(0xFF16A34A), fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(14.dp))
            AndroidView(modifier = Modifier.fillMaxWidth().aspectRatio(0.56f), factory = { c -> SurfaceView(c).apply { holder.addCallback(object : SurfaceHolder.Callback { override fun surfaceCreated(h: SurfaceHolder) { try { decoder = H264Decoder(h.surface, size.first, size.second).also { it.start() } } catch (_: Exception) {} }; override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h2: Int) {}; override fun surfaceDestroyed(h: SurfaceHolder) { decoder?.stop(); decoder = null } }) } })
            Spacer(Modifier.height(16.dp)); OutlinedButton({ decoder?.stop(); client.close(); connected = false }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(15.dp)) { Text("Disconnect") }
        }
    }
}

@Composable private fun ErrorCard(message: String) { Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)), shape = RoundedCornerShape(14.dp)) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.ErrorOutline, null, tint = Color(0xFFDC2626)); Spacer(Modifier.width(10.dp)); Text(message, color = Color(0xFF991B1B), fontSize = 13.sp) } } }
