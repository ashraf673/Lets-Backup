package com.letsbackup.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView

    companion object {
        private const val MEDIA_PERMISSION_REQUEST = 1001
        private const val PICK_BACKUP_REQUEST = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.statusText)

        findViewById<LinearLayout>(R.id.backupCard).setOnClickListener {
            if (hasMediaAccess()) {
                scanMedia()
            } else {
                requestMediaAccess()
            }
        }

        findViewById<LinearLayout>(R.id.restoreCard).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "application/zip"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(intent, PICK_BACKUP_REQUEST)
        }
    }

    private fun hasMediaAccess(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestMediaAccess() {
        val permissions = if (android.os.Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        ActivityCompat.requestPermissions(this, permissions, MEDIA_PERMISSION_REQUEST)
    }

    private fun scanMedia() {
        status.text = "Scanning photos and videos…"
        Thread {
            val items = MediaScanner.scanImagesAndVideos(contentResolver)
            val bytes = items.sumOf { it.size }
            runOnUiThread {
                status.text = "Media scan complete.\n\n" +
                        "Files found: ${items.size}\n" +
                        "Total size: ${formatBytes(bytes)}\n\n" +
                        "Next: Album & date selection UI"
            }
        }.start()
    }

    private fun formatBytes(v: Long): String {
        if (v < 1024) return "$v B"
        val kb = v / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.2f GB".format(mb / 1024.0)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        results: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == MEDIA_PERMISSION_REQUEST) {
            if (hasMediaAccess()) {
                scanMedia()
            } else {
                status.text = "Photo/video access was not granted.\nPlease allow access to continue."
            }
        }
    }

    @Deprecated("Prototype callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_BACKUP_REQUEST && resultCode == Activity.RESULT_OK) {
            status.text = if (data?.data != null) {
                "Backup file selected.\n\nFull restore engine coming next."
            } else {
                "No backup selected."
            }
        }
    }
}
