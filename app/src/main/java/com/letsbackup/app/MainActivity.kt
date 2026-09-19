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
import com.letsbackup.app.ui.BugReportActivity
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

    private var allItems: List<MediaItem> = emptyList()
    private var albums: Map<String, List<MediaItem>> = emptyMap()
    private val selectedAlbums = mutableSetOf<String>()
    private var fromDate: Long? = null
    private var toDate: Long? = null
    private var includePhotos = true
    private var includeVideos = true
    private var isBackingUp = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var watermarkNormal: View? = null
    private var watermarkBig: View? = null
    private var themeMode = 0

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    companion object {
        private const val MEDIA_PERMISSION_REQUEST = 1001
        private const val PICK_BACKUP_REQUEST = 1002
        private const val PICK_DESTINATION_REQUEST = 1003
    }

    override fun onCreate(savedInstanceState: Bundle?) {
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
        watermarkNormal = findViewById(R.id.watermarkText)
        watermarkBig = findViewById(R.id.watermarkProgress)

        findViewById<TextView>(R.id.bugReportBtn)?.setOnClickListener {
            startActivity(Intent(this, BugReportActivity::class.java))
        }

        AppLog.i("MainActivity", "App started v1.3")
        checkIncompleteBackup()

        findViewById<LinearLayout>(R.id.backupCard).setOnClickListener {
            if (hasMediaAccess()) startBackupFlow() else requestMediaAccess()
        }

        findViewById<LinearLayout>(R.id.restoreCard).setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
            }, PICK_BACKUP_REQUEST)
        }

        findViewById<Button>(R.id.selectAllBtn).setOnClickListener {
            selectedAlbums.clear(); selectedAlbums.addAll(albums.keys)
            refreshAlbumCheckboxes(); updateSize()
        }
        findViewById<Button>(R.id.clearAllBtn).setOnClickListener {
            selectedAlbums.clear(); refreshAlbumCheckboxes(); updateSize()
        }

        fromDateBtn.setOnClickListener { showDatePicker(true) }
        toDateBtn.setOnClickListener { showDatePicker(false) }

        mediaTypeGroup.setOnCheckedChangeListener { _, checkedId ->
            includePhotos = checkedId == R.id.radioPhotos || checkedId == R.id.radioBoth
            includeVideos = checkedId == R.id.radioVideos || checkedId == R.id.radioBoth
            updateSize()
        }

        startBackupBtn.setOnClickListener {
            if (isBackingUp) return@setOnClickListener
            if (buildSelection().totalFiles == 0) {
                statusText.text = "No files selected."; return@setOnClickListener
            }
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }, PICK_DESTINATION_REQUEST)
        }

        findViewById<Button>(R.id.backToHomeBtn).setOnClickListener {
            if (isBackingUp) { Toast.makeText(this, "Please wait.", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            selectionPanel.visibility = View.GONE
            homePanel.visibility = View.VISIBLE
            statusText.text = "Ready"
        }
    }

    private fun updateThemeButton(btn: Button?) {
        btn?.text = when (AppCompatDelegate.getDefaultNightMode()) {
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
    }

    private fun showProgressWatermark(show: Boolean) {
        runOnUiThread {
            watermarkNormal?.visibility = if (show) View.GONE else View.VISIBLE
            watermarkBig?.visibility = if (show) View.VISIBLE else View.GONE
        }
    }

    private fun checkIncompleteBackup() {
        if (!BackupState.isInProgress(this)) return
        val fileName = BackupState.getFileName(this) ?: return
        AlertDialog.Builder(this)
            .setTitle("Incomplete Backup Found")
            .setMessage("File: $fileName\nProgress: ${BackupState.getCompletedFiles(this)} / ${BackupState.getTotalFiles(this)}")
            .setPositiveButton("Delete") { _, _ -> BackupState.markFinished(this) }
            .setNegativeButton("Keep", null).show()
    }

    private fun startBackupFlow() {
        statusText.text = "Scanning…"
        homePanel.visibility = View.GONE
        selectionPanel.visibility = View.VISIBLE
        Thread {
            allItems = MediaScanner.scanImagesAndVideos(contentResolver)
            albums = MediaScanner.groupByAlbum(allItems)
            selectedAlbums.clear(); selectedAlbums.addAll(albums.keys)
            runOnUiThread {
                buildAlbumCheckboxes(); updateSize()
                statusText.text = "Found ${allItems.size} files. Select and start backup."
            }
        }.start()
    }

    private fun startRealBackup(destinationUri: Uri) {
        val selection = buildSelection()
        if (selection.totalFiles == 0) return
        isBackingUp = true
        startBackupBtn.isEnabled = false
        startBackupBtn.text = "BACKING UP…"
        statusText.text = "Starting backup…"
        showProgressWatermark(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LetsBackup::BackupLock")
        wakeLock?.acquire(60 * 60 * 1000L)
        try { contentResolver.takePersistableUriPermission(destinationUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
        BackupState.markStarted(this, destinationUri, "backup.lb.zip", selection.totalFiles)
        Thread {
            val writer = ArchiveWriter(this, selection, destinationUri) { current, total, bytesCopied, totalBytes, stage ->
                BackupState.updateProgress(this, current)
                runOnUiThread {
                    val percent = if (total > 0) current * 100 / total else 0
                    statusText.text = "$stage\nFile: $current/$total\n${formatBytes(bytesCopied)} / ${formatBytes(totalBytes)}\n$percent%"
                }
            }
            val result = writer.createArchive()
            runOnUiThread {
                isBackingUp = false
                startBackupBtn.isEnabled = true
                startBackupBtn.text = "START BACKUP"
                showProgressWatermark(false)
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                wakeLock?.release()
                when (result) {
                    is ArchiveWriter.Result.Success -> {
                        BackupState.markFinished(this)
                        statusText.text = "✅ Backup completed\n${result.fileName}"
                        AlertDialog.Builder(this).setTitle("Backup Completed").setMessage(result.fileName).setPositiveButton("OK", null).show()
                    }
                    is ArchiveWriter.Result.Error -> statusText.text = "❌ ${result.message}"
                }
            }
        }.start()
    }

    private fun startRestore(uri: Uri, totalHint: Int, mode: RestoreMode) {
        isBackingUp = true
        statusText.text = "Restoring…"
        showProgressWatermark(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Thread {
            val restorer = ArchiveRestorer(this, uri, mode) { current, total, stage ->
                runOnUiThread {
                    val t = if (total > 0) total else totalHint
                    statusText.text = "$stage\n$current / $t"
                }
            }
            val result = restorer.restoreAll()
            runOnUiThread {
                isBackingUp = false
                showProgressWatermark(false)
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                statusText.text = "✅ Restored: ${result.restored}  Failed: ${result.failed}"
                AlertDialog.Builder(this).setTitle("Restore Completed")
                    .setMessage("Restored: ${result.restored}\nFailed: ${result.failed}\nVerified: ${result.verified}")
                    .setPositiveButton("OK", null).show()
            }
        }.start()
    }

    private fun buildAlbumCheckboxes() {
        albumListContainer.removeAllViews()
        albums.forEach { (album, items) ->
            val cb = CheckBox(this).apply {
                text = "$album (${items.size})"
                setTextColor(resources.getColor(R.color.lb_text_primary, theme))
                isChecked = selectedAlbums.contains(album)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedAlbums.add(album) else selectedAlbums.remove(album)
                    updateSize()
                }
            }
            albumListContainer.addView(cb)
        }
    }

    private fun refreshAlbumCheckboxes() {
        for (i in 0 until albumListContainer.childCount) {
            val cb = albumListContainer.getChildAt(i) as? CheckBox ?: continue
            cb.isChecked = selectedAlbums.contains(cb.text.toString().substringBefore(" ("))
        }
    }

    private fun showDatePicker(isFrom: Boolean) {
        val cal = Calendar.getInstance()
        DatePickerDialog(this, { _, y, m, d ->
            cal.set(y, m, d, 0, 0, 0)
            if (isFrom) { fromDate = cal.timeInMillis; fromDateBtn.text = "From: ${dateFormat.format(cal.time)}" }
            else { cal.set(Calendar.HOUR_OF_DAY, 23); toDate = cal.timeInMillis; toDateBtn.text = "To: ${dateFormat.format(cal.time)}" }
            updateSize()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun buildSelection() = BackupSelection(
        allItems.filter {
            selectedAlbums.contains(it.albumName) &&
            ((it.isImage && includePhotos) || (it.isVideo && includeVideos)) &&
            (fromDate == null || (if (it.dateTaken > 0) it.dateTaken else it.dateModified) >= fromDate!!) &&
            (toDate == null || (if (it.dateTaken > 0) it.dateTaken else it.dateModified) <= toDate!!)
        }, selectedAlbums.toList(), fromDate, toDate, includePhotos, includeVideos
    )

    private fun updateSize() {
        val sel = buildSelection()
        sizeText.text = "Selected: ${sel.totalFiles}  •  ${formatBytes(sel.totalBytes)}"
    }

    private fun hasMediaAccess() = if (android.os.Build.VERSION.SDK_INT >= 33)
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
    else ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestMediaAccess() {
        val p = if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        ActivityCompat.requestPermissions(this, p, MEDIA_PERMISSION_REQUEST)
    }

    private fun formatBytes(v: Long): String {
        if (v < 1024) return "$v B"
        val kb = v / 1024.0; if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0; if (mb < 1024) return "%.1f MB".format(mb)
        return "%.2f GB".format(mb / 1024.0)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == MEDIA_PERMISSION_REQUEST && hasMediaAccess()) startBackupFlow()
    }

    @Deprecated("")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data?.data == null) return
        when (requestCode) {
            PICK_DESTINATION_REQUEST -> startRealBackup(data.data!!)
            PICK_BACKUP_REQUEST -> {
                val uri = data.data!!
                statusText.text = "Checking backup…"
                Thread {
                    val info = RestoreHelper.isValidLetsBackup(this, uri)
                    runOnUiThread {
                        if (info == null) { statusText.text = "Not a valid Let's Backup archive."; return@runOnUiThread }
                        AlertDialog.Builder(this)
                            .setTitle("Restore Backup")
                            .setMessage("Valid backup found:\n\n${info.fileName}\nFiles: ${info.totalFiles}\nSize: ${formatBytes(info.totalBytes)}\n\nWhere do you want to restore?")
                            .setPositiveButton("Original locations") { _, _ -> startRestore(uri, info.totalFiles, RestoreMode.ORIGINAL_PATHS) }
                            .setNeutralButton("Dedicated folder") { _, _ -> startRestore(uri, info.totalFiles, RestoreMode.DEDICATED_FOLDER) }
                            .setNegativeButton("Cancel", null).show()
                    }
                }.start()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.let { if (it.isHeld) it.release() }
    }
}
