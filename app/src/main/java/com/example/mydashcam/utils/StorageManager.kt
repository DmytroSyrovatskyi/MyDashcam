package com.example.mydashcam.utils

import android.content.ContentUris
import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.MediaStore
import android.widget.Toast
import com.example.mydashcam.R

object StorageManager {

    fun checkStorageSpace(context: Context): Boolean {
        try {
            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
            val freeMb = freeBytes / (1024 * 1024)

            if (freeMb < 500) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, context.getString(R.string.toast_storage_critical), Toast.LENGTH_LONG).show()
                }
                return false
            } else if (freeMb < 2000) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, context.getString(R.string.toast_storage_warning), Toast.LENGTH_SHORT).show()
                }
            }
        } catch (_: Exception) {}
        return true
    }

    fun manageLoopStorage(context: Context, limit: Int) {
        if (limit > 50) return
        try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media._ID),
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?",
                arrayOf("%Movies/MyDashcam%", "BVR_PRO_%"),
                "${MediaStore.Video.Media.DATE_ADDED} ASC"
            )?.use { c ->
                var toDel = c.count - limit + 1
                while (c.moveToNext() && toDel > 0) {
                    val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                    context.contentResolver.delete(uri, null, null)
                    toDel--
                }
            }
        } catch (_: Exception) {}
    }

    fun getFilesCount(context: Context): Int {
        var count = 0
        try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media._ID),
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?",
                arrayOf("%Movies/MyDashcam%", "BVR_PRO_%"),
                null
            )?.use { c -> count = c.count }
        } catch (_: Exception) {}
        return count
    }
}