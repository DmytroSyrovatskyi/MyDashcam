package com.example.mydashcam

import android.Manifest
import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.media.MediaRecorder
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.provider.Settings
import android.text.SpannableString
import android.text.style.StyleSpan
import android.util.Rational
import android.util.Size
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.*
import com.example.mydashcam.views.StorageRingView
import com.example.mydashcam.utils.StorageManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class VideoItem(
    val id: Long, val name: String, val size: Long, val duration: Long, val dateAdded: Long, val uri: Uri, val isLocked: Boolean
)

enum class TabState { TIMELINE, PROTECTED, PREVIEW }

class MainActivity : AppCompatActivity() {

    private lateinit var topBar: LinearLayout
    private lateinit var bottomIsland: View
    private lateinit var galleryContainer: LinearLayout
    private lateinit var btnOpenGallery: ImageButton
    private lateinit var btnTabCloseGallery: ImageButton

    private lateinit var btnToggleRecord: CardView
    private lateinit var iconToggleRecord: ImageView
    private lateinit var btnLockFile: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var btnTripJournal: ImageButton
    private lateinit var statusText: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var storageRing: StorageRingView

    private lateinit var btnTabTemp: TextView
    private lateinit var btnTabSaved: TextView
    private lateinit var tabLayout: LinearLayout
    private lateinit var previewImageView: TextureView

    private var cameraServiceBinder: CameraService.LocalBinder? = null
    private var isFirstRunDialogOpen = false

    private var settingsTvStorageInfo: TextView? = null

