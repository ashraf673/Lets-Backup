package com.letsbackup.app.util

import java.nio.file.Paths

object PathSafety {
    fun safeRelativePath(name: String): java.nio.file.Path? {
        if (name.isBlank() || name.startsWith("/") || name.startsWith("\\")) return null
        val p = Paths.get(name.replace('\\', '/')).normalize()
        if (p.isAbsolute || p.nameCount == 0) return null
        if (p.any { it.toString() == ".." }) return null
        return p
    }
}
