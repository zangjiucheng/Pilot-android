package com.example.adbflow.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.adbflow.R
import com.example.adbflow.service.AutomationAccessibilityService
import com.example.adbflow.scripts.BundledScriptRepository
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.random.Random

class MainActivity : AppCompatActivity() {
    private companion object {
        const val BACKGROUND_ASSET_DIR = "genshin-backgrounds"
    }

    private lateinit var commandEditor: EditText
    private lateinit var logView: TextView
    private lateinit var filePathView: TextView
    private lateinit var backgroundView: ImageView
    private lateinit var countdownSecondsSeekbar: SeekBar
    private lateinit var countdownValueView: TextView
    private lateinit var rootDrawer: DrawerLayout
    private lateinit var pageTitleView: TextView
    private lateinit var pageAutomation: LinearLayout
    private lateinit var pageFiles: LinearLayout
    private lateinit var pageRecord: LinearLayout
    private lateinit var pageLogs: LinearLayout
    private lateinit var pageTips: LinearLayout
    private lateinit var recordStatusView: TextView
    private var lastBackgroundRes: Int? = null
    private var lastBackgroundAssetPath: String? = null
    private var assetBackgroundCandidates: List<String> = emptyList()
    private var scriptTouchDownX = 0f
    private var scriptTouchDownY = 0f
    private var scriptDragged = false
    private lateinit var bundledScriptRepository: BundledScriptRepository
    private lateinit var templateSpinner: Spinner
    private lateinit var templateAdapter: ArrayAdapter<String>
    private var currentFileUri: Uri? = null
    private val backgroundCandidates = listOf(
        R.drawable.genshin_bg_anemo,
        R.drawable.genshin_bg_cryo,
        R.drawable.genshin_bg_pyro,
        R.drawable.genshin_bg_hydro,
    )
    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult

        takePersistedPermission(uri, read = true, write = true)
        val content = readTextFromUri(uri)
        if (content == null) {
            toast("Failed to load script")
            return@registerForActivityResult
        }

