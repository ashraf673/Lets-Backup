package com.letsbackup.app.archive

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.letsbackup.app.model.BackupSelection
import com.letsbackup.app.model.ManifestFile
import com.letsbackup.app.model.BackupManifest
import com.letsbackup.app.storage.DestinationHelper
import com.letsbackup.app.util.PathSafety
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.letsbackup.app.util.AppLog

class ArchiveWriter(
    private val context: Context,
    private val selection: BackupSelection,
    private val destinationTreeUri: Uri,
    private val onProgress: (currentFile: Int, totalFiles: Int, bytesCopied: Long, totalBytes: Long, stage: String) -> Unit
) {
    private val buffer = ByteArray(512 * 1024)

    fun createArchive(): Result {
        AppLog.i("ArchiveWriter", "createArchive started - ${selection.totalFiles} files, ${selection.totalBytes} bytes")
        val tree = DocumentFile.fromTreeUri(context, destinationTreeUri)
            ?: return Result.Error("Cannot access selected destination folder")

        val fileName = DestinationHelper.createBackupFileName()
        val docFile = tree.createFile("application/zip", fileName)
            ?: return Result.Error("Cannot create backup file. Check folder permissions.")

        val checksumLines = mutableListOf<String>()
        val manifestFiles = mutableListOf<ManifestFile>()
        var totalCopied = 0L
        val totalBytes = selection.totalBytes
        val totalFiles = selection.items.size

        try {
            context.contentResolver.openOutputStream(docFile.uri)?.use { rawOut ->
                ZipOutputStream(BufferedOutputStream(rawOut, 256 * 1024)).use { zos ->

                    selection.items.forEachIndexed { index, item ->
                        onProgress(index + 1, totalFiles, totalCopied, totalBytes, "Copying ${item.displayName}")

                        val entryName = PathSafety.safeRelativePath(item.relativePath)?.toString()
                            ?: item.displayName.replace("/", "_")

                        val uri = Uri.parse(item.uriString)

                        // Pass 1: stream once to compute CRC + SHA-256 + size (no full-file in RAM → no OOM)
                        val crc = CRC32()
                        val digest = MessageDigest.getInstance("SHA-256")
                        var fileSize = 0L
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                crc.update(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                fileSize += read
                            }
                        } ?: run {
                            AppLog.e("ArchiveWriter", "Cannot open: ${item.displayName}")
                            return@forEachIndexed
                        }

                        val sha = digest.digest().joinToString("") { "%02x".format(it) }

                        val entry = ZipEntry(entryName)
                        entry.method = ZipEntry.STORED
                        entry.size = fileSize
                        entry.compressedSize = fileSize
                        entry.crc = crc.value

                        // Pass 2: stream again into the zip (constant memory)
                        zos.putNextEntry(entry)
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                zos.write(buffer, 0, read)
                            }
                        }
                        zos.closeEntry()

                        totalCopied += fileSize
                        checksumLines.add("$sha  $entryName")
                        manifestFiles.add(
                            ManifestFile(
                                path = entryName,
                                size = fileSize,
                                mimeType = item.mimeType,
                                dateModified = item.dateModified,
                                dateTaken = item.dateTaken,
                                sha256 = sha
                            )
                        )
                    }

                    // checksums.sha256
                    onProgress(totalFiles, totalFiles, totalCopied, totalBytes, "Writing checksums")
                    writeStoredEntry(zos, BackupFormat.CHECKSUMS, checksumLines.joinToString("\n").toByteArray(Charsets.UTF_8))

                    // manifest.json
                    onProgress(totalFiles, totalFiles, totalCopied, totalBytes, "Writing manifest")
                    val manifest = BackupManifest(
                        createdAt = System.currentTimeMillis(),
                        selectionMode = "manual",
                        selectedAlbums = selection.selectedAlbums,
                        fromDate = selection.fromDate,
                        toDate = selection.toDate,
                        includePhotos = selection.includePhotos,
                        includeVideos = selection.includeVideos,
                        totalFiles = totalFiles,
                        totalBytes = totalCopied,
                        files = manifestFiles
                    )
                    writeStoredEntry(zos, BackupFormat.MANIFEST, buildManifestJson(manifest).toByteArray(Charsets.UTF_8))
                }
            }

            onProgress(totalFiles, totalFiles, totalCopied, totalBytes, "Backup completed")
            AppLog.i("ArchiveWriter", "Archive created successfully: $fileName")
            return Result.Success(docFile.uri, fileName)

        } catch (e: Exception) {
            try { docFile.delete() } catch (_: Exception) {}
            AppLog.e("ArchiveWriter", "Archive failed", e)
            return Result.Error(e.message ?: "Backup failed")
        }
    }

    private fun writeStoredEntry(zos: ZipOutputStream, name: String, data: ByteArray) {
        val crc = CRC32()
        crc.update(data)
        val entry = ZipEntry(name)
        entry.method = ZipEntry.STORED
        entry.size = data.size.toLong()
        entry.compressedSize = data.size.toLong()
        entry.crc = crc.value
        zos.putNextEntry(entry)
        zos.write(data)
        zos.closeEntry()
    }

    private fun buildManifestJson(m: BackupManifest): String {
        val root = JSONObject()
        root.put("createdAt", m.createdAt)
        root.put("selectionMode", m.selectionMode)
        root.put("selectedAlbums", JSONArray(m.selectedAlbums))
        root.put("fromDate", m.fromDate ?: JSONObject.NULL)
        root.put("toDate", m.toDate ?: JSONObject.NULL)
        root.put("includePhotos", m.includePhotos)
        root.put("includeVideos", m.includeVideos)
        root.put("totalFiles", m.totalFiles)
        root.put("totalBytes", m.totalBytes)
        val arr = JSONArray()
        for (f in m.files) {
            val o = JSONObject()
            o.put("path", f.path)
            o.put("size", f.size)
            o.put("mimeType", f.mimeType)
            o.put("dateModified", f.dateModified)
            o.put("dateTaken", f.dateTaken)
            o.put("sha256", f.sha256)
            arr.put(o)
        }
        root.put("files", arr)
        return root.toString(2)
    }

    sealed class Result {
        data class Success(val uri: Uri, val fileName: String) : Result()
        data class Error(val message: String) : Result()
    }
}
