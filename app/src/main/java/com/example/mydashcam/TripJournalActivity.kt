@file:Suppress("DEPRECATION")

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
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
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

    private var currentFilterStart = 0L
    private var currentFilterEnd = Long.MAX_VALUE

    // Google Sign-In
    private lateinit var googleSignInClient: GoogleSignInClient
    private var googleAccount: GoogleSignInAccount? = null
    private var cloudDialog: AlertDialog? = null

    private val googleAuthLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                googleAccount = task.getResult(Exception::class.java)
                Toast.makeText(this, "Signed in as: ${googleAccount?.email}", Toast.LENGTH_SHORT).show()
                openCloudSyncDialog()
                smartSync(isSilent = true)
            } catch (e: Exception) {
                Toast.makeText(this, "Sign in failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) importDatabaseFromCSV(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trip_journal)

        dbHelper = TripDatabaseHelper(this)
        contentLayout = findViewById(R.id.contentLayout)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnExport).setOnClickListener { exportDatabaseToCSV() }
        findViewById<ImageButton>(R.id.btnImport).setOnClickListener {
            importLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "*/*"))
        }

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_APPDATA))
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)
        googleAccount = GoogleSignIn.getLastSignedInAccount(this)

        findViewById<ImageButton>(R.id.btnCloudSync).setOnClickListener { openCloudSyncDialog() }

        val btnToday = findViewById<Button>(R.id.btnToday)
        val btnWeek = findViewById<Button>(R.id.btnWeek)
        val btnMonth = findViewById<Button>(R.id.btnMonth)
        val btnCalendar = findViewById<Button>(R.id.btnCalendar)

        val cal = Calendar.getInstance()
        btnToday.setOnClickListener {
            cal.timeInMillis = System.currentTimeMillis()
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0)
            currentFilterStart = cal.timeInMillis; currentFilterEnd = Long.MAX_VALUE
            loadDataAndRender()
        }
        btnWeek.setOnClickListener {
            cal.timeInMillis = System.currentTimeMillis(); cal.add(Calendar.DAY_OF_YEAR, -7)
            currentFilterStart = cal.timeInMillis; currentFilterEnd = Long.MAX_VALUE
            loadDataAndRender()
        }
        btnMonth.setOnClickListener {
            cal.timeInMillis = System.currentTimeMillis(); cal.add(Calendar.DAY_OF_YEAR, -30)
            currentFilterStart = cal.timeInMillis; currentFilterEnd = Long.MAX_VALUE
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

        loadDataAndRender()

        if (googleAccount != null) {
            smartSync(isSilent = true)
        }
    }

    private fun getDriveService(): Drive? {
        val account = googleAccount?.account ?: return null
        val credential = GoogleAccountCredential.usingOAuth2(this, listOf(DriveScopes.DRIVE_APPDATA))
        credential.selectedAccount = account
        return Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName("MyDashcam")
            .build()
    }

    private fun smartSync(isSilent: Boolean = false) {
        val progressDialog = if (!isSilent) AlertDialog.Builder(this).setMessage(if (isRu) "Синхронизация..." else "Syncing...").setCancelable(false).show() else null

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val driveService = getDriveService() ?: throw Exception("Drive not initialized")
                val fileList = driveService.files().list().setSpaces("appDataFolder").setQ("name = 'trip_journal_backup.csv'").execute()

                val localTrips = dbHelper.getAllTrips()
                val sdfFull = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                // ИСПРАВЛЕНИЕ ДУБЛИКАТОВ: сохраняем строковое представление времени, отбрасывая миллисекунды!
                val localStartTimesStr = localTrips.map { sdfFull.format(Date(it.startTime)) }.toSet()

                if (fileList.files.isNotEmpty()) {
                    val tempFile = java.io.File(cacheDir, "temp_sync.csv")
                    java.io.FileOutputStream(tempFile).use { outputStream ->
                        driveService.files().get(fileList.files[0].id).executeMediaAndDownloadTo(outputStream)
                    }

                    val lines = tempFile.readLines()
                    if (lines.size > 1) {
                        for (i in 1 until lines.size) {
                            val cols = lines[i].split(",")
                            if (cols.size >= 7) {
                                try {
                                    val csvTimeStr = "${cols[0]} ${cols[1]}"
                                    // Проверяем по СТРОКЕ, а не по Long, чтобы избежать конфликта миллисекунд
                                    if (!localStartTimesStr.contains(csvTimeStr)) {
                                        val startMs = sdfFull.parse(csvTimeStr)?.time ?: continue
                                        var endMs = sdfFull.parse("${cols[0]} ${cols[2]}")?.time ?: continue
                                        if (endMs < startMs) endMs += 86400000L
                                        dbHelper.insertTrip(TripRecord(
                                            startTime = startMs, endTime = endMs,
                                            distanceMeters = (cols[3].toFloatOrNull() ?: 0f) * 1000f,
                                            maxSpeedKmh = cols[5].toFloatOrNull() ?: 0f,
                                            maxGForce = cols[6].toFloatOrNull() ?: 0f
                                        ))
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    }
                    tempFile.delete()
                }

                val updatedTrips = dbHelper.getAllTrips()
                val csvContent = java.lang.StringBuilder()
                csvContent.append("Date,Start Time,End Time,Distance (km),Avg Speed (km/h),Max Speed (km/h),Max G-Force\n")
                val sdfDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val sdfTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

                updatedTrips.forEach { t ->
                    val dateStr = sdfDate.format(Date(t.startTime))
                    val startStr = sdfTime.format(Date(t.startTime))
                    val endStr = sdfTime.format(Date(t.endTime))
                    val distKm = t.distanceMeters / 1000f
                    val timeHours = (t.endTime - t.startTime) / 3600000f
                    val avgSpeed = if (timeHours > 0) distKm / timeHours else 0f
                    csvContent.append(String.format(Locale.US, "%s,%s,%s,%.2f,%.1f,%.1f,%.2f\n", dateStr, startStr, endStr, distKm, avgSpeed, t.maxSpeedKmh, t.maxGForce))
                }

                val tempOutFile = java.io.File(cacheDir, "temp_cloud_backup.csv")
                tempOutFile.writeText(csvContent.toString())
                val fileContent = FileContent("text/csv", tempOutFile)

                if (fileList.files.isNotEmpty()) {
                    driveService.files().update(fileList.files[0].id, null, fileContent).execute()
                } else {
                    val fileMetadata = com.google.api.services.drive.model.File().apply {
                        name = "trip_journal_backup.csv"
                        parents = listOf("appDataFolder")
                    }
                    driveService.files().create(fileMetadata, fileContent).execute()
                }
                tempOutFile.delete()

                withContext(Dispatchers.Main) {
                    progressDialog?.dismiss()
                    cloudDialog?.dismiss()
                    if (!isSilent) Toast.makeText(this@TripJournalActivity, if (isRu) "Синхронизировано!" else "Synced!", Toast.LENGTH_SHORT).show()
                    loadDataAndRender()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog?.dismiss()
                    if (!isSilent) Toast.makeText(this@TripJournalActivity, "Sync Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun openCloudSyncDialog() {
        cloudDialog?.dismiss()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(64, 64, 64, 64); gravity = Gravity.CENTER_HORIZONTAL
        }

        val tvStatus = TextView(this).apply {
            text = if (googleAccount != null) "👤 ${googleAccount?.email}" else getString(R.string.cloud_status_not_signed)
            textSize = 16f; setTypeface(null, Typeface.BOLD)
            setTextColor(if (googleAccount != null) "#66BB6A".toColorInt() else Color.GRAY)
            setPadding(0, 0, 0, 48)
        }
        layout.addView(tvStatus)

        val btnAuth = Button(this).apply {
            text = if (googleAccount != null) getString(R.string.cloud_btn_sign_out) else getString(R.string.cloud_btn_sign_in)
            setBackgroundColor(if (googleAccount != null) "#333333".toColorInt() else "#4285F4".toColorInt())
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 48 }
            setOnClickListener {
                if (googleAccount != null) {
                    googleSignInClient.signOut().addOnCompleteListener {
                        googleAccount = null; openCloudSyncDialog(); Toast.makeText(this@TripJournalActivity, "Signed out", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    googleAuthLauncher.launch(googleSignInClient.signInIntent)
                }
            }
        }
        layout.addView(btnAuth)

        val btnSync = Button(this).apply {
            text = if (isRu) "🔄 Синхронизировать сейчас" else "🔄 Sync Now"
            isEnabled = googleAccount != null
            setBackgroundColor("#333333".toColorInt())
            setTextColor(if (googleAccount != null) Color.WHITE else Color.DKGRAY)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener { smartSync(isSilent = false) }
        }
        layout.addView(btnSync)

        cloudDialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.cloud_title))
            .setView(layout)
            .setPositiveButton(if (isRu) "ЗАКРЫТЬ" else "CLOSE", null)
            .show()
    }

    private fun importDatabaseFromCSV(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val lines = inputStream.bufferedReader().readLines()
                    if (lines.size <= 1) return@use

                    var importedCount = 0
                    val sdfFull = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

                    for (i in 1 until lines.size) {
                        val cols = lines[i].split(",")
                        if (cols.size >= 7) {
                            try {
                                val dateStr = cols[0]; val startStr = cols[1]; val endStr = cols[2]
                                val distKm = cols[3].toFloatOrNull() ?: 0f; val maxSpeed = cols[5].toFloatOrNull() ?: 0f; val maxG = cols[6].toFloatOrNull() ?: 0f

                                val startMs = sdfFull.parse("$dateStr $startStr")?.time ?: continue
                                var endMs = sdfFull.parse("$dateStr $endStr")?.time ?: continue
                                if (endMs < startMs) endMs += 86400000L

                                dbHelper.insertTrip(TripRecord(
                                    startTime = startMs, endTime = endMs,
                                    distanceMeters = distKm * 1000f, maxSpeedKmh = maxSpeed, maxGForce = maxG
                                ))
                                importedCount++
                            } catch (_: Exception) {}
                        }
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@TripJournalActivity, getString(R.string.msg_import_success, importedCount), Toast.LENGTH_LONG).show()
                        loadDataAndRender()
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(this@TripJournalActivity, getString(R.string.msg_import_error), Toast.LENGTH_SHORT).show() }
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
                    val dateStr = sdfDate.format(Date(t.startTime)); val startStr = sdfTime.format(Date(t.startTime)); val endStr = sdfTime.format(Date(t.endTime))
                    val distKm = t.distanceMeters / 1000f; val timeHours = (t.endTime - t.startTime) / 3600000f
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
                textSize = 16f; setTextColor(Color.GRAY); textAlignment = View.TEXT_ALIGNMENT_CENTER
                setPadding(0, 64, 0, 0)
            })
            renderFactoryResetButton()
            return
        }

        var totalOdoMeters = 0f
        allTrips.forEach { totalOdoMeters += it.distanceMeters }

        contentLayout.addView(TextView(this).apply {
            text = "ODO: ${String.format(Locale.US, "%.1f", totalOdoMeters / 1000f)} km"
            textSize = 26f; setTypeface(null, Typeface.BOLD); setTextColor("#E0E0E0".toColorInt())
            textAlignment = View.TEXT_ALIGNMENT_CENTER; setPadding(0, 0, 0, 16)
        })

        val prefs = getSharedPreferences("DashcamPrefs", MODE_PRIVATE)
        val tripBStartTime = prefs.getLong("trip_b_start", 0L)
        var tripBDist = 0f; var tripBTime = 0L
        allTrips.filter { it.startTime >= tripBStartTime }.forEach { tripBDist += it.distanceMeters; tripBTime += (it.endTime - it.startTime) }
        val tripBHours = tripBTime / 3600000f
        val tripBAvg = if (tripBHours > 0) (tripBDist / 1000f) / tripBHours else 0f

        val tripBLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, 0, 0, 32) }
        tripBLayout.addView(TextView(this).apply {
            text = String.format(Locale.US, "${if (isRu) "TRIP B (Пользовательский)" else "TRIP B (Custom)"}\n%.1f km  (∅ %.1f km/h)  ", tripBDist / 1000f, tripBAvg)
            textSize = 16f; setTypeface(null, Typeface.BOLD); textAlignment = View.TEXT_ALIGNMENT_CENTER; setTextColor("#FFB74D".toColorInt())
        })
        tripBLayout.addView(ImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(96, 96); setBackgroundColor(Color.TRANSPARENT)
            setImageResource(android.R.drawable.ic_popup_sync); setColorFilter("#FFB74D".toColorInt())
            setOnClickListener {
                AlertDialog.Builder(this@TripJournalActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(if (isRu) "Сбросить Trip B?" else "Reset Trip B?")
                    .setMessage(if (isRu) "Пробег Trip B начнется с нуля. Общий ODO не изменится." else "Trip B will start from zero.")
                    .setPositiveButton(if (isRu) "СБРОС" else "RESET") { _, _ -> prefs.edit { putLong("trip_b_start", System.currentTimeMillis()) }; loadDataAndRender() }
                    .setNegativeButton(if (isRu) "Отмена" else "Cancel", null).show()
            }
        })
        contentLayout.addView(tripBLayout)

        val filteredTrips = allTrips.filter { it.startTime in currentFilterStart..currentFilterEnd }
        var periodDist = 0f; var periodTime = 0L
        filteredTrips.forEach { periodDist += it.distanceMeters; periodTime += (it.endTime - it.startTime) }
        val periodHours = periodTime / 3600000f
        val periodAvgSpeed = if (periodHours > 0) (periodDist / 1000f) / periodHours else 0f

        contentLayout.addView(TextView(this).apply {
            text = String.format(Locale.US, "${if (isRu) "TRIP A (Суточный/Период)" else "TRIP A (Daily/Period)"}\n%.1f km  (∅ %.1f km/h)", periodDist / 1000f, periodAvgSpeed)
            textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor("#66BB6A".toColorInt())
            textAlignment = View.TEXT_ALIGNMENT_CENTER; setPadding(0, 0, 0, 24)
        })

        if (filteredTrips.isEmpty()) {
            contentLayout.addView(TextView(this).apply {
                text = if (isRu) "В этом периоде нет поездок." else "No trips in this period."
                textSize = 14f; setTextColor(Color.DKGRAY); textAlignment = View.TEXT_ALIGNMENT_CENTER; setPadding(0, 32, 0, 0)
            })
        } else {
            val sdfDay = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()); val sdfTime = SimpleDateFormat("HH:mm", Locale.getDefault())
            filteredTrips.groupBy { sdfDay.format(Date(it.startTime)) }.forEach { (dayString, dayTrips) ->
                var dayDist = 0f; var dayTime = 0L
                dayTrips.forEach { dayDist += it.distanceMeters; dayTime += (it.endTime - it.startTime) }
                val dayDistKm = dayDist / 1000f; val dayHours = dayTime / 3600000f
                val dayAvgSpeed = if (dayHours > 0) dayDistKm / dayHours else 0f

                val card = CardView(this).apply { setCardBackgroundColor("#1E1E1E".toColorInt()); radius = 24f; setContentPadding(40, 40, 40, 40); useCompatPadding = true }
                val cardLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                cardLayout.addView(TextView(this).apply {
                    text = "📅 $dayString\n⏱ ${dayTime / 3600000}h ${(dayTime % 3600000) / 60000}m  |  🚗 ${String.format(Locale.US, "%.1f", dayDistKm)} km  |  ∅ ${String.format(Locale.US, "%.1f", dayAvgSpeed)} km/h"
                    setTextColor(Color.WHITE); textSize = 15f; setTypeface(null, Typeface.BOLD)
                })

                val detailsLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(0, 32, 0, 0) }
                dayTrips.forEach { t ->
                    detailsLayout.addView(TextView(this).apply {
                        text = "🔹 ${sdfTime.format(Date(t.startTime))} - ${sdfTime.format(Date(t.endTime))}  |  ${String.format(Locale.US, "%.1f", t.distanceMeters / 1000f)} km\n     Max: ${t.maxSpeedKmh.toInt()} km/h  |  Max G: ${String.format(Locale.US, "%.2f", t.maxGForce)}"
                        setTextColor(Color.LTGRAY); textSize = 14f; setPadding(0, 16, 0, 16)
                    })
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
            setTextColor(Color.RED); setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 96, 0, 48) }
            setOnClickListener {
                AlertDialog.Builder(this@TripJournalActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(if (isRu) "ВНИМАНИЕ" else "WARNING")
                    .setMessage(if (isRu) "Удалить всю базу данных?" else "Wipe the entire database?")
                    .setPositiveButton(if (isRu) "ДА" else "YES") { _, _ ->
                        dbHelper.clearAllTrips(); getSharedPreferences("DashcamPrefs", MODE_PRIVATE).edit { putLong("trip_b_start", 0L) }
                        Toast.makeText(context, "Database wiped", Toast.LENGTH_SHORT).show()
                        loadDataAndRender()
                    }
                    .setNegativeButton(if (isRu) "ОТМЕНА" else "CANCEL", null).show()
            }
        })
    }
}