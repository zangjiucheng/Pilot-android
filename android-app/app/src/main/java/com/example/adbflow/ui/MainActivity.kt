package com.example.adbflow.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
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

class MainActivity : AppCompatActivity() {
    private lateinit var commandEditor: EditText
    private lateinit var logView: TextView
    private lateinit var filePathView: TextView
    private var currentFileUri: Uri? = null

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

        val enableService = findViewById<Button>(R.id.enableServiceButton)
        val loadButton = findViewById<Button>(R.id.loadButton)
        val saveButton = findViewById<Button>(R.id.saveButton)
        val saveAsButton = findViewById<Button>(R.id.saveAsButton)
        val runButton = findViewById<Button>(R.id.runButton)
        val stopButton = findViewById<Button>(R.id.stopButton)
        val clearLogsButton = findViewById<Button>(R.id.clearLogsButton)

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
            AutomationAccessibilityService.startScript(commandEditor.text.toString())
        }

        stopButton.setOnClickListener {
            AutomationAccessibilityService.stopScript()
        }

        clearLogsButton.setOnClickListener {
            AutomationAccessibilityService.clearLogs()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AutomationAccessibilityService.logs.collect { lines ->
                    logView.text = lines.joinToString("\n")
                }
            }
        }
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

    private fun defaultScript(): String {
        return """
            # Example
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
