package com.letsbackup.app

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.letsbackup.app.archive.ArchiveWriter
import com.letsbackup.app.media.MediaScanner
import com.letsbackup.app.model.BackupSelection
import com.letsbackup.app.model.MediaItem
import com.letsbackup.app.restore.ArchiveRestorer
import com.letsbackup.app.restore.RestoreMode
import com.letsbackup.app.ui.BugReportActivity
import com.letsbackup.app.ui.LogoAsset
import com.letsbackup.app.util.AppLog
import com.letsbackup.app.util.ThemeHelper
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

    private var progressPanel: LinearLayout? = null
    private var progressTitle: TextView? = null
    private var progressCircle: ProgressBar? = null
    private var progressPercent: TextView? = null
    private var progressCount: TextView? = null
    private var progressRemaining: TextView? = null
    private var progressEta: TextView? = null
    private var progressStage: TextView? = null
    private var progressResult: TextView? = null
    private var progressDoneBtn: Button? = null

    private var allItems: List<MediaItem> = emptyList()
    private var albums: Map<String, List<MediaItem>> = emptyMap()
    private val selectedAlbums = mutableSetOf<String>()
    private var fromDate: Long? = null
    private var toDate: Long? = null
    private var includePhotos = true
    private var includeVideos = true
    private var isWorking = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var shineAnimator: android.animation.ObjectAnimator? = null
    private var jobStartElapsed = 0L

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

        progressPanel = findViewById(R.id.progressPanel)
        progressTitle = findViewById(R.id.progressTitle)
        progressCircle = findViewById(R.id.progressCircle)
        progressPercent = findViewById(R.id.progressPercent)
        progressCount = findViewById(R.id.progressCount)
        progressRemaining = findViewById(R.id.progressRemaining)
        progressEta = findViewById(R.id.progressEta)
        progressStage = findViewById(R.id.progressStage)
        progressResult = findViewById(R.id.progressResult)
        progressDoneBtn = findViewById(R.id.progressDoneBtn)
        progressDoneBtn?.setOnClickListener { hideProgressScreen() }

        val themeBtn = findViewById<Button>(R.id.themeBtn)
        themeBtn?.text = "Theme: ${ThemeHelper.currentLabel(this)}"
        themeBtn?.setOnClickListener {
            themeBtn.text = "Theme: ${ThemeHelper.cycle(this)}"
        }

        watermarkNormal = findViewById(R.id.watermarkText)
        watermarkNormal?.text = "Ashraf creation"
        findViewById<TextView>(R.id.watermarkProgress)?.text = "Ashraf creation"

        findViewById<ImageView>(R.id.logoImage)?.let { LogoAsset.load(it) }

        findViewById<TextView>(R.id.bugReportBtn)?.setOnClickListener {
            startActivity(Intent(this, BugReportActivity::class.java))
        }

        findViewById<LinearLayout>(R.id.backupCard)?.setOnClickListener {
            if (!isWorking) startBackupFlow()
        }
        findViewById<LinearLayout>(R.id.restoreCard)?.setOnClickListener {
            if (!isWorking) startRestoreFlow()
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

        AppLog.i("MainActivity", "App started v1.7")
        setWatermarkShine(false)
    }

    private fun startBackupFlow() {
        val needImages = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED
        val needLegacy = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (needImages && needLegacy) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ),
                MEDIA_PERMISSION_REQUEST
            )
            return
        }
        doScanAndShowSelection()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MEDIA_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                doScanAndShowSelection()
            } else {
                statusText.text = "Permission needed to read photos/videos"
            }
        }
    }

    private fun doScanAndShowSelection() {
        statusText.text = "Scanning media…"
        Thread {
            try {
                allItems = MediaScanner.scanImagesAndVideos(contentResolver)
                albums = MediaScanner.groupByAlbum(allItems)
                runOnUiThread {
                    homePanel.visibility = View.GONE
                    selectionPanel.visibility = View.VISIBLE
                    albumListContainer.removeAllViews()
                    selectedAlbums.clear()
                    albums.keys.sorted().forEach { name ->
                        val count = albums[name]?.size ?: 0
                        val cb = CheckBox(this).apply {
                            text = "$name ($count)"
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
        val bytes = filtered.sumOf { it.size }
        val mb = bytes / (1024.0 * 1024.0)
        sizeText.text = "Selected: ${filtered.size} files · ${"%.1f".format(mb)} MB"
    }

    private fun filteredItems(): List<MediaItem> {
        return allItems.filter { item ->
            val albumOk = selectedAlbums.isEmpty() || selectedAlbums.contains(item.albumName)
            val typeOk = (includePhotos && item.isImage) || (includeVideos && item.isVideo)
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
        if (filteredItems().isEmpty()) {
            statusText.text = "Nothing selected"
            return
        }
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_DESTINATION_REQUEST)
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
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LetsBackup::Job").apply {
                acquire(3 * 60 * 60 * 1000L)
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

    private fun showProgressScreen(title: String) {
        homePanel.visibility = View.GONE
        selectionPanel.visibility = View.GONE
        progressPanel?.visibility = View.VISIBLE
        progressTitle?.text = title
        progressCircle?.progress = 0
        progressPercent?.text = "0%"
        progressCount?.text = "0 / 0"
        progressRemaining?.text = "Remaining: —"
        progressEta?.text = "Estimated time: —"
        progressStage?.text = ""
        progressResult?.visibility = View.GONE
        progressResult?.text = ""
        progressDoneBtn?.visibility = View.GONE
        setWatermarkShine(true)
        jobStartElapsed = SystemClock.elapsedRealtime()
    }

    private fun hideProgressScreen() {
        progressPanel?.visibility = View.GONE
        homePanel.visibility = View.VISIBLE
        selectionPanel.visibility = View.GONE
        setWatermarkShine(false)
        statusText.text = "Ready"
    }

    private fun updateProgressUi(
        current: Int,
        total: Int,
        bytesCopied: Long,
        totalBytes: Long,
        stage: String
    ) {
        val pct = if (totalBytes > 0) {
            ((bytesCopied * 100) / totalBytes).toInt().coerceIn(0, 100)
        } else if (total > 0) {
            ((current * 100) / total).coerceIn(0, 100)
        } else 0

        progressCircle?.progress = pct
        progressPercent?.text = "$pct%"
        progressCount?.text = "$current / $total"
        progressRemaining?.text = "Remaining: ${(total - current).coerceAtLeast(0)} files"
        progressStage?.text = stage

        val elapsed = SystemClock.elapsedRealtime() - jobStartElapsed
        if (pct in 1..99 && elapsed > 2000) {
            val totalEst = (elapsed * 100.0 / pct).toLong()
            val leftMs = (totalEst - elapsed).coerceAtLeast(0)
            progressEta?.text = "Estimated time: ${formatDuration(leftMs)}"
        } else if (pct >= 100) {
            progressEta?.text = "Estimated time: done"
        } else {
            progressEta?.text = "Estimated time: calculating…"
        }
    }

    private fun formatDuration(ms: Long): String {
        val sec = ms / 1000
        val m = sec / 60
        val s = sec % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }

    private fun showCompleted(message: String) {
        progressResult?.visibility = View.VISIBLE
        progressResult?.text = message
        progressDoneBtn?.visibility = View.VISIBLE
        progressStage?.text = ""
        progressCircle?.progress = 100
        progressPercent?.text = "100%"
        progressEta?.text = "Estimated time: done"
        progressRemaining?.text = "Remaining: 0 files"
        setWatermarkShine(false)
    }

    private fun runBackup(destinationUri: Uri) {
        val items = filteredItems()
        if (items.isEmpty()) return
        isWorking = true
        acquireWakeLock()
        showProgressScreen("Backing up")

        Thread {
            try {
                val selection = BackupSelection(
                    items = items,
                    selectedAlbums = selectedAlbums.toList(),
                    fromDate = fromDate,
                    toDate = toDate,
                    includePhotos = includePhotos,
                    includeVideos = includeVideos
                )
                val writer = ArchiveWriter(this, selection, destinationUri) { current, total, bytes, totalBytes, stage ->
                    runOnUiThread { updateProgressUi(current, total, bytes, totalBytes, stage) }
                }
                when (val result = writer.createArchive()) {
                    is ArchiveWriter.Result.Success -> {
                        runOnUiThread {
                            updateProgressUi(items.size, items.size, selection.totalBytes, selection.totalBytes, "Done")
                            showCompleted("Backup completed\n${result.fileName}")
                            AppLog.i("Backup", "SUCCESS: ${result.fileName}")
                            statusText.text = "Backup completed: ${result.fileName}"
                        }
                    }
                    is ArchiveWriter.Result.Error -> {
                        runOnUiThread {
                            showCompleted("Backup failed\n${result.message}")
                            AppLog.e("Backup", "FAILED: ${result.message}")
                            statusText.text = "Backup failed: ${result.message}"
                        }
                    }
                }
            } catch (e: Exception) {
                AppLog.e("Backup", e.message ?: "unknown", e)
                runOnUiThread {
                    showCompleted("Backup error\n${e.message ?: "Unknown error"}")
                    statusText.text = "Error: ${e.message}"
                }
            } finally {
                runOnUiThread {
                    isWorking = false
                    releaseWakeLock()
                }
            }
        }.start()
    }

    private fun runRestore(uri: Uri) {
        isWorking = true
        acquireWakeLock()
        showProgressScreen("Restoring")

        Thread {
            try {
                val restorer = ArchiveRestorer(this, uri, RestoreMode.ORIGINAL_PATHS) { current, total, stage ->
                    runOnUiThread {
                        updateProgressUi(current, total, current.toLong(), total.toLong().coerceAtLeast(1), stage)
                    }
                }
                val result = restorer.restoreAll()
                runOnUiThread {
                    val t = result.restored + result.skipped + result.failed
                    updateProgressUi(t, t, 1, 1, "Done")
                    showCompleted("Restore completed\nRestored ${result.restored} · Skipped ${result.skipped} · Failed ${result.failed}")
                    statusText.text = "Restore completed"
                    AppLog.i("Restore", "done restored=${result.restored}")
                }
            } catch (e: Exception) {
                AppLog.e("Restore", e.message ?: "unknown", e)
                runOnUiThread {
                    showCompleted("Restore error\n${e.message ?: "Unknown error"}")
                    statusText.text = "Restore error: ${e.message}"
                }
            } finally {
                runOnUiThread {
                    isWorking = false
                    releaseWakeLock()
                }
            }
        }.start()
    }

    private fun setWatermarkShine(active: Boolean) {
        runOnUiThread {
            watermarkNormal?.visibility = View.VISIBLE
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

    override fun onDestroy() {
        super.onDestroy()
        shineAnimator?.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
    }
}
