package com.example.mydashcam

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.hardware.camera2.*
import android.media.*
import android.net.Uri
import android.os.*
import android.os.Process
import android.provider.MediaStore
import android.util.*
import android.view.OrientationEventListener
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.*
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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

    private var isoEma = 100f
    private var minHardwareIso = 100f
    private var maxHardwareIso = 1600f
    private var nextTargetBitrate = 18_000_000

    private var prefCameraType = "auto"
    private var prefResolution = "1080"
    private var prefFps = 30

    companion object {
        var isRecordingActive = false
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_EXIT = "ACTION_EXIT"
        const val ACTION_LOCK = "ACTION_LOCK"
        const val ACTION_STANDBY = "ACTION_STANDBY"
        const val RECORDING_DURATION_MS = 600000L // 10 минут
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
    }

    private fun setupThermalListener() {
        thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
            isThermalDowngradeActive = status >= PowerManager.THERMAL_STATUS_SEVERE
            if (status >= PowerManager.THERMAL_STATUS_CRITICAL && isRecordingActive) {
                stopHardware()
                isRecordingActive = false
                updateNotification()
            }
        }
        powerManager?.addThermalStatusListener(thermalListener!!)
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
        when (intent?.action) {
            ACTION_START -> if (!isRecordingActive) {
                isRecordingActive = true
                isoEma = minHardwareIso
                val prefs = getSharedPreferences("DashcamPrefs", MODE_PRIVATE)
                prefCameraType = prefs.getString("pref_camera", "auto") ?: "auto"
                prefResolution = prefs.getString("pref_resolution", "1080") ?: "1080"
                prefFps = prefs.getInt("pref_fps", 30)
                startHardwareLoop()
            }
            ACTION_STOP -> if (isRecordingActive) {
                isRecordingActive = false
                playStopSound()
                stopHardware()
                updateNotification()
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
                // Только обновляем шторку
            }
        }
        updateNotification()
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

        val startPI = PendingIntent.getService(this, 10, Intent(this, CameraService::class.java).apply { action = ACTION_START }, PendingIntent.FLAG_IMMUTABLE)
        val stopPI = PendingIntent.getService(this, 1, Intent(this, CameraService::class.java).apply { action = ACTION_STOP }, PendingIntent.FLAG_IMMUTABLE)
        val lockPI = PendingIntent.getService(this, 3, Intent(this, CameraService::class.java).apply { action = ACTION_LOCK }, PendingIntent.FLAG_IMMUTABLE)
        val exitPI = PendingIntent.getService(this, 4, Intent(this, CameraService::class.java).apply { action = ACTION_EXIT }, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, chId)
            .setContentTitle(notifTitle)
            .setContentText(statsText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setColorized(true)
            .setColor(if (isRecordingActive) 0xFFD32F2F.toInt() else 0xFF388E3C.toInt())

        if (isRecordingActive) {
            builder.addAction(android.R.drawable.ic_media_pause, getString(R.string.btn_stop), stopPI)
            builder.addAction(android.R.drawable.ic_lock_lock, getString(R.string.btn_lock), lockPI)
        } else {
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

                val resMult = if(prefResolution == "4k") 2.5f else 1.0f
                val fpsMult = if(prefFps == 60) 1.5f else 1.0f
                val baseBitrate = (16_000_000f + (12_000_000f * darkness)) * resMult * fpsMult
                nextTargetBitrate = if (isThermalDowngradeActive) (baseBitrate * 0.7f).toInt() else baseBitrate.toInt()

                if (++timer >= 15) { updateNotification(); timer = 0 }
            }
        }
    }

    // Воспроизведение звука принудительно через встроенный динамик телефона
    private fun playStartSound() {
        try {
            val mp = MediaPlayer.create(this, R.raw.s25_ultra_notification)
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            val speakers = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            speakers.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { speaker ->
                mp.setPreferredDevice(speaker) // Игнорируем Bluetooth для вывода звука
            }
            mp.start()
            mp.setOnCompletionListener { it.release() }
        } catch(_:Exception){}
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
        } catch(_:Exception){}
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

    private fun manageStorageSpace() {
        val limit = getSharedPreferences("DashcamPrefs", MODE_PRIVATE).getInt("max_loop_files", 6)
        contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Video.Media._ID),
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("%Movies/MyDashcam%", "BVR_PRO_%"), "${MediaStore.Video.Media.DATE_ADDED} ASC")?.use { c ->
            var toDel = c.count - limit + 1
            while (c.moveToNext() && toDel > 0) {
                contentResolver.delete(ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0)), null, null)
                toDel--
            }
        }
    }

    private fun startHardwareLoop() {
        if (!isRecordingActive) return
        manageStorageSpace()
        startHardware()
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
    private fun startHardware() {
        try {
            currentVideoName = "BVR_PRO_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())}.mp4"
            currentVideoUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, currentVideoName)
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MyDashcam")
            })
            pfd = currentVideoUri?.let { contentResolver.openFileDescriptor(it, "rw") } ?: return

            val w = if(prefResolution == "4k") 3840 else if(prefResolution == "720") 1280 else 1920
            val h = if(prefResolution == "4k") 2160 else if(prefResolution == "720") 720 else 1080

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

                // Ищем встроенный микрофон телефона, чтобы переопределить настройки
                val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
                val mics = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                val builtInMic = mics.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

                mediaRecorder = MediaRecorder(this).apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

                    setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)

                    setVideoEncodingBitRate(nextTargetBitrate)
                    setVideoFrameRate(prefFps)
                    setVideoSize(w, h)

                    // Жесткая настройка студийного аудио
                    setAudioChannels(1)
                    setAudioSamplingRate(48000)
                    setAudioEncodingBitRate(128000)

                    setOutputFile(pfd!!.fileDescriptor)
                    setOrientationHint(rot)

                    // Переопределяем запись строго на микрофон телефона
                    builtInMic?.let { setPreferredDevice(it) }

                    try { prepare() } catch(_: Exception) {
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
                        setOrientationHint(rot)

                        builtInMic?.let { setPreferredDevice(it) }
                        prepare()
                    }
                }

                val selector = ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(w, h), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build()

                val previewBuilder = Preview.Builder().setResolutionSelector(selector)
                val extender = Camera2Interop.Extender(previewBuilder)

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

                val preview = previewBuilder.build()
                preview.setSurfaceProvider(ContextCompat.getMainExecutor(this)) { request ->
                    request.provideSurface(mediaRecorder!!.surface, ContextCompat.getMainExecutor(this)) {}
                }

                try {
                    provider.unbindAll()
                    val cam = provider.bindToLifecycle(this, CameraSelector.Builder().addCameraFilter { _ -> listOf(target) }.build(), preview)
                    cameraControl = cam.cameraControl
                    cameraInfo = cam.cameraInfo
                    Camera2CameraInfo.from(cameraInfo!!).getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let {
                        minHardwareIso = it.lower.toFloat()
                        maxHardwareIso = minOf(it.upper.toFloat(), 3200f)
                    }
                    mediaRecorder?.start()
                    playStartSound()
                } catch (_: Exception) {}
            }, ContextCompat.getMainExecutor(this))
        } catch (_: Exception) {}
    }

    private fun stopHardware() {
        loopJob?.cancel()
        dynamicUpdateJob?.cancel()
        cameraControl = null
        cameraInfo = null
        try { cameraProvider?.unbindAll() } catch(_:Exception){}
        try { mediaRecorder?.stop() } catch(_:Exception) { currentVideoUri?.let { contentResolver.delete(it, null, null) } }
        finally {
            try { mediaRecorder?.release() } catch(_:Exception){}
            mediaRecorder = null
            try { pfd?.close() } catch(_:Exception){}
            pfd = null
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