package com.letsbackup.app.backup

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

object BackupState {
    private const val PREFS = "lets_backup_state"
    private const val KEY_IN_PROGRESS = "in_progress"
    private const val KEY_DEST_URI = "dest_uri"
    private const val KEY_FILE_NAME = "file_name"
    private const val KEY_TOTAL_FILES = "total_files"
    private const val KEY_COMPLETED_FILES = "completed_files"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun markStarted(ctx: Context, destUri: Uri, fileName: String, totalFiles: Int) {
        prefs(ctx).edit()
            .putBoolean(KEY_IN_PROGRESS, true)
            .putString(KEY_DEST_URI, destUri.toString())
            .putString(KEY_FILE_NAME, fileName)
            .putInt(KEY_TOTAL_FILES, totalFiles)
            .putInt(KEY_COMPLETED_FILES, 0)
            .apply()
    }

    fun updateProgress(ctx: Context, completed: Int) {
        prefs(ctx).edit().putInt(KEY_COMPLETED_FILES, completed).apply()
    }

    fun markFinished(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }

    fun isInProgress(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_IN_PROGRESS, false)

    fun getDestUri(ctx: Context): String? =
        prefs(ctx).getString(KEY_DEST_URI, null)

    fun getFileName(ctx: Context): String? =
        prefs(ctx).getString(KEY_FILE_NAME, null)

    fun getTotalFiles(ctx: Context): Int =
        prefs(ctx).getInt(KEY_TOTAL_FILES, 0)

    fun getCompletedFiles(ctx: Context): Int =
        prefs(ctx).getInt(KEY_COMPLETED_FILES, 0)
}
