package com.example.mydashcam.utils

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.example.mydashcam.R

object StorageManager {

    // Умная проверка: доступен ли настроенный внешний диск прямо сейчас
    fun getEffectiveStorageUri(context: Context): String? {
        val customUriStr = context.getSharedPreferences("DashcamPrefs", Context.MODE_PRIVATE).getString("pref_custom_storage_uri", "")
        if (customUriStr.isNullOrEmpty()) return null

        try {
            val treeUri = customUriStr.toUri()
            val docDir = DocumentFile.fromTreeUri(context, treeUri)
            // Если диск физически подключен, он существует и доступен для записи
            if (docDir != null && docDir.exists() && docDir.canWrite()) {
                return customUriStr
            }
        } catch (_: Exception) {}

        // Если флешку вытащили или нет прав — откатываемся на внутреннюю память
        return null
    }

    fun checkStorageSpace(context: Context): Boolean {
        try {
            val effectiveUriStr = getEffectiveStorageUri(context)

            val stat = if (effectiveUriStr == null) {
                StatFs(Environment.getExternalStorageDirectory().path)
            } else {
                val treeUri = effectiveUriStr.toUri()
                val docFile = DocumentFile.fromTreeUri(context, treeUri)

                val pfd = context.contentResolver.openFileDescriptor(docFile!!.uri, "r")
                val fdStat = StatFs("/proc/self/fd/${pfd!!.fd}")
                pfd.close()
                fdStat
            }

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
            val effectiveUriStr = getEffectiveStorageUri(context)

            if (effectiveUriStr == null) {
                context.contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME),
                    "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?",
                    arrayOf("%Movies/MyDashcam%", "BVR_PRO_%"),
                    "${MediaStore.Video.Media.DATE_ADDED} ASC"
                )?.use { c ->
                    var toDel = c.count - limit + 1
                    while (c.moveToNext() && toDel > 0) {
                        val id = c.getLong(0)
                        val name = c.getString(1)
                        val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

                        context.contentResolver.delete(uri, null, null)
                        deleteCompanionSrt(context, name)
                        toDel--
                    }
                }
            } else {
                val treeUri = effectiveUriStr.toUri()
                val docDir = DocumentFile.fromTreeUri(context, treeUri)

                val files = docDir?.listFiles()?.filter { it.name?.startsWith("BVR_PRO_") == true }?.sortedBy { it.lastModified() } ?: emptyList()
                var toDel = files.size - limit + 1

                for (file in files) {
                    if (toDel <= 0) break
                    val name = file.name ?: continue
                    file.delete()
                    docDir?.findFile(name.replace(".mp4", ".srt"))?.delete()
                    toDel--
                }
            }
        } catch (_: Exception) {}
    }

    fun getFilesCount(context: Context): Int {
        var count = 0
        try {
            val effectiveUriStr = getEffectiveStorageUri(context)
            if (effectiveUriStr == null) {
                context.contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Video.Media._ID),
                    "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?",
                    arrayOf("%Movies/MyDashcam%", "BVR_PRO_%"),
                    null
                )?.use { c -> count = c.count }
            } else {
                val treeUri = effectiveUriStr.toUri()
                val docDir = DocumentFile.fromTreeUri(context, treeUri)
                count = docDir?.listFiles()?.count { it.name?.startsWith("BVR_PRO_") == true } ?: 0
            }
        } catch (_: Exception) {}
        return count
    }

    fun deleteCompanionSrt(context: Context, videoName: String) {
        val srtName = videoName.substringBeforeLast(".") + ".srt"
        try {
            val uri = MediaStore.Files.getContentUri("external")
            context.contentResolver.delete(uri, "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ?", arrayOf(srtName))
        } catch (_: Exception) {}
    }

    fun renameCompanionSrt(context: Context, oldVideoName: String, newVideoName: String) {
        val oldSrt = oldVideoName.substringBeforeLast(".") + ".srt"
        val newSrt = newVideoName.substringBeforeLast(".") + ".srt"
        try {
            val uri = MediaStore.Files.getContentUri("external")
            context.contentResolver.query(
                uri, arrayOf(MediaStore.Files.FileColumns._ID),
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ?", arrayOf(oldSrt), null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val srtUri = ContentUris.withAppendedId(uri, c.getLong(0))
                    val cv = ContentValues().apply { put(MediaStore.Files.FileColumns.DISPLAY_NAME, newSrt) }
                    context.contentResolver.update(srtUri, cv, null, null)
                }
            }
        } catch (_: Exception) {}
    }
}