package com.letsbackup.app.model

data class MediaItem(
    val uriString: String,
    val relativePath: String,
    val displayName: String,
    val mimeType: String,
    val size: Long,
    val dateTaken: Long,
    val dateModified: Long
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")
    val isImage: Boolean get() = mimeType.startsWith("image/")
    
    val albumName: String
        get() {
            val path = relativePath.trimEnd('/')
            val lastSlash = path.lastIndexOf('/')
            return if (lastSlash > 0) path.substring(0, lastSlash) else "Other"
        }
}