        commandEditor.setText(content)
        currentFileUri = uri
        updateFilePathLabel(uri)
        switchPage(Page.AUTOMATION)
        toast("Loaded script from storage app")
    }
    private val createDocumentLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@registerForActivityResult

        takePersistedPermission(uri, read = true, write = true)
        if (writeTextToUri(uri, commandEditor.text.toString())) {
            currentFileUri = uri
            updateFilePathLabel(uri)
            toast("Saved script to storage app")
        } else {
            toast("Failed to save script")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        commandEditor = findViewById(R.id.commandEditor)
        logView = findViewById(R.id.logView)
        filePathView = findViewById(R.id.filePathView)
        backgroundView = findViewById(R.id.backgroundImageView)
        countdownSecondsSeekbar = findViewById(R.id.countdownSecondsSeekbar)
        countdownValueView = findViewById(R.id.countdownValueView)
        rootDrawer = findViewById(R.id.rootDrawer)
        pageTitleView = findViewById(R.id.pageTitleView)
        pageAutomation = findViewById(R.id.pageAutomation)
        pageFiles = findViewById(R.id.pageFiles)
        pageRecord = findViewById(R.id.pageRecord)
        pageLogs = findViewById(R.id.pageLogs)
        pageTips = findViewById(R.id.pageTips)
        recordStatusView = findViewById(R.id.recordStatusView)
        templateSpinner = findViewById(R.id.templateSpinner)
        assetBackgroundCandidates = listAssetBackgrounds()
        bundledScriptRepository = BundledScriptRepository(this)

        val enableService = findViewById<Button>(R.id.enableServiceButton)
        val runButton = findViewById<Button>(R.id.runButton)
        val stopButton = findViewById<Button>(R.id.stopButton)
        val clearScriptButton = findViewById<Button>(R.id.clearScriptButton)
        val startRecordButton = findViewById<Button>(R.id.startRecordButton)
        val stopRecordInsertButton = findViewById<Button>(R.id.stopRecordInsertButton)
        val clearLogsButton = findViewById<Button>(R.id.clearLogsButton)
        val sidebarToggleButton = findViewById<View>(R.id.sidebarToggleButton)
        val navAutomationButton = findViewById<Button>(R.id.navAutomationButton)
        val navFilesButton = findViewById<Button>(R.id.navFilesButton)
        val navRecordButton = findViewById<Button>(R.id.navRecordButton)
        val navLogsButton = findViewById<Button>(R.id.navLogsButton)
        val navTipsButton = findViewById<Button>(R.id.navTipsButton)
        val applyTemplateButton = findViewById<Button>(R.id.applyTemplateButton)
        val loadScriptButton = findViewById<Button>(R.id.loadScriptButton)
        val saveScriptButton = findViewById<Button>(R.id.saveScriptButton)
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()

        countdownSecondsSeekbar.progress = 5
        countdownValueView.text = "5s"
        countdownSecondsSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                countdownValueView.text = "${progress}s"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        countdownSecondsSeekbar.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                countdownValueView.text = "${countdownSecondsSeekbar.progress}s"
            }
            false
        }

        templateAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, bundledScriptRepository.listNames())
        templateAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        templateSpinner.adapter = templateAdapter

        sidebarToggleButton.setOnClickListener {
            rootDrawer.openDrawer(GravityCompat.START)
        }
        navAutomationButton.setOnClickListener { switchPage(Page.AUTOMATION) }
        navFilesButton.setOnClickListener { switchPage(Page.FILES) }
        navRecordButton.setOnClickListener { switchPage(Page.RECORD) }
        navLogsButton.setOnClickListener { switchPage(Page.LOGS) }
        navTipsButton.setOnClickListener { switchPage(Page.TIPS) }
        switchPage(Page.AUTOMATION)

        commandEditor.isFocusable = false
        commandEditor.isFocusableInTouchMode = false
        commandEditor.isCursorVisible = false
        commandEditor.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    scriptTouchDownX = event.x
                    scriptTouchDownY = event.y
                    scriptDragged = false
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!scriptDragged) {
                        val dx = kotlin.math.abs(event.x - scriptTouchDownX)
                        val dy = kotlin.math.abs(event.y - scriptTouchDownY)
                        if (dx > touchSlop || dy > touchSlop) {
                            scriptDragged = true
                        }
                    }
                }
            }
            false
        }
        commandEditor.setOnClickListener {
            if (!scriptDragged) {
                showScriptEditorDialog()
            }
        }

        commandEditor.setText("")
        filePathView.setText(R.string.file_path_default)

        enableService.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        runButton.setOnClickListener {
            val delaySeconds = countdownSecondsSeekbar.progress
            AutomationAccessibilityService.startScript(commandEditor.text.toString(), delaySeconds)
        }

        stopButton.setOnClickListener {
            AutomationAccessibilityService.stopScript()
        }

        clearScriptButton.setOnClickListener {
            commandEditor.setText("")
            currentFileUri = null
            filePathView.setText(R.string.file_path_default)
            toast("Current script cleared")
        }

        startRecordButton.setOnClickListener {
            val delaySeconds = countdownSecondsSeekbar.progress
            AutomationAccessibilityService.startOperationRecording(delaySeconds)
        }

        stopRecordInsertButton.setOnClickListener {
            val recorded = AutomationAccessibilityService.stopOperationRecordingAndExport()
            if (recorded.isBlank()) {
                toast("No operations recorded. Try record outside this app and perform taps/scrolls.")
                return@setOnClickListener
            }
            val current = commandEditor.text.toString().trimEnd()
            val merged = if (current.isBlank()) recorded else "$current\n$recorded"
            commandEditor.setText(merged)
            toast("Recorded operations inserted")
        }

        clearLogsButton.setOnClickListener {
            AutomationAccessibilityService.clearLogs()
        }

        applyTemplateButton.setOnClickListener {
            val selectedTemplate = templateSpinner.selectedItem?.toString().orEmpty()
            val script = bundledScriptRepository.getByName(selectedTemplate)
            if (script == null) {
                toast("Failed to load template")
                return@setOnClickListener
            }
            commandEditor.setText(script)
            currentFileUri = null
            filePathView.setText(R.string.file_path_default)
            switchPage(Page.AUTOMATION)
            toast("Template applied: $selectedTemplate")
        }

        loadScriptButton.setOnClickListener {
            openDocumentLauncher.launch(arrayOf("text/plain", "text/*", "*/*"))
        }

        saveScriptButton.setOnClickListener {
            val targetUri = currentFileUri
            if (targetUri == null) {
                createDocumentLauncher.launch(defaultScriptFileName())
                return@setOnClickListener
            }

            if (writeTextToUri(targetUri, commandEditor.text.toString())) {
                updateFilePathLabel(targetUri)
                toast("Saved script to storage app")
            } else {
                toast("Failed to save script")
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    AutomationAccessibilityService.logs.collect { lines ->
                        logView.text = lines.joinToString("\n")
                    }
                }
                launch {
                    AutomationAccessibilityService.isRecording.collect { recording ->
                        startRecordButton.isEnabled = !recording
                        stopRecordInsertButton.isEnabled = recording
                        recordStatusView.setText(
                            if (recording) R.string.record_status_recording else R.string.record_status_idle
                        )
                    }
                }
                launch {
                    AutomationAccessibilityService.pendingRecordedScript.collect { pending ->
                        if (pending.isNullOrBlank()) return@collect
                        val current = commandEditor.text.toString().trimEnd()
                        val merged = if (current.isBlank()) pending else "$current\n$pending"
                        commandEditor.setText(merged)
                        AutomationAccessibilityService.clearPendingRecordedScript()
                        switchPage(Page.AUTOMATION)
                        toast("Recorded operations inserted")
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        applyRandomBackground()
    }

    override fun onBackPressed() {
        if (rootDrawer.isDrawerOpen(GravityCompat.START)) {
            rootDrawer.closeDrawer(GravityCompat.START)
            return
        }
        super.onBackPressed()
    }

    private fun switchPage(page: Page) {
        pageAutomation.visibility = if (page == Page.AUTOMATION) android.view.View.VISIBLE else android.view.View.GONE
        pageFiles.visibility = if (page == Page.FILES) android.view.View.VISIBLE else android.view.View.GONE
        pageRecord.visibility = if (page == Page.RECORD) android.view.View.VISIBLE else android.view.View.GONE
        pageLogs.visibility = if (page == Page.LOGS) android.view.View.VISIBLE else android.view.View.GONE
        pageTips.visibility = if (page == Page.TIPS) android.view.View.VISIBLE else android.view.View.GONE

        val titleRes = when (page) {
            Page.AUTOMATION -> R.string.page_automation
            Page.FILES -> R.string.page_files
            Page.RECORD -> R.string.page_record
            Page.LOGS -> R.string.page_logs
            Page.TIPS -> R.string.page_tips
        }
        pageTitleView.setText(titleRes)
        rootDrawer.closeDrawer(GravityCompat.START)
    }

    private enum class Page {
        AUTOMATION,
        FILES,
        RECORD,
        LOGS,
        TIPS,
    }

    private fun applyRandomBackground() {
        try {
            if (assetBackgroundCandidates.isNotEmpty()) {
                val nextAsset = pickNextAssetBackground()
                val loaded = decodeAssetBitmap(nextAsset)
                if (loaded != null) {
                    backgroundView.setImageBitmap(loaded)
                    lastBackgroundAssetPath = nextAsset
                    return
                }
            }
        } catch (_: Exception) {
            // Keep UI responsive and fall back to drawable background.
        }

        val selected = pickNextBackground()
        backgroundView.setImageResource(selected)
        lastBackgroundRes = selected
    }

    private fun pickNextBackground(): Int {
        if (backgroundCandidates.size == 1) return backgroundCandidates.first()

        val previous = lastBackgroundRes
        val options = if (previous == null) {
            backgroundCandidates
        } else {
            backgroundCandidates.filter { it != previous }
        }
        return options[Random.nextInt(options.size)]
    }

    private fun pickNextAssetBackground(): String {
        if (assetBackgroundCandidates.size == 1) return assetBackgroundCandidates.first()

        val previous = lastBackgroundAssetPath
        val options = if (previous == null) {
            assetBackgroundCandidates
        } else {
            assetBackgroundCandidates.filter { it != previous }
        }
        return options[Random.nextInt(options.size)]
    }

    private fun listAssetBackgrounds(): List<String> {
        val files = try {
            assets.list(BACKGROUND_ASSET_DIR)?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }

        return files
            .filter { name ->
                name.endsWith(".jpg", ignoreCase = true) ||
                    name.endsWith(".jpeg", ignoreCase = true) ||
                    name.endsWith(".png", ignoreCase = true) ||
                    name.endsWith(".webp", ignoreCase = true)
            }
            .map { "$BACKGROUND_ASSET_DIR/$it" }
    }

    private fun decodeAssetBitmap(path: String): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        assets.open(path).use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val targetW = if (backgroundView.width > 0) backgroundView.width else resources.displayMetrics.widthPixels
        val targetH = if (backgroundView.height > 0) backgroundView.height else resources.displayMetrics.heightPixels
        val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, targetW, targetH)

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        }
        return assets.open(path).use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
    }

    private fun calculateInSampleSize(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
        var inSampleSize = 1
        var halfW = srcW / 2
        var halfH = srcH / 2

        while (halfW / inSampleSize >= reqW && halfH / inSampleSize >= reqH) {
            inSampleSize *= 2
        }
        return inSampleSize.coerceAtLeast(1)
    }

    private fun updateFilePathLabel(uri: Uri) {
        val name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: uri.toString()
        filePathView.text = "Storage app: $name"
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showScriptEditorDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_script_editor, null)
        val dialogEditor = dialogView.findViewById<EditText>(R.id.dialogScriptEditor)
        dialogEditor.setText(commandEditor.text.toString())
        dialogEditor.setSelection(dialogEditor.text.length)

        AlertDialog.Builder(this)
            .setTitle(R.string.script_editor_title)
            .setView(dialogView)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.apply) { _, _ ->
                commandEditor.setText(dialogEditor.text.toString())
            }
            .show()
    }

    private fun defaultScriptFileName(): String {
        return "script_${System.currentTimeMillis()}.txt"
    }

    private fun readTextFromUri(uri: Uri): String? {
        return try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeTextToUri(uri: Uri, text: String): Boolean {
        return try {
            contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(text) } != null
        } catch (_: Exception) {
            false
        }
    }

    private fun takePersistedPermission(uri: Uri, read: Boolean, write: Boolean) {
        val flags = (if (read) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        if (flags == 0) return
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: Exception) {
            // Some providers do not support persisted URI permissions.
        }
    }
}
