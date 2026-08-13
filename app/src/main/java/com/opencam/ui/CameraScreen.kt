package com.opencam.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.SurfaceTexture
import android.view.TextureView
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opencam.CameraLens
import com.opencam.Codec
import com.opencam.FPS_PRESETS
import com.opencam.QUALITY_PRESETS
import com.opencam.RESOLUTION_PRESETS
import com.opencam.StreamConfig
import com.opencam.WhiteBalance
import com.opencam.service.StreamingService
import com.opencam.stream.StreamState
import com.opencam.util.Permissions
import com.opencam.util.PreviewTransform
import com.opencam.util.QrUtils
import kotlin.math.roundToInt

/**
 * Root composable: handles runtime permissions, starts the foreground service
 * once granted, and hosts the camera screen.
 */
@Composable
fun CameraApp() {
    val context = LocalContext.current
    val viewModel: CameraViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val config by viewModel.config.collectAsState()

    var permissionGranted by remember { mutableStateOf(Permissions.allGranted(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionGranted = Permissions.allGranted(context)
    }

    LaunchedEffect(Unit) {
        if (!permissionGranted) {
            permissionLauncher.launch(Permissions.requestPermissions())
        }
    }

    LaunchedEffect(permissionGranted) {
        if (permissionGranted) {
            StreamingService.start(context)
        }
    }

    DisposableEffect(context, config.keepScreenOn) {
        val activity = context.findActivity()
        if (config.keepScreenOn) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    if (!permissionGranted) {
        PermissionScreen(onRequest = { permissionLauncher.launch(Permissions.requestPermissions()) })
        return
    }

    CameraScreen(viewModel, state, config)
}

@Composable
private fun CameraScreen(
    viewModel: CameraViewModel,
    state: StreamState,
    config: StreamConfig,
) {
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    var showZoom by remember { mutableStateOf(false) }
    // Reset the zoom UI whenever the camera switches — the new camera starts at 1x.
    var zoomScale by remember(config.lens) { mutableFloatStateOf(1f) }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        CameraPreview(
            viewModel = viewModel,
            state = state,
            mirror = config.mirror,
            zoomScale = zoomScale,
            onZoomChange = {
                zoomScale = it
                viewModel.setZoom(it)
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Scrims for readability of the overlays.
        Box(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .height(150.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)
                    )
                )
        )
        Box(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .height(220.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                    )
                )
        )

        StatusBar(
            state = state,
            modifier = Modifier.align(Alignment.TopCenter),
            onToggleStream = {
                if (state.running) StreamingService.stop(context) else StreamingService.start(context)
            },
            onQr = { showQr = true },
            onSettings = { showSettings = true },
        )

        TallyChip(
            state = state,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 56.dp, end = 12.dp)
        )

        state.serverError?.let { error ->
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 64.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        if (!state.running) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                    modifier = Modifier.size(128.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Videocam,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(52.dp)
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    "Streaming is off",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Start to broadcast this camera to OBS",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(28.dp))
                Surface(
                    onClick = { StreamingService.start(context) },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(76.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "Start streaming",
                            tint = Color.Black,
                            modifier = Modifier.size(44.dp)
                        )
                    }
                }
            }
        }

        ControlsBar(
            config = config,
            viewModel = viewModel,
            modifier = Modifier.align(Alignment.BottomCenter),
            onZoom = { showZoom = true },
        )
    }

    if (showQr) {
        QrDialog(state = state, onDismiss = { showQr = false })
    }
    if (showSettings) {
        SettingsSheet(state, config, viewModel, onDismiss = { showSettings = false })
    }
    if (showZoom) {
        ZoomSheet(
            zoom = zoomScale,
            maxZoom = state.maxZoom,
            onZoomChange = {
                zoomScale = it
                viewModel.setZoom(it)
            },
            onDismiss = { showZoom = false }
        )
    }
}

// ---------------- preview ----------------

