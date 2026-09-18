package com.letsbackup.app.ui

import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.letsbackup.app.R
import com.letsbackup.app.util.AppLog

class BugReportActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bug_report)

        val logText = findViewById<TextView>(R.id.logText)
        logText.text = AppLog.getAll().ifEmpty { "No logs yet." }

        findViewById<Button>(R.id.btnRefresh).setOnClickListener {
            logText.text = AppLog.getAll().ifEmpty { "No logs yet." }
            findViewById<ScrollView>(R.id.logScroll).post {
                findViewById<ScrollView>(R.id.logScroll).fullScroll(ScrollView.FOCUS_DOWN)
            }
        }

        findViewById<Button>(R.id.btnClear).setOnClickListener {
            AppLog.clear()
            logText.text = "Logs cleared."
            Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("LetsBackup Logs", AppLog.getAll())
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnClose).setOnClickListener {
            finish()
        }
    }
}
