package com.letsbackup.app.archive

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.letsbackup.app.model.BackupSelection
import com.letsbackup.app.model.ManifestFile
import com.letsbackup.app.model.BackupManifest
import com.letsbackup.app.model.MediaItem
import com.letsbackup.app.storage.DestinationHelper
import com.letsbackup.app.util.PathSafety
import com.letsbackup.app.util.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes lossless .lb.zip archive(s).
 *
 * SD cards are often FAT32 → max ~4GB per file (EFBIG).
 * We split into multiple volumes under [MAX_VOLUME_BYTES] so backups work on SD.
 */
class ArchiveWriter(
    private val context: Context,
    private val selection: BackupSelection,
    private val destinationTreeUri: Uri,
    private val onProgress: (currentFile: Int, totalFiles: Int, bytesCopied: Long, totalBytes: Long, stage: String) -> Unit
) {
    private val buffer = ByteArray(512 * 1024)

    companion object {
        /** ~3.5 GB — safe under FAT32 4 GiB limit (with room for zip overhead). */
        const val MAX_VOLUME_BYTES = 3_500_000_000L
        /** Hard ceiling: a single media file larger than this cannot be stored on FAT32. */
        const val FAT32_HARD_LIMIT = 4_000_000_000L
    }

    fun createArchive(): Result {
        AppLog.i(
            "ArchiveWriter",
            "createArchive started - ${selection.totalFiles} files, ${selection.totalBytes} bytes"
        )
        val tree = DocumentFile.fromTreeUri(context, destinationTreeUri)
            ?: return Result.Error("Cannot access selected destination folder")

        val totalBytes = selection.totalBytes
        val totalFiles = selection.items.size
        if (totalFiles == 0) return Result.Error("No files to backup")

        val tooBig = selection.items.firstOrNull { it.size >= FAT32_HARD_LIMIT }
        if (tooBig != null) {
            return Result.Error(
                "File too large for SD card (FAT32 max ~4 GB): ${tooBig.displayName} " +
                    "(${tooBig.size / (1024 * 1024)} MB). Back up to internal storage or reformat SD as exFAT."
            )
        }

        val baseName = DestinationHelper.createBackupBaseName()
        val volumes = planVolumes(selection.items)
        AppLog.i("ArchiveWriter", "Planned ${volumes.size} volume(s) for $totalFiles files")

        val writtenNames = mutableListOf<String>()
        val writtenUris = mutableListOf<Uri>()
        var globalIndex = 0
        var totalCopied = 0L

        try {
            volumes.forEachIndexed { volIndex, volumeItems ->
                val partLabel = if (volumes.size == 1) "" else ".part${String.format("%03d", volIndex + 1)}"
                val fileName = "${baseName}${partLabel}${BackupFormat.EXTENSION}"
                val docFile = tree.createFile("application/zip", fileName)
                    ?: return Result.Error("Cannot create backup file: $fileName. Check folder permissions.")

                val checksumLines = mutableListOf<String>()
                val manifestFiles = mutableListOf<ManifestFile>()
                var volumeCopied = 0L

                onProgress(
                    globalIndex.coerceAtLeast(1),
                    totalFiles,
                    totalCopied,
                    totalBytes,
                    "Writing volume ${volIndex + 1}/${volumes.size}"
                )

                try {
                    context.contentResolver.openOutputStream(docFile.uri)?.use { rawOut ->
                        ZipOutputStream(BufferedOutputStream(rawOut, 256 * 1024)).use { zos ->
                            volumeItems.forEach { item ->
                                globalIndex++
                                onProgress(
                                    globalIndex,
                                    totalFiles,
                                    totalCopied,
                                    totalBytes,
                                    "Copying ${item.displayName}"
                                )

                                val entryName = PathSafety.safeRelativePath(item.relativePath)?.toString()
                                    ?: item.displayName.replace("/", "_")
                                val uri = Uri.parse(item.uriString)

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
                                    return@forEach
                                }

                                if (fileSize >= FAT32_HARD_LIMIT) {
                                    throw java.io.IOException(
                                        "File too large for this storage (max ~4 GB): ${item.displayName}"
                                    )
                                }

                                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                                val entry = ZipEntry(entryName)
                                entry.method = ZipEntry.STORED
                                entry.size = fileSize
                                entry.compressedSize = fileSize
                                entry.crc = crc.value

                                zos.putNextEntry(entry)
                                context.contentResolver.openInputStream(uri)?.use { input ->
                                    var read: Int
                                    while (input.read(buffer).also { read = it } != -1) {
                                        zos.write(buffer, 0, read)
                                    }
                                }
                                zos.closeEntry()

                                volumeCopied += fileSize
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

                            onProgress(globalIndex, totalFiles, totalCopied, totalBytes, "Writing checksums")
                            writeStoredEntry(
                                zos,
                                BackupFormat.CHECKSUMS,
                                checksumLines.joinToString("\n").toByteArray(Charsets.UTF_8)
                            )

                            onProgress(globalIndex, totalFiles, totalCopied, totalBytes, "Writing manifest")
                            val manifest = BackupManifest(
                                createdAt = System.currentTimeMillis(),
                                selectionMode = "manual",
                                selectedAlbums = selection.selectedAlbums,
                                fromDate = selection.fromDate,
                                toDate = selection.toDate,
                                includePhotos = selection.includePhotos,
                                includeVideos = selection.includeVideos,
                                totalFiles = manifestFiles.size,
                                totalBytes = volumeCopied,
                                files = manifestFiles
                            )
                            writeStoredEntry(
                                zos,
                                BackupFormat.MANIFEST,
                                buildManifestJson(
                                    manifest,
                                    volumeIndex = volIndex + 1,
                                    volumeCount = volumes.size,
                                    baseName = baseName
                                ).toByteArray(Charsets.UTF_8)
                            )
                        }
                    } ?: return Result.Error("Cannot open output stream for $fileName")
                } catch (e: Exception) {
                    try {
                        docFile.delete()
                    } catch (_: Exception) {
                    }
                    throw e
                }

                writtenNames.add(fileName)
                writtenUris.add(docFile.uri)
                AppLog.i(
                    "ArchiveWriter",
                    "Volume ${volIndex + 1}/${volumes.size} done: $fileName (${volumeCopied} bytes, ${volumeItems.size} files)"
                )
            }

            onProgress(totalFiles, totalFiles, totalCopied, totalBytes, "Backup completed")
            AppLog.i("ArchiveWriter", "Archive created successfully: ${writtenNames.joinToString()}")
            return Result.Success(
                uris = writtenUris,
                fileNames = writtenNames,
                volumeCount = writtenNames.size
            )
        } catch (e: Exception) {
            writtenUris.forEach { uri ->
                try {
                    DocumentFile.fromSingleUri(context, uri)?.delete()
                } catch (_: Exception) {
                }
            }
            AppLog.e("ArchiveWriter", "Archive failed", e)
            val msg = e.message ?: "Backup failed"
            return Result.Error(
                if (msg.contains("EFBIG", ignoreCase = true) || msg.contains("File too large", ignoreCase = true)) {
                    "File too large for this storage (often SD card FAT32 limit ~4 GB). " +
                        "The app splits backups automatically — if this still appears, try internal storage or reformat SD as exFAT. ($msg)"
                } else {
                    msg
                }
            )
        }
    }

    private fun planVolumes(items: List<MediaItem>): List<List<MediaItem>> {
        val volumes = mutableListOf<MutableList<MediaItem>>()
        var current = mutableListOf<MediaItem>()
        var currentBytes = 0L

        for (item in items) {
            val size = item.size.coerceAtLeast(0)
            if (current.isNotEmpty() && currentBytes + size > MAX_VOLUME_BYTES) {
                volumes.add(current)
                current = mutableListOf()
                currentBytes = 0L
            }
            current.add(item)
            currentBytes += size
        }
        if (current.isNotEmpty()) volumes.add(current)
        return volumes
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

    private fun buildManifestJson(
        m: BackupManifest,
        volumeIndex: Int,
        volumeCount: Int,
        baseName: String
    ): String {
        val root = JSONObject()
        root.put("format", m.format)
        root.put("version", m.version)
        root.put("createdAt", m.createdAt)
        root.put("selectionMode", m.selectionMode)
        root.put("selectedAlbums", JSONArray(m.selectedAlbums))
        root.put("fromDate", m.fromDate ?: JSONObject.NULL)
        root.put("toDate", m.toDate ?: JSONObject.NULL)
        root.put("includePhotos", m.includePhotos)
        root.put("includeVideos", m.includeVideos)
        root.put("totalFiles", m.totalFiles)
        root.put("totalBytes", m.totalBytes)
        root.put("volumeIndex", volumeIndex)
        root.put("volumeCount", volumeCount)
        root.put("baseName", baseName)
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
        data class Success(
            val uris: List<Uri>,
            val fileNames: List<String>,
            val volumeCount: Int
        ) : Result() {
            val fileName: String
                get() = if (fileNames.size == 1) fileNames.first()
                else "${volumeCount} parts: ${fileNames.joinToString(", ")}"
        }

        data class Error(val message: String) : Result()
    }
}
