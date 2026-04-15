package com.example.mydashcam

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.media.MediaRecorder
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.text.SpannableString
import android.text.style.StyleSpan
import android.view.*
import android.widget.*
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.*
import java.text.SimpleDateFormat
import java.util.*

data class VideoItem(
    val id: Long,
    val name: String,
    val size: Long,
    val duration: Long,
    val dateAdded: Long,
    val uri: Uri,
    val isLocked: Boolean
)

class MainActivity : Activity() {

    private lateinit var btnToggleRecord: CardView
    private lateinit var iconToggleRecord: ImageView
    private lateinit var btnLockFile: CardView
    private lateinit var btnSettings: ImageButton
    private lateinit var statusText: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var btnTabTemp: TextView
    private lateinit var btnTabSaved: TextView

    private var showingSaved = false
    private val videoList = mutableListOf<VideoItem>()
    private lateinit var adapter: VideoAdapter

    private val mediaStoreObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            loadVideos()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("DashcamPrefs", MODE_PRIVATE)
        val lang = prefs.getString("app_lang", if (Locale.getDefault().language == "ru") "ru" else "en") ?: "en"
        setAppLocale(lang)

        setContentView(R.layout.activity_main)

        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
        } else {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        }
        requestPermissions(permissions, 101)

        btnToggleRecord = findViewById(R.id.btnToggleRecord)
        iconToggleRecord = findViewById(R.id.iconToggleRecord)
        btnLockFile = findViewById(R.id.btnLockFile)
        btnSettings = findViewById(R.id.btnSettings)
        statusText = findViewById(R.id.statusText)
        recyclerView = findViewById(R.id.recyclerViewVideos)
        btnTabTemp = findViewById(R.id.btnTabTemp)
        btnTabSaved = findViewById(R.id.btnTabSaved)

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = VideoAdapter()
        recyclerView.adapter = adapter

        btnTabTemp.setOnClickListener { showingSaved = false; updateTabsUI(); loadVideos() }
        btnTabSaved.setOnClickListener { showingSaved = true; updateTabsUI(); loadVideos() }

        btnToggleRecord.setOnClickListener {
            val intent = Intent(this, CameraService::class.java).apply {
                action = if (CameraService.isRecordingActive) CameraService.ACTION_STOP else CameraService.ACTION_START
            }
            ContextCompat.startForegroundService(this, intent)
            Handler(Looper.getMainLooper()).postDelayed({ updateUiState() }, 400)
        }

        btnLockFile.setOnClickListener {
            if (CameraService.isRecordingActive) {
                ContextCompat.startForegroundService(this, Intent(this, CameraService::class.java).apply { action = CameraService.ACTION_LOCK })
                Toast.makeText(this, getString(R.string.msg_locked), Toast.LENGTH_SHORT).show()
            }
        }

        btnSettings.setOnClickListener { openSmartSettingsDialog() }

        if (!prefs.contains("f_run")) {
            Handler(Looper.getMainLooper()).post { openSmartSettingsDialog() }
            prefs.edit { putBoolean("f_run", true) }
        }
    }

    private fun setAppLocale(langCode: String) {
        val lm = getSystemService(LocaleManager::class.java)
        lm.applicationLocales = LocaleList(Locale(langCode))
    }

    override fun onResume() {
        super.onResume()
        contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaStoreObserver)
        loadVideos()
        updateTabsUI()
        updateUiState()

        // ВЫЗОВ ПАНЕЛИ: Если права есть, кидаем команду STANDBY для отображения уведомления
        val hasCam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        if (hasCam && hasMic) {
            val intent = Intent(this, CameraService::class.java).apply {
                action = CameraService.ACTION_STANDBY
            }
            ContextCompat.startForegroundService(this, intent)
        }
    }

    override fun onPause() {
        super.onPause()
        contentResolver.unregisterContentObserver(mediaStoreObserver)
    }

    private fun updateTabsUI() {
        btnTabTemp.isSelected = !showingSaved
        btnTabSaved.isSelected = showingSaved

        val active = ContextCompat.getColor(this, R.color.text_active)
        val inactive = ContextCompat.getColor(this, R.color.text_inactive)

        btnTabTemp.setTextColor(if (!showingSaved) active else inactive)
        btnTabSaved.setTextColor(if (showingSaved) active else inactive)
    }

    @SuppressLint("SetTextI18n")
    private fun updateUiState() {
        val prefs = getSharedPreferences("DashcamPrefs", MODE_PRIVATE)
        val isRu = Locale.getDefault().language == "ru"

        val cam = when(prefs.getString("pref_camera", "auto")) {
            "front" -> if(isRu) "САЛОН" else "CABIN"
            "back" -> if(isRu) "ОСНОВНАЯ" else "MAIN"
            else -> if(isRu) "ШИРИК" else "WIDE"
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
        val prefs = getSharedPreferences("DashcamPrefs", MODE_PRIVATE)
        var cCam = prefs.getString("pref_camera", "auto") ?: "auto"
        var cFiles = prefs.getInt("max_loop_files", 6)
        val cLang = prefs.getString("app_lang", "en") ?: "en"

        ProcessCameraProvider.getInstance(this).addListener({
            val provider = ProcessCameraProvider.getInstance(this).get()
            val all = provider.availableCameraInfos
            val fCam = all.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
            val bStd = all.filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }.maxByOrNull { Camera2CameraInfo.from(it).getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: 0f }
            val bWide = all.filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }.minByOrNull { Camera2CameraInfo.from(it).getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: Float.MAX_VALUE }

            val scroll = ScrollView(this)
            val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(64, 48, 64, 48) }

            fun addHeader(t: String) {
                val tv = TextView(this).apply {
                    text = t.uppercase(); textSize = 11f; setTypeface(null, Typeface.BOLD); setTextColor("#66BB6A".toColorInt()); setPadding(0, 48, 0, 16)
                }
                layout.addView(tv)
            }

            // 1. LANGUAGE
            addHeader(if(cLang == "ru") "Язык" else "Language")
            val rgL = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
            val rbRu = RadioButton(this).apply { text = "RU"; setPadding(0,0,40,0) }
            val rbEn = RadioButton(this).apply { text = "EN" }
            rgL.addView(rbRu); rgL.addView(rbEn); if(cLang == "ru") rbRu.isChecked = true else rbEn.isChecked = true
            layout.addView(rgL)

            // 2. LENS
            addHeader(if(cLang == "ru") "Объектив" else "Camera Lens")
            val rgC = RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
            val rbA = RadioButton(this).apply { text = if(cLang == "ru") "Ультра-широкий (Авто)" else "Ultra-Wide (Auto)" }
            val rbB = RadioButton(this).apply { text = if(cLang == "ru") "Основной сенсор" else "Main Sensor" }
            val rbF = RadioButton(this).apply { text = if(cLang == "ru") "Салон (Фронт)" else "Cabin Front" }
            if (fCam == null) rbF.isEnabled = false
            rgC.addView(rbA); rgC.addView(rbB); rgC.addView(rbF)
            when(cCam) { "front" -> rbF.isChecked = true; "back" -> rbB.isChecked = true; else -> rbA.isChecked = true }
            layout.addView(rgC)

            // 3. RESOLUTION
            addHeader(if(cLang == "ru") "Разрешение" else "Resolution")
            val rb4 = RadioButton(this).apply { text = "4K UHD" }
            val rb1 = RadioButton(this).apply { text = "1080p Full HD" }
            val rb7 = RadioButton(this).apply { text = "720p HD" }
            val rgR = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
            rgR.addView(rb4); rgR.addView(rb1); rgR.addView(rb7)
            val sRes = prefs.getString("pref_resolution", "1080")
            if(sRes == "4k") rb4.isChecked = true else if(sRes == "720") rb7.isChecked = true else rb1.isChecked = true
            layout.addView(rgR)

            // 4. FRAMERATE
            addHeader(if(cLang == "ru") "Частота кадров" else "Framerate")
            val rgF = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
            val rb6 = RadioButton(this).apply { text = "60 FPS"; setPadding(0,0,40,0) }
            val rb3 = RadioButton(this).apply { text = "30 FPS" }
            rgF.addView(rb6); rgF.addView(rb3)
            if(prefs.getInt("pref_fps", 30) == 60) rb6.isChecked = true else rb3.isChecked = true
            layout.addView(rgF)

            // 5. STORAGE LIMIT
            addHeader(if(cLang == "ru") "Лимит памяти" else "Storage Limit")
            val loopL = TextView(this).apply { textSize = 15f; setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_active)) }
            val cycleI = TextView(this).apply { text = "⏱ 1 cycle = 10 minutes"; textSize = 12f; setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_inactive)); setPadding(0,4,0,16) }
            val seek = SeekBar(this).apply { max = 17; progress = cFiles - 3 }
            val storL = TextView(this).apply { textSize = 14f; setTypeface(null, Typeface.BOLD); setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_active)); setPadding(0,16,0,0) }

            fun refresh() {
                val t = when(cCam) { "front" -> fCam; "back" -> bStd; else -> bWide ?: bStd }
                var s4=false; var s1=false; var s6=false
                t?.let { c ->
                    val ch = Camera2CameraInfo.from(c)
                    val m = ch.getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val sz = m?.getOutputSizes(MediaRecorder::class.java)
                    s1 = sz?.any { it.width == 1920 } == true
                    s4 = sz?.any { it.width >= 3840 } == true
                    s6 = ch.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.any { it.upper >= 60 } == true
                }
                rb4.isEnabled = s4; rb1.isEnabled = s1; rb6.isEnabled = s6
                if(!s4 && rb4.isChecked) rb1.isChecked = true
                if(!s6 && rb6.isChecked) rb3.isChecked = true

                val mult = (if(rb4.isChecked) 2.8 else if(rb7.isChecked) 0.6 else 1.0) * (if(rb6.isChecked) 1.6 else 1.0)
                storL.text = String.format(Locale.US, if(rbRu.isChecked) "Объем цикла: %.1f ГБ" else "Estimated loop: %.1f GB", cFiles * 1.5 * mult)
                loopL.text = if(rbRu.isChecked) "Файлов в цикле: $cFiles" else "Files in loop: $cFiles"
            }

            refresh()
            rgC.setOnCheckedChangeListener { _, id -> cCam = when(id){rbF.id->"front";rbB.id->"back";else->"auto"}; refresh() }
            rgR.setOnCheckedChangeListener { _, _ -> refresh() }
            rgF.setOnCheckedChangeListener { _, _ -> refresh() }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { cFiles = p + 3; refresh() }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })

            layout.addView(loopL); layout.addView(cycleI); layout.addView(seek); layout.addView(storL)
            scroll.addView(layout)

            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(getString(R.string.settings_title))
                .setView(scroll)
                .setPositiveButton(getString(R.string.btn_save_config)) { _, _ ->
                    val sR = if(rb4.isChecked)"4k" else if(rb7.isChecked)"720" else "1080"
                    val sL = if(rbRu.isChecked) "ru" else "en"
                    prefs.edit {
                        putString("app_lang", sL)
                        putString("pref_camera", cCam)
                        putString("pref_resolution", sR)
                        putInt("pref_fps", if(rb6.isChecked)60 else 30)
                        putInt("max_loop_files", cFiles)
                    }
                    if (sL != cLang) { setAppLocale(sL); recreate() } else updateUiState()
                }.show()
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

                    if (showingSaved && isL) videoList.add(item)
                    else if (!showingSaved && name.startsWith("BVR_PRO_")) videoList.add(item)
                }
            }
            adapter.notifyDataSetChanged()
        } catch (_: Exception) {}
    }

    inner class VideoAdapter : RecyclerView.Adapter<VideoAdapter.VideoViewHolder>() {
        inner class VideoViewHolder(v: View) : RecyclerView.ViewHolder(v) {
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
            h.itemView.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(v.uri, "video/mp4"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) })
            }
        }
        override fun getItemCount() = videoList.size
    }
}