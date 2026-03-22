package com.fintrack.pk.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ScrollView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.fintrack.pk.R
import com.fintrack.pk.databinding.ActivityDebugBinding
import com.google.android.material.tabs.TabLayout
import androidx.core.app.ShareCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DebugActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDebugBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()                                          // BEFORE setContentView
        binding = ActivityDebugBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        ViewCompat.setOnApplyWindowInsetsListener(binding.appBarLayout) { view, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(0, top, 0, 0)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.debugRoot) { view, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.setPadding(0, 0, 0, nav)
            insets
        }

        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.debug_tab_app))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.debug_tab_server))

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                binding.logSwitcher.displayedChild = tab.position
                loadLogs(tab.position)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        binding.fab.setOnClickListener { exportLogs() }
        loadLogs(0)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.debug_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_refresh -> { loadLogs(binding.tabLayout.selectedTabPosition); true }
        android.R.id.home -> { finish(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun loadLogs(tab: Int) {
        val fileName = if (tab == 0) "app_logs.txt" else "server_logs.txt"
        val file = File(filesDir, "logs/$fileName")
        val textView = if (tab == 0) binding.appLogsText else binding.serverLogsText
        val scrollView = textView.parent as? ScrollView

        textView.text = if (file.exists()) {
            val content = file.readLines().takeLast(500).joinToString("\n")
            content.ifEmpty { getString(R.string.debug_no_logs) }
        } else {
            getString(R.string.debug_no_logs)
        }
        scrollView?.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun exportLogs() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val exportFile = File(cacheDir, "fintrack_logs_$timestamp.txt")
        val appLogs = File(filesDir, "logs/app_logs.txt").takeIf { it.exists() }?.readText() ?: "(empty)"
        val serverLogs = File(filesDir, "logs/server_logs.txt").takeIf { it.exists() }?.readText() ?: "(empty)"
        exportFile.writeText("=== APP LOGS ===\n$appLogs\n\n=== SERVER LOGS ===\n$serverLogs")

        ShareCompat.IntentBuilder(this)
            .setType("text/plain")
            .setStream(FileProvider.getUriForFile(this, "$packageName.fileprovider", exportFile))
            .setChooserTitle(getString(R.string.debug_export_chooser))
            .startChooser()
    }
}
