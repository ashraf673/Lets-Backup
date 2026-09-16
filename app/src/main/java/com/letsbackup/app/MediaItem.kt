package com.letsbackup.app

data class MediaItem(
    val uriString: String,
    val relativePath: String,
    val displayName: String,
    val mimeType: String,
    val size: Long,
    val dateTaken: Long,
    val dateModified: Long
)
