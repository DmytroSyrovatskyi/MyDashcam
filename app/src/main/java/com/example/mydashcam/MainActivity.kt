package com.example.mydashcam

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.LocaleManager
import android.app.PictureInPictureParams
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.media.MediaRecorder
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.text.SpannableString
import android.text.style.StyleSpan
import android.util.Rational
import android.util.Size
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class VideoItem(
    val id: Long,
    val name: String,
    val size: Long,
    val duration: Long,
    val dateAdded: Long,
    val uri: Uri,
    val isLocked: Boolean
)

enum class TabState {
    TIMELINE, PROTECTED, PREVIEW
}

class MainActivity : AppCompatActivity() {

    private lateinit var btnToggleRecord: CardView
    private lateinit var iconToggleRecord: ImageView
    private lateinit var btnLockFile: CardView
    private lateinit var btnSettings: ImageButton
    private lateinit var statusText: TextView
    private lateinit var recyclerView: RecyclerView

    private lateinit var btnTabTemp: TextView
    private lateinit var btnTabSaved: TextView
    private lateinit var btnTabPreview: TextView

    private lateinit var controlCard: CardView
    private lateinit var tabLayout: LinearLayout
    private lateinit var previewImageView: TextureView

    private var cameraServiceBinder: CameraService.LocalBinder? = null
    private var isFirstRunDialogOpen = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            cameraServiceBinder = service as CameraService.LocalBinder

