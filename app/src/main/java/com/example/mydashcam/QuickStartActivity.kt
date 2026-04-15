package com.example.mydashcam

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat

class QuickStartActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Проверяем права. Если их нет — открываем основное окно
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

        // Права есть — переключаем запись
        val serviceIntent = Intent(this, CameraService::class.java)

        if (CameraService.isRecordingActive) {
            serviceIntent.action = CameraService.ACTION_STOP
            Toast.makeText(this, getString(R.string.toast_recording_stopped), Toast.LENGTH_SHORT).show()
        } else {
            serviceIntent.action = CameraService.ACTION_START
            Toast.makeText(this, getString(R.string.toast_recording_started), Toast.LENGTH_SHORT).show()
        }

        // Запускаем сервис (ContextCompat обязателен для современных Android)
        ContextCompat.startForegroundService(this, serviceIntent)

        // Мгновенно закрываем эту активити, чтобы не было видно интерфейса
        finish()
    }
}