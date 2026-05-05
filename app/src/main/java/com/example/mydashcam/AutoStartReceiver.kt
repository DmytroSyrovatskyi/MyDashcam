package com.example.mydashcam

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.ContextCompat

class AutoStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("DashcamPrefs", Context.MODE_PRIVATE)

        val autoPower = prefs.getBoolean("pref_autostart", false)
        val autoBt = prefs.getBoolean("pref_autostart_bt", false)
        val targetMac = prefs.getString("pref_bt_mac", "")

        // Идеально чистый код для Android 13+
        val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                if (autoPower) sendCommand(context, CameraService.ACTION_START, "Boot: Starting")
            }
            Intent.ACTION_POWER_CONNECTED -> {
                if (autoPower) sendCommand(context, CameraService.ACTION_START, "Power Connected: Starting")
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                if (autoPower) sendCommand(context, CameraService.ACTION_STOP, "Power Disconnected: Stopping")
            }
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                if (autoBt && targetMac == device?.address) {
                    sendCommand(context, CameraService.ACTION_START, "Bluetooth Connected: Starting")
                }
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                if (autoBt && targetMac == device?.address) {
                    sendCommand(context, CameraService.ACTION_STOP, "Bluetooth Disconnected: Stopping")
                }
            }
            "com.example.mydashcam.START_RECORDING" -> sendCommand(context, CameraService.ACTION_START, "MacroDroid: Starting")
            "com.example.mydashcam.STOP_RECORDING" -> sendCommand(context, CameraService.ACTION_STOP, "MacroDroid: Stopping")
        }
    }

    private fun sendCommand(context: Context, action: String, msg: String) {
        try {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            val serviceIntent = Intent(context, CameraService::class.java).apply {
                this.action = action
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (_: Exception) {}
    }
}