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

class ArchiveWriter(
    private val context: Context,
    private val selection: BackupSelection,
    private val destinationTreeUri: Uri,
    private val onProgress: (currentFile: Int, totalFiles: Int, bytesCopied: Long, totalBytes: Long, stage: String) -> Unit
) {
    private val buffer = ByteArray(512 * 1024)

    fun createArchive(): Result {
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

                        val inputStream = context.contentResolver.openInputStream(Uri.parse(item.uriString))
                            ?: return@forEachIndexed

                        val crc = CRC32()
                        val digest = MessageDigest.getInstance("SHA-256")
                        val chunks = mutableListOf<ByteArray>()
                        var fileSize = 0L

                        inputStream.use { input ->
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                val chunk = buffer.copyOf(read)
                                chunks.add(chunk)
                                crc.update(chunk)
                                digest.update(chunk)
                                fileSize += read
                            }
                        }

                        val sha = digest.digest().joinToString("") { "%02x".format(it) }

                        val entry = ZipEntry(entryName)
                        entry.method = ZipEntry.STORED
                        entry.size = fileSize
                        entry.compressedSize = fileSize
                        entry.crc = crc.value

                        zos.putNextEntry(entry)
                        chunks.forEach { zos.write(it) }
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

                    onProgress(totalFiles, totalFiles, totalCopied, totalBytes, "Writing checksums")
                    writeStoredEntry(zos, BackupFormat.CHECKSUMS, checksumLines.joinToString("\n").toByteArray(Charsets.UTF_8))

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
            return Result.Success(docFile.uri, fileName)

        } catch (e: Exception) {
            docFile.delete()
            return Result.Error(e.message ?: "Backup failed")
        }
    }

    private fun writeStoredEntry(zos: ZipOutputStream, name: String, data: ByteArray) {
        val entry = ZipEntry(name)
        entry.method = ZipEntry.STORED
        entry.size = data.size.toLong()
        entry.compressedSize = data.size.toLong()
        val crc = CRC32()
        crc.update(data)
        entry.crc = crc.value
        zos.putNextEntry(entry)
        zos.write(data)
        zos.closeEntry()
    }

    private fun buildManifestJson(m: BackupManifest): String {
        val root = JSONObject()
        root.put("format", m.format)
        root.put("version", m.version)
        root.put("createdAt", m.createdAt)
        root.put("selectionMode", m.selectionMode)
        root.put("selectedAlbums", JSONArray(m.selectedAlbums))
        root.put("fromDate", m.fromDate)
        root.put("toDate", m.toDate)
        root.put("includePhotos", m.includePhotos)
        root.put("includeVideos", m.includeVideos)
        root.put("totalFiles", m.totalFiles)
        root.put("totalBytes", m.totalBytes)

        val filesArr = JSONArray()
        m.files.forEach { f ->
            val obj = JSONObject()
            obj.put("path", f.path)
            obj.put("size", f.size)
            obj.put("mimeType", f.mimeType)
            obj.put("dateModified", f.dateModified)
            obj.put("dateTaken", f.dateTaken)
            obj.put("sha256", f.sha256)
            filesArr.put(obj)
        }
        root.put("files", filesArr)
        return root.toString(2)
    }

    sealed class Result {
        data class Success(val uri: Uri, val fileName: String) : Result()
        data class Error(val message: String) : Result()
    }
}
