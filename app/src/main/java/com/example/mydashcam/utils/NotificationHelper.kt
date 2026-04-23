package com.example.mydashcam.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.graphics.toColorInt
import com.example.mydashcam.CameraService
import com.example.mydashcam.MainActivity
import com.example.mydashcam.R

object NotificationHelper {

    private const val CHANNEL_ID = "DashcamChannel"

    fun createNotification(
        context: Context,
        isRecording: Boolean,
        cameraType: String,
        resolution: String,
        fps: Int,
        iso: Int,
        bitrateMbps: Int,
        recordingStartTime: Long,
        usePip: Boolean,
        progressPercent: Int
    ): Notification {

        val nm = context.getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel(CHANNEL_ID, "BVR", NotificationManager.IMPORTANCE_LOW))

        val camLabel = when (cameraType) {
            "front" -> context.getString(R.string.cam_front)
            "back" -> context.getString(R.string.cam_main)
            else -> context.getString(R.string.cam_wide)
        }
        val resLabel = if (resolution == "4k") "4K UHD" else "${resolution}p"

        val statsText = if (isRecording) {
            context.getString(R.string.notif_stats_format, camLabel, resLabel, fps, iso, bitrateMbps)
        } else {
            context.getString(R.string.notif_body_ready)
        }

        val notifTitle = if (isRecording) context.getString(R.string.notif_title_recording) else context.getString(R.string.notif_title_standby)

        val startPI = if (usePip) {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("auto_start_pip", true)
            }
            PendingIntent.getActivity(context, 11, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } else {
            val intent = Intent(context, CameraService::class.java).apply { action = CameraService.ACTION_START }
            PendingIntent.getService(context, 10, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        val stopPI = PendingIntent.getService(context, 1, Intent(context, CameraService::class.java).apply { action = CameraService.ACTION_STOP }, PendingIntent.FLAG_IMMUTABLE)
        val lockPI = PendingIntent.getService(context, 3, Intent(context, CameraService::class.java).apply { action = CameraService.ACTION_LOCK }, PendingIntent.FLAG_IMMUTABLE)
        val exitPI = PendingIntent.getService(context, 4, Intent(context, CameraService::class.java).apply { action = CameraService.ACTION_EXIT }, PendingIntent.FLAG_IMMUTABLE)

        val smallIconRes = if (isRecording) R.drawable.ic_recording_active else R.drawable.ic_cam_standby
        val colorActive = "#D32F2F".toColorInt()
        val colorStandby = "#388E3C".toColorInt()

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(notifTitle)
            .setContentText(statsText)
            .setSmallIcon(smallIconRes)
            .setOngoing(true)
            .setColorized(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setColor(if (isRecording) colorActive else colorStandby)

        if (progressPercent > 0) {
            builder.setProgress(100, progressPercent.coerceIn(0, 100), false)
        } else {
            builder.setProgress(0, 0, false)
        }

        if (isRecording) {
            builder.setUsesChronometer(true)
            builder.setWhen(recordingStartTime)
            builder.addAction(android.R.drawable.ic_media_pause, context.getString(R.string.btn_stop), stopPI)
            builder.addAction(android.R.drawable.ic_lock_lock, context.getString(R.string.btn_lock), lockPI)
        } else {
            builder.setUsesChronometer(false)
            builder.addAction(android.R.drawable.ic_media_play, context.getString(R.string.btn_start), startPI)
        }
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, context.getString(R.string.btn_exit), exitPI)

        return builder.build()
    }
}