    private val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            getSharedPreferences("DashcamPrefs", MODE_PRIVATE).edit { putString("pref_custom_storage_uri", uri.toString()) }
            Toast.makeText(this, getString(R.string.msg_storage_changed), Toast.LENGTH_SHORT).show()
            settingsTvStorageInfo?.text = getString(R.string.storage_external_selected)
            settingsTvStorageInfo?.setTextColor("#00E5FF".toColorInt())
        }
    }

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
                            val canvas = previewImageView.lockCanvas() ?: return@runOnUiThread
                            canvas.drawColor(Color.BLACK)
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
                            canvas.drawBitmap(bitmap, matrix, Paint().apply { isFilterBitmap = true })
                            previewImageView.unlockCanvasAndPost(canvas)
                        }
                    }
                }
            }
            runOnUiThread { updateUiState() }
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
            runOnUiThread { updateUiState() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("DashcamPrefs", MODE_PRIVATE)
        val lang = prefs.getString("app_lang", "en") ?: "en"
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(lang))

        setContentView(R.layout.activity_main)

        topBar = findViewById(R.id.topBar)
        bottomIsland = findViewById(R.id.bottomIsland)
        galleryContainer = findViewById(R.id.galleryContainer)
        btnOpenGallery = findViewById(R.id.btnOpenGallery)
        btnTabCloseGallery = findViewById(R.id.btnTabCloseGallery)

        tabLayout = findViewById(R.id.tabLayout)
        previewImageView = findViewById(R.id.previewImageView)

        btnToggleRecord = findViewById(R.id.btnToggleRecord)
        iconToggleRecord = findViewById(R.id.iconToggleRecord)
        btnLockFile = findViewById(R.id.btnLockFile)
        btnSettings = findViewById(R.id.btnSettings)
        btnTripJournal = findViewById(R.id.btnTripJournal)
        statusText = findViewById(R.id.statusText)
        recyclerView = findViewById(R.id.recyclerViewVideos)
        storageRing = findViewById(R.id.storageRing)

        btnTabTemp = findViewById(R.id.btnTabTemp)
        btnTabSaved = findViewById(R.id.btnTabSaved)

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = VideoAdapter()
        recyclerView.adapter = adapter

        btnTabTemp.setOnClickListener { switchTab(TabState.TIMELINE) }
        btnTabSaved.setOnClickListener { switchTab(TabState.PROTECTED) }

        btnOpenGallery.setOnClickListener { switchTab(TabState.TIMELINE) }
        btnTabCloseGallery.setOnClickListener { switchTab(TabState.PREVIEW) }

        btnTripJournal.setOnClickListener {
            startActivity(Intent(this, TripJournalActivity::class.java))
        }

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
                Toast.makeText(this, getString(R.string.msg_settings_stop_record), Toast.LENGTH_SHORT).show()
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

    private fun getPipRatio(): Rational {
        val w = previewImageView.width
        val h = previewImageView.height
        return if (w > 0 && h > 0) Rational(w, h) else Rational(16, 9)
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
            val params = PictureInPictureParams.Builder().setAspectRatio(getPipRatio()).build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            topBar.visibility = View.GONE
            bottomIsland.visibility = View.GONE
            galleryContainer.visibility = View.GONE
        } else {
            topBar.visibility = View.VISIBLE
            bottomIsland.visibility = View.VISIBLE
            switchTab(currentTab)
            updateUiState()
        }
    }

    private fun requestAppPermissions() {
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        if (!hasPermissions()) requestPermissions(permissions, 101)
    }

    private fun hasPermissions(): Boolean {
        val hasCam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        return hasCam && hasMic
    }

    private fun checkFirstRunDialog() {
        val prefs = getSharedPreferences("DashcamPrefs", MODE_PRIVATE)
        if (!prefs.getBoolean("f_run", false) && !isFirstRunDialogOpen) {
            isFirstRunDialogOpen = true
            window.decorView.post {
                if (!isFinishing && !isDestroyed) openSmartSettingsDialog()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasPermissions()) {
            checkFirstRunDialog()
            try { contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaStoreObserver) } catch (_: Exception) {}
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
            Handler(Looper.getMainLooper()).postDelayed({ checkFirstRunDialog() }, 500)
            try { contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaStoreObserver) } catch (_: Exception) {}
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
        try { contentResolver.unregisterContentObserver(mediaStoreObserver) } catch (_: Exception) {}
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
                val intent = Intent(this, CameraService::class.java).apply { action = CameraService.ACTION_PAUSE_PREVIEW }
                startService(intent)
            } catch (_: Exception) {}
        }
    }

    private fun switchTab(tab: TabState) {
        currentTab = tab

        val active = "#00E5FF".toColorInt()
        val inactive = "#B3B3B3".toColorInt()

        btnTabTemp.isSelected = (tab == TabState.TIMELINE)
        btnTabSaved.isSelected = (tab == TabState.PROTECTED)

        btnTabTemp.setTextColor(if (tab == TabState.TIMELINE) active else inactive)
        btnTabSaved.setTextColor(if (tab == TabState.PROTECTED) active else inactive)

        if (tab == TabState.PREVIEW) {
            galleryContainer.visibility = View.GONE
            topBar.visibility = View.VISIBLE
            bottomIsland.visibility = View.VISIBLE
        } else {
            galleryContainer.visibility = View.VISIBLE
            topBar.visibility = View.GONE
            bottomIsland.visibility = View.GONE
            if (hasPermissions()) loadVideos()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateUiState() {
        val prefs = getSharedPreferences("DashcamPrefs", MODE_PRIVATE)
        val limit = prefs.getInt("max_loop_files", 6)

        val cam = when(prefs.getString("pref_camera", "auto")) {
            "front" -> getString(R.string.cam_front)
            "back" -> getString(R.string.cam_main)
            else -> getString(R.string.cam_wide)
        }
        val res = prefs.getString("pref_resolution", "1080").let { if(it=="4k") "4K" else "${it}p" }
        val fps = prefs.getInt("pref_fps", 30)

        val currentFiles = StorageManager.getFilesCount(this)
        val loopText = if (limit > 50) "∞" else "$currentFiles/$limit"

        val startTime = cameraServiceBinder?.getRecordingStartTime() ?: 0L
        val fileProgress = if (CameraService.isRecordingActive && startTime > 0) {
            val elapsed = System.currentTimeMillis() - startTime
            (elapsed.toFloat() / 600000f).coerceIn(0f, 1f)
        } else 0f
        storageRing.setProgress(fileProgress)

        val statusLine = if (CameraService.isRecordingActive) "REC" else "READY"
        val configLine = "$cam • $res • $fps fps • Loop: $loopText"

        val fullText = SpannableString("$statusLine  |  $configLine")
        fullText.setSpan(StyleSpan(Typeface.BOLD), 0, statusLine.length, 0)
        statusText.text = fullText

        if (CameraService.isRecordingActive) {
            btnToggleRecord.setCardBackgroundColor("#1C1C1E".toColorInt())
            iconToggleRecord.setImageResource(android.R.drawable.ic_media_pause)
            iconToggleRecord.setColorFilter("#FF1744".toColorInt())
            statusText.setTextColor(Color.WHITE)
        } else {
            btnToggleRecord.setCardBackgroundColor("#FF1744".toColorInt())
            iconToggleRecord.setImageResource(android.R.drawable.ic_media_play)
            iconToggleRecord.setColorFilter(Color.WHITE)
            statusText.setTextColor("#E0E0E0".toColorInt())
        }
    }

    @SuppressLint("SetTextI18n", "UnsafeOptInUsageError")
    private fun openSmartSettingsDialog() {
        isFirstRunDialogOpen = true
        val prefs = getSharedPreferences("DashcamPrefs", MODE_PRIVATE)
        // Отмечаем первый запуск как завершенный сразу при открытии настроек
        prefs.edit { putBoolean("f_run", true) }

        val bottomSheetView = layoutInflater.inflate(R.layout.layout_settings, findViewById(android.R.id.content), false)
        val bottomSheetDialog = BottomSheetDialog(this)
        bottomSheetDialog.setContentView(bottomSheetView)
        bottomSheetDialog.setCancelable(true)
        bottomSheetDialog.setOnDismissListener { isFirstRunDialogOpen = false }

        bottomSheetDialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        bottomSheetDialog.behavior.skipCollapsed = true

        (bottomSheetView.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
        bottomSheetView.background = ContextCompat.getDrawable(this, R.drawable.bg_glass_sheet)

        val rgLang = bottomSheetView.findViewById<RadioGroup>(R.id.rgLang)
        val rbRu = bottomSheetView.findViewById<RadioButton>(R.id.rbRu)
        val rbEn = bottomSheetView.findViewById<RadioButton>(R.id.rbEn)

        settingsTvStorageInfo = bottomSheetView.findViewById(R.id.tvStorageInfo)
        val btnStorageSelect = bottomSheetView.findViewById<Button>(R.id.btnStorageSelect)
        val btnStorageReset = bottomSheetView.findViewById<Button>(R.id.btnStorageReset)

        val rgLens = bottomSheetView.findViewById<RadioGroup>(R.id.rgLens)
        val rbLensWide = bottomSheetView.findViewById<RadioButton>(R.id.rbLensWide)
        val rbLensMain = bottomSheetView.findViewById<RadioButton>(R.id.rbLensMain)
        val rbLensFront = bottomSheetView.findViewById<RadioButton>(R.id.rbLensFront)

        val rgRes = bottomSheetView.findViewById<RadioGroup>(R.id.rgRes)
        val rbRes1080 = bottomSheetView.findViewById<RadioButton>(R.id.rbRes1080)
        val rbRes720 = bottomSheetView.findViewById<RadioButton>(R.id.rbRes720)
        val rbRes4K = bottomSheetView.findViewById<RadioButton>(R.id.rbRes4K)

        val rgFps = bottomSheetView.findViewById<RadioGroup>(R.id.rgFps)
        val rbFps30 = bottomSheetView.findViewById<RadioButton>(R.id.rbFps30)
        val rbFps60 = bottomSheetView.findViewById<RadioButton>(R.id.rbFps60)

        val rgCodec = bottomSheetView.findViewById<RadioGroup>(R.id.rgCodec)
        val rbCodecAuto = bottomSheetView.findViewById<RadioButton>(R.id.rbCodecAuto)
        val rbCodecHevc = bottomSheetView.findViewById<RadioButton>(R.id.rbCodecHevc)
        val rbCodecAvc = bottomSheetView.findViewById<RadioButton>(R.id.rbCodecAvc)

        val swPip = bottomSheetView.findViewById<SwitchCompat>(R.id.swPip)
        val swAutoCharge = bottomSheetView.findViewById<SwitchCompat>(R.id.swAutoCharge)
        val swAutoBt = bottomSheetView.findViewById<SwitchCompat>(R.id.swAutoBt)
        val tvBtDevice = bottomSheetView.findViewById<TextView>(R.id.tvBtDevice)

        val swStampDate = bottomSheetView.findViewById<SwitchCompat>(R.id.swStampDate)
        val swStampSpeed = bottomSheetView.findViewById<SwitchCompat>(R.id.swStampSpeed)
        val swStampGps = bottomSheetView.findViewById<SwitchCompat>(R.id.swStampGps)
        val swStampGforce = bottomSheetView.findViewById<SwitchCompat>(R.id.swStampGforce)

        val tvLoopTitle = bottomSheetView.findViewById<TextView>(R.id.tvLoopTitle)
        val tvStorageEst = bottomSheetView.findViewById<TextView>(R.id.tvStorageEst)
        val seekStorage = bottomSheetView.findViewById<SeekBar>(R.id.seekStorage)

        // 1. УСТАНАВЛИВАЕМ НАЧАЛЬНЫЕ ЗНАЧЕНИЯ ИЗ ПАМЯТИ
        val sysLang = Locale.getDefault().language
        val cLang = prefs.getString("app_lang", if (sysLang == "ru") "ru" else "en") ?: "en"
        if (cLang == "ru") rbRu.isChecked = true else rbEn.isChecked = true

        val configuredUri = prefs.getString("pref_custom_storage_uri", "")
        val effectiveUri = StorageManager.getEffectiveStorageUri(this)
        settingsTvStorageInfo?.text = if (configuredUri.isNullOrEmpty()) getString(R.string.storage_internal_default)
        else if (effectiveUri == null) getString(R.string.storage_warning_disconnected)
        else getString(R.string.storage_external_selected)
        settingsTvStorageInfo?.setTextColor(if (!configuredUri.isNullOrEmpty() && effectiveUri == null) Color.RED else "#00E5FF".toColorInt())

        when (prefs.getString("pref_camera", "auto")) {
            "front" -> rbLensFront.isChecked = true
            "back" -> rbLensMain.isChecked = true
            else -> rbLensWide.isChecked = true
        }

        when (prefs.getString("pref_resolution", "1080")) {
            "720" -> rbRes720.isChecked = true
            "4k" -> rbRes4K.isChecked = true
            else -> rbRes1080.isChecked = true
        }

        if (prefs.getInt("pref_fps", 30) == 60) rbFps60.isChecked = true else rbFps30.isChecked = true

        when (prefs.getString("pref_codec", "auto")) {
            "hevc" -> rbCodecHevc.isChecked = true
            "avc" -> rbCodecAvc.isChecked = true
            else -> rbCodecAuto.isChecked = true
        }

        swPip.isChecked = prefs.getBoolean("pref_pip", false)
        swAutoCharge.isChecked = prefs.getBoolean("pref_autostart", false)
        swAutoBt.isChecked = prefs.getBoolean("pref_autostart_bt", false)
        swStampDate.isChecked = prefs.getBoolean("pref_stamp_date", true)
        swStampSpeed.isChecked = prefs.getBoolean("pref_stamp_speed", false)
        swStampGps.isChecked = prefs.getBoolean("pref_stamp_gps", false)
        swStampGforce.isChecked = prefs.getBoolean("pref_stamp_gforce", false)

        tvBtDevice.text = prefs.getString("pref_bt_name", getString(R.string.set_device_none))
        tvBtDevice.visibility = if (swAutoBt.isChecked) View.VISIBLE else View.GONE

        var currentFiles = prefs.getInt("max_loop_files", 6)
        seekStorage.progress = if (currentFiles > 50) 18 else currentFiles - 3

        @SuppressLint("BatteryLife")
        fun requestBackgroundPermissions() {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = "package:$packageName".toUri() }) } catch (_: Exception) {}
            }
            if (!Settings.canDrawOverlays(this@MainActivity)) {
                Toast.makeText(this@MainActivity, getString(R.string.msg_overlay_permission_needed), Toast.LENGTH_LONG).show()
                try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply { data = "package:$packageName".toUri() }) } catch (_: Exception) {}
            }
        }

        fun updateStorageLabels() {
            val is4k = rbRes4K.isChecked
            val is720 = rbRes720.isChecked
            val is60 = rbFps60.isChecked
            val isAutoCodec = rbCodecAuto.isChecked
            val isHevc = rbCodecHevc.isChecked

            val mult = (if(is4k) 2.8 else if(is720) 0.6 else 1.0) * (if(is60) 1.6 else 1.0)
            val codecMult = if(isHevc || isAutoCodec) 0.75 else 1.0

            if (currentFiles > 50) {
                tvLoopTitle.text = getString(R.string.set_loop_off)
                tvStorageEst.text = getString(R.string.infinite_storage)
            } else {
                tvLoopTitle.text = getString(R.string.set_loop_count, currentFiles)
                if (isAutoCodec) {
                    val minGb = currentFiles * 1.5 * mult * 0.7
                    val maxGb = currentFiles * 1.5 * mult * 1.0
                    tvStorageEst.text = getString(R.string.set_est_auto, minGb, maxGb)
                } else {
                    tvStorageEst.text = getString(R.string.set_est_fixed, currentFiles * 1.5 * mult * codecMult)
                }
            }
        }
        updateStorageLabels()

        // Проверка аппаратной совместимости
        ProcessCameraProvider.getInstance(this).addListener({
            val provider = ProcessCameraProvider.getInstance(this).get()
            val all = provider.availableCameraInfos

            fun checkHardware(camType: String) {
                val target = when(camType) {
                    "front" -> all.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
                    "back" -> all.filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }.maxByOrNull { Camera2CameraInfo.from(it).getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: 0f }
                    else -> all.filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }.minByOrNull { Camera2CameraInfo.from(it).getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: Float.MAX_VALUE }
                }
                target?.let {
                    val map = Camera2CameraInfo.from(it).getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val ranges = Camera2CameraInfo.from(it).getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    val sizes = map?.getOutputSizes(MediaRecorder::class.java)

                    rbRes4K.isEnabled = sizes?.any { s -> s.width >= 3840 } == true
                    val officiallySupports60 = ranges?.any { r -> r.upper >= 60 } == true
                    rbFps60.text = if (officiallySupports60) getString(R.string.set_opt_60fps) else getString(R.string.set_fps_exp)
                    if (!rbRes4K.isEnabled && rbRes4K.isChecked) rbRes1080.isChecked = true
                }
            }
            checkHardware(when { rbLensFront.isChecked -> "front"; rbLensMain.isChecked -> "back"; else -> "auto" })

            // 2. ВЕШАЕМ СЛУШАТЕЛИ С МГНОВЕННЫМ СОХРАНЕНИЕМ (INSTANT SAVE)
            rgLens.setOnCheckedChangeListener { _, id ->
                val newCam = when(id) { R.id.rbLensFront -> "front"; R.id.rbLensMain -> "back"; else -> "auto" }
                prefs.edit { putString("pref_camera", newCam) }
                checkHardware(newCam)
                updateUiState()
                // Мгновенный перезапуск камеры без старта записи!
                if (hasPermissions() && !CameraService.isRecordingActive) {
                    try { startForegroundService(Intent(this@MainActivity, CameraService::class.java).apply { action = CameraService.ACTION_STANDBY }) } catch (_: Exception) {}
                }
            }
        }, ContextCompat.getMainExecutor(this))

        btnStorageSelect.setOnClickListener { folderPickerLauncher.launch(null) }
        btnStorageReset.setOnClickListener {
            prefs.edit { remove("pref_custom_storage_uri") }
            settingsTvStorageInfo?.text = getString(R.string.storage_internal_default)
            settingsTvStorageInfo?.setTextColor(Color.LTGRAY)
            Toast.makeText(this, getString(R.string.msg_storage_reset), Toast.LENGTH_SHORT).show()
        }

        // Мгновенное сохранение свитчей
        swPip.setOnCheckedChangeListener { _, isC -> prefs.edit { putBoolean("pref_pip", isC) } }
        swStampDate.setOnCheckedChangeListener { _, isC -> prefs.edit { putBoolean("pref_stamp_date", isC) } }
        swStampSpeed.setOnCheckedChangeListener { _, isC ->
            prefs.edit { putBoolean("pref_stamp_speed", isC) }
            if (isC && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 102)
            }
        }
        swStampGps.setOnCheckedChangeListener { _, isC ->
            prefs.edit { putBoolean("pref_stamp_gps", isC) }
            if (isC && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 102)
            }
        }
        swStampGforce.setOnCheckedChangeListener { _, isC -> prefs.edit { putBoolean("pref_stamp_gforce", isC) } }

        swAutoCharge.setOnCheckedChangeListener { _, isC ->
            prefs.edit { putBoolean("pref_autostart", isC) }
            if (isC) requestBackgroundPermissions()
        }

        swAutoBt.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("pref_autostart_bt", isChecked) }
            tvBtDevice.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) {
                requestBackgroundPermissions()
                val btManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    val devices = btManager.adapter?.bondedDevices?.toList() ?: emptyList()
                    if (devices.isNotEmpty()) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(getString(R.string.set_select_audio))
                            .setItems(devices.map { it.name ?: it.address }.toTypedArray()) { _, which ->
                                val d = devices[which]
                                prefs.edit { putString("pref_bt_mac", d.address); putString("pref_bt_name", d.name ?: d.address) }
                                tvBtDevice.text = d.name ?: d.address
                            }
                            .setOnCancelListener { swAutoBt.isChecked = false }
                            .show()
                    } else {
                        Toast.makeText(this@MainActivity, getString(R.string.set_no_devices), Toast.LENGTH_SHORT).show()
                        swAutoBt.isChecked = false
                    }
                } else {
                    requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 103)
                    swAutoBt.isChecked = false
                }
            } else {
                prefs.edit { putString("pref_bt_mac", ""); putString("pref_bt_name", "") }
                tvBtDevice.text = getString(R.string.set_device_none)
            }
        }

        // Мгновенное сохранение параметров качества
        rgRes.setOnCheckedChangeListener { _, id ->
            prefs.edit { putString("pref_resolution", when(id) { R.id.rbRes4K -> "4k"; R.id.rbRes720 -> "720"; else -> "1080" }) }
            updateStorageLabels()
            updateUiState()
        }
        rgFps.setOnCheckedChangeListener { _, id ->
            prefs.edit { putInt("pref_fps", if(id == R.id.rbFps60) 60 else 30) }
            updateStorageLabels()
            updateUiState()
        }
        rgCodec.setOnCheckedChangeListener { _, id ->
            prefs.edit { putString("pref_codec", when(id){ R.id.rbCodecHevc -> "hevc"; R.id.rbCodecAvc -> "avc"; else -> "auto" }) }
            updateStorageLabels()
        }

        seekStorage.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                currentFiles = if (p == 18) 999 else p + 3; updateStorageLabels()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                prefs.edit { putInt("max_loop_files", currentFiles) }
                updateUiState()
            }
        })

        // Мгновенная смена языка
        rgLang.setOnCheckedChangeListener { _, id ->
            val newLang = if (id == R.id.rbRu) "ru" else "en"
            val currentLang = prefs.getString("app_lang", "en") ?: "en"

            if (newLang != currentLang) {
                prefs.edit { putString("app_lang", newLang) }
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(newLang))
                bottomSheetDialog.dismiss()
            }
        }

        bottomSheetDialog.show()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun loadVideos() {
        lifecycleScope.launch(Dispatchers.IO) {
            val newList = mutableListOf<VideoItem>()
            val effectiveUriStr = StorageManager.getEffectiveStorageUri(this@MainActivity)

            if (effectiveUriStr == null) {
                try {
                    contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.DATE_ADDED), "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?", arrayOf("%Movies/MyDashcam%"), "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(1) ?: ""
                            val isL = name.startsWith("LOCKED_")
                            val item = VideoItem(cursor.getLong(0), name, cursor.getLong(2), cursor.getLong(3), cursor.getLong(4), ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)), isL)
                            if (currentTab == TabState.PROTECTED && isL) newList.add(item)
                            else if (currentTab == TabState.TIMELINE && name.startsWith("BVR_PRO_")) newList.add(item)
                        }
                    }
                } catch (_: Exception) {}
            } else {
                try {
                    val treeUri = effectiveUriStr.toUri()
                    val docFile = DocumentFile.fromTreeUri(this@MainActivity, treeUri)
                    docFile?.listFiles()?.forEach { file ->
                        val name = file.name ?: ""
                        if (name.endsWith(".mp4")) {
                            val isL = name.startsWith("LOCKED_")
                            val item = VideoItem(
                                id = file.uri.hashCode().toLong(),
                                name = name,
                                size = file.length(),
                                duration = 600000L,
                                dateAdded = file.lastModified() / 1000L,
                                uri = file.uri,
                                isLocked = isL
                            )
                            if (currentTab == TabState.PROTECTED && isL) newList.add(item)
                            else if (currentTab == TabState.TIMELINE && name.startsWith("BVR_PRO_")) newList.add(item)
                        }
                    }
                    newList.sortByDescending { it.dateAdded }
                } catch (_: Exception) {}
            }

            withContext(Dispatchers.Main) {
                videoList.clear()
                videoList.addAll(newList)
                adapter.notifyDataSetChanged()
                updateUiState()
            }
        }
    }

    inner class VideoAdapter : RecyclerView.Adapter<VideoAdapter.VideoViewHolder>() {
        inner class VideoViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView? = v.findViewById(R.id.imgThumbnail); val tD: TextView = v.findViewById(R.id.textVideoDate)
            val tI: TextView = v.findViewById(R.id.textVideoInfo); val bL: ImageButton = v.findViewById(R.id.btnLockUnlock); val bD: ImageButton = v.findViewById(R.id.btnDelete)
        }
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VideoViewHolder(LayoutInflater.from(p.context).inflate(R.layout.item_video, p, false))

        override fun onBindViewHolder(h: VideoViewHolder, p: Int) {
            val v = videoList[p]
            val context = h.itemView.context
            val sDate = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(v.dateAdded * 1000L))
            val eDate = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date((v.dateAdded * 1000L) + v.duration))
            h.tD.text = context.getString(R.string.video_time_range, sDate, eDate)
            val sz = if (v.size > 1073741824) String.format(Locale.US, "%.2f GB", v.size / 1073741824.0) else "${v.size / 1048576} MB"
            h.tI.text = context.getString(R.string.video_info_format, (v.duration / 60000).toInt(), sz)

            h.bL.setImageResource(if (v.isLocked) android.R.drawable.ic_menu_revert else android.R.drawable.ic_menu_save)

            h.bL.setOnClickListener {
                val newName = if (v.isLocked) v.name.replace("LOCKED_BVR_PRO_", "BVR_PRO_") else v.name.replace("BVR_PRO_", "LOCKED_BVR_PRO_")
                val effectiveUriStr = StorageManager.getEffectiveStorageUri(context)

                if (effectiveUriStr == null) {
                    contentResolver.update(v.uri, ContentValues().apply { put(MediaStore.Video.Media.DISPLAY_NAME, newName) }, null, null)
                    val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "MyDashcam")
                    val oldSrtFile = File(folder, v.name.replace(".mp4", ".srt"))
                    if (oldSrtFile.exists()) oldSrtFile.renameTo(File(folder, newName.replace(".mp4", ".srt")))
                    else StorageManager.renameCompanionSrt(context, v.name, newName)
                } else {
                    try {
                        val treeUri = effectiveUriStr.toUri()
                        val docDir = DocumentFile.fromTreeUri(context, treeUri)
                        docDir?.findFile(v.name)?.renameTo(newName)
                        docDir?.findFile(v.name.replace(".mp4", ".srt"))?.renameTo(newName.replace(".mp4", ".srt"))
                    } catch (_: Exception) {}
                }
                loadVideos()
            }

            h.bD.alpha = if (v.isLocked) 0.3f else 1.0f

            h.bD.setOnClickListener {
                if (v.isLocked) {
                    Toast.makeText(context, getString(R.string.msg_unlock_first), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val dialog = AlertDialog.Builder(context)
                    .setTitle(getString(R.string.dialog_delete_title))
                    .setMessage(getString(R.string.dialog_delete_msg))
                    .setPositiveButton(getString(R.string.btn_delete)) { _, _ ->
                        val effectiveUriStr = StorageManager.getEffectiveStorageUri(context)

                        if (effectiveUriStr == null) {
                            contentResolver.delete(v.uri, null, null)
                            val srtFile = File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "MyDashcam"), v.name.replace(".mp4", ".srt"))
                            if (srtFile.exists()) srtFile.delete() else StorageManager.deleteCompanionSrt(context, v.name)
                        } else {
                            try {
                                val treeUri = effectiveUriStr.toUri()
                                val docDir = DocumentFile.fromTreeUri(context, treeUri)
                                docDir?.findFile(v.name)?.delete()
                                docDir?.findFile(v.name.replace(".mp4", ".srt"))?.delete()
                            } catch (_: Exception) {}
                        }
                        loadVideos()
                    }.setNegativeButton(getString(R.string.btn_cancel), null)
                    .create()

                dialog.show()
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor("#FF5252".toColorInt())
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.WHITE)
            }

            try { h.img?.setImageBitmap(contentResolver.loadThumbnail(v.uri, Size(256, 256), null)) } catch (_: Exception) { h.img?.setImageDrawable(null) }
            h.itemView.setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(v.uri, "video/mp4"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }) }
        }
        override fun getItemCount() = videoList.size
    }
}