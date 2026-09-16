package com.letsbackup.app.model

data class BackupSelection(
    val items: List<MediaItem>,
    val selectedAlbums: List<String> = emptyList(),
    val fromDate: Long? = null,
    val toDate: Long? = null,
    val includePhotos: Boolean = true,
    val includeVideos: Boolean = true
) {
    val totalBytes: Long get() = items.sumOf { it.size }
    val photoCount: Int get() = items.count { it.isImage }
    val videoCount: Int get() = items.count { it.isVideo }
    val totalFiles: Int get() = items.size
}
