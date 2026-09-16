package com.letsbackup.app

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.letsbackup.app.media.MediaScanner
import com.letsbackup.app.model.BackupSelection
import com.letsbackup.app.model.MediaItem
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

    private var allItems: List<MediaItem> = emptyList()
    private var albums: Map<String, List<MediaItem>> = emptyMap()
    private val selectedAlbums = mutableSetOf<String>()
    private var fromDate: Long? = null
    private var toDate: Long? = null
    private var includePhotos = true
    private var includeVideos = true

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

        findViewById<LinearLayout>(R.id.backupCard).setOnClickListener {
            if (hasMediaAccess()) startBackupFlow() else requestMediaAccess()
        }

        findViewById<LinearLayout>(R.id.restoreCard).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(intent, PICK_BACKUP_REQUEST)
        }

        findViewById<Button>(R.id.selectAllBtn).setOnClickListener {
            selectedAlbums.clear()
            selectedAlbums.addAll(albums.keys)
            refreshAlbumCheckboxes()
            updateSize()
        }

        findViewById<Button>(R.id.clearAllBtn).setOnClickListener {
            selectedAlbums.clear()
            refreshAlbumCheckboxes()
            updateSize()
        }

        fromDateBtn.setOnClickListener { showDatePicker(true) }
        toDateBtn.setOnClickListener { showDatePicker(false) }

        mediaTypeGroup.setOnCheckedChangeListener { _, checkedId ->
            includePhotos = checkedId == R.id.radioPhotos || checkedId == R.id.radioBoth
            includeVideos = checkedId == R.id.radioVideos || checkedId == R.id.radioBoth
            updateSize()
        }

        findViewById<Button>(R.id.startBackupBtn).setOnClickListener {
            val selection = buildSelection()
            if (selection.totalFiles == 0) {
                statusText.text = "No files selected. Please choose albums or change filters."
                return@setOnClickListener
            }
            statusText.text = "Ready to backup:\n\n" +
                    "Files: ${selection.totalFiles}\n" +
                    "Photos: ${selection.photoCount}\n" +
                    "Videos: ${selection.videoCount}\n" +
                    "Size: ${formatBytes(selection.totalBytes)}\n\n" +
                    "Full .lb.zip engine will be connected next."
            Toast.makeText(this, "Selection ready – archive engine coming", Toast.LENGTH_LONG).show()
        }

        findViewById<Button>(R.id.backToHomeBtn).setOnClickListener {
            selectionPanel.visibility = View.GONE
            homePanel.visibility = View.VISIBLE
            statusText.text = "Ready"
        }
    }

    private fun startBackupFlow() {
        statusText.text = "Scanning photos and videos…"
        homePanel.visibility = View.GONE
        selectionPanel.visibility = View.VISIBLE

        Thread {
            allItems = MediaScanner.scanImagesAndVideos(contentResolver)
            albums = MediaScanner.groupByAlbum(allItems)
            selectedAlbums.clear()
            selectedAlbums.addAll(albums.keys)

            runOnUiThread {
                buildAlbumCheckboxes()
                updateSize()
                statusText.text = "Scan complete. ${allItems.size} files found.\nSelect albums and date range, then start backup."
            }
        }.start()
    }

    private fun buildAlbumCheckboxes() {
        albumListContainer.removeAllViews()
        albums.forEach { (album, items) ->
            val cb = CheckBox(this).apply {
                text = "$album  (${items.size})"
                isChecked = selectedAlbums.contains(album)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedAlbums.add(album) else selectedAlbums.remove(album)
                    updateSize()
                }
            }
            albumListContainer.addView(cb)
        }
    }

    private fun refreshAlbumCheckboxes() {
        for (i in 0 until albumListContainer.childCount) {
            val cb = albumListContainer.getChildAt(i) as? CheckBox ?: continue
            val album = cb.text.toString().substringBefore("  (")
            cb.isChecked = selectedAlbums.contains(album)
        }
    }

    private fun showDatePicker(isFrom: Boolean) {
        val cal = Calendar.getInstance()
        DatePickerDialog(this, { _, y, m, d ->
            cal.set(y, m, d, 0, 0, 0)
            cal.set(Calendar.MILLISECOND, 0)
            if (isFrom) {
                fromDate = cal.timeInMillis
                fromDateBtn.text = "From: ${dateFormat.format(cal.time)}"
            } else {
                cal.set(Calendar.HOUR_OF_DAY, 23)
                cal.set(Calendar.MINUTE, 59)
                toDate = cal.timeInMillis
                toDateBtn.text = "To: ${dateFormat.format(cal.time)}"
            }
            updateSize()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun buildSelection(): BackupSelection {
        val filtered = allItems.filter { item ->
            val albumOk = selectedAlbums.contains(item.albumName)
            val typeOk = (item.isImage && includePhotos) || (item.isVideo && includeVideos)
            val date = if (item.dateTaken > 0) item.dateTaken else item.dateModified
            val dateOk = (fromDate == null || date >= fromDate!!) &&
                    (toDate == null || date <= toDate!!)
            albumOk && typeOk && dateOk
        }
        return BackupSelection(
            items = filtered,
            selectedAlbums = selectedAlbums.toList(),
            fromDate = fromDate,
            toDate = toDate,
            includePhotos = includePhotos,
            includeVideos = includeVideos
        )
    }

    private fun updateSize() {
        val sel = buildSelection()
        sizeText.text = "Selected: ${sel.totalFiles} files  •  ${formatBytes(sel.totalBytes)}"
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
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        ActivityCompat.requestPermissions(this, permissions, MEDIA_PERMISSION_REQUEST)
    }

    private fun formatBytes(v: Long): String {
        if (v < 1024) return "$v B"
        val kb = v / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.2f GB".format(mb / 1024.0)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == MEDIA_PERMISSION_REQUEST) {
            if (hasMediaAccess()) startBackupFlow()
            else statusText.text = "Permission denied. Please allow photo/video access."
        }
    }

    @Deprecated("Prototype")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_BACKUP_REQUEST && resultCode == Activity.RESULT_OK) {
            statusText.text = if (data?.data != null)
                "Backup file selected.\nFull restore engine will be connected next."
            else "No backup selected."
        }
    }
}