@Composable
private fun CameraPreview(
    viewModel: CameraViewModel,
    state: StreamState,
    mirror: Boolean,
    zoomScale: Float,
    onZoomChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentZoom by rememberUpdatedState(zoomScale)
    val maxZoom by rememberUpdatedState(state.maxZoom)
    var textureView by remember { mutableStateOf<TextureView?>(null) }

    val applyTransform = {
        val tv = textureView
        if (tv != null && tv.width > 0 && tv.height > 0) {
            val bufferWidth = if (state.width > 0) state.width else tv.width
            val bufferHeight = if (state.height > 0) state.height else tv.height
            PreviewTransform.apply(
                tv, bufferWidth, bufferHeight,
                state.frontFacing,
                mirror,
            )
        }
    }

    AndroidView(
        modifier = modifier
            .pointerInputTapToFocus(viewModel)
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoomChange, _ ->
                    onZoomChange((currentZoom * zoomChange).coerceIn(1f, maxZoom))
                }
            },
        factory = { ctx ->
            TextureView(ctx).also { tv ->
                textureView = tv
                tv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    applyTransform()
                }
                tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                        viewModel.attachPreview(surface)
                        applyTransform()
                    }

                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                        // TextureView just resized its buffer (e.g. on rotation);
                        // put the stream-sized buffer back so nothing distorts.
                        viewModel.reassertPreviewBuffer()
                        applyTransform()
                    }

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        viewModel.detachPreview()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                }
            }
        }
    )

    LaunchedEffect(
        state.sensorOrientation,
        state.frontFacing,
        state.width,
        state.height,
        mirror,
        textureView,
    ) {
        applyTransform()
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Modifier.pointerInputTapToFocus(viewModel: CameraViewModel): Modifier =
    this.then(
        Modifier.pointerInput(Unit) {
            detectTapGestures { offset ->
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                if (w > 0 && h > 0) viewModel.focusAt(offset.x / w, offset.y / h)
            }
        }
    )

// ---------------- top bar ----------------

@Composable
private fun StatusBar(
    state: StreamState,
    modifier: Modifier = Modifier,
    onToggleStream: () -> Unit,
    onQr: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilledTonalIconButton(onClick = onToggleStream) {
            Icon(
                if (state.running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = if (state.running) "Stop streaming" else "Start streaming",
                tint = if (state.running) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "OpenCam",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                statusLine(state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (state.ipAddress != null) {
            Icon(
                Icons.Filled.Wifi,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
        IconButton(onClick = onQr) {
            Icon(Icons.Filled.QrCode2, contentDescription = "Show QR code")
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings")
        }
    }
}

private fun statusLine(state: StreamState): String {
    if (!state.running) return "Streaming is off"
    val ip = state.ipAddress ?: "no network"
    val codec = state.codec?.wireName?.uppercase() ?: ""
    val res = when {
        state.streamWidth > 0 && state.streamHeight > 0 -> "${state.streamWidth}x${state.streamHeight}"
        state.width > 0 && state.height > 0 -> "${state.width}x${state.height}"
        else -> ""
    }
    val fps = if (state.actualFps > 0) "${state.actualFps}fps" else ""
    val clients = "${state.videoClients} client(s) • ${state.battery}%"
    return listOf("$ip:${state.port}", codec, res, fps, clients)
        .filter { it.isNotEmpty() }
        .joinToString("  ")
}

@Composable
private fun TallyChip(state: StreamState, modifier: Modifier = Modifier) {
    val (color, label) = when (state.tally) {
        "program" -> MaterialTheme.colorScheme.secondary to "LIVE"
        "preview" -> Color(0xFFFFC107) to "PREVIEW"
        else -> Color(0xFF9AA5B8) to "IDLE"
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(color, CircleShape)
            )
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

// ---------------- bottom controls ----------------

@Composable
private fun ControlsBar(
    config: StreamConfig,
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier,
    onZoom: () -> Unit,
) {
    Surface(
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        shadowElevation = 8.dp
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ControlIconButton(
                    Icons.Filled.Cameraswitch,
                    "Switch camera"
                ) { viewModel.flipCamera() }
                ControlIconButton(
                    Icons.Filled.Flip,
                    "Mirror",
                    tint = if (config.mirror) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                ) { viewModel.toggleMirror() }
                ControlIconButton(
                    if (config.torch) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                    "Torch",
                    tint = if (config.torch) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                ) { viewModel.toggleTorch() }
                ControlIconButton(
                    if (config.audioEnabled) Icons.Filled.Mic else Icons.Filled.MicOff,
                    "Microphone",
                    tint = if (config.audioEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                ) { viewModel.updateConfig { it.copy(audioEnabled = !it.audioEnabled) } }
                ControlIconButton(Icons.Filled.ZoomIn, "Zoom") { onZoom() }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MenuChip(
                    label = config.codec.displayName,
                    options = Codec.entries.map { it.displayName },
                    selected = config.codec.displayName
                ) { i ->
                    viewModel.updateConfig { it.copy(codec = Codec.entries[i]) }
                }
                MenuChip(
                    label = "${config.width}x${config.height}",
                    options = RESOLUTION_PRESETS.map { "${it.first}x${it.second}" },
                    selected = "${config.width}x${config.height}"
                ) { i ->
                    val r = RESOLUTION_PRESETS[i]
                    viewModel.updateConfig { it.copy(width = r.first, height = r.second) }
                }
                MenuChip(
                    label = "${config.fps} fps",
                    options = FPS_PRESETS.map { "$it fps" },
                    selected = "${config.fps} fps"
                ) { i ->
                    viewModel.updateConfig { it.copy(fps = FPS_PRESETS[i]) }
                }
            }
        }
    }
}

@Composable
private fun ControlIconButton(
    icon: ImageVector,
    contentDescription: String?,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(26.dp))
    }
}

@Composable
private fun MenuChip(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium)
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { i, opt ->
                DropdownMenuItem(
                    text = { Text(opt) },
                    onClick = {
                        expanded = false
                        onSelect(i)
                    },
                    leadingIcon = if (opt == selected) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    }
                )
            }
        }
    }
}

