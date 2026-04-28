package com.example.mydashcam

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.ContentValues
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.mydashcam.utils.TripDatabaseHelper
import com.example.mydashcam.utils.TripRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class TripJournalActivity : AppCompatActivity() {

    private lateinit var contentLayout: LinearLayout
    private lateinit var dbHelper: TripDatabaseHelper
    private var allTrips: List<TripRecord> = emptyList()

    private val isRu by lazy { Locale.getDefault().language == "ru" }

    // Переменная текущего фильтра для TRIP A
    private var currentFilterStart = 0L
    private var currentFilterEnd = Long.MAX_VALUE

    // Лаунчер для выбора файла импорта
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            importDatabaseFromCSV(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trip_journal)

        dbHelper = TripDatabaseHelper(this)
        contentLayout = findViewById(R.id.contentLayout)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnExport).setOnClickListener { exportDatabaseToCSV() }

        // Кнопка импорта
        findViewById<ImageButton>(R.id.btnImport).setOnClickListener {
            importLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "*/*"))
        }

        val btnToday = findViewById<Button>(R.id.btnToday)
        val btnWeek = findViewById<Button>(R.id.btnWeek)
        val btnMonth = findViewById<Button>(R.id.btnMonth)
        val btnCalendar = findViewById<Button>(R.id.btnCalendar)

        val cal = Calendar.getInstance()
        btnToday.setOnClickListener {
            cal.timeInMillis = System.currentTimeMillis()
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0)
            currentFilterStart = cal.timeInMillis
            currentFilterEnd = Long.MAX_VALUE
            loadDataAndRender()
        }
        btnWeek.setOnClickListener {
            cal.timeInMillis = System.currentTimeMillis()
            cal.add(Calendar.DAY_OF_YEAR, -7)
            currentFilterStart = cal.timeInMillis
            currentFilterEnd = Long.MAX_VALUE
            loadDataAndRender()
        }
        btnMonth.setOnClickListener {
            cal.timeInMillis = System.currentTimeMillis()
            cal.add(Calendar.DAY_OF_YEAR, -30)
            currentFilterStart = cal.timeInMillis
            currentFilterEnd = Long.MAX_VALUE
            loadDataAndRender()
        }
        btnCalendar.setOnClickListener {
            val currentCal = Calendar.getInstance()
            DatePickerDialog(this, { _, year, month, dayOfMonth ->
                val selectedCal = Calendar.getInstance()
                selectedCal.set(year, month, dayOfMonth, 0, 0, 0)
                currentFilterStart = selectedCal.timeInMillis
                selectedCal.set(year, month, dayOfMonth, 23, 59, 59)
                currentFilterEnd = selectedCal.timeInMillis
                loadDataAndRender()
            }, currentCal.get(Calendar.YEAR), currentCal.get(Calendar.MONTH), currentCal.get(Calendar.DAY_OF_MONTH)).show()
        }

        // По умолчанию грузим ВСЁ время для TRIP A
        loadDataAndRender()
    }

    private fun importDatabaseFromCSV(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val lines = inputStream.bufferedReader().readLines()
                    if (lines.size <= 1) return@use // Пустой файл или только заголовки

                    var importedCount = 0
                    val sdfFull = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

                    for (i in 1 until lines.size) {
                        val cols = lines[i].split(",")
                        if (cols.size >= 7) {
                            try {
                                val dateStr = cols[0]
                                val startStr = cols[1]
                                val endStr = cols[2]
                                val distKm = cols[3].toFloatOrNull() ?: 0f
                                val maxSpeed = cols[5].toFloatOrNull() ?: 0f
                                val maxG = cols[6].toFloatOrNull() ?: 0f

                                val startMs = sdfFull.parse("$dateStr $startStr")?.time ?: continue
                                var endMs = sdfFull.parse("$dateStr $endStr")?.time ?: continue

                                if (endMs < startMs) endMs += 86400000L

                                dbHelper.insertTrip(TripRecord(
                                    startTime = startMs,
                                    endTime = endMs,
                                    distanceMeters = distKm * 1000f,
                                    maxSpeedKmh = maxSpeed,
                                    maxGForce = maxG
                                ))
                                importedCount++
                            } catch (_: Exception) {
                                // ИСПРАВЛЕНО: Заменили e на _ (пропускаем кривые строки)
                            }
                        }
                    }

                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@TripJournalActivity, getString(R.string.msg_import_success, importedCount), Toast.LENGTH_LONG).show()
                        loadDataAndRender()
                    }
                }
            } catch (_: Exception) {
                // ИСПРАВЛЕНО: Заменили e на _ (выводим общую ошибку без деталей)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@TripJournalActivity, getString(R.string.msg_import_error), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun exportDatabaseToCSV() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val trips = dbHelper.getAllTrips()
                if (trips.isEmpty()) {
                    withContext(Dispatchers.Main) { Toast.makeText(this@TripJournalActivity, getString(R.string.msg_export_empty), Toast.LENGTH_SHORT).show() }
                    return@launch
                }

                val csvContent = java.lang.StringBuilder()
                csvContent.append("Date,Start Time,End Time,Distance (km),Avg Speed (km/h),Max Speed (km/h),Max G-Force\n")
                val sdfDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val sdfTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

                trips.forEach { t ->
                    val dateStr = sdfDate.format(Date(t.startTime))
                    val startStr = sdfTime.format(Date(t.startTime))
                    val endStr = sdfTime.format(Date(t.endTime))
                    val distKm = t.distanceMeters / 1000f
                    val timeHours = (t.endTime - t.startTime) / 3600000f
                    val avgSpeed = if (timeHours > 0) distKm / timeHours else 0f
                    csvContent.append(String.format(Locale.US, "%s,%s,%s,%.2f,%.1f,%.1f,%.2f\n", dateStr, startStr, endStr, distKm, avgSpeed, t.maxSpeedKmh, t.maxGForce))
                }

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "TripJournal_$timestamp.csv")
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DashcamBackups")
                }

                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { os -> os.write(csvContent.toString().toByteArray(Charsets.UTF_8)) }
                    withContext(Dispatchers.Main) { Toast.makeText(this@TripJournalActivity, getString(R.string.msg_export_success), Toast.LENGTH_LONG).show() }
                }
            } catch (e: Exception) {
                // А вот здесь 'e' нам нужен, так как мы выводим детали ошибки записи файла: e.message
                withContext(Dispatchers.Main) { Toast.makeText(this@TripJournalActivity, "Export Error: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun loadDataAndRender() {
        lifecycleScope.launch(Dispatchers.IO) {
            allTrips = dbHelper.getAllTrips()
            withContext(Dispatchers.Main) { renderTrips() }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun renderTrips() {
        contentLayout.removeAllViews()

        if (allTrips.isEmpty()) {
            contentLayout.addView(TextView(this).apply {
                text = if (isRu) "Журнал поездок пуст." else "Trip journal is empty."
                textSize = 16f
                setTextColor(Color.GRAY)
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                setPadding(0, 64, 0, 0)
            })
            renderFactoryResetButton()
            return
        }

        var totalOdoMeters = 0f
        allTrips.forEach { totalOdoMeters += it.distanceMeters }

        contentLayout.addView(TextView(this).apply {
            text = "ODO: ${String.format(Locale.US, "%.1f", totalOdoMeters / 1000f)} km"
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
            setTextColor("#E0E0E0".toColorInt())
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(0, 0, 0, 16)
        })

        val prefs = getSharedPreferences("DashcamPrefs", MODE_PRIVATE)
        val tripBStartTime = prefs.getLong("trip_b_start", 0L)

        var tripBDist = 0f
        var tripBTime = 0L
        allTrips.filter { it.startTime >= tripBStartTime }.forEach {
            tripBDist += it.distanceMeters
            tripBTime += (it.endTime - it.startTime)
        }
        val tripBHours = tripBTime / 3600000f
        val tripBAvg = if (tripBHours > 0) (tripBDist / 1000f) / tripBHours else 0f

        val tripBLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }

        val labelB = if (isRu) "TRIP B (Пользовательский)" else "TRIP B (Custom)"
        val tvTripB = TextView(this).apply {
            text = String.format(Locale.US, "$labelB\n%.1f km  (∅ %.1f km/h)  ", tripBDist / 1000f, tripBAvg)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setTextColor("#FFB74D".toColorInt())
        }
        tripBLayout.addView(tvTripB)

        val btnResetB = ImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(96, 96)
            setBackgroundColor(Color.TRANSPARENT)
            setImageResource(android.R.drawable.ic_popup_sync)
            setColorFilter("#FFB74D".toColorInt())
            setOnClickListener {
                AlertDialog.Builder(this@TripJournalActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(if (isRu) "Сбросить Trip B?" else "Reset Trip B?")
                    .setMessage(if (isRu) "Пробег Trip B начнется с нуля. Общий ODO не изменится." else "Trip B will start from zero. ODO remains unchanged.")
                    .setPositiveButton(if (isRu) "СБРОС" else "RESET") { _, _ ->
                        prefs.edit { putLong("trip_b_start", System.currentTimeMillis()) }
                        loadDataAndRender()
                    }
                    .setNegativeButton(if (isRu) "Отмена" else "Cancel", null)
                    .show()
            }
        }
        tripBLayout.addView(btnResetB)
        contentLayout.addView(tripBLayout)

        val filteredTrips = allTrips.filter { it.startTime in currentFilterStart..currentFilterEnd }

        var periodDist = 0f
        var periodTime = 0L
        filteredTrips.forEach {
            periodDist += it.distanceMeters
            periodTime += (it.endTime - it.startTime)
        }
        val periodHours = periodTime / 3600000f
        val periodAvgSpeed = if (periodHours > 0) (periodDist / 1000f) / periodHours else 0f

        val labelA = if (isRu) "TRIP A (Суточный/Период)" else "TRIP A (Daily/Period)"
        contentLayout.addView(TextView(this).apply {
            text = String.format(Locale.US, "$labelA\n%.1f km  (∅ %.1f km/h)", periodDist / 1000f, periodAvgSpeed)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor("#66BB6A".toColorInt())
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(0, 0, 0, 24)
        })

        if (filteredTrips.isEmpty()) {
            contentLayout.addView(TextView(this).apply {
                text = if (isRu) "В этом периоде нет поездок." else "No trips in this period."
                textSize = 14f
                setTextColor(Color.DKGRAY)
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                setPadding(0, 32, 0, 0)
            })
        } else {
            val sdfDay = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
            val sdfTime = SimpleDateFormat("HH:mm", Locale.getDefault())
            val grouped = filteredTrips.groupBy { sdfDay.format(Date(it.startTime)) }

            grouped.forEach { (dayString, dayTrips) ->
                var dayDist = 0f
                var dayTime = 0L
                dayTrips.forEach {
                    dayDist += it.distanceMeters
                    dayTime += (it.endTime - it.startTime)
                }
                val dayDistKm = dayDist / 1000f
                val dayHours = dayTime / 3600000f
                val dayAvgSpeed = if (dayHours > 0) dayDistKm / dayHours else 0f
                val timeStr = "${dayTime / 3600000}h ${(dayTime % 3600000) / 60000}m"

                val card = CardView(this).apply {
                    setCardBackgroundColor("#1E1E1E".toColorInt())
                    radius = 24f
                    setContentPadding(40, 40, 40, 40)
                    useCompatPadding = true
                }
                val cardLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

                val dayHeader = TextView(this).apply {
                    text = "📅 $dayString\n⏱ $timeStr  |  🚗 ${String.format(Locale.US, "%.1f", dayDistKm)} km  |  ∅ ${String.format(Locale.US, "%.1f", dayAvgSpeed)} km/h"
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    setTypeface(null, Typeface.BOLD)
                }
                cardLayout.addView(dayHeader)

                val detailsLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    visibility = View.GONE
                    setPadding(0, 32, 0, 0)
                }

                dayTrips.forEach { t ->
                    val tDistKm = t.distanceMeters / 1000f
                    val tripLine = TextView(this).apply {
                        text = "🔹 ${sdfTime.format(Date(t.startTime))} - ${sdfTime.format(Date(t.endTime))}  |  ${String.format(Locale.US, "%.1f", tDistKm)} km\n     Max: ${t.maxSpeedKmh.toInt()} km/h  |  Max G: ${String.format(Locale.US, "%.2f", t.maxGForce)}"
                        setTextColor(Color.LTGRAY)
                        textSize = 14f
                        setPadding(0, 16, 0, 16)
                    }
                    detailsLayout.addView(tripLine)
                }

                cardLayout.addView(detailsLayout)
                card.addView(cardLayout)
                card.setOnClickListener { detailsLayout.isVisible = !detailsLayout.isVisible }
                contentLayout.addView(card)
            }
        }

        renderFactoryResetButton()
    }

    private fun renderFactoryResetButton() {
        contentLayout.addView(Button(this).apply {
            text = if (isRu) "ПОЛНЫЙ СБРОС (FACTORY RESET)" else "FACTORY RESET ODO"
            setTextColor(Color.RED)
            setBackgroundColor(Color.TRANSPARENT)
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 96, 0, 48)
            layoutParams = params
            setOnClickListener {
                AlertDialog.Builder(this@TripJournalActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(if (isRu) "ВНИМАНИЕ" else "WARNING")
                    .setMessage(if (isRu) "Вы собираетесь удалить всю базу данных. ODO и TRIP B будут безвозвратно стерты. Продолжить?" else "You are about to wipe the entire database. ODO and TRIP B will be permanently erased. Continue?")
                    .setPositiveButton(if (isRu) "ДА, УДАЛИТЬ" else "YES, WIPE IT") { _, _ ->
                        dbHelper.clearAllTrips()
                        getSharedPreferences("DashcamPrefs", MODE_PRIVATE).edit { putLong("trip_b_start", 0L) }
                        Toast.makeText(context, if (isRu) "База данных очищена" else "Database wiped", Toast.LENGTH_SHORT).show()
                        loadDataAndRender()
                    }
                    .setNegativeButton(if (isRu) "ОТМЕНА" else "CANCEL", null)
                    .show()
            }
        })
    }
}