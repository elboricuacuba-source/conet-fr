package com.gns.phonebridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.gns.phonebridge.pairing.PairingClient
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {

    private var pairingStatus by mutableStateOf("")
    private var isPairing by mutableStateOf(false)

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val text = result.contents
        if (text == null) {
            isPairing = false
            return@registerForActivityResult
        }
        handleScannedQr(text)
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) beginPairing() else {
            isPairing = false
            pairingStatus = "Se necesita permiso de cámara para escanear el QR de la PC"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme {
                BridgeScreen(
                    pairingStatus = pairingStatus,
                    isPairing = isPairing,
                    onConnectClick = { startPairingFlow() },
                    onStop = { stopServer() },
                    onRequestFileAccess = { requestAllFilesAccess() },
                    hasFileAccess = hasAllFilesAccess(),
                    onRequestBatteryExemption = { requestBatteryExemption() },
                    hasBatteryExemption = hasBatteryExemption(),
                )
            }
        }
    }

    private fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    private fun hasBatteryExemption(): Boolean {
        val powerManager = getSystemService(PowerManager::class.java) ?: return true
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryExemption() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    private fun startPairingFlow() {
        isPairing = true
        pairingStatus = "Iniciando servidor..."

        val hasCameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasCameraPermission) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            beginPairing()
        }
    }

    private fun beginPairing() {
        lifecycleScope.launch {
            val intent = Intent(this@MainActivity, FtpServerService::class.java).apply {
                action = FtpServerService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)

            val state = withTimeoutOrNull(4000) { FtpServerService.state.first { it.running && it.ip != null } }
            if (state == null) {
                isPairing = false
                pairingStatus = "No se pudo iniciar el servidor (¿hay Wi-Fi conectado?)"
                return@launch
            }

            pairingStatus = "Escanea el código QR que muestra la PC"
            scanLauncher.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("Apunta al código QR de la PC")
                    .setBeepEnabled(false)
                    .setOrientationLocked(true)
            )
        }
    }

    private fun handleScannedQr(text: String) {
        val pc = PairingClient.parsePcQr(text)
        if (pc == null) {
            isPairing = false
            pairingStatus = "Ese código QR no es de Conet FR"
            return
        }

        val state = FtpServerService.state.value
        if (!state.running || state.ip == null) {
            isPairing = false
            pairingStatus = "El servidor no está activo"
            return
        }

        pairingStatus = "Conectando con la PC..."
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    PairingClient.sendPairing(pc, state.ip, state.port, state.username, state.password)
                }.getOrDefault(false)
            }
            isPairing = false
            pairingStatus = if (ok) "Conectado - ya puedes ver tus archivos en la PC" else "No se pudo conectar con la PC"
        }
    }

    private fun stopServer() {
        val intent = Intent(this, FtpServerService::class.java).apply { action = FtpServerService.ACTION_STOP }
        startService(intent)
        pairingStatus = ""
    }
}

@Composable
fun BridgeScreen(
    pairingStatus: String,
    isPairing: Boolean,
    onConnectClick: () -> Unit,
    onStop: () -> Unit,
    onRequestFileAccess: () -> Unit,
    hasFileAccess: Boolean,
    onRequestBatteryExemption: () -> Unit,
    hasBatteryExemption: Boolean,
) {
    val state by FtpServerService.state.collectAsStateWithLifecycle()

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Conet FR", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Abre la app en la PC, y cuando muestre su código QR, tócalo aquí para escanearlo",
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))

            if (!hasFileAccess) {
                OutlinedButton(onClick = onRequestFileAccess) {
                    Text("Permitir acceso a todos los archivos")
                }
                Spacer(Modifier.height(16.dp))
            }

            if (!hasBatteryExemption) {
                OutlinedButton(onClick = onRequestBatteryExemption) {
                    Text("No cerrar el servidor en segundo plano")
                }
                Text(
                    "Para que la conexión no se corte al cerrar la app",
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
            }

            Button(
                onClick = onConnectClick,
                enabled = !isPairing && (hasFileAccess || Build.VERSION.SDK_INT < Build.VERSION_CODES.R),
            ) {
                Text("Conectar con la PC")
            }

            if (pairingStatus.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(pairingStatus, textAlign = TextAlign.Center)
            }

            if (state.running) {
                Spacer(Modifier.height(24.dp))
                Text("Servidor activo en ${state.ip}:${state.port}", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onStop) { Text("Detener servidor") }
            }
        }
    }
}
