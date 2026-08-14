package com.opencam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Range
import android.util.Size
import android.view.Surface
import com.opencam.CameraLens
import com.opencam.util.CameraRotation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/** Camera2 wrapper with all mutable camera state confined to one HandlerThread. */
class Camera2Controller(context: Context) {

    data class CameraInfo(
        val id: String,
        val facing: Int,
        val focalLength: Float,
        val sensorOrientation: Int,
    )

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraThread = HandlerThread("opencam-camera").apply { start() }
    private val handler = Handler(cameraThread.looper)

    private var device: CameraDevice? = null
    @Volatile private var characteristics: CameraCharacteristics? = null
    private var session: CameraCaptureSession? = null
    private var repeatingBuilder: CaptureRequest.Builder? = null
    private var pendingSessionTask: (() -> Unit)? = null
    private val sessionCloseWaiters = mutableListOf<CountDownLatch>()
    private val deviceCloseWaiters = mutableListOf<CountDownLatch>()
    private var sessionGeneration = 0L
    private var sessionConfigurationInFlight = false

    private var previewTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    private val torchOn = AtomicBoolean(false)
    private val openGeneration = AtomicLong(0L)
    private var openInFlight = false

    @Volatile var jpegQuality: Int = 85
    @Volatile var exposureEv: Int = 0
    @Volatile var whiteBalanceMode: Int = CameraMetadata.CONTROL_AWB_MODE_AUTO
    @Volatile var eisEnabled: Boolean = false

    private var baseCrop: Rect? = null
    @Volatile private var zoomScale = 1f

    /** Current digital zoom (read from any thread for status reporting). */
    val zoomLevel: Float get() = zoomScale

    @Volatile var currentCameraId: String? = null
        private set
    @Volatile private var sensorOrientationCache = 0
    @Volatile private var frontFacingCache = false

    fun listCameras(): List<CameraInfo> {
        val result = mutableListOf<CameraInfo>()
        try {
            for (id in manager.cameraIdList) {
                val info = manager.getCameraCharacteristics(id)
                result += CameraInfo(
                    id = id,
                    facing = info.get(CameraCharacteristics.LENS_FACING)
                        ?: CameraCharacteristics.LENS_FACING_BACK,
                    focalLength = info.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        ?.firstOrNull() ?: 0f,
                    sensorOrientation = info.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
                )
            }
        } catch (_: Exception) {
        }
        return result
    }

    fun pickCameraId(lens: CameraLens): String? {
        val cameras = listCameras()
        if (cameras.isEmpty()) return null
        val back = cameras.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
        val front = cameras.filter { it.facing == CameraCharacteristics.LENS_FACING_FRONT }
        return when (lens) {
            CameraLens.FRONT -> front.firstOrNull()?.id ?: back.firstOrNull()?.id
            CameraLens.BACK -> back.firstOrNull()?.id ?: front.firstOrNull()?.id
            CameraLens.BACK_WIDE -> back.minByOrNull { it.focalLength }?.id ?: back.firstOrNull()?.id
            CameraLens.BACK_TELE -> back.maxByOrNull { it.focalLength }?.id ?: back.firstOrNull()?.id
        }
    }

    fun sensorOrientation(cameraId: String? = currentCameraId): Int {
        val id = cameraId ?: currentCameraId
        if (id == null) return sensorOrientationCache
        return try {
            val orient = manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: sensorOrientationCache
            sensorOrientationCache = orient
            orient
        } catch (_: Exception) {
            sensorOrientationCache
        }
    }

    val isFrontFacing: Boolean
        get() {
            val id = currentCameraId
            if (id == null) return frontFacingCache
            return try {
                val facing = manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
                frontFacingCache = facing
                facing
            } catch (_: Exception) {
                frontFacingCache
            }
        }

