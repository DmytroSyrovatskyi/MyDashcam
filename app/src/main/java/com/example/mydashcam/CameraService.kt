package com.example.mydashcam

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.hardware.camera2.*
import android.location.LocationListener
import android.location.LocationManager
import android.media.*
import android.net.Uri
import android.os.*
import android.os.Process
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.OrientationEventListener
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.*
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.mydashcam.utils.NotificationHelper
import com.example.mydashcam.utils.StorageManager
import kotlinx.coroutines.*
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

@OptIn(ExperimentalCamera2Interop::class)
class CameraService : LifecycleService() {

    private var mediaRecorder: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var srtOutputStream: OutputStream? = null
    private var loopJob: Job? = null
    private var dynamicUpdateJob: Job? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraControl: CameraControl? = null
    private var cameraInfo: CameraInfo? = null

    private var currentVideoUri: Uri? = null
    private var currentVideoName: String? = null
    private var powerManager: PowerManager? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private var isThermalDowngradeActive = false
    private var orientationEventListener: OrientationEventListener? = null
    private var deviceOrientationAngle = 0

    private var recordingStartTime = 0L

    private var isoEma = 100f
    private var minHardwareIso = 100f
    private var maxHardwareIso = 1600f
    private var nextTargetBitrate = 18_000_000

    private var prefCameraType = "auto"
    private var prefResolution = "1080"
    private var prefFps = 30
    private var prefCodec = "auto"

    private var prefStampDate = true
    private var prefStampSpeed = false
    private var prefStampGps = false

    private var locationManager: LocationManager? = null
    private var currentSpeedKmH = 0f
    private var currentLat = 0.0
    private var currentLon = 0.0