// ---------------- dialogs / sheets ----------------

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun QrDialog(state: StreamState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val connectText = "${state.ipAddress ?: "unknown"}:${state.port}"
    val text = "OpenCam\n${state.ipAddress ?: "no network"}\nPort: ${state.port}"
    val bmp = remember(state.ipAddress, state.port) { QrUtils.generate(text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect in OBS") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (bmp != null) {
                    Image(
                        bmp.asImageBitmap(),
                        contentDescription = "QR code",
                        modifier = Modifier.size(240.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "IP: $connectText",
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(connectText))
                        Toast.makeText(context, "Copied: $connectText", Toast.LENGTH_SHORT).show()
                    },
                    enabled = state.ipAddress != null
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy IP:port")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Add a DroidCam source in OBS, click Refresh, and pick this device — or enter the IP and port manually.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ZoomSheet(
    zoom: Float,
    maxZoom: Float,
    onZoomChange: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val range = 1f..maxOf(1f, maxZoom)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                "Zoom  ${"%.1f".format(zoom)}x  (max ${"%.1f".format(range.endInclusive)}x)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Slider(
                value = zoom.coerceIn(range),
                onValueChange = onZoomChange,
                valueRange = range
            )
            Text(
                "You can also pinch the preview to zoom.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    state: StreamState,
    config: StreamConfig,
    viewModel: CameraViewModel,
    onDismiss: () -> Unit,
) {
    var portText by remember(config.port) { mutableStateOf(config.port.toString()) }
    val focusManager = LocalFocusManager.current
    fun commitPort() {
        val port = portText.toIntOrNull()
        if (port != null && port in 1024..65535) {
            if (port != config.port) viewModel.updateConfig { it.copy(port = port) }
        } else {
            portText = config.port.toString()
        }
    }
    ModalBottomSheet(onDismissRequest = {
        commitPort()
        onDismiss()
    }) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            SectionTitle("Camera")

            Text("Camera lens", style = MaterialTheme.typography.titleSmall)
            MenuChip(
                label = config.lens.displayName,
                options = CameraLens.entries.map { it.displayName },
                selected = config.lens.displayName
            ) { i ->
                viewModel.updateConfig { it.copy(lens = CameraLens.entries[i]) }
            }
            Text(
                "The camera-switch button toggles front/back; wide and tele lenses are here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text("White balance", style = MaterialTheme.typography.titleSmall)
            MenuChip(
                label = config.whiteBalance.displayName,
                options = WhiteBalance.entries.map { it.displayName },
                selected = config.whiteBalance.displayName
            ) { i ->
                viewModel.setWhiteBalance(WhiteBalance.entries[i])
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Video stabilization (EIS)", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Reduces handheld shake (may crop the image)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = config.eisEnabled,
                    onCheckedChange = viewModel::setEisEnabled
                )
            }

            val evMin = minOf(state.exposureMin, state.exposureMax)
            val evMax = maxOf(state.exposureMin, state.exposureMax)
            Text(
                "Exposure compensation: ${config.exposureEv} EV",
                style = MaterialTheme.typography.titleSmall
            )
            if (evMax > evMin) {
                Slider(
                    value = config.exposureEv.coerceIn(evMin, evMax).toFloat(),
                    onValueChange = { v -> viewModel.setExposure(v.roundToInt()) },
                    valueRange = evMin.toFloat()..evMax.toFloat()
                )
            } else {
                Text(
                    "Not supported by this camera",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SectionTitle("Video")

            Text("Quality preset", style = MaterialTheme.typography.titleSmall)
            MenuChip(
                label = QUALITY_PRESETS.firstOrNull {
                    it.second.first == config.width && it.second.second == config.height &&
                        it.third == config.bitrateMbps
                }?.first ?: "Custom",
                options = QUALITY_PRESETS.map { it.first },
                selected = QUALITY_PRESETS.firstOrNull {
                    it.second.first == config.width && it.second.second == config.height &&
                        it.third == config.bitrateMbps
                }?.first ?: "Custom"
            ) { i ->
                val p = QUALITY_PRESETS[i]
                viewModel.updateConfig {
                    it.copy(width = p.second.first, height = p.second.second, bitrateMbps = p.third)
                }
            }
            Text(
                "Presets set the resolution and bitrate together; you can still fine-tune them below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                "Bitrate: ${config.bitrateMbps} Mbps",
                style = MaterialTheme.typography.titleSmall
            )
            Slider(
                value = config.bitrateMbps.toFloat(),
                onValueChange = { v ->
                    viewModel.updateConfig { it.copy(bitrateMbps = v.roundToInt()) }
                },
                valueRange = 1f..20f,
                steps = 18
            )

            Text(
                "JPEG quality (MJPEG mode): ${config.jpegQuality}",
                style = MaterialTheme.typography.titleSmall
            )
            Slider(
                value = config.jpegQuality.toFloat(),
                onValueChange = { v ->
                    viewModel.updateConfig { it.copy(jpegQuality = v.roundToInt()) }
                },
                valueRange = 50f..95f,
                steps = 44
            )

            SectionTitle("Server")

            Text("Server port", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                singleLine = true,
                isError = portText.toIntOrNull()?.let { it !in 1024..65535 } ?: true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = {
                    commitPort()
                    focusManager.clearFocus()
                }),
                modifier = Modifier.onFocusChanged { focusState ->
                    if (!focusState.isFocused) commitPort()
                },
            )
            Text(
                "Default 4747 (the port the OBS plugin connects to)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SectionTitle("Power")

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Keep screen on", style = MaterialTheme.typography.titleSmall)
                Switch(
                    checked = config.keepScreenOn,
                    onCheckedChange = { checked ->
                        viewModel.updateConfig { c -> c.copy(keepScreenOn = checked) }
                    }
                )
            }

            HorizontalDivider()

            Text(
                "How to use with OBS",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                "1. Install the DroidCam OBS plugin (droidcam.app/obs) and add a DroidCam source.\n" +
                    "2. Click Refresh — this phone appears as \"${"OpenCam"} ... (WiFi)\".\n" +
                    "3. Or enter the IP and port manually: ${state.ipAddress ?: "…"}:${state.port}.\n" +
                    "4. USB mode: enable USB debugging, then just pick the device in the plugin's list — it forwards ports automatically.\n" +
                    "All features (HD, audio, autofocus, zoom, torch, multi-lens, background streaming) are free in OpenCam.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "OpenCam needs camera and microphone access",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Turn your phone into a wireless webcam for OBS — with audio, HD video, autofocus, zoom, torch and more. No payment required.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) {
            Text("Grant permissions")
        }
    }
}
