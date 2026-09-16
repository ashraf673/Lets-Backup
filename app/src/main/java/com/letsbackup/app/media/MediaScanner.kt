package com.letsbackup.app.media

import android.content.ContentResolver
import android.provider.MediaStore
import com.letsbackup.app.model.MediaItem

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
                resolver.query(
                    collection, projection, null, null,
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                )?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                    val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val modifiedCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    val relativeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                    val takenCol = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)

                    while (c.moveToNext()) {
                        val mediaId = c.getLong(idCol)
                        val isVideo = collection == MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        val capture = if (!isVideo && takenCol >= 0 && !c.isNull(takenCol)) {
                            c.getLong(takenCol)
                        } else 0L

                        val name = c.getString(nameCol) ?: ""
                        val relative = c.getString(relativeCol) ?: ""

                        result += MediaItem(
                            uriString = MediaStore.Files.getContentUri("external", mediaId).toString(),
                            relativePath = relative + name,
                            displayName = name,
                            mimeType = c.getString(mimeCol) ?: "",
                            size = c.getLong(sizeCol),
                            dateTaken = capture,
                            dateModified = c.getLong(modifiedCol) * 1000L
                        )
                    }
                }
            } catch (_: Exception) { }
        }
        return result
    }

    fun groupByAlbum(items: List<MediaItem>): Map<String, List<MediaItem>> {
        return items.groupBy { it.albumName }.toSortedMap()
    }
}
