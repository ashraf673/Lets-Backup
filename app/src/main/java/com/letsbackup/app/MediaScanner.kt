package com.letsbackup.app

import android.content.ContentResolver
import android.provider.MediaStore

object MediaScanner {
    fun scanImagesAndVideos(resolver: ContentResolver): List<MediaItem> {
        val result = mutableListOf<MediaItem>()
        val collections = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.Images.Media.DATE_TAKEN
        )

        for (collection in collections) {
            try {
                resolver.query(collection, projection, null, null,
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { c ->
                    val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val name = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val mime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                    val size = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val modified = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    val relative = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                    val taken = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                    while (c.moveToNext()) {
                        val mediaId = c.getLong(id)
                        val isVideo = collection == MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        val capture = if (!isVideo && taken >= 0 && !c.isNull(taken)) c.getLong(taken) else 0L
                        result += MediaItem(
                            MediaStore.Files.getContentUri("external", mediaId).toString(),
                            (c.getString(relative) ?: "") + (c.getString(name) ?: ""),
                            c.getString(name) ?: "",
                            c.getString(mime) ?: "",
                            c.getLong(size),
                            capture,
                            c.getLong(modified) * 1000L
                        )
                    }
                }
            } catch (_: Exception) { }
        }
        return result
    }
}
