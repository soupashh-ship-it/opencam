package com.opencam.stream

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.OrientationEventListener
import android.util.Size
import com.opencam.BuildConfig
import com.opencam.CameraLens
import com.opencam.Codec
import com.opencam.StreamConfig
import com.opencam.WhiteBalance
import com.opencam.camera.Camera2Controller
import com.opencam.discovery.NsdHelper
import com.opencam.encode.AudioEncoder
import com.opencam.encode.GlRotator
import com.opencam.encode.VideoEncoder
import com.opencam.server.AudioClient
import com.opencam.server.Protocol
import com.opencam.server.ServerCallbacks
import com.opencam.server.StreamServer
import com.opencam.server.VideoClient
import com.opencam.util.BatteryUtils
import com.opencam.util.NetworkUtils
import com.opencam.util.Nv21Rotation
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Process-wide holder so the Activity, Service and ViewModel share one manager. */
object StreamManagerHolder {
    @Volatile
    var instance: StreamManager? = null
}

data class StreamState(
    val running: Boolean = false,
    val serverError: String? = null,
    val ipAddress: String? = null,
    val port: Int = Protocol.DEFAULT_PORT,
    val videoClients: Int = 0,
    val audioClients: Int = 0,
    val cameraId: String? = null,
    val sensorOrientation: Int = 0,
    val frontFacing: Boolean = false,
    val torchAvailable: Boolean = false,
    val codec: Codec? = null,
    /** Unrotated camera buffer dimensions. */
    val width: Int = 0,
    val height: Int = 0,
    val actualFps: Int = 0,
    val tally: String = "idle",
    val battery: Int = 100,
    val maxZoom: Float = 1f,
    val exposureMin: Int = 0,
    val exposureMax: Int = 0,
    /** Actual encoded dimensions after rotation. */
    val streamWidth: Int = 0,
    val streamHeight: Int = 0,
)

/**
 * Owns the camera, encoders, discovery and server for the lifetime of the app process.
 * All mutable lifecycle/rebuild state is serialized on [managerHandler].
 */
class StreamManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("opencam", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(StreamState())
    val state: StateFlow<StreamState> = _state.asStateFlow()

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<StreamConfig> = _config.asStateFlow()

    private val camera = Camera2Controller(appContext)
    private val managerThread = HandlerThread("opencam-manager").apply { start() }
    private val managerHandler = Handler(managerThread.looper)
    private var jpegThread: HandlerThread? = null
    private var jpegHandler: Handler? = null

    @Volatile private var server: StreamServer? = null
    private var nsd: NsdHelper? = null
    private var videoEncoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    private var glRotator: GlRotator? = null
    private var jpegReader: ImageReader? = null
    private var previewTexture: SurfaceTexture? = null

    private val started = AtomicBoolean(false)
    private var rebuildInProgress = false
    private var rebuildQueued = false
    private var rebuildGeneration = 0L
    private var cameraGeneration = 0L

    /**
     * Last (codec, width, height) a client explicitly asked for. A reconnect
     * that re-sends the same request must not revert settings the user changed
     * in the app, so only genuinely new requests override the config.
     */
    private var lastClientRequested: Triple<Codec, Int, Int>? = null

    private var deviceOrientationDegrees = 0

    private val orientationEventListener = object : OrientationEventListener(appContext) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            val newDeg = when (orientation) {
                in 45..134 -> 90
                in 135..224 -> 180
                in 225..314 -> 270
                else -> 0
            }
            if (newDeg != deviceOrientationDegrees) {
                deviceOrientationDegrees = newDeg
                managerHandler.post {
                    if (started.get()) {
                        scheduleRebuild()
                    }
                }
            }
        }
    }

    private val rebuildRunnable = Runnable { requestRebuildInternal() }
    private val batteryRunnable = object : Runnable {
        override fun run() {
            if (!started.get()) return
            _state.update {
                it.copy(
                    battery = BatteryUtils.batteryPercent(appContext),
                    ipAddress = NetworkUtils.getLocalIpv4() ?: it.ipAddress,
                )
            }
            managerHandler.postDelayed(this, BATTERY_REFRESH_MS)
        }
    }

    companion object {
        private const val KEY_CODEC = "codec"
        private const val KEY_WIDTH = "width"
        private const val KEY_HEIGHT = "height"
        private const val KEY_FPS = "fps"
        private const val KEY_BITRATE = "bitrate"
        private const val KEY_JPEG = "jpeg"
        private const val KEY_AUDIO = "audio"
        private const val KEY_PORT = "port"
        private const val KEY_LENS = "lens"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_EXPOSURE = "exposure"
        private const val KEY_WB = "white_balance"
        private const val KEY_EIS = "eis"
        private const val KEY_MIRROR = "mirror"

        private const val REBUILD_DEBOUNCE_MS = 120L
        private const val BATTERY_REFRESH_MS = 30_000L
    }

    // ---------------- lifecycle ----------------

    fun start() {
        if (!started.compareAndSet(false, true)) return
        managerHandler.post { startInternal() }
    }

    private fun startInternal() {
        if (!started.get()) return
        val cfg = _config.value
        _state.value = StreamState(
            running = true,
            port = cfg.port,
            ipAddress = NetworkUtils.getLocalIpv4(),
            battery = BatteryUtils.batteryPercent(appContext),
        )
        try { orientationEventListener.enable() } catch (_: Exception) {}
        restartServer(cfg.port)
        managerHandler.removeCallbacks(batteryRunnable)
        managerHandler.post(batteryRunnable)
        openCameraAndConfigure()
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        val finished = CountDownLatch(1)
        managerHandler.post {
            try {
                try { orientationEventListener.disable() } catch (_: Exception) {}
                managerHandler.removeCallbacks(rebuildRunnable)
                managerHandler.removeCallbacks(batteryRunnable)
                rebuildGeneration++
                cameraGeneration++
                rebuildInProgress = false
                rebuildQueued = false
                camera.closeSessionAndWait()
                stopEncoders()
                camera.close()
                stopServer()
                val cfg = _config.value
                _state.value = StreamState(
                    running = false,
                    port = cfg.port,
                    battery = BatteryUtils.batteryPercent(appContext),
                )
            } finally {
                finished.countDown()
            }
        }
        try {
            finished.await(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // ---------------- config ----------------

    fun updateConfig(transform: (StreamConfig) -> StreamConfig) {
        while (true) {
            val old = _config.value
            val next = sanitizeConfig(transform(old))
            if (next == old) return
            if (_config.compareAndSet(old, next)) {
                persist(next)
                _state.update { it.copy(port = next.port) }
                managerHandler.post { applyConfigChange(old, next) }
                return
            }
        }
    }

    private fun applyConfigChange(old: StreamConfig, new: StreamConfig) {
        camera.jpegQuality = new.jpegQuality
        if (old.torch != new.torch) camera.setTorch(new.torch)
        if (old.exposureEv != new.exposureEv) camera.setExposureCompensation(new.exposureEv)
        if (old.whiteBalance != new.whiteBalance) camera.applyWhiteBalance(new.whiteBalance.mode)
        if (old.eisEnabled != new.eisEnabled) camera.applyEis(new.eisEnabled)
        if (!started.get()) return

        if (old.port != new.port) restartServer(new.port)
        if (old.lens != new.lens) {
            openCameraAndConfigure()
            return
        }

        val streamChanged = old.codec != new.codec ||
            old.width != new.width || old.height != new.height ||
            old.fps != new.fps || old.bitrateMbps != new.bitrateMbps ||
            old.jpegQuality != new.jpegQuality || old.audioEnabled != new.audioEnabled ||
            old.mirror != new.mirror
        if (streamChanged) scheduleRebuild()
    }

    fun flipCamera() {
        updateConfig { cfg ->
            val lens = if (cfg.lens == CameraLens.FRONT) CameraLens.BACK else CameraLens.FRONT
            cfg.copy(lens = lens)
        }
    }

    /** Mirrors the stream (and the local preview) left/right, like DroidCam. */
    fun toggleMirror() = updateConfig { it.copy(mirror = !it.mirror) }

    // ---------------- preview ----------------

    fun attachPreview(texture: SurfaceTexture) {
        managerHandler.post {
            if (previewTexture === texture) {
                reassertPreviewBufferInternal()
                return@post
            }
            previewTexture = texture
            camera.preparePreview(texture)
            if (started.get()) scheduleRebuild()
        }
    }

    fun detachPreview() {
        managerHandler.post {
            previewTexture = null
            camera.preparePreview(null)
            if (started.get()) scheduleRebuild()
        }
    }

    fun reassertPreviewBuffer() {
        managerHandler.post { reassertPreviewBufferInternal() }
    }

    private fun reassertPreviewBufferInternal() {
        val width = _state.value.width
        val height = _state.value.height
        if (width > 0 && height > 0) {
            try { previewTexture?.setDefaultBufferSize(width, height) } catch (_: Exception) {}
        }
    }

    // ---------------- camera controls ----------------

    fun toggleTorch() {
        val on = !_config.value.torch
        updateConfig { it.copy(torch = on) }
    }

    fun setZoom(scale: Float) = camera.setZoom(scale)

    fun focusAt(nx: Float, ny: Float) = camera.focusAt(nx, ny)

    // ---------------- PC-client status / settings ----------------

    /** JSON snapshot served to `GET /v1/status` (used by the PC client). */
    fun statusJson(): String = try {
        val s = _state.value
        val c = _config.value
        JSONObject().apply {
            put("version", BuildConfig.VERSION_NAME)
            put("codec", c.codec.wireName)
            put("width", c.width)
            put("height", c.height)
            put("streamWidth", s.streamWidth)
            put("streamHeight", s.streamHeight)
            put("fps", c.fps)
            put("actualFps", s.actualFps)
            put("bitrateMbps", c.bitrateMbps)
            put("jpegQuality", c.jpegQuality)
            put("audioEnabled", c.audioEnabled)
            put("mirror", c.mirror)
            put("torch", c.torch)
            put("lens", c.lens.name)
            put("frontFacing", s.frontFacing)
            put("port", c.port)
            put("running", s.running)
            put("battery", s.battery)
            put("tally", s.tally)
            put("sensorOrientation", s.sensorOrientation)
            put("maxZoom", s.maxZoom)
            put("zoom", camera.zoomLevel)
            put("videoClients", s.videoClients)
            put("ip", s.ipAddress ?: "")
        }.toString()
    } catch (_: Exception) {
        "{}"
    }

    /** Applies settings pushed by the PC client (`PUT /v1/settings?k=v&...`). */
    fun applySettings(params: Map<String, String>) {
        val p = params.mapKeys { it.key.lowercase() }
        val rebuildKeys = setOf("codec", "width", "height", "fps", "bitrate", "jpeg", "mirror", "audio", "lens", "torch")
        if (p.keys.any { it in rebuildKeys }) {
            updateConfig { cfg ->
                var next = cfg
                p["codec"]?.let { Codec.fromWire(it)?.let { codec -> next = next.copy(codec = codec) } }
                p["width"]?.toIntOrNull()?.let { w -> next = next.copy(width = w) }
                p["height"]?.toIntOrNull()?.let { h -> next = next.copy(height = h) }
                p["fps"]?.toIntOrNull()?.let { f -> next = next.copy(fps = f) }
                p["bitrate"]?.toIntOrNull()?.let { b -> next = next.copy(bitrateMbps = b) }
                p["jpeg"]?.toIntOrNull()?.let { j -> next = next.copy(jpegQuality = j) }
                p["mirror"]?.let { next = next.copy(mirror = parseBool(it)) }
                p["audio"]?.let { next = next.copy(audioEnabled = parseBool(it)) }
                p["torch"]?.let { next = next.copy(torch = parseBool(it)) }
                p["lens"]?.let { name ->
                    CameraLens.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                        ?.let { next = next.copy(lens = it) }
                }
                next
            }
        }
        p["zoom"]?.toFloatOrNull()?.let { camera.setZoom(it) }
    }

    private fun parseBool(value: String): Boolean =
        value == "1" || value.equals("true", ignoreCase = true) || value.equals("on", ignoreCase = true)

    fun setExposure(ev: Int) {
        val range = camera.exposureCompensationRange
        val value = ev.coerceIn(range.lower, range.upper)
        updateConfig { it.copy(exposureEv = value) }
    }

    fun setWhiteBalance(wb: WhiteBalance) = updateConfig { it.copy(whiteBalance = wb) }

    fun setEisEnabled(on: Boolean) = updateConfig { it.copy(eisEnabled = on) }

    // ---------------- server / camera setup ----------------

    private fun restartServer(port: Int) {
        stopServer()
        val next = StreamServer(port, callbacks)
        val ok = next.start()
        if (ok) {
            server = next
            nsd = NsdHelper(appContext).also { it.start(port) }
            _state.update { it.copy(port = port, serverError = null) }
        } else {
            next.stop()
            _state.update { it.copy(port = port, serverError = "Could not bind port $port") }
        }
    }

    private fun stopServer() {
        try { server?.stop() } catch (_: Exception) {}
        server = null
        try { nsd?.stop() } catch (_: Exception) {}
        nsd = null
    }

    private fun openCameraAndConfigure() {
        if (!started.get()) return
        val cfg = _config.value
        val id = camera.pickCameraId(cfg.lens)
        if (id == null) {
            _state.update { it.copy(serverError = "No camera is available on this device") }
            return
        }

        val generation = ++cameraGeneration
        rebuildGeneration++
        rebuildInProgress = false
        rebuildQueued = false
        camera.closeSessionAndWait()
        stopEncoders()
        server?.closeVideoClients()
        server?.closeAudioClients()
        camera.open(id) { opened ->
            managerHandler.post callback@{
                if (!started.get() || generation != cameraGeneration) return@callback
                if (!opened) {
                    _state.update { it.copy(serverError = "Could not open camera") }
                    return@callback
                }
                val active = _config.value
                camera.jpegQuality = active.jpegQuality
                camera.setTorch(active.torch)
                camera.setExposureCompensation(active.exposureEv)
                camera.applyWhiteBalance(active.whiteBalance.mode)
                camera.applyEis(active.eisEnabled)
                _state.update {
                    val range = camera.exposureCompensationRange
                    it.copy(
                        cameraId = id,
                        sensorOrientation = camera.sensorOrientation(id),
                        frontFacing = camera.isFrontFacing,
                        torchAvailable = camera.isTorchAvailable(),
                        maxZoom = camera.maxDigitalZoom.coerceAtLeast(1f),
                        exposureMin = range.lower,
                        exposureMax = range.upper,
                        serverError = if (it.serverError?.startsWith("Could not open camera") == true) null else it.serverError,
                    )
                }
                requestRebuildInternal()
            }
        }
    }

    private fun scheduleRebuild() {
        managerHandler.removeCallbacks(rebuildRunnable)
        managerHandler.postDelayed(rebuildRunnable, REBUILD_DEBOUNCE_MS)
    }

    private fun requestRebuildInternal() {
        if (!started.get() || camera.currentCameraId == null) return
        if (rebuildInProgress) {
            rebuildQueued = true
            return
        }
        doRebuild()
    }

    private fun doRebuild() {
        if (!started.get()) return
        rebuildInProgress = true
        rebuildQueued = false
        val generation = ++rebuildGeneration
        val cfg = _config.value

        camera.closeSessionAndWait()
        stopEncoders()
        server?.closeVideoClients()
        server?.closeAudioClients()
        camera.preparePreview(previewTexture)

        val (requestedWidth, requestedHeight) = clampSize(cfg.codec, cfg.width, cfg.height)
        val previewSize = camera.pickPreviewOutputSize(requestedWidth, requestedHeight)
        val sourceWidth = previewSize.width.coerceAtLeast(2)
        val sourceHeight = previewSize.height.coerceAtLeast(2)
        try { previewTexture?.setDefaultBufferSize(sourceWidth, sourceHeight) } catch (_: Exception) {}

        // Incorporate physical accelerometer device orientation so the stream
        // stays upright when the phone is rotated to 9 o'clock, 3 o'clock, or 6 o'clock.
        val rotation = camera.streamRotationDegrees(deviceOrientationDegrees)
        val outputWidth = sourceWidth
        val outputHeight = sourceHeight

        // The front camera's frames carry the HAL selfie mirror (the same flip
        // the preview cancels with flipX = frontFacing != mirror). Leaving that
        // mirror inside the rotation chain inverts the response to phone holds,
        // which turns a 90-degree hold into a 180-degree-off (upside-down)
        // landscape stream. Flip the front stream once to cancel it; the user's
        // mirror toggle then re-applies it, exactly like the preview and MJPEG.
        val mirror = cfg.mirror xor camera.isFrontFacing

        when (cfg.codec) {
            Codec.MJPEG -> startMjpegSession(
                cfg = cfg,
                generation = generation,
                requestedWidth = requestedWidth,
                requestedHeight = requestedHeight,
                rotation = rotation,
                mirror = mirror,
            )
            Codec.AVC, Codec.HEVC -> startEncodedSession(
                cfg = cfg,
                generation = generation,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                outputWidth = outputWidth,
                outputHeight = outputHeight,
                rotation = rotation,
                mirror = mirror,
            )
        }
    }

    private fun startMjpegSession(
        cfg: StreamConfig,
        generation: Long,
        requestedWidth: Int,
        requestedHeight: Int,
        rotation: Int,
        mirror: Boolean,
    ) {
        val yuvSize = camera.pickYuvOutputSize(requestedWidth, requestedHeight)
        val width = yuvSize.width
        val height = yuvSize.height
        if (width < 2 || height < 2 || width % 2 != 0 || height % 2 != 0) {
            failRebuild(generation, "Camera did not provide a valid MJPEG source size")
            return
        }
        val sourceSize = Size(width, height)
        val reader = try {
            ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 3)
        } catch (_: Exception) {
            failRebuild(generation, "Could not create MJPEG camera output")
            return
        }
        jpegReader = reader

        // Reused on the single JPEG handler thread. This avoids allocating two
        // full RGB Bitmaps for every frame merely to rotate MJPEG output.
        val nv21 = ByteArray(width * height * 3 / 2)
        val rotatedNv21 = if (rotation == 0) nv21 else ByteArray(nv21.size)
        val mirroredNv21 = if (mirror) ByteArray(nv21.size) else null
        val swap = rotation == 90 || rotation == 270
        val outputWidth = if (swap) height else width
        val outputHeight = if (swap) width else height
        val jpegOutput = ByteArrayOutputStream((width * height / 2).coerceAtLeast(32 * 1024))

        reader.setOnImageAvailableListener({ imageReader ->
            val image = try { imageReader.acquireLatestImage() } catch (_: Exception) { null }
            if (image != null) {
                try {
                    if (!copyYuv420ToNv21(image, nv21, width, height)) return@setOnImageAvailableListener
                    val rotated = if (rotation == 0) nv21 else {
                        Nv21Rotation.rotate(nv21, rotatedNv21, width, height, rotation)
                        rotatedNv21
                    }
                    val frame = if (mirror) {
                        Nv21Rotation.mirrorHorizontally(rotated, checkNotNull(mirroredNv21), outputWidth, outputHeight)
                        mirroredNv21
                    } else {
                        rotated
                    }
                    jpegOutput.reset()
                    val quality = _config.value.jpegQuality.coerceIn(1, 100)
                    val compressed = YuvImage(
                        frame,
                        ImageFormat.NV21,
                        outputWidth,
                        outputHeight,
                        null,
                    ).compressToJpeg(Rect(0, 0, outputWidth, outputHeight), quality, jpegOutput)
                    if (compressed) broadcastVideo(jpegOutput.toByteArray(), image.timestamp / 1_000L)
                } catch (_: Exception) {
                } finally {
                    try { image.close() } catch (_: Exception) {}
                }
            }
        }, ensureJpegHandler())

        startAudioIfNeeded(cfg)
        camera.configure(
            targets = listOf(reader.surface),
            requestedWidth = width,
            requestedHeight = height,
            requestedFps = cfg.fps,
        ) { fps, _ ->
            managerHandler.post {
                completeRebuild(
                    generation = generation,
                    fps = fps,
                    codec = Codec.MJPEG,
                    sourceSize = sourceSize,
                    rotation = rotation,
                )
            }
        }
    }

    private fun startEncodedSession(
        cfg: StreamConfig,
        generation: Long,
        sourceWidth: Int,
        sourceHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
        rotation: Int,
        mirror: Boolean,
    ) {
        var activeCodec = cfg.codec
        var encoder = createVideoEncoder(activeCodec, outputWidth, outputHeight, cfg)
        if (!encoder.start()) {
            encoder.stop()
            if (activeCodec == Codec.HEVC) {
                activeCodec = Codec.AVC
                encoder = createVideoEncoder(activeCodec, outputWidth, outputHeight, cfg)
                if (!encoder.start()) {
                    encoder.stop()
                    failRebuild(generation, "No H.264/H.265 hardware encoder is available")
                    return
                }
                val fallbackConfig = _config.value.copy(codec = Codec.AVC)
                _config.value = fallbackConfig
                persist(fallbackConfig)
            } else {
                failRebuild(generation, "No ${activeCodec.displayName} hardware encoder is available")
                return
            }
        }

        val encoderSurface = encoder.surface
        if (encoderSurface == null) {
            encoder.stop()
            failRebuild(generation, "Video encoder did not provide an input surface")
            return
        }

        // The GL pass is the verified transform path. The encoder surface is
        // only consumed directly when the camera buffer needs no transform at
        // all (sensor already aligned with the display and no mirror): the
        // encoder's handling of the HAL buffer transform is device-dependent,
        // so anything else goes through the GL rotator. The front camera is
        // always excluded because its built-in selfie mirror must be cancelled
        // by the GL pass.
        val direct = rotation == 0 && !mirror && camera.sensorOrientation() == 0 && !camera.isFrontFacing
        val target = if (direct) {
            encoderSurface
        } else {
            val rotator = try {
                GlRotator(encoderSurface, sourceWidth, sourceHeight, rotation, mirrored = mirror)
            } catch (_: Throwable) {
                encoder.stop()
                failRebuild(generation, "Could not initialize video rotation")
                return
            }
            glRotator = rotator
            rotator.inputSurface
        }
        videoEncoder = encoder
        startAudioIfNeeded(cfg)

        val finalCodec = activeCodec
        camera.configure(
            targets = listOf(target),
            requestedWidth = sourceWidth,
            requestedHeight = sourceHeight,
            requestedFps = cfg.fps,
        ) { fps, _ ->
            managerHandler.post {
                completeRebuild(
                    generation = generation,
                    fps = fps,
                    codec = finalCodec,
                    sourceSize = Size(sourceWidth, sourceHeight),
                    rotation = rotation,
                )
            }
        }
    }

    private fun createVideoEncoder(
        codec: Codec,
        width: Int,
        height: Int,
        cfg: StreamConfig,
    ): VideoEncoder = VideoEncoder(
        codec = codec,
        width = width,
        height = height,
        fps = cfg.fps,
        bitrate = cfg.bitrateMbps.coerceAtLeast(1) * 1_000_000,
    ) { data, ptsUs, isConfig ->
        broadcastVideo(data, ptsUs, isConfig)
    }

    private fun startAudioIfNeeded(cfg: StreamConfig) {
        if (!cfg.audioEnabled) return
        val encoder = AudioEncoder { data, ptsUs, isConfig ->
            broadcastAudio(data, ptsUs, isConfig)
        }
        if (encoder.start()) audioEncoder = encoder else encoder.stop()
    }

    private fun completeRebuild(
        generation: Long,
        fps: Int,
        codec: Codec,
        sourceSize: Size,
        rotation: Int,
    ) {
        if (generation != rebuildGeneration || !started.get()) return
        if (fps <= 0 || sourceSize.width <= 0 || sourceSize.height <= 0) {
            camera.closeSessionAndWait()
            stopEncoders()
            failRebuild(generation, "Camera session configuration failed")
            return
        }
        _state.update {
            it.copy(
                codec = codec,
                width = sourceSize.width,
                height = sourceSize.height,
                streamWidth = sourceSize.width,
                streamHeight = sourceSize.height,
                actualFps = fps,
                sensorOrientation = camera.sensorOrientation(),
                frontFacing = camera.isFrontFacing,
                serverError = null,
            )
        }
        finishRebuild(generation)
    }

    private fun failRebuild(generation: Long, message: String) {
        if (generation != rebuildGeneration) return
        _state.update { it.copy(serverError = message, actualFps = 0) }
        finishRebuild(generation)
    }

    private fun finishRebuild(generation: Long) {
        if (generation != rebuildGeneration) return
        rebuildInProgress = false
        if (rebuildQueued && started.get()) {
            rebuildQueued = false
            doRebuild()
        }
    }

    /** GL must be released before its encoder surface/codec is destroyed. */
    private fun stopEncoders() {
        try { glRotator?.release() } catch (_: Exception) {}
        glRotator = null
        try { videoEncoder?.stop() } catch (_: Exception) {}
        videoEncoder = null
        try { audioEncoder?.stop() } catch (_: Exception) {}
        audioEncoder = null
        try { jpegReader?.close() } catch (_: Exception) {}
        jpegReader = null
        stopJpegThread()
    }

    private fun ensureJpegHandler(): Handler {
        jpegHandler?.let { return it }
        val thread = HandlerThread("opencam-jpeg").apply { start() }
        return Handler(thread.looper).also {
            jpegThread = thread
            jpegHandler = it
        }
    }

    private fun stopJpegThread() {
        val handler = jpegHandler
        val thread = jpegThread
        jpegHandler = null
        jpegThread = null
        try { handler?.removeCallbacksAndMessages(null) } catch (_: Exception) {}
        try { thread?.quitSafely() } catch (_: Exception) {}
        try { thread?.join(500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    /** Copies flexible YUV_420_888 planes into tightly packed NV21 (Y + interleaved VU). */
    private fun copyYuv420ToNv21(image: Image, output: ByteArray, width: Int, height: Int): Boolean {
        if (image.planes.size < 3 || output.size < width * height * 3 / 2) return false
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer.duplicate()
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()
        val yBase = yBuffer.position()
        val uBase = uBuffer.position()
        val vBase = vBuffer.position()

        for (row in 0 until height) {
            val rowStart = yBase + row * yPlane.rowStride
            if (yPlane.pixelStride == 1) {
                if (rowStart < 0 || rowStart + width > yBuffer.limit()) return false
                yBuffer.position(rowStart)
                yBuffer.get(output, row * width, width)
            } else {
                for (col in 0 until width) {
                    val index = rowStart + col * yPlane.pixelStride
                    if (index !in yBase until yBuffer.limit()) return false
                    output[row * width + col] = yBuffer.get(index)
                }
            }
        }

        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val chromaOffset = width * height
        for (row in 0 until chromaHeight) {
            val uRow = uBase + row * uPlane.rowStride
            val vRow = vBase + row * vPlane.rowStride
            for (col in 0 until chromaWidth) {
                val uIndex = uRow + col * uPlane.pixelStride
                val vIndex = vRow + col * vPlane.pixelStride
                if (uIndex !in uBase until uBuffer.limit() || vIndex !in vBase until vBuffer.limit()) {
                    return false
                }
                val out = chromaOffset + (row * chromaWidth + col) * 2
                output[out] = vBuffer.get(vIndex)
                output[out + 1] = uBuffer.get(uIndex)
            }
        }
        return true
    }


    private fun broadcastVideo(data: ByteArray, ptsUs: Long, isConfig: Boolean = false) {
        if (data.isEmpty()) return
        val packet = Protocol.framePacket(data, if (isConfig) Protocol.NO_PTS else ptsUs)
        server?.videoClients?.forEach { it.send(packet) }
    }

    private fun broadcastAudio(data: ByteArray, ptsUs: Long, isConfig: Boolean = false) {
        if (data.isEmpty()) return
        val packet = Protocol.framePacket(data, if (isConfig) Protocol.NO_PTS else ptsUs)
        server?.audioClients?.forEach { it.send(packet) }
    }

    private fun clampSize(codec: Codec, requestedWidth: Int, requestedHeight: Int): Pair<Int, Int> {
        var width = requestedWidth.coerceAtLeast(2).toDouble()
        var height = requestedHeight.coerceAtLeast(2).toDouble()
        val minWidth = 320.0
        val minHeight = 240.0
        val maxWidth = 3840.0
        val maxHeight = if (codec == Codec.MJPEG) 1080.0 else 2160.0

        val upscale = maxOf(minWidth / width, minHeight / height, 1.0)
        width *= upscale
        height *= upscale
        val downscale = minOf(maxWidth / width, maxHeight / height, 1.0)
        width *= downscale
        height *= downscale

        fun even(value: Double): Int = ((value.roundToInt().coerceAtLeast(2) + 1) / 2) * 2
        return even(width) to even(height)
    }

    private fun sanitizeConfig(config: StreamConfig): StreamConfig = config.copy(
        width = config.width.coerceIn(2, 7680),
        height = config.height.coerceIn(2, 4320),
        fps = config.fps.coerceIn(1, 120),
        bitrateMbps = config.bitrateMbps.coerceIn(1, 100),
        jpegQuality = config.jpegQuality.coerceIn(1, 100),
        port = config.port.coerceIn(1024, 65535),
    )

    private fun loadConfig(): StreamConfig {
        val defaults = StreamConfig()
        return sanitizeConfig(
            StreamConfig(
                codec = Codec.entries.firstOrNull { it.name == prefs.getString(KEY_CODEC, null) } ?: defaults.codec,
                width = prefs.getInt(KEY_WIDTH, defaults.width),
                height = prefs.getInt(KEY_HEIGHT, defaults.height),
                fps = prefs.getInt(KEY_FPS, defaults.fps),
                bitrateMbps = prefs.getInt(KEY_BITRATE, defaults.bitrateMbps),
                jpegQuality = prefs.getInt(KEY_JPEG, defaults.jpegQuality),
                audioEnabled = prefs.getBoolean(KEY_AUDIO, defaults.audioEnabled),
                port = prefs.getInt(KEY_PORT, defaults.port),
                lens = CameraLens.entries.firstOrNull { it.name == prefs.getString(KEY_LENS, null) } ?: defaults.lens,
                keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, defaults.keepScreenOn),
                exposureEv = prefs.getInt(KEY_EXPOSURE, defaults.exposureEv),
                whiteBalance = WhiteBalance.entries.firstOrNull { it.name == prefs.getString(KEY_WB, null) }
                    ?: defaults.whiteBalance,
                eisEnabled = prefs.getBoolean(KEY_EIS, defaults.eisEnabled),
                mirror = prefs.getBoolean(KEY_MIRROR, defaults.mirror),
            ),
        )
    }

    private fun persist(config: StreamConfig) {
        prefs.edit()
            .putString(KEY_CODEC, config.codec.name)
            .putInt(KEY_WIDTH, config.width)
            .putInt(KEY_HEIGHT, config.height)
            .putInt(KEY_FPS, config.fps)
            .putInt(KEY_BITRATE, config.bitrateMbps)
            .putInt(KEY_JPEG, config.jpegQuality)
            .putBoolean(KEY_AUDIO, config.audioEnabled)
            .putInt(KEY_PORT, config.port)
            .putString(KEY_LENS, config.lens.name)
            .putBoolean(KEY_KEEP_SCREEN_ON, config.keepScreenOn)
            .putInt(KEY_EXPOSURE, config.exposureEv)
            .putString(KEY_WB, config.whiteBalance.name)
            .putBoolean(KEY_EIS, config.eisEnabled)
            .putBoolean(KEY_MIRROR, config.mirror)
            .apply()
    }

    private val callbacks = object : ServerCallbacks {
        override fun onVideoClientConnected(client: VideoClient) {
            managerHandler.post {
                _state.update { it.copy(videoClients = server?.videoClients?.size ?: 0) }
                val current = _config.value
                val requestedWidth = client.width.coerceIn(2, 7680)
                val requestedHeight = client.height.coerceIn(2, 4320)
                val requested = Triple(client.codec, requestedWidth, requestedHeight)
                val previousRequest = lastClientRequested
                lastClientRequested = requested
                val streamParametersChanged = requested != Triple(current.codec, current.width, current.height)
                // A plain reconnect re-sends the same request it sent before;
                // honoring it would revert settings the user just changed in the
                // app (and the server closes video clients on every rebuild, so
                // every app-side change triggers exactly such a reconnect). Only
                // genuinely new client requests override the app's config.
                if (streamParametersChanged && requested != previousRequest) {
                    server?.closeVideoClients()
                    val changed = sanitizeConfig(
                        current.copy(
                            codec = client.codec,
                            width = requestedWidth,
                            height = requestedHeight,
                        ),
                    )
                    _config.value = changed
                    persist(changed)
                    scheduleRebuild()
                    _state.update { it.copy(videoClients = 0) }
                }
            }
        }

        override fun onVideoClientDisconnected(client: VideoClient) {
            _state.update { it.copy(videoClients = server?.videoClients?.size ?: 0) }
        }

        override fun onAudioClientConnected(client: AudioClient) {
            _state.update { it.copy(audioClients = server?.audioClients?.size ?: 0) }
        }

        override fun onAudioClientDisconnected(client: AudioClient) {
            _state.update { it.copy(audioClients = server?.audioClients?.size ?: 0) }
        }

        override fun batteryPercent(): Int = BatteryUtils.batteryPercent(appContext)

        override fun statusJson(): String = this@StreamManager.statusJson()

        override fun applySettings(params: Map<String, String>) =
            this@StreamManager.applySettings(params)

        override fun onTally(state: String) {
            _state.update { it.copy(tally = state) }
        }
    }
}
