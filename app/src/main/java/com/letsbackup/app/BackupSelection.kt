package com.letsbackup.app

data class BackupSelection(
    val items: List<MediaItem>,
    val selectedAlbums: List<String>,
    val fromDate: Long? = null,
    val toDate: Long? = null
) {
    val totalBytes: Long get() = items.sumOf { it.size }
}
