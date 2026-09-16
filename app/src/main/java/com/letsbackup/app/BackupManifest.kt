package com.letsbackup.app

data class ManifestFile(
    val path: String,
    val size: Long,
    val mimeType: String,
    val dateModified: Long,
    val dateTaken: Long,
    val sha256: String
)

data class BackupManifest(
    val format: String = BackupFormat.FORMAT,
    val version: Int = BackupFormat.VERSION,
    val createdAt: Long,
    val selectionMode: String,
    val selectedAlbums: List<String>,
    val fromDate: Long?,
    val toDate: Long?,
    val totalFiles: Int,
    val totalBytes: Long,
    val files: List<ManifestFile>
)