            cameraServiceBinder?.setPreviewListener { bitmap, rotationDegrees ->
                runOnUiThread {
                    if (previewImageView.isAvailable) {

                        updatePreviewRatio(rotationDegrees)

                        val viewWidth = previewImageView.width.toFloat()
                        val viewHeight = previewImageView.height.toFloat()

                        if (viewWidth > 0 && viewHeight > 0) {
                            val canvas = previewImageView.lockCanvas()
                            if (canvas != null) {
                                canvas.drawColor(android.graphics.Color.BLACK)

                                val matrix = Matrix()
                                val bw = bitmap.width.toFloat()
                                val bh = bitmap.height.toFloat()

                                matrix.postTranslate(-bw / 2f, -bh / 2f)
                                matrix.postRotate(rotationDegrees.toFloat())

                                val isPortrait = rotationDegrees % 180 != 0
                                val rotatedW = if (isPortrait) bh else bw
                                val rotatedH = if (isPortrait) bw else bh

                                val scale = maxOf(viewWidth / rotatedW, viewHeight / rotatedH)
                                matrix.postScale(scale, scale)

                                if (cameraServiceBinder?.isFrontCamera() == true) {
                                    matrix.postScale(-1f, 1f)
                                }

                                matrix.postTranslate(viewWidth / 2f, viewHeight / 2f)

                                val paint = Paint().apply { isFilterBitmap = true }
                                canvas.drawBitmap(bitmap, matrix, paint)

                                previewImageView.unlockCanvasAndPost(canvas)
                            }
                        }
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cameraServiceBinder?.setPreviewListener(null)
            cameraServiceBinder = null
        }
    }

    private var lastKnownRotation = -1

    private fun updatePreviewRatio(rotation: Int) {
        if (lastKnownRotation == rotation) return
        lastKnownRotation = rotation

        val params = previewImageView.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        val isPortrait = rotation % 180 != 0
        params.dimensionRatio = if (isPortrait) "9:16" else "16:9"
        previewImageView.layoutParams = params
    }

    private var currentTab = TabState.PREVIEW
    private val videoList = mutableListOf<VideoItem>()
    private lateinit var adapter: VideoAdapter

    private val mediaStoreObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            if (currentTab != TabState.PREVIEW) loadVideos()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("DashcamPrefs", MODE_PRIVATE)
        val lang = prefs.getString("app_lang", "en") ?: "en"
        setAppLocale(lang)

        setContentView(R.layout.activity_main)

        controlCard = findViewById(R.id.controlCard)
        tabLayout = findViewById(R.id.tabLayout)
        previewImageView = findViewById(R.id.previewImageView)

        btnToggleRecord = findViewById(R.id.btnToggleRecord)
        iconToggleRecord = findViewById(R.id.iconToggleRecord)
        btnLockFile = findViewById(R.id.btnLockFile)
        btnSettings = findViewById(R.id.btnSettings)
        statusText = findViewById(R.id.statusText)
        recyclerView = findViewById(R.id.recyclerViewVideos)

        btnTabTemp = findViewById(R.id.btnTabTemp)
        btnTabSaved = findViewById(R.id.btnTabSaved)
        btnTabPreview = findViewById(R.id.btnTabPreview)

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = VideoAdapter()
        recyclerView.adapter = adapter

        btnTabTemp.setOnClickListener { switchTab(TabState.TIMELINE) }
        btnTabSaved.setOnClickListener { switchTab(TabState.PROTECTED) }
        btnTabPreview.setOnClickListener { switchTab(TabState.PREVIEW) }

        btnToggleRecord.setOnClickListener {
            if (!hasPermissions()) {
                Toast.makeText(this, getString(R.string.toast_perms_needed), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            try {
                val intent = Intent(this, CameraService::class.java).apply {
                    action = if (CameraService.isRecordingActive) CameraService.ACTION_STOP else CameraService.ACTION_START
                }
                ContextCompat.startForegroundService(this, intent)
                Handler(Looper.getMainLooper()).postDelayed({ updateUiState() }, 400)
            } catch (e: Exception) {
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        btnLockFile.setOnClickListener {
            if (CameraService.isRecordingActive) {
                try {
                    ContextCompat.startForegroundService(this, Intent(this, CameraService::class.java).apply { action = CameraService.ACTION_LOCK })
                    Toast.makeText(this, getString(R.string.msg_locked), Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
            }
        }

        btnSettings.setOnClickListener {
            if (CameraService.isRecordingActive) {
                val msg = if (Locale.getDefault().language == "ru") "Остановите запись для настройки" else "Stop recording to change settings"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            } else {
                openSmartSettingsDialog()
            }
        }

        requestAppPermissions()
        handleAutoStartPip(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAutoStartPip(intent)
    }

    // ИСПРАВЛЕНИЕ: Вычисляет правильную пропорцию для PiP-окна
    private fun getPipRatio(): Rational {
        val w = previewImageView.width
        val h = previewImageView.height
        if (w > 0 && h > 0) {
            return Rational(w, h)
        }
        return Rational(16, 9)
    }

    private fun handleAutoStartPip(intent: Intent?) {
        if (intent?.getBooleanExtra("auto_start_pip", false) == true) {
            intent.removeExtra("auto_start_pip")
            if (!CameraService.isRecordingActive && hasPermissions()) {
                val serviceIntent = Intent(this, CameraService::class.java).apply { action = CameraService.ACTION_START }
                ContextCompat.startForegroundService(this, serviceIntent)
            }
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val params = PictureInPictureParams.Builder().setAspectRatio(getPipRatio()).build()
                    enterPictureInPictureMode(params)
                } catch (_: Exception) {}
            }, 600)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val prefs = getSharedPreferences("DashcamPrefs", MODE_PRIVATE)
        if (CameraService.isRecordingActive && prefs.getBoolean("pref_pip", false)) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(getPipRatio())
                .build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)

        if (isInPictureInPictureMode) {
            controlCard.visibility = View.GONE
            tabLayout.visibility = View.GONE
            recyclerView.visibility = View.GONE
            previewImageView.visibility = View.VISIBLE
        } else {
            controlCard.visibility = View.VISIBLE
            tabLayout.visibility = View.VISIBLE
            switchTab(currentTab)
            updateUiState()
        }
    }

    private fun requestAppPermissions() {
        val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_MEDIA_VIDEO, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
        if (!hasPermissions()) {
            requestPermissions(permissions, 101)
        }
    }

    private fun hasPermissions(): Boolean {
        val hasCam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        return hasCam && hasMic
    }

    // ИСПРАВЛЕНИЕ: Гарантированный запуск диалога только когда UI готов
    private fun checkFirstRunDialog() {
        val prefs = getSharedPreferences("DashcamPrefs", MODE_PRIVATE)
        if (!prefs.getBoolean("f_run", false) && !isFirstRunDialogOpen) {
            isFirstRunDialogOpen = true
            window.decorView.post {
                if (!isFinishing && !isDestroyed) {
                    openSmartSettingsDialog()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasPermissions()) {
            checkFirstRunDialog()
            try {
                contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaStoreObserver)
            } catch (_: Exception) {}
        }
    }

    override fun onStart() {
        super.onStart()
        if (hasPermissions()) {
            try {
                val intent = Intent(this, CameraService::class.java).apply { action = CameraService.ACTION_STANDBY }
                ContextCompat.startForegroundService(this, intent)
                bindService(Intent(this, CameraService::class.java), serviceConnection, BIND_AUTO_CREATE)
            } catch (_: Exception) {}
        }
    }

    override fun onResume() {
        super.onResume()
        CameraService.isAppVisible = true

        if (hasPermissions()) {
            // Задержка на первый запуск, чтобы не блочило UI
            Handler(Looper.getMainLooper()).postDelayed({
                checkFirstRunDialog()
            }, 500)

            try {
                contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaStoreObserver)
            } catch (_: Exception) {}
        }

        switchTab(currentTab)
        updateUiState()

        if (hasPermissions() && cameraServiceBinder == null) {
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val intent = Intent(this, CameraService::class.java).apply { action = CameraService.ACTION_STANDBY }
                    ContextCompat.startForegroundService(this, intent)
                    bindService(Intent(this, CameraService::class.java), serviceConnection, BIND_AUTO_CREATE)
                } catch (_: Exception) {}
            }, 300)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            contentResolver.unregisterContentObserver(mediaStoreObserver)
        } catch (_: Exception) {}
    }

    override fun onStop() {
        super.onStop()

        if (isInPictureInPictureMode) return

        CameraService.isAppVisible = false
        cameraServiceBinder?.setPreviewListener(null)
        try { unbindService(serviceConnection) } catch (_: Exception) {}
        cameraServiceBinder = null

        if (hasPermissions() && !CameraService.isRecordingActive) {
            try {
                val intent = Intent(this, CameraService::class.java).apply {
                    action = CameraService.ACTION_PAUSE_PREVIEW
                }
                startService(intent)
            } catch (_: Exception) {}
        }
    }

    private fun setAppLocale(langCode: String) {
        try {
            val lm = getSystemService(LocaleManager::class.java)
            val currentLang = if (lm?.applicationLocales?.isEmpty == false) lm.applicationLocales[0].language else ""
            if (currentLang != langCode) {
                lm?.applicationLocales = LocaleList(Locale.forLanguageTag(langCode))
            }
        } catch (_: Exception) {}
    }

    private fun switchTab(tab: TabState) {
        currentTab = tab
        val active = ContextCompat.getColor(this, R.color.text_active)
        val inactive = ContextCompat.getColor(this, R.color.text_inactive)

        btnTabTemp.isSelected = (tab == TabState.TIMELINE)
        btnTabSaved.isSelected = (tab == TabState.PROTECTED)
        btnTabPreview.isSelected = (tab == TabState.PREVIEW)

        btnTabTemp.setTextColor(if (tab == TabState.TIMELINE) active else inactive)
        btnTabSaved.setTextColor(if (tab == TabState.PROTECTED) active else inactive)
        btnTabPreview.setTextColor(if (tab == TabState.PREVIEW) active else inactive)

        if (tab == TabState.PREVIEW) {
            recyclerView.visibility = View.GONE
            previewImageView.visibility = View.VISIBLE
        } else {
            previewImageView.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            if (hasPermissions()) loadVideos()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateUiState() {
        val prefs = getSharedPreferences("DashcamPrefs", MODE_PRIVATE)

        val cam = when(prefs.getString("pref_camera", "auto")) {
            "front" -> getString(R.string.cam_front)
            "back" -> getString(R.string.cam_main)
            else -> getString(R.string.cam_wide)
        }
        val res = prefs.getString("pref_resolution", "1080").let { if(it=="4k") "4K UHD" else "${it}p HD" }
        val fps = prefs.getInt("pref_fps", 30)

        val statusLine = if (CameraService.isRecordingActive) getString(R.string.status_recording) else getString(R.string.status_waiting)
        val configLine = "$cam  |  $res  |  $fps FPS"

        val fullText = SpannableString("$statusLine\n$configLine")
        fullText.setSpan(StyleSpan(Typeface.BOLD), 0, statusLine.length, 0)
        statusText.text = fullText

        if (CameraService.isRecordingActive) {
            btnToggleRecord.setCardBackgroundColor(ContextCompat.getColor(this, R.color.accent_record_on))
            iconToggleRecord.setImageResource(android.R.drawable.ic_media_pause)
            statusText.setTextColor(ContextCompat.getColor(this, R.color.accent_record_on))
        } else {
            btnToggleRecord.setCardBackgroundColor(ContextCompat.getColor(this, R.color.accent_record_off))
            iconToggleRecord.setImageResource(android.R.drawable.ic_media_play)
            statusText.setTextColor(ContextCompat.getColor(this, R.color.text_inactive))
        }
    }

    @SuppressLint("SetTextI18n", "UnsafeOptInUsageError")
    private fun openSmartSettingsDialog() {
        isFirstRunDialogOpen = true
        val prefs = getSharedPreferences("DashcamPrefs", MODE_PRIVATE)
        var cCam = prefs.getString("pref_camera", "auto") ?: "auto"
        var cFiles = prefs.getInt("max_loop_files", 6)
        val cLang = prefs.getString("app_lang", "en") ?: "en"
        var cCodec = prefs.getString("pref_codec", "auto") ?: "auto"

        val isFirstRun = !prefs.getBoolean("f_run", false)

        ProcessCameraProvider.getInstance(this).addListener({
            val provider = ProcessCameraProvider.getInstance(this).get()
            val all = provider.availableCameraInfos

            val fCam = all.firstOrNull { info: CameraInfo -> info.lensFacing == CameraSelector.LENS_FACING_FRONT }
            val bStd = all.filter { info: CameraInfo -> info.lensFacing == CameraSelector.LENS_FACING_BACK }.maxByOrNull { info: CameraInfo -> Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: 0f }
            val bWide = all.filter { info: CameraInfo -> info.lensFacing == CameraSelector.LENS_FACING_BACK }.minByOrNull { info: CameraInfo -> Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: Float.MAX_VALUE }

            val scroll = ScrollView(this)
            val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(64, 48, 64, 48) }

            fun addHeader(t: String) {
                val tv = TextView(this).apply {
                    text = t.uppercase(); textSize = 11f; setTypeface(null, Typeface.BOLD); setTextColor("#66BB6A".toColorInt()); setPadding(0, 48, 0, 16)
                }
                layout.addView(tv)
            }

            addHeader(if(cLang == "ru") "Язык" else "Language")
            val rgL = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
            val rbRu = RadioButton(this).apply { text = "RU"; setPadding(0,0,40,0) }
            val rbEn = RadioButton(this).apply { text = "EN" }
            rgL.addView(rbRu); rgL.addView(rbEn); if(cLang == "ru") rbRu.isChecked = true else rbEn.isChecked = true
            layout.addView(rgL)

            addHeader(getString(R.string.settings_pip))
            val swPip = SwitchCompat(this).apply { text = getString(R.string.settings_pip_desc); isChecked = prefs.getBoolean("pref_pip", false) }
            layout.addView(swPip)

            addHeader(if(cLang == "ru") "Объектив" else "Camera Lens")
            val rgC = RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
            val rbA = RadioButton(this).apply { text = if(cLang == "ru") "Ультра-широкий (Авто)" else "Ultra-Wide (Auto)" }
            val rbB = RadioButton(this).apply { text = if(cLang == "ru") "Основной сенсор" else "Main Sensor" }
            val rbF = RadioButton(this).apply { text = if(cLang == "ru") "Салон (Фронт)" else "Cabin Front" }
            if (fCam == null) rbF.isEnabled = false
            rgC.addView(rbA); rgC.addView(rbB); rgC.addView(rbF)
            when(cCam) { "front" -> rbF.isChecked = true; "back" -> rbB.isChecked = true; else -> rbA.isChecked = true }
            layout.addView(rgC)

            addHeader(if(cLang == "ru") "Разрешение" else "Resolution")
            val rb4 = RadioButton(this).apply { text = "4K UHD" }
            val rb1 = RadioButton(this).apply { text = "1080p Full HD" }
            val rb7 = RadioButton(this).apply { text = "720p HD" }
            val rgR = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
            rgR.addView(rb4); rgR.addView(rb1); rgR.addView(rb7)
            val sRes = prefs.getString("pref_resolution", "1080")
            if(sRes == "4k") rb4.isChecked = true else if(sRes == "720") rb7.isChecked = true else rb1.isChecked = true
            layout.addView(rgR)

            addHeader(if(cLang == "ru") "Частота кадров" else "Framerate")
            val rgF = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
            val rb6 = RadioButton(this).apply { text = "60 FPS"; setPadding(0,0,40,0) }
            val rb3 = RadioButton(this).apply { text = "30 FPS" }
            rgF.addView(rb6); rgF.addView(rb3)
            if(prefs.getInt("pref_fps", 30) == 60) rb6.isChecked = true else rb3.isChecked = true
            layout.addView(rgF)

            addHeader(if(cLang == "ru") "Формат видео (Кодек)" else "Video Codec")
            val rgCodec = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
            val rbCodecAuto = RadioButton(this).apply { text = if(cLang == "ru") "АВТО (HEVC -> H.264)" else "AUTO (HEVC -> H.264)" }
            val rbCodecHevc = RadioButton(this).apply { text = if(cLang == "ru") "HEVC / H.265 (Экономичный)" else "HEVC / H.265 (High Efficiency)" }
            val rbCodecAvc = RadioButton(this).apply { text = if(cLang == "ru") "AVC / H.264 (Совместимый)" else "AVC / H.264 (Compatible)" }
            rgCodec.addView(rbCodecAuto); rgCodec.addView(rbCodecHevc); rgCodec.addView(rbCodecAvc)
            when(cCodec) { "hevc" -> rbCodecHevc.isChecked = true; "avc" -> rbCodecAvc.isChecked = true; else -> rbCodecAuto.isChecked = true }
            layout.addView(rgCodec)

            addHeader(if(cLang == "ru") "Лимит памяти" else "Storage Limit")
            val loopL = TextView(this).apply { textSize = 15f; setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_active)) }
            val cycleI = TextView(this).apply { text = "⏱ 1 cycle = 10 minutes"; textSize = 12f; setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_inactive)); setPadding(0,4,0,16) }
            val seek = SeekBar(this).apply { max = 18; progress = if(cFiles > 50) 18 else cFiles - 3 }
            val storL = TextView(this).apply { textSize = 14f; setTypeface(null, Typeface.BOLD); setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_active)); setPadding(0,16,0,0) }

            fun refresh() {
                val t = when(cCam) { "front" -> fCam; "back" -> bStd; else -> bWide ?: bStd }
                var s4=false; var s1=false; var s6=false
                t?.let { c ->
                    val ch = Camera2CameraInfo.from(c)
                    val m = ch.getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val sz = m?.getOutputSizes(MediaRecorder::class.java)
                    s1 = sz?.any { size -> size.width == 1920 } == true
                    s4 = sz?.any { size -> size.width >= 3840 } == true
                    s6 = ch.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.any { range -> range.upper >= 60 } == true
                }
                rb4.isEnabled = s4; rb1.isEnabled = s1; rb6.isEnabled = s6
                if(!s4 && rb4.isChecked) rb1.isChecked = true
                if(!s6 && rb6.isChecked) rb3.isChecked = true

                val mult = (if(rb4.isChecked) 2.8 else if(rb7.isChecked) 0.6 else 1.0) * (if(rb6.isChecked) 1.6 else 1.0)
                val codecMult = if(rbCodecHevc.isChecked) 0.7 else 1.0

                if (cFiles > 50) {
                    storL.text = getString(R.string.infinite_storage)
                    loopL.text = if(rbRu.isChecked) "Перезапись: ВЫКЛ" else "Loop Record: OFF"
                } else {
                    storL.text = String.format(Locale.US, if(rbRu.isChecked) "Объем цикла: %.1f ГБ" else "Estimated loop: %.1f GB", cFiles * 1.5 * mult * codecMult)
                    loopL.text = if(rbRu.isChecked) "Файлов в цикле: $cFiles" else "Files in loop: $cFiles"
                }
            }

            refresh()
            rgC.setOnCheckedChangeListener { _, id -> cCam = when(id){rbF.id->"front";rbB.id->"back";else->"auto"}; refresh() }
            rgR.setOnCheckedChangeListener { _, _ -> refresh() }
            rgF.setOnCheckedChangeListener { _, _ -> refresh() }
            rgCodec.setOnCheckedChangeListener { _, id -> cCodec = when(id){rbCodecHevc.id->"hevc";rbCodecAvc.id->"avc";else->"auto"}; refresh() }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    cFiles = if (p == 18) 999 else p + 3
                    refresh()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })

            layout.addView(loopL); layout.addView(cycleI); layout.addView(seek); layout.addView(storL)
            scroll.addView(layout)

            val dialogBuilder = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(getString(R.string.settings_title))
                .setView(scroll)
                .setCancelable(!isFirstRun)
                .setOnDismissListener { isFirstRunDialogOpen = false }
                .setPositiveButton(getString(R.string.btn_save_config)) { _, _ ->
                    val sR = if(rb4.isChecked)"4k" else if(rb7.isChecked)"720" else "1080"
                    val sL = if(rbRu.isChecked) "ru" else "en"

                    prefs.edit {
                        putBoolean("f_run", true)
                        putString("app_lang", sL)
                        putBoolean("pref_pip", swPip.isChecked)
                        putString("pref_camera", cCam)
                        putString("pref_resolution", sR)
                        putInt("pref_fps", if(rb6.isChecked)60 else 30)
                        putInt("max_loop_files", cFiles)
                        putString("pref_codec", cCodec)
                    }

                    if (sL != cLang) {
                        setAppLocale(sL)
                    } else {
                        updateUiState()
                        if (hasPermissions() && !CameraService.isRecordingActive) {
                            try {
                                val intent = Intent(this@MainActivity, CameraService::class.java).apply { action = CameraService.ACTION_STANDBY }
                                ContextCompat.startForegroundService(this@MainActivity, intent)
                            } catch (_: Exception) {}
                        }
                    }
                }

            if (!isFirstRun) {
                dialogBuilder.setNegativeButton(getString(R.string.btn_cancel), null)
            }

            dialogBuilder.show()
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun loadVideos() {
        videoList.clear()
        try {
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.DATE_ADDED),
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("%Movies/MyDashcam%"),
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1) ?: ""
                    val isL = name.startsWith("LOCKED_")
                    val item = VideoItem(cursor.getLong(0), name, cursor.getLong(2), cursor.getLong(3), cursor.getLong(4), ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)), isL)

                    if (currentTab == TabState.PROTECTED && isL) videoList.add(item)
                    else if (currentTab == TabState.TIMELINE && name.startsWith("BVR_PRO_")) videoList.add(item)
                }
            }
            adapter.notifyDataSetChanged()
        } catch (_: Exception) {}
    }

    inner class VideoAdapter : RecyclerView.Adapter<VideoAdapter.VideoViewHolder>() {
        inner class VideoViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView? = v.findViewById(R.id.imgThumbnail)
            val tD: TextView = v.findViewById(R.id.textVideoDate)
            val tI: TextView = v.findViewById(R.id.textVideoInfo)
            val bL: ImageButton = v.findViewById(R.id.btnLockUnlock)
            val bD: ImageButton = v.findViewById(R.id.btnDelete)
        }
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VideoViewHolder(LayoutInflater.from(p.context).inflate(R.layout.item_video, p, false))

        override fun onBindViewHolder(h: VideoViewHolder, p: Int) {
            val v = videoList[p]
            val context = h.itemView.context

            val sDate = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(v.dateAdded * 1000L))
            val eDate = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date((v.dateAdded * 1000L) + v.duration))
            h.tD.text = context.getString(R.string.video_time_range, sDate, eDate)

            val sz = if (v.size > 1073741824) String.format(Locale.US, "%.2f GB", v.size / 1073741824.0) else "${v.size / 1048576} MB"
            val durationMin = (v.duration / 60000).toInt()

            h.tI.text = context.getString(R.string.video_info_format, durationMin, sz)

            h.bL.setImageResource(if (v.isLocked) android.R.drawable.ic_menu_revert else android.R.drawable.ic_menu_save)
            h.bL.setOnClickListener {
                val newName = if (v.isLocked) v.name.replace("LOCKED_BVR_PRO_", "BVR_PRO_") else v.name.replace("BVR_PRO_", "LOCKED_BVR_PRO_")
                contentResolver.update(v.uri, ContentValues().apply { put(MediaStore.Video.Media.DISPLAY_NAME, newName) }, null, null)
            }
            h.bD.setOnClickListener {
                AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(getString(R.string.dialog_delete_title))
                    .setMessage(getString(R.string.dialog_delete_msg))
                    .setPositiveButton(getString(R.string.btn_delete)) { _, _ -> contentResolver.delete(v.uri, null, null) }
                    .setNegativeButton(getString(R.string.btn_cancel), null).show()
            }

            h.img?.let { imageView ->
                try {
                    val thumbnail = contentResolver.loadThumbnail(v.uri, Size(256, 256), null)
                    imageView.setImageBitmap(thumbnail)
                } catch (_: Exception) {
                    imageView.setImageDrawable(null)
                }
            }

            h.itemView.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(v.uri, "video/mp4"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) })
            }
        }
        override fun getItemCount() = videoList.size
    }
}