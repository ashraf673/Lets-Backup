package com.letsbackup.app.storage

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DestinationHelper {

    data class DestinationInfo(
        val name: String,
        val freeBytes: Long,
        val totalBytes: Long,
        val type: Type
    ) {
        enum class Type { INTERNAL, SD_CARD, USB, SAF }
    }

    fun getInternalStorageInfo(): DestinationInfo {
        val path = Environment.getExternalStorageDirectory()
        val stat = StatFs(path.path)
        return DestinationInfo(
            name = "Internal Storage",
            freeBytes = stat.availableBytes,
            totalBytes = stat.totalBytes,
            type = DestinationInfo.Type.INTERNAL
        )
    }

    fun createBackupFileName(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
        return "LetsBackup_${sdf.format(Date())}.lb.zip"
    }
}
