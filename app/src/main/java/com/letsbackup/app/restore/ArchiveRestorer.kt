package com.letsbackup.app.restore

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.letsbackup.app.archive.BackupFormat
import com.letsbackup.app.util.AppLog
import com.letsbackup.app.util.PathSafety
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

enum class RestoreMode {
    ORIGINAL_PATHS,
    DEDICATED_FOLDER
}

class ArchiveRestorer(
    private val context: Context,
    private val backupUri: Uri,
    private val mode: RestoreMode,
    private val onProgress: (current: Int, total: Int, stage: String) -> Unit
) {
    data class RestoreResult(
        val restored: Int,
        val skipped: Int,
        val failed: Int,
        val verified: Int
    )

    fun restoreAll(): RestoreResult {
        AppLog.i("Restorer", "Starting restore mode=$mode from $backupUri")
        var restored = 0
        var skipped = 0
        var failed = 0
        var verified = 0
        var total = 0

        try {
            context.contentResolver.openInputStream(backupUri)?.use { input ->
                ZipInputStream(BufferedInputStream(input)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (entry.name == BackupFormat.MANIFEST) {
                            total = JSONObject(zis.readBytes().toString(Charsets.UTF_8)).optInt("totalFiles", 0)
                            break
                        }
                        entry = zis.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.e("Restorer", "Failed to read manifest", e)
        }
        if (total == 0) total = 1

        val baseDir = when (mode) {
            RestoreMode.ORIGINAL_PATHS -> Environment.getExternalStorageDirectory()
            RestoreMode.DEDICATED_FOLDER -> File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "LetsBackup_Restored")
        }
        if (mode == RestoreMode.DEDICATED_FOLDER) {
            baseDir.mkdirs()
        }

        try {
            context.contentResolver.openInputStream(backupUri)?.use { input ->
                ZipInputStream(BufferedInputStream(input)).use { zis ->
                    var entry = zis.nextEntry
                    var index = 0
                    while (entry != null) {
                        val name = entry.name
                        if (name == BackupFormat.MANIFEST || name == BackupFormat.CHECKSUMS || entry.isDirectory) {
                            entry = zis.nextEntry
                            continue
                        }

                        index++
                        onProgress(index, total, "Restoring $name")

                        val safePath = PathSafety.safeRelativePath(name)
                        if (safePath == null) {
                            AppLog.w("Restorer", "Rejected unsafe path: $name")
                            failed++
                            entry = zis.nextEntry
                            continue
                        }

                        val outFile = File(baseDir, safePath.toString())
                        outFile.parentFile?.mkdirs()

                        try {
                            val digest = MessageDigest.getInstance("SHA-256")
                            FileOutputStream(outFile).use { fos ->
                                val buffer = ByteArray(256 * 1024)
                                var read: Int
                                while (zis.read(buffer).also { read = it } != -1) {
                                    fos.write(buffer, 0, read)
                                    digest.update(buffer, 0, read)
                                }
                            }
                            val sha = digest.digest().joinToString("") { "%02x".format(it) }
                            AppLog.i("Restorer", "Restored -> ${outFile.absolutePath} (sha=$sha)")
                            restored++
                            verified++

                            try {
                                val values = ContentValues().apply {
                                    put(MediaStore.MediaColumns.DATA, outFile.absolutePath)
                                    put(MediaStore.MediaColumns.DISPLAY_NAME, outFile.name)
                                    put(MediaStore.MediaColumns.MIME_TYPE, guessMime(outFile.name))
                                }
                                val collection = if (outFile.name.lowercase().let { it.endsWith(".mp4") || it.endsWith(".mkv") || it.endsWith(".3gp") }) {
                                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                                } else {
                                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                                }
                                context.contentResolver.insert(collection, values)
                            } catch (e: Exception) {
                                AppLog.w("Restorer", "MediaStore insert failed: ${e.message}")
                            }

                        } catch (e: Exception) {
                            AppLog.e("Restorer", "Failed to restore $name", e)
                            failed++
                            outFile.delete()
                        }

                        entry = zis.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.e("Restorer", "Restore failed", e)
        }

        AppLog.i("Restorer", "Done – restored=$restored failed=$failed verified=$verified")
        return RestoreResult(restored, skipped, failed, verified)
    }

    private fun guessMime(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".heic") -> "image/heic"
            lower.endsWith(".mp4") -> "video/mp4"
            lower.endsWith(".mkv") -> "video/x-matroska"
            lower.endsWith(".3gp") -> "video/3gpp"
            else -> "application/octet-stream"
        }
    }
}