    private var previewCallback: ((Bitmap, Int) -> Unit)? = null
    private var reusableBitmap: Bitmap? = null
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun setPreviewListener(listener: ((Bitmap, Int) -> Unit)?) {
            previewCallback = listener
        }
        fun isFrontCamera(): Boolean = prefCameraType == "front"
        fun getRecordingStartTime(): Long = recordingStartTime
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    companion object {
        var isAppVisible = false
        var isRecordingActive = false
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_EXIT = "ACTION_EXIT"
        const val ACTION_LOCK = "ACTION_LOCK"
        const val ACTION_STANDBY = "ACTION_STANDBY"
        const val ACTION_PAUSE_PREVIEW = "ACTION_PAUSE_PREVIEW"
        const val RECORDING_DURATION_MS = 600000L
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val prefs = getSharedPreferences("DashcamPrefs", MODE_PRIVATE)
            if (!prefs.getBoolean("pref_autostart", false)) return

            when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    if (!isRecordingActive) {
                        Toast.makeText(this@CameraService, "Auto-Start: Power Connected", Toast.LENGTH_SHORT).show()
                        startService(Intent(this@CameraService, CameraService::class.java).apply { action = ACTION_START })
                    }
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    if (isRecordingActive) {
                        Toast.makeText(this@CameraService, "Auto-Stop: Power Disconnected", Toast.LENGTH_SHORT).show()
                        startService(Intent(this@CameraService, CameraService::class.java).apply { action = ACTION_STOP })
                    }
                }
            }
        }
    }

    // НОВОЕ: Слушатель GPS (через лямбду, как просила Студия)
    private val locationListener = LocationListener { location ->
        currentSpeedKmH = if (location.hasSpeed()) location.speed * 3.6f else 0f
        currentLat = location.latitude
        currentLon = location.longitude
    }

    override fun onCreate() {
        super.onCreate()
        powerManager = getSystemService(POWER_SERVICE) as PowerManager
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        setupThermalListener()
        setupOrientationListener()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(powerReceiver, filter)

        loadPrefs()
    }

    private fun loadPrefs() {
        val prefs = getSharedPreferences("DashcamPrefs", MODE_PRIVATE)
        prefCameraType = prefs.getString("pref_camera", "auto") ?: "auto"
        prefResolution = prefs.getString("pref_resolution", "1080") ?: "1080"
        prefFps = prefs.getInt("pref_fps", 30)
        prefCodec = prefs.getString("pref_codec", "auto") ?: "auto"

        prefStampDate = prefs.getBoolean("pref_stamp_date", true)
        prefStampSpeed = prefs.getBoolean("pref_stamp_speed", false)
        prefStampGps = prefs.getBoolean("pref_stamp_gps", false)
    }

    @SuppressLint("MissingPermission")
    private fun startLocationTracking() {
        if ((prefStampSpeed || prefStampGps) && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try { locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener) } catch (_: Exception) {}
        }
    }

    private fun stopLocationTracking() {
        try { locationManager?.removeUpdates(locationListener) } catch (_: Exception) {}
    }

    private fun setupThermalListener() {
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            isThermalDowngradeActive = status >= PowerManager.THERMAL_STATUS_SEVERE
            if (status >= PowerManager.THERMAL_STATUS_CRITICAL && isRecordingActive) {
                stopHardware()
                isRecordingActive = false
                updateNotification()
            }
        }
        thermalListener = listener
        powerManager?.addThermalStatusListener(listener)
    }

    private fun setupOrientationListener() {
        orientationEventListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                deviceOrientationAngle = when (orientation) {
                    in 45..134 -> 90
                    in 135..224 -> 180
                    in 225..314 -> 270
                    else -> 0
                }
            }
        }
        orientationEventListener?.enable()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        updateNotification()

        when (intent?.action) {
            ACTION_START -> if (!isRecordingActive) {
                isRecordingActive = true
                isoEma = minHardwareIso
                recordingStartTime = System.currentTimeMillis()
                loadPrefs()
                startLocationTracking()
                startHardwareLoop()
            }
            ACTION_STOP -> if (isRecordingActive) {
                isRecordingActive = false
                recordingStartTime = 0L
                playStopSound()
                stopHardware()
                stopLocationTracking()
                updateNotification()
                if (isAppVisible) startHardware(isRecording = false)
            }
            ACTION_LOCK -> executeLock()
            ACTION_EXIT -> {
                if (isRecordingActive) { isRecordingActive = false; stopHardware() }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                Process.killProcess(Process.myPid())
                return START_NOT_STICKY
            }
            ACTION_STANDBY -> {
                if (!isRecordingActive && isAppVisible) {
                    loadPrefs()
                    startHardware(isRecording = false)
                }
            }
            ACTION_PAUSE_PREVIEW -> {
                if (!isRecordingActive) {
                    try { cameraProvider?.unbindAll() } catch(_: Exception) {}
                    cameraControl = null
                    cameraInfo = null
                    updateNotification()
                }
            }
        }
        return START_STICKY
    }

    private fun updateNotification(currentFileProgress: Int = 0) {
        val prefs = getSharedPreferences("DashcamPrefs", MODE_PRIVATE)
        val limit = prefs.getInt("max_loop_files", 6)
        val usePip = prefs.getBoolean("pref_pip", false)
        val filesCount = StorageManager.getFilesCount(this)
        val loopInfo = if (limit > 50) "∞" else "$filesCount/$limit"

        val notification = NotificationHelper.createNotification(
            context = this,
            isRecording = isRecordingActive,
            cameraType = "$prefCameraType ($loopInfo)",
            resolution = prefResolution,
            fps = prefFps,
            iso = isoEma.toInt(),
            bitrateMbps = nextTargetBitrate / 1_000_000,
            recordingStartTime = recordingStartTime,
            usePip = usePip,
            progressPercent = currentFileProgress
        )

        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    private fun formatSrtTime(seconds: Int): String {
        return String.format(Locale.US, "%02d:%02d:%02d,000", seconds / 3600, (seconds % 3600) / 60, seconds % 60)
    }

    private fun startDynamicUpdates() {
        dynamicUpdateJob?.cancel()
        dynamicUpdateJob = lifecycleScope.launch {
            var currEv = 0.0f
            var srtCounter = 0

            while (isRecordingActive) {
                delay(1000)

                val darkness = ((isoEma - minHardwareIso) / (maxHardwareIso - minHardwareIso)).coerceIn(0f, 1f)
                val targetEV = 0.0f - (1.5f * darkness)
                currEv = (targetEV * 0.05f) + (currEv * 0.95f)

                val step = cameraInfo?.exposureState?.exposureCompensationStep
                if (step != null && step.numerator != 0) {
                    val idx = (currEv / (step.numerator.toFloat()/step.denominator.toFloat())).roundToInt()
                    cameraControl?.setExposureCompensationIndex(idx)
                }

                val codecMult = if (prefCodec == "hevc" || prefCodec == "auto") 0.7f else 1.0f
                val resMult = if(prefResolution == "4k") 3.0f else if(prefResolution == "720") 0.6f else 1.0f
                val fpsMult = if(prefFps >= 60) 1.6f else 1.0f
                val baseBitrate = (15_000_000f + (8_000_000f * darkness)) * resMult * fpsMult * codecMult
                nextTargetBitrate = if (isThermalDowngradeActive) (baseBitrate * 0.7f).toInt() else baseBitrate.toInt()

                if (srtOutputStream != null && (prefStampDate || prefStampSpeed || prefStampGps)) {
                    val startT = formatSrtTime(srtCounter)
                    val endT = formatSrtTime(srtCounter + 1)
                    val parts = mutableListOf<String>()

                    if (prefStampDate) parts.add(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(System.currentTimeMillis()))
                    if (prefStampSpeed) parts.add(String.format(Locale.US, "%d km/h", currentSpeedKmH.toInt()))
                    if (prefStampGps) parts.add(String.format(Locale.US, "%.5f, %.5f", currentLat, currentLon))

                    if (parts.isNotEmpty()) {
                        val textLine = parts.joinToString("  |  ")
                        val block = "${srtCounter + 1}\n$startT --> $endT\n$textLine\n\n"
                        try { srtOutputStream?.write(block.toByteArray()) } catch (_: Exception) {}
                    }
                }
                srtCounter++

                val elapsed = System.currentTimeMillis() - recordingStartTime
                val fileProgress = ((elapsed.toFloat() / RECORDING_DURATION_MS) * 100).toInt()
                updateNotification(fileProgress)
            }
        }
    }

    private fun playStartSound() { try { MediaPlayer.create(this, R.raw.s25_ultra_notification).start() } catch(_: Exception) {} }
    private fun playStopSound() { try { MediaPlayer.create(this, R.raw.alpha).start() } catch(_: Exception) {} }

    private fun executeLock() {
        val uri = currentVideoUri
        val name = currentVideoName
        if (isRecordingActive && uri != null && name != null) {
            stopHardware()
            val newName = name.replace("BVR_PRO_", "LOCKED_BVR_PRO_")
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.DISPLAY_NAME, newName) }, null, null)
            StorageManager.renameCompanionSrt(this, name, newName)
            startHardwareLoop()
        }
    }

    private fun startHardwareLoop() {
        if (!isRecordingActive) return

        if (!StorageManager.checkStorageSpace(this)) {
            isRecordingActive = false; recordingStartTime = 0L; playStopSound(); stopHardware(); stopLocationTracking(); updateNotification(); return
        }

        val limit = getSharedPreferences("DashcamPrefs", MODE_PRIVATE).getInt("max_loop_files", 6)
        StorageManager.manageLoopStorage(this, limit)

        startHardware(isRecording = true)
        startDynamicUpdates()
        updateNotification()
        loopJob?.cancel()
        loopJob = lifecycleScope.launch {
            delay(RECORDING_DURATION_MS)
            if (isRecordingActive) {
                stopHardware()
                startHardwareLoop()
            }
        }
    }

    @SuppressLint("MissingPermission", "UnsafeOptInUsageError")
    private fun startHardware(isRecording: Boolean) {
        try {
            cameraProvider?.unbindAll()
            ProcessCameraProvider.getInstance(this).addListener({
                val provider = ProcessCameraProvider.getInstance(this).get()
                cameraProvider = provider

                val all = provider.availableCameraInfos
                val target = when(prefCameraType) {
                    "front" -> all.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
                    "back" -> all.filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }.maxByOrNull { Camera2CameraInfo.from(it).getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: 0f }
                    else -> all.filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }.minByOrNull { Camera2CameraInfo.from(it).getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: Float.MAX_VALUE }
                } ?: all.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_BACK } ?: return@addListener

                val chars = Camera2CameraInfo.from(target).getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val sizes = chars?.getOutputSizes(MediaRecorder::class.java) ?: emptyArray()
                val reqW = if(prefResolution == "4k") 3840 else if(prefResolution == "720") 1280 else 1920
                val bestSize = sizes.filter { it.width >= reqW }.minByOrNull { it.width } ?: sizes.maxByOrNull { it.width } ?: Size(1920, 1080)

                val ranges = Camera2CameraInfo.from(target).getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                val bestRange = ranges?.firstOrNull { it.upper == prefFps && it.lower == prefFps } ?: ranges?.firstOrNull { it.upper == prefFps } ?: Range(30, 30)

                val sensorOri = Camera2CameraInfo.from(target).getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                val rot = (sensorOri + (if (target.lensFacing == CameraSelector.LENS_FACING_FRONT) -deviceOrientationAngle else deviceOrientationAngle) + 360) % 360

                if (isRecording) {
                    setupRecorder(bestSize.width, bestSize.height, rot)
                }

                val selector = ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(bestSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build()

                val recordPreview = Preview.Builder().setResolutionSelector(selector).let { builder ->
                    val extender = Camera2Interop.Extender(builder)

                    extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, bestRange)
                    extender.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    extender.setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                    extender.setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)

                    extender.setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(s: CameraCaptureSession, req: CaptureRequest, res: TotalCaptureResult) {
                            res.get(CaptureResult.SENSOR_SENSITIVITY)?.let { iso -> isoEma = (iso * 0.15f) + (isoEma * 0.85f) }
                        }
                    })
                    builder.build()
                }

                if (isRecording && mediaRecorder != null) {
                    recordPreview.setSurfaceProvider(ContextCompat.getMainExecutor(this)) { request ->
                        request.provideSurface(mediaRecorder!!.surface, ContextCompat.getMainExecutor(this)) {}
                    }
                }

                val analysisSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(Size(854, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build()

                val imageAnalysis = ImageAnalysis.Builder()
                    .setResolutionSelector(analysisSelector)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build().apply {
                        setAnalyzer(ContextCompat.getMainExecutor(this@CameraService)) { imageProxy ->
                            if (previewCallback != null && isAppVisible) {
                                if (reusableBitmap == null || reusableBitmap!!.width != imageProxy.width) {
                                    reusableBitmap = createBitmap(imageProxy.width, imageProxy.height)
                                }
                                imageProxy.planes[0].buffer.rewind()
                                reusableBitmap!!.copyPixelsFromBuffer(imageProxy.planes[0].buffer)
                                previewCallback?.invoke(reusableBitmap!!, imageProxy.imageInfo.rotationDegrees)
                            }
                            imageProxy.close()
                        }
                    }

                try {
                    provider.unbindAll()
                    val useCases = mutableListOf<UseCase>(imageAnalysis)
                    if (isRecording) useCases.add(recordPreview)
                    val cam = provider.bindToLifecycle(this, CameraSelector.Builder().addCameraFilter { _ -> listOf(target) }.build(), *useCases.toTypedArray())
                    cameraControl = cam.cameraControl
                    cameraInfo = cam.cameraInfo
                    if (isRecording) { mediaRecorder?.start(); playStartSound() }
                } catch (_: Exception) {}
            }, ContextCompat.getMainExecutor(this))
        } catch (_: Exception) {}
    }

    private fun setupRecorder(w: Int, h: Int, rot: Int) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
            currentVideoName = "BVR_PRO_$timestamp.mp4"
            val srtName = "BVR_PRO_$timestamp.srt"

            currentVideoUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, currentVideoName)
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MyDashcam")
            })

            pfd = contentResolver.openFileDescriptor(currentVideoUri!!, "rw")

            if (prefStampDate || prefStampSpeed || prefStampGps) {
                val srtUri = contentResolver.insert(MediaStore.Files.getContentUri("external"), ContentValues().apply {
                    put(MediaStore.Files.FileColumns.DISPLAY_NAME, srtName)
                    put(MediaStore.Files.FileColumns.MIME_TYPE, "application/x-subrip")
                    put(MediaStore.Files.FileColumns.RELATIVE_PATH, "Movies/MyDashcam")
                })
                if (srtUri != null) srtOutputStream = contentResolver.openOutputStream(srtUri)
            }

            mediaRecorder = MediaRecorder(this).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(if (prefCodec == "hevc" || prefCodec == "auto") MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoEncodingBitRate(nextTargetBitrate)
                setVideoFrameRate(prefFps)
                setVideoSize(w, h)
                setOrientationHint(rot)
                setOutputFile(pfd!!.fileDescriptor)
                prepare()
            }
        } catch (_: Exception) {
            isRecordingActive = false
            stopHardware()
        }
    }

    private fun stopHardware() {
        loopJob?.cancel()
        dynamicUpdateJob?.cancel()
        cameraControl = null
        cameraInfo = null
        try { mediaRecorder?.stop() } catch(_: Exception) {}
        finally {
            try { mediaRecorder?.release() } catch(_: Exception) {}
            mediaRecorder = null
            try { pfd?.close() } catch(_: Exception) {}
            pfd = null
            try { srtOutputStream?.close() } catch(_: Exception) {}
            srtOutputStream = null
            try { cameraProvider?.unbindAll() } catch(_: Exception) {}
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!isRecordingActive) {
            try { cameraProvider?.unbindAll() } catch(_: Exception) {}
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(powerReceiver) } catch (_: Exception) {}
        stopLocationTracking()
        orientationEventListener?.disable()
        thermalListener?.let { powerManager?.removeThermalStatusListener(it) }
        isRecordingActive = false
        stopHardware()
        super.onDestroy()
    }
}