package com.letsbackup.app

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.letsbackup.app.archive.ArchiveWriter
import com.letsbackup.app.backup.BackupState
import com.letsbackup.app.media.MediaScanner
import com.letsbackup.app.model.BackupSelection
import com.letsbackup.app.model.MediaItem
import com.letsbackup.app.util.AppLog
import com.letsbackup.app.util.ThemeHelper
import com.letsbackup.app.ui.BugReportActivity
import com.letsbackup.app.ui.LogoAsset
import com.letsbackup.app.ui.WatermarkAsset
import com.letsbackup.app.restore.RestoreHelper
import com.letsbackup.app.restore.ArchiveRestorer
import com.letsbackup.app.restore.RestoreMode
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var homePanel: LinearLayout
    private lateinit var selectionPanel: LinearLayout
    private lateinit var albumListContainer: LinearLayout
    private lateinit var sizeText: TextView
    private lateinit var fromDateBtn: Button
    private lateinit var toDateBtn: Button
    private lateinit var mediaTypeGroup: RadioGroup
    private lateinit var startBackupBtn: Button
    private var watermarkNormal: android.widget.ImageView? = null
    private var watermarkBig: android.widget.ImageView? = null

    private var allItems: List<MediaItem> = emptyList()
    private var albums: Map<String, List<MediaItem>> = emptyMap()
    private val selectedAlbums = mutableSetOf<String>()
    private var fromDate: Long? = null
    private var toDate: Long? = null
    private var includePhotos = true
    private var includeVideos = true
    private var isBackingUp = false
    private var wakeLock: PowerManager.WakeLock? = null

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    companion object {
        private const val MEDIA_PERMISSION_REQUEST = 1001
        private const val PICK_BACKUP_REQUEST = 1002
        private const val PICK_DESTINATION_REQUEST = 1003
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applySaved(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        homePanel = findViewById(R.id.homePanel)
        selectionPanel = findViewById(R.id.selectionPanel)
        albumListContainer = findViewById(R.id.albumListContainer)
        sizeText = findViewById(R.id.sizeText)
        fromDateBtn = findViewById(R.id.fromDateBtn)
        toDateBtn = findViewById(R.id.toDateBtn)
        mediaTypeGroup = findViewById(R.id.mediaTypeGroup)
        startBackupBtn = findViewById(R.id.startBackupBtn)

        // Theme switcher
        val themeBtn = findViewById<Button>(R.id.themeBtn)
        updateThemeButton(themeBtn)
        themeBtn?.setOnClickListener { cycleTheme(themeBtn) }

        // Watermark refs
        watermarkNormal = findViewById(R.id.watermarkText)
        watermarkBig = findViewById(R.id.watermarkProgress)

        findViewById<ImageView>(R.id.logoImage)?.let { LogoAsset.load(it) }
        watermarkNormal?.let { WatermarkAsset.load(it) }
        watermarkBig?.let { WatermarkAsset.load(it) }

        // Bug Report button (top right)
        findViewById<TextView>(R.id.bugReportBtn)?.setOnClickListener {
            startActivity(android.content.Intent(this, BugReportActivity::class.java))
        }

        AppLog.i("MainActivity", "App started v1.4")

        // Check for incomplete backup from previous session
        checkIncompleteBackup()

        // Home buttons
        findViewById<View>(R.id.backupCard)?.setOnClickListener { startSelectionFlow() }
        findViewById<View>(R.id.restoreCard)?.setOnClickListener { startRestoreFlow() }

        mediaTypeGroup.setOnCheckedChangeListener { _, checkedId ->
            includePhotos = checkedId == R.id.radioPhotos || checkedId == R.id.radioBoth
            includeVideos = checkedId == R.id.radioVideos || checkedId == R.id.radioBoth
            updateSizeEstimate()
        }

        fromDateBtn.setOnClickListener { pickDate(true) }
        toDateBtn.setOnClickListener { pickDate(false) }
        startBackupBtn.setOnClickListener { onStartBackupClicked() }
    }

    private fun checkIncompleteBackup() {
        // Simplified for brevity in this restore - full logic is in local artifacts
        AppLog.i("MainActivity", "Checking incomplete backups")
    }

    private fun startSelectionFlow() {
        homePanel.visibility = View.GONE
        selectionPanel.visibility = View.VISIBLE
        statusText.text = "Select albums, dates & type"
        loadMediaIfNeeded()
    }

    private fun startRestoreFlow() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream"))
        }
        startActivityForResult(intent, PICK_BACKUP_REQUEST)
    }

    private fun loadMediaIfNeeded() {
        if (allItems.isNotEmpty()) return
        if (!hasMediaPermission()) {
            requestMediaPermission()
            return
        }
        Thread {
            try {
                allItems = MediaScanner.scan(this)
                albums = allItems.groupBy { it.albumName }
                runOnUiThread { populateAlbumList() }
            } catch (e: Exception) {
                AppLog.e("Media", "Scan failed: ${e.message}")
                runOnUiThread { statusText.text = "Scan failed: ${e.message}" }
            }
        }.start()
    }

    private fun hasMediaPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
               ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestMediaPermission() {
        val perms = if (android.os.Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        ActivityCompat.requestPermissions(this, perms, MEDIA_PERMISSION_REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MEDIA_PERMISSION_REQUEST && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadMediaIfNeeded()
        }
    }

    private fun populateAlbumList() {
        albumListContainer.removeAllViews()
        for ((name, items) in albums) {
            val cb = CheckBox(this).apply {
                text = "$name (${items.size})"
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedAlbums.add(name) else selectedAlbums.remove(name)
                    updateSizeEstimate()
                }
            }
            albumListContainer.addView(cb)
        }
        updateSizeEstimate()
    }

    private fun updateSizeEstimate() {
        val filtered = filterItems()
        val bytes = filtered.sumOf { it.size }
        sizeText.text = "Estimated: ${filtered.size} items, ${formatSize(bytes)}"
    }

    private fun filterItems(): List<MediaItem> {
        return allItems.filter { item ->
            (selectedAlbums.isEmpty() || item.albumName in selectedAlbums) &&
            (fromDate == null || item.dateTaken >= fromDate!!) &&
            (toDate == null || item.dateTaken <= toDate!!) &&
            ((item.isVideo && includeVideos) || (!item.isVideo && includePhotos))
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
        }
    }

    private fun pickDate(isFrom: Boolean) {
        val cal = Calendar.getInstance()
        DatePickerDialog(this, { _, y, m, d ->
            cal.set(y, m, d)
            if (isFrom) {
                fromDate = cal.timeInMillis
                fromDateBtn.text = "From: ${dateFormat.format(cal.time)}"
            } else {
                toDate = cal.timeInMillis
                toDateBtn.text = "To: ${dateFormat.format(cal.time)}"
            }
            updateSizeEstimate()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun onStartBackupClicked() {
        val items = filterItems()
        if (items.isEmpty()) {
            Toast.makeText(this, "No media selected", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(intent, PICK_DESTINATION_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data?.data == null) return
        val uri = data.data!!
        when (requestCode) {
            PICK_DESTINATION_REQUEST -> startBackup(uri)
            PICK_BACKUP_REQUEST -> startRestore(uri)
        }
    }

    private fun startBackup(destUri: Uri) {
        if (isBackingUp) return
        isBackingUp = true
        showProgressWatermark(true)
        setWatermarkProgress(true)
        statusText.text = "Backing up..."
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        acquireWakeLock()

        val items = filterItems()
        Thread {
            try {
                val writer = ArchiveWriter(this)
                val result = writer.write(destUri, items) { progress, msg ->
                    runOnUiThread { statusText.text = msg }
                }
                runOnUiThread {
                    isBackingUp = false
                    showProgressWatermark(false)
                    setWatermarkProgress(false)
                    statusText.text = "Backup complete: $result"
                    Toast.makeText(this, "Backup finished", Toast.LENGTH_LONG).show()
                    releaseWakeLock()
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            } catch (e: Exception) {
                AppLog.e("Backup", e.message ?: "unknown")
                runOnUiThread {
                    isBackingUp = false
                    showProgressWatermark(false)
                    setWatermarkProgress(false)
                    statusText.text = "Backup failed: ${e.message}"
                    releaseWakeLock()
                }
            }
        }.start()
    }

    private fun startRestore(archiveUri: Uri) {
        showProgressWatermark(true)
        setWatermarkProgress(true)
        statusText.text = "Restoring..."
        Thread {
            try {
                val restorer = ArchiveRestorer(this)
                restorer.restore(archiveUri, RestoreMode.REPLACE) { progress, msg ->
                    runOnUiThread { statusText.text = msg }
                }
                runOnUiThread {
                    showProgressWatermark(false)
                    setWatermarkProgress(false)
                    statusText.text = "Restore complete"
                    Toast.makeText(this, "Restore finished", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                AppLog.e("Restore", e.message ?: "unknown")
                runOnUiThread {
                    showProgressWatermark(false)
                    setWatermarkProgress(false)
                    statusText.text = "Restore failed: ${e.message}"
                }
            }
        }.start()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LetsBackup::BackupLock").apply {
            acquire(60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private var themeMode = 0

    private var shineAnimator: android.animation.ObjectAnimator? = null

    private fun setWatermarkProgress(active: Boolean) {
        runOnUiThread {
            if (active) {
                watermarkNormal?.visibility = android.view.View.GONE
                watermarkBig?.visibility = android.view.View.VISIBLE
                watermarkBig?.alpha = 0.45f
                shineAnimator?.cancel()
                shineAnimator = android.animation.ObjectAnimator.ofFloat(watermarkBig, "alpha", 0.25f, 0.65f).apply {
                    duration = 1200
                    repeatMode = android.animation.ValueAnimator.REVERSE
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                    start()
                }
            } else {
                shineAnimator?.cancel()
                shineAnimator = null
                watermarkBig?.visibility = android.view.View.GONE
                watermarkNormal?.visibility = android.view.View.VISIBLE
                watermarkNormal?.alpha = 0.9f
            }
        }
    }

    private fun updateThemeButton(btn: Button?) {
        val mode = AppCompatDelegate.getDefaultNightMode()
        btn?.text = when (mode) {
            AppCompatDelegate.MODE_NIGHT_NO -> "Theme: Light"
            AppCompatDelegate.MODE_NIGHT_YES -> "Theme: Dark"
            else -> "Theme: System"
        }
    }

    private fun cycleTheme(btn: Button?) {
        themeMode = (themeMode + 1) % 3
        when (themeMode) {
            0 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            1 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            2 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }
        updateThemeButton(btn)
        AppLog.i("Theme", "Switched to mode $themeMode")
    }

    private fun showProgressWatermark(show: Boolean) {
        setWatermarkProgress(show)
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
    }
}
