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
    private var watermarkNormal: TextView? = null
    private var watermarkBig: TextView? = null

    private var allItems: List<MediaItem> = emptyList()
    private var albums: Map<String, List<MediaItem>> = emptyMap()
    private val selectedAlbums = mutableSetOf<String>()
    private var fromDate: Long? = null
    private var toDate: Long? = null
    private var includePhotos = true
    private var includeVideos = true
    private var isBackingUp = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var themeMode = 0
    private var shineAnimator: android.animation.ObjectAnimator? = null

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

        val themeBtn = findViewById<Button>(R.id.themeBtn)
        updateThemeButton(themeBtn)
        themeBtn?.setOnClickListener { cycleTheme(themeBtn) }

        // Fixed TOP watermark — only "Ashraf creation", never moves
        watermarkNormal = findViewById(R.id.watermarkText)
        watermarkBig = findViewById(R.id.watermarkProgress)
        watermarkNormal?.text = "Ashraf creation"
        watermarkBig?.text = "Ashraf creation"

        findViewById<ImageView>(R.id.logoImage)?.let { LogoAsset.load(it) }

        findViewById<TextView>(R.id.bugReportBtn)?.setOnClickListener {
            startActivity(android.content.Intent(this, BugReportActivity::class.java))
        }

        findViewById<LinearLayout>(R.id.backupCard)?.setOnClickListener {
            if (!isBackingUp) startBackupFlow()
        }
        findViewById<LinearLayout>(R.id.restoreCard)?.setOnClickListener {
            if (!isBackingUp) startRestoreFlow()
        }
        findViewById<Button>(R.id.backToHomeBtn)?.setOnClickListener {
            selectionPanel.visibility = View.GONE
            homePanel.visibility = View.VISIBLE
        }
        startBackupBtn.setOnClickListener { pickDestinationAndBackup() }
        fromDateBtn.setOnClickListener { pickDate(true) }
        toDateBtn.setOnClickListener { pickDate(false) }
        mediaTypeGroup.setOnCheckedChangeListener { _, checkedId ->
            includePhotos = checkedId != R.id.typeVideos
            includeVideos = checkedId != R.id.typePhotos
            updateSize()
        }

        AppLog.i("MainActivity", "App started v1.5")
        setWatermarkProgress(false)
    }

    private fun startBackupFlow() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_EXTERNAL_STORAGE),
                MEDIA_PERMISSION_REQUEST
            )
            return
        }
        doScanAndShowSelection()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MEDIA_PERMISSION_REQUEST && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            doScanAndShowSelection()
        } else {
            statusText.text = "Permission needed to read photos/videos"
        }
    }

    private fun doScanAndShowSelection() {
        statusText.text = "Scanning media…"
        Thread {
            try {
                val scanner = MediaScanner(this)
                allItems = scanner.scanAll()
                albums = allItems.groupBy { it.albumName.ifBlank { "Camera" } }
                runOnUiThread {
                    homePanel.visibility = View.GONE
                    selectionPanel.visibility = View.VISIBLE
                    albumListContainer.removeAllViews()
                    selectedAlbums.clear()
                    albums.keys.sorted().forEach { name ->
                        val cb = CheckBox(this).apply {
                            text = "$name (${albums[name]?.size ?: 0})"
                            setOnCheckedChangeListener { _, isChecked ->
                                if (isChecked) selectedAlbums.add(name) else selectedAlbums.remove(name)
                                updateSize()
                            }
                        }
                        albumListContainer.addView(cb)
                    }
                    updateSize()
                    statusText.text = "Select albums, then Start Backup"
                }
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "Scan failed: ${e.message}" }
                AppLog.e("Backup", "Scan error: ${e.message}")
            }
        }.start()
    }

    private fun updateSize() {
        val filtered = filteredItems()
        val bytes = filtered.sumOf { it.sizeBytes }
        val mb = bytes / (1024.0 * 1024.0)
        sizeText.text = "Selected: ${filtered.size} files · ${"%.1f".format(mb)} MB"
    }

    private fun filteredItems(): List<MediaItem> {
        return allItems.filter { item ->
            val albumOk = selectedAlbums.isEmpty() || selectedAlbums.contains(item.albumName.ifBlank { "Camera" })
            val typeOk = (includePhotos && item.isPhoto) || (includeVideos && !item.isPhoto)
            val dateOk = (fromDate == null || item.dateTaken >= fromDate!!) &&
                    (toDate == null || item.dateTaken <= toDate!!)
            albumOk && typeOk && dateOk
        }
    }

    private fun pickDate(isFrom: Boolean) {
        val cal = Calendar.getInstance()
        DatePickerDialog(this, { _, y, m, d ->
            cal.set(y, m, d, if (isFrom) 0 else 23, if (isFrom) 0 else 59)
            if (isFrom) {
                fromDate = cal.timeInMillis
                fromDateBtn.text = dateFormat.format(cal.time)
            } else {
                toDate = cal.timeInMillis
                toDateBtn.text = dateFormat.format(cal.time)
            }
            updateSize()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun pickDestinationAndBackup() {
        val items = filteredItems()
        if (items.isEmpty()) {
            statusText.text = "Nothing selected"
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(intent, PICK_DESTINATION_REQUEST)
    }

    private fun startRestoreFlow() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, PICK_BACKUP_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data?.data == null) return
        val uri = data.data!!
        when (requestCode) {
            PICK_DESTINATION_REQUEST -> runBackup(uri)
            PICK_BACKUP_REQUEST -> runRestore(uri)
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LetsBackup::Backup").apply {
                acquire(60 * 60 * 1000L)
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (_: Exception) {}
    }

    private fun runBackup(destinationUri: Uri) {
        val items = filteredItems()
        if (items.isEmpty()) return
        isBackingUp = true
        setWatermarkProgress(true)
        acquireWakeLock()
        statusText.text = "Backing up ${items.size} files…"
        selectionPanel.visibility = View.GONE
        homePanel.visibility = View.VISIBLE

        Thread {
            try {
                val selection = BackupSelection(
                    items = items,
                    includePhotos = includePhotos,
                    includeVideos = includeVideos
                )
                val writer = ArchiveWriter(this)
                val result = writer.write(destinationUri, selection) { progress, msg ->
                    runOnUiThread { statusText.text = msg }
                }
                runOnUiThread {
                    isBackingUp = false
                    setWatermarkProgress(false)
                    releaseWakeLock()
                    if (result.success) {
                        statusText.text = "Backup done: ${result.fileName}"
                        AppLog.i("Backup", "SUCCESS: ${result.fileName}")
                    } else {
                        statusText.text = "Backup failed: ${result.message}"
                        AppLog.e("Backup", "FAILED: ${result.message}")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    isBackingUp = false
                    setWatermarkProgress(false)
                    releaseWakeLock()
                    statusText.text = "Error: ${e.message}"
                    AppLog.e("Backup", e.message ?: "unknown")
                }
            }
        }.start()
    }

    private fun runRestore(uri: Uri) {
        isBackingUp = true
        setWatermarkProgress(true)
        acquireWakeLock()
        statusText.text = "Restoring…"
        Thread {
            try {
                val restorer = ArchiveRestorer(this)
                val result = restorer.restore(uri, RestoreMode.REPLACE) { msg ->
                    runOnUiThread { statusText.text = msg }
                }
                runOnUiThread {
                    isBackingUp = false
                    setWatermarkProgress(false)
                    releaseWakeLock()
                    statusText.text = if (result.success) "Restore done" else "Restore failed: ${result.message}"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    isBackingUp = false
                    setWatermarkProgress(false)
                    releaseWakeLock()
                    statusText.text = "Restore error: ${e.message}"
                }
            }
        }.start()
    }

    /** Watermark stays fixed at TOP. Only soft shine during process. Progress stays lower. */
    private fun setWatermarkProgress(active: Boolean) {
        runOnUiThread {
            watermarkNormal?.visibility = View.VISIBLE
            watermarkBig?.visibility = View.GONE
            watermarkNormal?.text = "Ashraf creation"
            if (active) {
                watermarkNormal?.textSize = 14f
                shineAnimator?.cancel()
                shineAnimator = android.animation.ObjectAnimator.ofFloat(watermarkNormal, "alpha", 0.55f, 1.0f).apply {
                    duration = 1400
                    repeatMode = android.animation.ValueAnimator.REVERSE
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                    start()
                }
            } else {
                shineAnimator?.cancel()
                shineAnimator = null
                watermarkNormal?.alpha = 0.75f
                watermarkNormal?.textSize = 13f
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

    override fun onDestroy() {
        super.onDestroy()
        shineAnimator?.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
    }
}
