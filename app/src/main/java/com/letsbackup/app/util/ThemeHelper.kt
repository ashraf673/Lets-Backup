package com.letsbackup.app.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object ThemeHelper {
    private const val PREFS = "lets_backup_theme"
    private const val KEY = "mode" // 0=system, 1=light, 2=dark

    fun applySaved(context: Context) {
        when (prefs(context).getInt(KEY, 0)) {
            1 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            2 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    fun cycle(context: Context): String {
        val next = (prefs(context).getInt(KEY, 0) + 1) % 3
        prefs(context).edit().putInt(KEY, next).apply()
        applySaved(context)
        return when (next) {
            1 -> "Light"
            2 -> "Dark"
            else -> "System"
        }
    }

    fun currentLabel(context: Context): String = when (prefs(context).getInt(KEY, 0)) {
        1 -> "Light"
        2 -> "Dark"
        else -> "System"
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
