package com.example.adbflow.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Path
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import com.example.adbflow.engine.CommandEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AutomationAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var scriptJob: Job? = null
    private var countdownOverlayView: TextView? = null

    companion object {
        @Volatile
        var instance: AutomationAccessibilityService? = null
            private set

        private val mutableLogs = MutableStateFlow<List<String>>(emptyList())
        val logs: StateFlow<List<String>> = mutableLogs

        fun appendLog(line: String) {
            val next = (mutableLogs.value + line).takeLast(300)
            mutableLogs.value = next
        }

        fun clearLogs() {
            mutableLogs.value = emptyList()
        }

        fun startScript(script: String, countdownSeconds: Int = 0) {
            val service = instance
            if (service == null) {
                appendLog("!! Accessibility service is not connected.")
                return
            }

            if (containsCheckColor(script) && !service.hasRootAccess()) {
                appendLog("!! CHECK_COLOR requires root. Script rejected.")
                return
            }
            service.runScriptInternal(script, countdownSeconds.coerceAtLeast(0))
        }

        fun stopScript() {
            instance?.removeCountdownOverlayNow()
            instance?.scriptJob?.cancel()
            appendLog("> Script stopped")
        }

        private fun containsCheckColor(script: String): Boolean {
            return script.lineSequence().any { line ->
                val trimmed = line.trim()
                trimmed.isNotEmpty() &&
                    !trimmed.startsWith("#") &&
                    trimmed.uppercase(Locale.US).startsWith("CHECK_COLOR")
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                stopScript()
                return false
            }
            if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                appendLog("!! Hard stop requested (Volume Down). Killing app process.")
                stopScript()
                stopSelf()
                android.os.Process.killProcess(android.os.Process.myPid())
                return true
            }
        }
        return super.onKeyEvent(event)
    }

    override fun onInterrupt() {
        appendLog("!! Accessibility interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        serviceInfo = info
        instance = this
        appendLog("> Accessibility service connected")
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    private fun runScriptInternal(script: String, countdownSeconds: Int) {
        scriptJob?.cancel()
        scriptJob = scope.launch {
            if (countdownSeconds > 0) {
                appendLog("> Starting in $countdownSeconds seconds")
                showGlobalCountdown(countdownSeconds)
            }
            appendLog("> Script started")
            val engine = CommandEngine(
                service = this@AutomationAccessibilityService,
                log = { appendLog(it) },
            )
            try {
                engine.runScript(script)
            } catch (_: CancellationException) {
                appendLog("> Script cancelled")
            } catch (e: Exception) {
                appendLog("!! Automation halted: ${e.message}")
            } finally {
                scriptJob = null
                appendLog("> Script finished")
            }
        }
    }

    private suspend fun showGlobalCountdown(seconds: Int) {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val countdownView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#AA000000"))
            setPadding(32, 18, 32, 18)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 140
        }

        withContext(Dispatchers.Main) {
            windowManager.addView(countdownView, params)
            countdownOverlayView = countdownView
        }
        try {
            for (remaining in seconds downTo 1) {
                withContext(Dispatchers.Main) {
                    countdownView.text = "Starting in $remaining..."
                }
                delay(1000)
            }
        } finally {
            removeCountdownOverlayNow()
        }
    }

    private fun removeCountdownOverlayNow() {
        val view = countdownOverlayView ?: return
        mainExecutor.execute {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            runCatching { wm.removeView(view) }
            countdownOverlayView = null
        }
    }

    fun hasRootAccess(): Boolean {
        var process: java.lang.Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            if (!process.waitFor(800, TimeUnit.MILLISECONDS)) {
                process.destroy()
                false
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.exitValue() == 0 && output.contains("uid=0")
            }
        } catch (_: Exception) {
            false
        } finally {
            process?.destroy()
        }
    }

    suspend fun executeAction(command: String, log: (String) -> Unit) {
        val tokens = command.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return

        val normalizedTokens = normalizeToNativeTokens(tokens, log) ?: return

        when (normalizedTokens[0].uppercase(Locale.US)) {
            "TAP" -> {
                val x = normalizedTokens.getOrNull(1)?.toFloatOrNull()
                val y = normalizedTokens.getOrNull(2)?.toFloatOrNull()
                if (x == null || y == null) {
                    log("!! TAP usage: TAP <x> <y>")
                    return
                }
                performTap(x, y)
                log("> TAP $x $y")
            }

            "SWIPE" -> {
                val x1 = normalizedTokens.getOrNull(1)?.toFloatOrNull()
                val y1 = normalizedTokens.getOrNull(2)?.toFloatOrNull()
                val x2 = normalizedTokens.getOrNull(3)?.toFloatOrNull()
                val y2 = normalizedTokens.getOrNull(4)?.toFloatOrNull()
                val duration = normalizedTokens.getOrNull(5)?.toLongOrNull() ?: 300L
                if (x1 == null || y1 == null || x2 == null || y2 == null) {
                    log("!! SWIPE usage: SWIPE <x1> <y1> <x2> <y2> [durationMs]")
                    return
                }
                performSwipe(x1, y1, x2, y2, duration)
                log("> SWIPE $x1 $y1 $x2 $y2 $duration")
            }

            "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "LOCK", "SLEEP_DEVICE" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                }
            }

            else -> {
                log("!! Unsupported command: $command. Use TAP/SWIPE/BACK/HOME/LOCK")
                throw IllegalArgumentException("Unsupported command: $command")
            }
        }
    }

    private fun normalizeToNativeTokens(tokens: List<String>, log: (String) -> Unit): List<String>? {
        if (tokens.isEmpty()) return null

        if (!tokens[0].equals("adb", ignoreCase = true)) {
            return tokens
        }

        if (tokens.size >= 2 && tokens[1].equals("devices", ignoreCase = true)) {
            log("> Ignoring legacy 'adb devices' line on phone")
            return null
        }

        if (tokens.size >= 4 && tokens[1].equals("shell", ignoreCase = true) && tokens[2].equals("input", ignoreCase = true)) {
            when (tokens[3].lowercase(Locale.US)) {
                "tap" -> {
                    val x = tokens.getOrNull(4)
                    val y = tokens.getOrNull(5)
                    if (x == null || y == null) {
                        log("!! Legacy format usage: adb shell input tap <x> <y>")
                        return null
                    }
                    log("> Converted legacy ADB line to TAP")
                    return listOf("TAP", x, y)
                }

                "swipe" -> {
                    val x1 = tokens.getOrNull(4)
                    val y1 = tokens.getOrNull(5)
                    val x2 = tokens.getOrNull(6)
                    val y2 = tokens.getOrNull(7)
                    val duration = tokens.getOrNull(8) ?: "300"
                    if (x1 == null || y1 == null || x2 == null || y2 == null) {
                        log("!! Legacy format usage: adb shell input swipe <x1> <y1> <x2> <y2> [duration]")
                        return null
                    }
                    log("> Converted legacy ADB line to SWIPE")
                    return listOf("SWIPE", x1, y1, x2, y2, duration)
                }

                "keyevent" -> {
                    val keyCode = tokens.getOrNull(4)?.uppercase(Locale.US)
                    return when (keyCode) {
                        "4", "KEYCODE_BACK" -> listOf("BACK")
                        "3", "KEYCODE_HOME" -> listOf("HOME")
                        "KEYCODE_SLEEP", "223" -> listOf("LOCK")
                        else -> throw IllegalArgumentException("Unsupported keyevent: $keyCode")
                    }
                }

                else -> {
                    log("!! Unsupported legacy input action: ${tokens[3]}")
                    throw IllegalArgumentException("Unsupported adb input action")
                }
            }
        }

        log("!! Unsupported legacy adb command: ${tokens.joinToString(" ")}")
        throw IllegalArgumentException("Unsupported adb shell command")
    }

    private suspend fun performTap(x: Float, y: Float) {
        performSwipe(x, y, x, y, 20L)
    }

    private suspend fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        suspendCancellableCoroutine { continuation ->
            val path = Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1L)))
                .build()

            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(IllegalStateException("Gesture cancelled"))
                        }
                    }
                },
                null,
            )

            if (!dispatched && continuation.isActive) {
                continuation.resumeWithException(IllegalStateException("Failed to dispatch gesture"))
            }
        }
    }

    suspend fun readPixelColor(x: Int, y: Int, log: (String) -> Unit): Triple<Int, Int, Int>? {
        if (!hasRootAccess()) {
            log("!! CHECK_COLOR requires root")
            return null
        }

        val bitmap = captureScreenshotViaRoot(log) ?: return null
        if (x < 0 || y < 0 || x >= bitmap.width || y >= bitmap.height) {
            log("!! coordinates ($x, $y) are outside screenshot bounds ${bitmap.width}x${bitmap.height}")
            return null
        }

        val pixel = bitmap.getPixel(x, y)
        return Triple(
            android.graphics.Color.red(pixel),
            android.graphics.Color.green(pixel),
            android.graphics.Color.blue(pixel),
        )
    }

    private fun captureScreenshotViaRoot(log: (String) -> Unit): Bitmap? {
        var process: java.lang.Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf("su", "-c", "screencap -p"))
            val pngBytes = process.inputStream.use { it.readBytes() }
            val errorText = process.errorStream.bufferedReader().use { it.readText() }

            if (!process.waitFor(4, TimeUnit.SECONDS)) {
                process.destroy()
                log("!! Root screencap timed out")
                return null
            }

            if (process.exitValue() != 0) {
                log("!! Root screencap failed: ${errorText.ifBlank { "unknown error" }}")
                return null
            }

            BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
                ?: run {
                    log("!! Failed to decode root screencap output")
                    null
                }
        } catch (e: Exception) {
            log("!! Root screencap error: ${e.message}")
            null
        } finally {
            process?.destroy()
        }
    }

    fun sendHomeAndSleep(log: (String) -> Unit) {
        performGlobalAction(GLOBAL_ACTION_HOME)
        log("> Sent HOME")
    }
}
