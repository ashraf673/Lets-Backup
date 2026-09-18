package com.letsbackup.app.restore

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.letsbackup.app.archive.BackupFormat
import com.letsbackup.app.util.AppLog
import org.json.JSONObject
import java.io.BufferedInputStream
import java.util.zip.ZipInputStream

data class BackupInfo(
    val uri: Uri,
    val fileName: String,
    val totalFiles: Int,
    val totalBytes: Long,
    val createdAt: Long,
    val format: String,
    val version: Int
)

object RestoreHelper {

    fun isValidLetsBackup(context: Context, uri: Uri): BackupInfo? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(BufferedInputStream(input)).use { zis ->
                    var entry = zis.nextEntry
                    var manifestJson: String? = null
                    while (entry != null) {
                        if (entry.name == BackupFormat.MANIFEST) {
                            manifestJson = zis.readBytes().toString(Charsets.UTF_8)
                            break
                        }
                        entry = zis.nextEntry
                    }
                    if (manifestJson == null) return null
                    val root = JSONObject(manifestJson)
                    if (root.optString("format") != BackupFormat.FORMAT) return null
                    BackupInfo(
                        uri = uri,
                        fileName = DocumentFile.fromSingleUri(context, uri)?.name ?: "backup.lb.zip",
                        totalFiles = root.optInt("totalFiles", 0),
                        totalBytes = root.optLong("totalBytes", 0),
                        createdAt = root.optLong("createdAt", 0),
                        format = root.optString("format"),
                        version = root.optInt("version", 1)
                    )
                }
            }
        } catch (e: Exception) {
            AppLog.e("RestoreHelper", "Invalid archive", e)
            null
        }
    }
}
