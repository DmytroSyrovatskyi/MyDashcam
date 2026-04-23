package com.example.mydashcam

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.ContextCompat

class QuickStartActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        if (!hasCamera || !hasMic) {
            Toast.makeText(this, getString(R.string.toast_perms_needed), Toast.LENGTH_LONG).show()
            val mainIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(mainIntent)
            finish()
            return
        }

        val prefs = getSharedPreferences("DashcamPrefs", MODE_PRIVATE)
        val usePip = prefs.getBoolean("pref_pip", false)

        if (!CameraService.isRecordingActive) {
            // Если мы не пишем и включен PiP — запускаем MainActivity с командой "свернуться"
            if (usePip) {
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    putExtra("auto_start_pip", true)
                }
                startActivity(intent)
                finish()
                return
            } else {
                // Если PiP выключен — просто стартуем в фоне
                val serviceIntent = Intent(this, CameraService::class.java).apply { action = CameraService.ACTION_START }
                ContextCompat.startForegroundService(this, serviceIntent)
                Toast.makeText(this, getString(R.string.toast_recording_started), Toast.LENGTH_SHORT).show()
            }
        } else {
            // Если уже пишем — просто останавливаем
            val serviceIntent = Intent(this, CameraService::class.java).apply { action = CameraService.ACTION_STOP }
            ContextCompat.startForegroundService(this, serviceIntent)
            Toast.makeText(this, getString(R.string.toast_recording_stopped), Toast.LENGTH_SHORT).show()
        }

        // Задержка полсекунды, чтобы Android 14 не убил сервис из-за слишком быстрого ухода в фон
        Handler(Looper.getMainLooper()).postDelayed({
            finish()
        }, 500)
    }
}