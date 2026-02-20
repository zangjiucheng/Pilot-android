package com.example.adbflow.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.adbflow.R
import com.example.adbflow.service.AutomationAccessibilityService
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
    private lateinit var countdownSecondsInput: EditText
    private var currentFileUri: Uri? = null
    private var lastBackgroundRes: Int? = null
    private var lastBackgroundAssetPath: String? = null
    private var assetBackgroundCandidates: List<String> = emptyList()
    private var scriptTouchDownX = 0f
    private var scriptTouchDownY = 0f
    private var scriptDragged = false
    private val backgroundCandidates = listOf(
        R.drawable.genshin_bg_anemo,
        R.drawable.genshin_bg_cryo,
        R.drawable.genshin_bg_pyro,
        R.drawable.genshin_bg_hydro,
    )

    private val openDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            currentFileUri = uri
            takePersistedPermission(uri)
            readTextFromUri(uri)?.let { text ->
                commandEditor.setText(text)
                updateFilePathLabel(uri)
                toast("Loaded script file")
            }
        }

    private val createDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri == null) return@registerForActivityResult
            currentFileUri = uri
            takePersistedPermission(uri)
            if (writeTextToUri(uri, commandEditor.text.toString())) {
                updateFilePathLabel(uri)
                toast("Saved script file")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        commandEditor = findViewById(R.id.commandEditor)
        logView = findViewById(R.id.logView)
        filePathView = findViewById(R.id.filePathView)
        backgroundView = findViewById(R.id.backgroundImageView)
        countdownSecondsInput = findViewById(R.id.countdownSecondsInput)
        assetBackgroundCandidates = listAssetBackgrounds()

        val enableService = findViewById<Button>(R.id.enableServiceButton)
        val loadButton = findViewById<Button>(R.id.loadButton)
        val saveButton = findViewById<Button>(R.id.saveButton)
        val saveAsButton = findViewById<Button>(R.id.saveAsButton)
        val runButton = findViewById<Button>(R.id.runButton)
        val stopButton = findViewById<Button>(R.id.stopButton)
        val clearLogsButton = findViewById<Button>(R.id.clearLogsButton)
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()

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

        commandEditor.setText(defaultScript())

        enableService.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        loadButton.setOnClickListener {
            openDocumentLauncher.launch(arrayOf("text/plain", "text/*"))
        }

        saveButton.setOnClickListener {
            val uri = currentFileUri
            if (uri == null) {
                createDocumentLauncher.launch("commands.txt")
            } else if (writeTextToUri(uri, commandEditor.text.toString())) {
                updateFilePathLabel(uri)
                toast("Saved script file")
            }
        }

        saveAsButton.setOnClickListener {
            createDocumentLauncher.launch("commands.txt")
        }

        runButton.setOnClickListener {
            val delaySeconds = countdownSecondsInput.text.toString().trim()
                .ifEmpty { "0" }
                .toIntOrNull()
            if (delaySeconds == null || delaySeconds < 0) {
                toast("Countdown must be a number >= 0")
                return@setOnClickListener
            }
            AutomationAccessibilityService.startScript(commandEditor.text.toString(), delaySeconds)
        }

        stopButton.setOnClickListener {
            AutomationAccessibilityService.stopScript()
        }

        clearLogsButton.setOnClickListener {
            AutomationAccessibilityService.clearLogs()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    AutomationAccessibilityService.logs.collect { lines ->
                        logView.text = lines.joinToString("\n")
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        applyRandomBackground()
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

    private fun readTextFromUri(uri: Uri): String? {
        return try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            toast("Failed to read file: ${e.message}")
            null
        }
    }

    private fun writeTextToUri(uri: Uri, content: String): Boolean {
        return try {
            contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { writer ->
                writer.write(content)
            }
            true
        } catch (e: Exception) {
            toast("Failed to save file: ${e.message}")
            false
        }
    }

    private fun updateFilePathLabel(uri: Uri) {
        filePathView.text = "File: $uri"
    }

    private fun takePersistedPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Some providers do not allow persisting both permissions.
        }
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

    private fun defaultScript(): String {
        return """
            # Example:
            LABEL Check
            CHECK_COLOR 200 2055 #e2933f 10 THEN Qiang ELSE Wait

            LABEL Qiang
            TAP 400 2055
            SLEEP 0.3
            TAP 543 1534
            JUMP Next

            LABEL Wait
            SWIPE 500 1800 500 600 300
            SLEEP 0.3
            JUMP Check

            LABEL Next
            SLEEP 15
            BACK
            JUMP Check
        """.trimIndent()
    }
}
