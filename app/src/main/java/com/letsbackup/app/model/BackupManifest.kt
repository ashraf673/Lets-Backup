package com.letsbackup.app.model

data class ManifestFile(
    val path: String,
    val size: Long,
    val mimeType: String,
    val dateModified: Long,
    val dateTaken: Long,
    val sha256: String
)

data class BackupManifest(
    val format: String = "Let's Backup Archive",
    val version: Int = 1,
    val createdAt: Long,
    val selectionMode: String,
    val selectedAlbums: List<String>,
    val fromDate: Long?,
    val toDate: Long?,
    val includePhotos: Boolean,
    val includeVideos: Boolean,
    val totalFiles: Int,
    val totalBytes: Long,
    val files: List<ManifestFile>
)
