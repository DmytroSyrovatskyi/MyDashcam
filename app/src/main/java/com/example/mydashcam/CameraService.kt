package com.example.mydashcam

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.hardware.camera2.*
import android.media.*
import android.net.Uri
import android.os.*
import android.os.Process
import android.provider.MediaStore
import android.util.*
import android.view.OrientationEventListener
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.*
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import androidx.lifecycle.*
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

@OptIn(ExperimentalCamera2Interop::class)
class CameraService : LifecycleService() {

    private var mediaRecorder: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
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

    private var previewCallback: ((Bitmap, Int) -> Unit)? = null
    private var reusableBitmap: Bitmap? = null
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun setPreviewListener(listener: ((Bitmap, Int) -> Unit)?) {
            previewCallback = listener
        }
        fun isFrontCamera(): Boolean = prefCameraType == "front"
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

    override fun onCreate() {
        super.onCreate()
        powerManager = getSystemService(POWER_SERVICE) as PowerManager
        setupThermalListener()
        setupOrientationListener()

        val prefs = getSharedPreferences("DashcamPrefs", MODE_PRIVATE)
        prefCameraType = prefs.getString("pref_camera", "auto") ?: "auto"
        prefResolution = prefs.getString("pref_resolution", "1080") ?: "1080"
        prefFps = prefs.getInt("pref_fps", 30)
        prefCodec = prefs.getString("pref_codec", "auto") ?: "auto"
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

                val prefs = getSharedPreferences("DashcamPrefs", MODE_PRIVATE)
                prefCameraType = prefs.getString("pref_camera", "auto") ?: "auto"
                prefResolution = prefs.getString("pref_resolution", "1080") ?: "1080"
                prefFps = prefs.getInt("pref_fps", 30)
                prefCodec = prefs.getString("pref_codec", "auto") ?: "auto"
                startHardwareLoop()
            }
            ACTION_STOP -> if (isRecordingActive) {
                isRecordingActive = false
                recordingStartTime = 0L
                playStopSound()
                stopHardware()
                updateNotification()
                if (isAppVisible) {
                    startHardware(isRecording = false)
                }
            }
            ACTION_LOCK -> executeLock()
            ACTION_EXIT -> {
                if (isRecordingActive) {
                    isRecordingActive = false
                    stopHardware()
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                Process.killProcess(Process.myPid())
                return START_NOT_STICKY
            }
            ACTION_STANDBY -> {
                if (!isRecordingActive && isAppVisible) {
                    val prefs = getSharedPreferences("DashcamPrefs", MODE_PRIVATE)
                    prefCameraType = prefs.getString("pref_camera", "auto") ?: "auto"
                    prefResolution = prefs.getString("pref_resolution", "1080") ?: "1080"
                    prefFps = prefs.getInt("pref_fps", 30)
                    startHardware(isRecording = false)
                }
            }
            ACTION_PAUSE_PREVIEW -> {
                // ИСПРАВЛЕНИЕ: Отвязываем железо камеры, если запись не идет
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

    private fun updateNotification() {
        val chId = "DashcamChannel"
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel(chId, "BVR", NotificationManager.IMPORTANCE_LOW))

        val camLabel = when(prefCameraType) {
            "front" -> getString(R.string.cam_front)
            "back" -> getString(R.string.cam_main)
            else -> getString(R.string.cam_wide)
        }
        val resLabel = if(prefResolution == "4k") "4K UHD" else "${prefResolution}p"

        val statsText = if (isRecordingActive) {
            getString(R.string.notif_stats_format, camLabel, resLabel, prefFps, isoEma.toInt(), nextTargetBitrate / 1_000_000)
        } else {
            getString(R.string.notif_body_ready)
        }

        val notifTitle = if (isRecordingActive) getString(R.string.notif_title_recording) else getString(R.string.notif_title_standby)

        val prefs = getSharedPreferences("DashcamPrefs", MODE_PRIVATE)
        val usePip = prefs.getBoolean("pref_pip", false)

        val startPI = if (usePip) {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("auto_start_pip", true)
            }
            PendingIntent.getActivity(this, 11, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } else {
            val intent = Intent(this, CameraService::class.java).apply { action = ACTION_START }
            PendingIntent.getService(this, 10, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        val stopPI = PendingIntent.getService(this, 1, Intent(this, CameraService::class.java).apply { action = ACTION_STOP }, PendingIntent.FLAG_IMMUTABLE)
        val lockPI = PendingIntent.getService(this, 3, Intent(this, CameraService::class.java).apply { action = ACTION_LOCK }, PendingIntent.FLAG_IMMUTABLE)
        val exitPI = PendingIntent.getService(this, 4, Intent(this, CameraService::class.java).apply { action = ACTION_EXIT }, PendingIntent.FLAG_IMMUTABLE)

        val smallIconRes = if (isRecordingActive) R.drawable.ic_recording_active else R.drawable.ic_cam_standby

        val colorActive = "#D32F2F".toColorInt()
        val colorStandby = "#388E3C".toColorInt()

        val builder = NotificationCompat.Builder(this, chId)
            .setContentTitle(notifTitle)
            .setContentText(statsText)
            .setSmallIcon(smallIconRes)
            .setOngoing(true)
            .setColorized(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setColor(if (isRecordingActive) colorActive else colorStandby)

        if (isRecordingActive) {
            builder.setUsesChronometer(true)
            builder.setWhen(recordingStartTime)

            builder.addAction(android.R.drawable.ic_media_pause, getString(R.string.btn_stop), stopPI)
            builder.addAction(android.R.drawable.ic_lock_lock, getString(R.string.btn_lock), lockPI)
        } else {
            builder.setUsesChronometer(false)
            builder.addAction(android.R.drawable.ic_media_play, getString(R.string.btn_start), startPI)
        }
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.btn_exit), exitPI)

        startForeground(
            1,
            builder.build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
    }

    private fun startDynamicUpdates() {
        dynamicUpdateJob?.cancel()
        dynamicUpdateJob = lifecycleScope.launch {
            var currEv = 0.0f
            var timer = 0
            while (isRecordingActive) {
                delay(150)
                val darkness = ((isoEma - minHardwareIso) / (maxHardwareIso - minHardwareIso)).coerceIn(0f, 1f)
                val targetEV = 0.0f - (1.5f * darkness)
                currEv = (targetEV * 0.05f) + (currEv * 0.95f)

                val step = cameraInfo?.exposureState?.exposureCompensationStep
                if (step != null && step.numerator != 0) {
                    val idx = (currEv / (step.numerator.toFloat()/step.denominator.toFloat())).roundToInt()
                    cameraControl?.setExposureCompensationIndex(idx)
                }

                val codecMult = if (prefCodec == "hevc" || prefCodec == "auto") 0.7f else 1.0f
                val resMult = if(prefResolution == "4k") 2.5f else 1.0f
                val fpsMult = if(prefFps == 60) 1.5f else 1.0f

                val baseBitrate = (16_000_000f + (12_000_000f * darkness)) * resMult * fpsMult * codecMult
                nextTargetBitrate = if (isThermalDowngradeActive) (baseBitrate * 0.7f).toInt() else baseBitrate.toInt()

                if (++timer >= 15) { updateNotification(); timer = 0 }
            }
        }
    }

    private fun playStartSound() {
        try {
            val mp = MediaPlayer.create(this, R.raw.s25_ultra_notification)
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            val speakers = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            speakers.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { speaker ->
                mp.setPreferredDevice(speaker)
            }
            mp.start()
            mp.setOnCompletionListener { it.release() }
        } catch(_: Exception) {}
    }

    private fun playStopSound() {
        try {
            val mp = MediaPlayer.create(this, R.raw.alpha)
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            val speakers = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            speakers.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { speaker ->
                mp.setPreferredDevice(speaker)
            }
            mp.start()
            mp.setOnCompletionListener { it.release() }
        } catch(_: Exception) {}
    }

    private fun executeLock() {
        val uri = currentVideoUri
        val name = currentVideoName
        if (isRecordingActive && uri != null && name != null) {
            stopHardware()
            val cv = ContentValues().apply { put(MediaStore.Video.Media.DISPLAY_NAME, name.replace("BVR_PRO_", "LOCKED_BVR_PRO_")) }
            contentResolver.update(uri, cv, null, null)
            startHardwareLoop()
        }
    }

    private fun checkStorageSpace(): Boolean {
        try {
            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
            val freeMb = freeBytes / (1024 * 1024)

            if (freeMb < 500) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(applicationContext, "КРИТИЧЕСКИ МАЛО ПАМЯТИ! Запись остановлена.", Toast.LENGTH_LONG).show()
                }
                return false
            } else if (freeMb < 2000) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(applicationContext, "ВНИМАНИЕ: Память телефона почти заполнена!", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (_: Exception) {}
        return true
    }

    private fun manageStorageSpace() {
        try {
            val limit = getSharedPreferences("DashcamPrefs", MODE_PRIVATE).getInt("max_loop_files", 6)

            if (limit > 50) return

            contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Video.Media._ID),
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?",
                arrayOf("%Movies/MyDashcam%", "BVR_PRO_%"), "${MediaStore.Video.Media.DATE_ADDED} ASC")?.use { c ->
                var toDel = c.count - limit + 1
                while (c.moveToNext() && toDel > 0) {
                    contentResolver.delete(ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0)), null, null)
                    toDel--
                }
            }
        } catch (_: Exception) {}
    }

    private fun startHardwareLoop() {
        if (!isRecordingActive) return

        if (!checkStorageSpace()) {
            isRecordingActive = false
            recordingStartTime = 0L
            playStopSound()
            stopHardware()
            updateNotification()
            return
        }

        manageStorageSpace()
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

    @SuppressLint("MissingPermission")
    private fun startHardware(isRecording: Boolean) {
        try {
            cameraProvider?.unbindAll()

            val w = if(prefResolution == "4k") 3840 else if(prefResolution == "720") 1280 else 1920
            val h = if(prefResolution == "4k") 2160 else if(prefResolution == "720") 720 else 1080

            if (isRecording) {
                try {
                    currentVideoName = "BVR_PRO_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())}.mp4"
                    currentVideoUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, currentVideoName)
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MyDashcam")
                    })

                    if (currentVideoUri == null) throw Exception("Media URI is null")

                    pfd = contentResolver.openFileDescriptor(currentVideoUri!!, "rw") ?: throw Exception("PFD is null")

                    mediaRecorder = MediaRecorder(this).apply {
                        setAudioSource(MediaRecorder.AudioSource.MIC)
                        setVideoSource(MediaRecorder.VideoSource.SURFACE)
                        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

                        if (prefCodec == "hevc" || prefCodec == "auto") {
                            setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
                        } else {
                            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                        }

                        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        setVideoEncodingBitRate(nextTargetBitrate)
                        setVideoFrameRate(prefFps)
                        setVideoSize(w, h)
                        setAudioChannels(1)
                        setAudioSamplingRate(48000)
                        setAudioEncodingBitRate(128000)
                        setOutputFile(pfd!!.fileDescriptor)

                        val mics = (getSystemService(AUDIO_SERVICE) as AudioManager).getDevices(AudioManager.GET_DEVICES_INPUTS)
                        mics.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }?.let { setPreferredDevice(it) }

                        try {
                            prepare()
                        } catch(_: Exception) {
                            try {
                                reset()
                                setAudioSource(MediaRecorder.AudioSource.MIC)
                                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                                setVideoEncodingBitRate(nextTargetBitrate)
                                setVideoFrameRate(prefFps)
                                setVideoSize(w, h)
                                setAudioChannels(1)
                                setAudioSamplingRate(48000)
                                setAudioEncodingBitRate(128000)
                                setOutputFile(pfd!!.fileDescriptor)
                                val micsFallback = (getSystemService(AUDIO_SERVICE) as AudioManager).getDevices(AudioManager.GET_DEVICES_INPUTS)
                                micsFallback.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }?.let { setPreferredDevice(it) }
                                prepare()
                            } catch (_: Exception) {
                                Handler(Looper.getMainLooper()).post {
                                    Toast.makeText(
                                        applicationContext,
                                        applicationContext.getString(R.string.toast_camera_unsupported, prefResolution, prefFps),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                                isRecordingActive = false
                                recordingStartTime = 0L
                                stopHardware()
                                updateNotification()
                                return
                            }
                        }
                    }
                } catch (e: Exception) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(applicationContext, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                    isRecordingActive = false
                    recordingStartTime = 0L
                    stopHardware()
                    updateNotification()
                    return
                }
            }

            ProcessCameraProvider.getInstance(this).addListener({
                val provider = ProcessCameraProvider.getInstance(this).get()
                cameraProvider = provider
                val all = provider.availableCameraInfos
                val target = when(prefCameraType) {
                    "front" -> all.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
                    "back" -> all.filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }.maxByOrNull { Camera2CameraInfo.from(it).getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: 0f }
                    else -> all.filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }.minByOrNull { Camera2CameraInfo.from(it).getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: Float.MAX_VALUE }
                } ?: all.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_BACK }

                val sensorOri = Camera2CameraInfo.from(target!!).getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                val rot = (sensorOri + (if (target.lensFacing == CameraSelector.LENS_FACING_FRONT) -deviceOrientationAngle else deviceOrientationAngle) + 360) % 360

                var recordPreview: Preview? = null
                if (isRecording && mediaRecorder != null) {
                    mediaRecorder?.setOrientationHint(rot)
                    val selector = ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(ResolutionStrategy(Size(w, h), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build()
                    val recordPreviewBuilder = Preview.Builder().setResolutionSelector(selector)
                    val extender = Camera2Interop.Extender(recordPreviewBuilder)

                    extender.setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { iso ->
                                isoEma = (iso * 0.15f) + (isoEma * 0.85f)
                            }
                        }
                    })

                    val ranges = Camera2CameraInfo.from(target).getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    val bestRange = ranges?.firstOrNull { it.upper == prefFps && it.lower == prefFps } ?: ranges?.firstOrNull { it.upper == prefFps } ?: Range(30, 30)

                    extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, bestRange)
                    extender.setCaptureRequestOption(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
                    extender.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)

                    recordPreview = recordPreviewBuilder.build()
                    recordPreview.setSurfaceProvider(ContextCompat.getMainExecutor(this)) { request ->
                        request.provideSurface(mediaRecorder!!.surface, ContextCompat.getMainExecutor(this)) {}
                    }
                }

                val analysisSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build()
                val imageAnalysis = ImageAnalysis.Builder()
                    .setResolutionSelector(analysisSelector)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
                    if (previewCallback != null) {
                        if (reusableBitmap == null || reusableBitmap!!.width != imageProxy.width || reusableBitmap!!.height != imageProxy.height) {
                            reusableBitmap = createBitmap(imageProxy.width, imageProxy.height)
                        }
                        imageProxy.planes[0].buffer.rewind()
                        reusableBitmap!!.copyPixelsFromBuffer(imageProxy.planes[0].buffer)
                        previewCallback?.invoke(reusableBitmap!!, imageProxy.imageInfo.rotationDegrees)
                    }
                    imageProxy.close()
                }

                try {
                    provider.unbindAll()

                    val useCases = mutableListOf<UseCase>(imageAnalysis)
                    if (isRecording && recordPreview != null) {
                        useCases.add(recordPreview)
                    }

                    val cam = provider.bindToLifecycle(this, CameraSelector.Builder().addCameraFilter { _ -> listOf(target) }.build(), *useCases.toTypedArray())
                    cameraControl = cam.cameraControl
                    cameraInfo = cam.cameraInfo

                    if (isRecording) {
                        mediaRecorder?.start()
                        playStartSound()
                    }
                } catch (_: Exception) {}
            }, ContextCompat.getMainExecutor(this))
        } catch (_: Exception) {}
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
            try { cameraProvider?.unbindAll() } catch(_: Exception) {}
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // ИСПРАВЛЕНИЕ: Если смахиваем приложение и запись не идет — отвязываем камеру, чтобы убрать зеленую точку!
        if (!isRecordingActive) {
            try { cameraProvider?.unbindAll() } catch(_: Exception) {}
        }
    }

    override fun onDestroy() {
        orientationEventListener?.disable()
        thermalListener?.let { powerManager?.removeThermalStatusListener(it) }
        isRecordingActive = false
        stopHardware()
        super.onDestroy()
    }
}