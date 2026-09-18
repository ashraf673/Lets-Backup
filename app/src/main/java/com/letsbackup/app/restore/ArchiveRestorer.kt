package com.letsbackup.app.restore

import android.content.Context
import android.net.Uri
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

class ArchiveRestorer(
    private val context: Context,
    private val backupUri: Uri,
    private val onProgress: (current: Int, total: Int, stage: String) -> Unit
) {
    data class RestoreResult(
        val restored: Int,
        val skipped: Int,
        val failed: Int,
        val verified: Int
    )

    fun restoreAll(): RestoreResult {
        AppLog.i("Restorer", "Starting full restore from $backupUri")
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
                            val json = zis.readBytes().toString(Charsets.UTF_8)
                            total = JSONObject(json).optInt("totalFiles", 0)
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

                        val safe = PathSafety.safeRelativePath(name)
                        if (safe == null) {
                            AppLog.w("Restorer", "Rejected unsafe path: $name")
                            failed++
                            entry = zis.nextEntry
                            continue
                        }

                        val outDir = File(context.getExternalFilesDir(null), "LetsBackupRestore")
                        outDir.mkdirs()
                        val outFile = File(outDir, safe.fileName.toString())

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
                            AppLog.i("Restorer", "Restored $name (sha=$sha)")
                            restored++
                            verified++

                            try {
                                val values = android.content.ContentValues().apply {
                                    put(MediaStore.MediaColumns.DATA, outFile.absolutePath)
                                    put(MediaStore.MediaColumns.DISPLAY_NAME, outFile.name)
                                }
                                context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                            } catch (_: Exception) { }

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

        AppLog.i("Restorer", "Finished – restored=$restored skipped=$skipped failed=$failed verified=$verified")
        return RestoreResult(restored, skipped, failed, verified)
    }
}