    /** Stream rotation incorporating physical accelerometer device orientation. */
    fun streamRotationDegrees(deviceOrientationDeg: Int = 0, cameraId: String? = currentCameraId): Int {
        val sensor = sensorOrientation(cameraId)
        val isFront = isFrontFacing
        return if (isFront) {
            CameraRotation.normalize(sensor + deviceOrientationDeg)
        } else {
            CameraRotation.normalize(sensor - deviceOrientationDeg)
        }
    }

    @SuppressLint("MissingPermission")
    fun open(cameraId: String, onResult: (Boolean) -> Unit) {
        val generation = openGeneration.incrementAndGet()
        // CameraDevice.close() is asynchronous. Wait briefly before opening a
        // different lens so rapid switches cannot fail with CAMERA_IN_USE.
        if (!closeDeviceAndWait()) {
            onResult(false)
            return
        }
        handler.post {
            if (generation != openGeneration.get()) return@post
            zoomScale = 1f
            baseCrop = null
            try {
                val info = manager.getCameraCharacteristics(cameraId)
                characteristics = info
                sensorOrientationCache = info.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                frontFacingCache = info.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_FRONT
                openInFlight = true
                manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        openInFlight = false
                        if (generation != openGeneration.get()) {
                            camera.close()
                            return
                        }
                        device = camera
                        currentCameraId = cameraId
                        onResult(true)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        openInFlight = false
                        camera.close()
                        if (device === camera) device = null
                        currentCameraId = null
                        if (generation == openGeneration.get()) onResult(false)
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        openInFlight = false
                        camera.close()
                        if (device === camera) device = null
                        currentCameraId = null
                        if (generation == openGeneration.get()) onResult(false)
                    }

                    override fun onClosed(camera: CameraDevice) {
                        if (device === camera) device = null
                        completeDeviceWaitersIfIdle()
                        completeSessionWaitersIfIdle()
                    }
                }, handler)
            } catch (_: Exception) {
                openInFlight = false
                characteristics = null
                currentCameraId = null
                completeDeviceWaitersIfIdle()
                if (generation == openGeneration.get()) onResult(false)
            }
        }
    }

    fun preparePreview(texture: SurfaceTexture?) {
        handler.post {
            if (texture === previewTexture) return@post
            try { previewSurface?.release() } catch (_: Exception) {}
            previewSurface = null
            previewTexture = texture
            if (texture != null) {
                try { previewSurface = Surface(texture) } catch (_: Exception) { previewSurface = null }
            }
        }
    }

    fun isTorchAvailable(): Boolean =
        characteristics?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false

    val maxDigitalZoom: Float
        get() = characteristics?.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f

    val exposureCompensationRange: Range<Int>
        get() = characteristics?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)

    fun pickPreviewOutputSize(reqW: Int, reqH: Int): Size {
        val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        return chooseSize(sizes, reqW, reqH)
    }

    fun pickJpegOutputSize(reqW: Int, reqH: Int): Size {
        val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
        return chooseSize(sizes, reqW, reqH)
    }

    fun pickYuvOutputSize(reqW: Int, reqH: Int): Size {
        val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        return chooseSize(sizes, reqW, reqH)
    }

    fun configure(
        targets: List<Surface>,
        requestedWidth: Int,
        requestedHeight: Int,
        requestedFps: Int,
        onReady: (actualFps: Int, size: Size) -> Unit,
    ) {
        val targetCopy = targets.toList()
        handler.post {
            val generation = ++sessionGeneration
            configureInternal(
                targetCopy,
                requestedWidth,
                requestedHeight,
                requestedFps,
                generation,
                onReady,
            )
        }
    }

    private fun configureInternal(
        targets: List<Surface>,
        requestedWidth: Int,
        requestedHeight: Int,
        requestedFps: Int,
        generation: Long,
        onReady: (actualFps: Int, size: Size) -> Unit,
    ) {
        val dev = device
        val info = characteristics
        if (dev == null || info == null || requestedWidth <= 0 || requestedHeight <= 0) {
            onReady(-1, Size(0, 0))
            return
        }
        try {
            val map: StreamConfigurationMap = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: run { onReady(-1, Size(0, 0)); return }
            val previewSizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
            val selectedSize = chooseSize(previewSizes, requestedWidth, requestedHeight)
            val fpsRange = chooseFpsRange(info, requestedFps)

            val template = if (targets.isNotEmpty()) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
            val builder = dev.createCaptureRequest(template)
            previewSurface?.let { builder.addTarget(it) }
            targets.forEach { builder.addTarget(it) }

            val fullSensor = info.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val requestedAspect = requestedWidth.toFloat() / requestedHeight.toFloat()
            baseCrop = fullSensor?.let { aspectCrop(it, requestedAspect) }
            baseCrop?.let { base ->
                builder.set(
                    CaptureRequest.SCALER_CROP_REGION,
                    if (zoomScale > 1f) zoomedCrop(base, zoomScale) else base,
                )
            }
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            builder.set(
                CaptureRequest.CONTROL_AWB_MODE,
                if (awbSupported(whiteBalanceMode)) whiteBalanceMode else CameraMetadata.CONTROL_AWB_MODE_AUTO,
            )
            info.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let { range ->
                builder.set(
                    CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                    exposureEv.coerceIn(range.lower, range.upper),
                )
            }
            val stabilizationModes = info.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            val stabilizationOn = eisEnabled &&
                stabilizationModes?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true
            builder.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                if (stabilizationOn) CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
                else CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
            )
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
            builder.set(CaptureRequest.JPEG_QUALITY, jpegQuality.coerceIn(1, 100).toByte())
            if (torchOn.get() && isTorchAvailable()) {
                builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
            }

            val allSurfaces = buildList {
                previewSurface?.let { add(it) }
                addAll(targets)
            }
            if (allSurfaces.isEmpty()) {
                onReady(-1, Size(0, 0))
                return
            }
            startSession(
                request = builder.build(),
                builder = builder,
                surfaces = allSurfaces,
                fpsUpper = fpsRange.upper,
                size = selectedSize,
                generation = generation,
                onReady = onReady,
            )
        } catch (_: Exception) {
            onReady(-1, Size(0, 0))
        }
    }

    private fun startSession(
        request: CaptureRequest,
        builder: CaptureRequest.Builder,
        surfaces: List<Surface>,
        fpsUpper: Int,
        size: Size,
        generation: Long,
        onReady: (actualFps: Int, size: Size) -> Unit,
    ) {
        val task = task@{
            if (generation != sessionGeneration) return@task
            val dev = device ?: run {
                onReady(-1, Size(0, 0))
                return@task
            }
            sessionConfigurationInFlight = true
            try {
                dev.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(newSession: CameraCaptureSession) {
                        sessionConfigurationInFlight = false
                        if (generation != sessionGeneration || device == null) {
                            try { newSession.close() } catch (_: Exception) {
                                completeSessionWaitersIfIdle()
                                runPendingSessionIfPossible()
                            }
                            return
                        }
                        session = newSession
                        repeatingBuilder = builder
                        try {
                            newSession.setRepeatingRequest(request, null, handler)
                            onReady(fpsUpper, size)
                        } catch (_: Exception) {
                            repeatingBuilder = null
                            try { newSession.close() } catch (_: Exception) {
                                if (session === newSession) session = null
                                completeSessionWaitersIfIdle()
                            }
                            onReady(-1, Size(0, 0))
                        }
                    }

                    override fun onConfigureFailed(failedSession: CameraCaptureSession) {
                        sessionConfigurationInFlight = false
                        try { failedSession.close() } catch (_: Exception) {}
                        if (generation == sessionGeneration) onReady(-1, Size(0, 0))
                        completeSessionWaitersIfIdle()
                        runPendingSessionIfPossible()
                    }

                    override fun onClosed(closedSession: CameraCaptureSession) {
                        if (session === closedSession) session = null
                        completeSessionWaitersIfIdle()
                        runPendingSessionIfPossible()
                    }
                }, handler)
            } catch (_: Exception) {
                sessionConfigurationInFlight = false
                if (generation == sessionGeneration) onReady(-1, Size(0, 0))
                completeSessionWaitersIfIdle()
                runPendingSessionIfPossible()
            }
        }

        pendingSessionTask = task
        val current = session
        when {
            current != null -> {
                try {
                    current.stopRepeating()
                } catch (_: Exception) {}
                try {
                    current.abortCaptures()
                } catch (_: Exception) {}
                try {
                    current.close()
                } catch (_: Exception) {
                    session = null
                    runPendingSessionIfPossible()
                }
            }
            sessionConfigurationInFlight -> Unit
            else -> runPendingSessionIfPossible()
        }
    }

    private fun runPendingSessionIfPossible() {
        if (session != null || sessionConfigurationInFlight) return
        val next = pendingSessionTask ?: return
        pendingSessionTask = null
        next.invoke()
    }

    private fun completeSessionWaitersIfIdle() {
        if (session != null || sessionConfigurationInFlight) return
        sessionCloseWaiters.toList().also { sessionCloseWaiters.clear() }
            .forEach { it.countDown() }
    }

    private fun completeDeviceWaitersIfIdle() {
        if (device != null || openInFlight) return
        deviceCloseWaiters.toList().also { deviceCloseWaiters.clear() }
            .forEach { it.countDown() }
    }

    private fun closeDeviceAndWait(timeoutMs: Long = 1_500L): Boolean {
        if (Looper.myLooper() == cameraThread.looper) {
            closeDeviceInternal(null)
            return device == null && !openInFlight
        }
        val latch = CountDownLatch(1)
        if (!handler.post { closeDeviceInternal(latch) }) return false
        return try {
            latch.await(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun closeDeviceInternal(waiter: CountDownLatch?) {
        sessionGeneration++
        pendingSessionTask = null
        repeatingBuilder = null
        waiter?.let { deviceCloseWaiters += it }

        val currentSession = session
        if (currentSession != null) {
            try { currentSession.stopRepeating() } catch (_: Exception) {}
            try { currentSession.abortCaptures() } catch (_: Exception) {}
            try { currentSession.close() } catch (_: Exception) { session = null }
        }

        val currentDevice = device
        device = null
        currentCameraId = null
        characteristics = null
        if (currentDevice == null) {
            completeDeviceWaitersIfIdle()
            completeSessionWaitersIfIdle()
            return
        }
        try {
            currentDevice.close()
        } catch (_: Exception) {
            deviceCloseWaiters.toList().also { deviceCloseWaiters.clear() }
                .forEach { it.countDown() }
            sessionConfigurationInFlight = false
            session = null
            completeSessionWaitersIfIdle()
        }
    }

    fun closeSession() {
        handler.post { closeSessionInternal(null) }
    }

    /** Waits briefly for Camera2 to stop targeting encoder/GL surfaces before they are released. */
    fun closeSessionAndWait(timeoutMs: Long = 1_500L): Boolean {
        if (Looper.myLooper() == cameraThread.looper) {
            closeSessionInternal(null)
            return true
        }
        val latch = CountDownLatch(1)
        if (!handler.post { closeSessionInternal(latch) }) return false
        return try {
            latch.await(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun closeSessionInternal(waiter: CountDownLatch?) {
        sessionGeneration++
        pendingSessionTask = null
        repeatingBuilder = null
        waiter?.let { sessionCloseWaiters += it }
        val current = session
        if (current == null) {
            completeSessionWaitersIfIdle()
            return
        }
        try { current.stopRepeating() } catch (_: Exception) {}
        try { current.abortCaptures() } catch (_: Exception) {}
        try {
            current.close()
        } catch (_: Exception) {
            if (session === current) session = null
            completeSessionWaitersIfIdle()
        }
    }

    fun close() {
        openGeneration.incrementAndGet()
        handler.post { closeInternal() }
    }

    private fun closeInternal() {
        sessionGeneration++
        pendingSessionTask = null
        try { session?.stopRepeating() } catch (_: Exception) {}
        try { session?.abortCaptures() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        session = null
        sessionConfigurationInFlight = false
        completeSessionWaitersIfIdle()
        repeatingBuilder = null
        val currentDevice = device
        try { currentDevice?.close() } catch (_: Exception) {
            device = null
            completeDeviceWaitersIfIdle()
        }
        device = null
        currentCameraId = null
        characteristics = null
        if (currentDevice == null) completeDeviceWaitersIfIdle()
        try { previewSurface?.release() } catch (_: Exception) {}
        previewSurface = null
        previewTexture = null
        baseCrop = null
        zoomScale = 1f
    }

    fun setTorch(on: Boolean) {
        torchOn.set(on)
        handler.post {
            if (!isTorchAvailable()) return@post
            updateRepeating {
                it.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                it.set(
                    CaptureRequest.FLASH_MODE,
                    if (on) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF,
                )
            }
        }
    }

    fun setZoom(scale: Float) {
        handler.post {
            val maxZoom = characteristics
                ?.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            zoomScale = scale.coerceIn(1f, maxZoom)
            val base = baseCrop ?: return@post
            updateRepeating { it.set(CaptureRequest.SCALER_CROP_REGION, zoomedCrop(base, zoomScale)) }
        }
    }

    fun setExposureCompensation(value: Int) {
        handler.post {
            val range = characteristics
                ?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: return@post
            exposureEv = value.coerceIn(range.lower, range.upper)
            updateRepeating { it.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureEv) }
        }
    }

    fun applyWhiteBalance(mode: Int) {
        handler.post {
            whiteBalanceMode = mode
            val applied = if (awbSupported(mode)) mode else CameraMetadata.CONTROL_AWB_MODE_AUTO
            updateRepeating { it.set(CaptureRequest.CONTROL_AWB_MODE, applied) }
        }
    }

    fun applyEis(on: Boolean) {
        handler.post {
            eisEnabled = on
            val modes = characteristics
                ?.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            val mode = if (on && modes?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true) {
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
            } else {
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            }
            updateRepeating { it.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, mode) }
        }
    }

    /** Tap-to-focus in upright preview coordinates, including zoom crop. */
    fun focusAt(nx: Float, ny: Float) {
        handler.post {
            val info = characteristics ?: return@post
            val full = info.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return@post
            val base = baseCrop ?: full
            val crop = if (zoomScale > 1f) zoomedCrop(base, zoomScale) else base
            val x = nx.coerceIn(0f, 1f)
            val y = ny.coerceIn(0f, 1f)
            // The preview shows the sensor-upright content and the app is locked
            // to portrait, so the view -> sensor mapping is just the sensor
            // orientation (same for front and back).
            val (u, v) = when (CameraRotation.normalize(sensorOrientation())) {
                90 -> (1f - y) to x
                180 -> (1f - x) to (1f - y)
                270 -> y to (1f - x)
                else -> x to y
            }
            val sensorX = (crop.left + u * crop.width()).toInt().coerceIn(crop.left, crop.right - 1)
            val sensorY = (crop.top + v * crop.height()).toInt().coerceIn(crop.top, crop.bottom - 1)
            val half = (minOf(crop.width(), crop.height()) * 0.06f).toInt().coerceAtLeast(1)
            val left = (sensorX - half).coerceIn(crop.left, (crop.right - 2).coerceAtLeast(crop.left))
            val top = (sensorY - half).coerceIn(crop.top, (crop.bottom - 2).coerceAtLeast(crop.top))
            val width = (half * 2).coerceAtMost(crop.right - left).coerceAtLeast(1)
            val height = (half * 2).coerceAtMost(crop.bottom - top).coerceAtLeast(1)
            val region = MeteringRectangle(left, top, width, height, MeteringRectangle.METERING_WEIGHT_MAX)

            val activeSession = session ?: return@post
            val builder = repeatingBuilder ?: return@post
            try {
                if ((info.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0) {
                    builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
                }
                if ((info.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0) {
                    builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
                }
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                activeSession.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        captureSession: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        val activeBuilder = repeatingBuilder ?: return
                        try {
                            activeBuilder.set(
                                CaptureRequest.CONTROL_AF_TRIGGER,
                                CameraMetadata.CONTROL_AF_TRIGGER_IDLE,
                            )
                            activeBuilder.set(
                                CaptureRequest.CONTROL_AF_MODE,
                                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
                            )
                            if ((info.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0) {
                                activeBuilder.set(
                                    CaptureRequest.CONTROL_AF_REGIONS,
                                    emptyArray<MeteringRectangle>(),
                                )
                            }
                            if ((info.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0) {
                                activeBuilder.set(
                                    CaptureRequest.CONTROL_AE_REGIONS,
                                    emptyArray<MeteringRectangle>(),
                                )
                            }
                            captureSession.setRepeatingRequest(activeBuilder.build(), null, handler)
                        } catch (_: Exception) {
                        }
                    }
                }, handler)
            } catch (_: Exception) {
            }
        }
    }

    private fun awbSupported(mode: Int): Boolean = characteristics
        ?.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
        ?.contains(mode) ?: false

    private fun updateRepeating(modify: (CaptureRequest.Builder) -> Unit) {
        val builder = repeatingBuilder ?: return
        val activeSession = session ?: return
        try {
            modify(builder)
            activeSession.setRepeatingRequest(builder.build(), null, handler)
        } catch (_: Exception) {
        }
    }

    private fun aspectCrop(full: Rect, aspect: Float): Rect {
        if (!aspect.isFinite() || aspect <= 0f || full.width() <= 0 || full.height() <= 0) {
            return Rect(full)
        }
        var width = full.width()
        var height = (width / aspect).toInt().coerceAtLeast(1)
        if (height > full.height()) {
            height = full.height()
            width = (height * aspect).toInt().coerceAtLeast(1)
        }
        width = width.coerceAtMost(full.width())
        height = height.coerceAtMost(full.height())
        val left = full.left + (full.width() - width) / 2
        val top = full.top + (full.height() - height) / 2
        return Rect(left, top, left + width, top + height)
    }

    private fun zoomedCrop(base: Rect, scale: Float): Rect {
        val safeScale = scale.coerceAtLeast(1f)
        val width = (base.width() / safeScale).toInt().coerceAtLeast(2)
        val height = (base.height() / safeScale).toInt().coerceAtLeast(2)
        val centerX = base.centerX()
        val centerY = base.centerY()
        return Rect(
            centerX - width / 2,
            centerY - height / 2,
            centerX - width / 2 + width,
            centerY - height / 2 + height,
        )
    }

    private fun chooseSize(sizes: List<Size>, reqW: Int, reqH: Int): Size {
        val safeW = reqW.coerceAtLeast(2)
        val safeH = reqH.coerceAtLeast(2)
        if (sizes.isEmpty()) return Size(safeW, safeH)
        val requestedAspect = safeW.toFloat() / safeH.toFloat()
        val sameAspect = sizes.filter {
            it.width > 0 && it.height > 0 && abs(it.width.toFloat() / it.height - requestedAspect) < 0.02f
        }
        val pool = sameAspect.ifEmpty { sizes }
        val requestedArea = safeW.toLong() * safeH.toLong()
        return pool.minByOrNull { abs(it.width.toLong() * it.height.toLong() - requestedArea) } ?: pool.first()
    }

    private fun chooseFpsRange(info: CameraCharacteristics, requested: Int): Range<Int> {
        val ranges = info.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return Range(30, 30)
        val target = requested.coerceIn(1, 120)
        ranges.firstOrNull { it.lower == target && it.upper == target }?.let { return it }
        return ranges.minByOrNull { range ->
            abs(range.upper - target) * 10 + abs(range.lower - target)
        } ?: Range(30, 30)
    }
}
