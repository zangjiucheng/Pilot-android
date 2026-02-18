package com.example.adbflow.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.example.adbflow.engine.CommandEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AutomationAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var scriptJob: Job? = null

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

        fun startScript(script: String) {
            val service = instance
            if (service == null) {
                appendLog("!! Accessibility service is not connected.")
                return
            }
            service.runScriptInternal(script)
        }

        fun stopScript() {
            instance?.scriptJob?.cancel()
            appendLog("> Script stopped")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        appendLog("!! Accessibility interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        appendLog("> Accessibility service connected")
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    private fun runScriptInternal(script: String) {
        scriptJob?.cancel()
        scriptJob = scope.launch {
            appendLog("> Script started")
            val engine = CommandEngine(
                service = this@AutomationAccessibilityService,
                log = { appendLog(it) },
            )
            try {
                engine.runScript(script)
            } catch (e: Exception) {
                appendLog("!! Automation halted: ${e.message}")
            } finally {
                appendLog("> Script finished")
            }
        }
    }

    suspend fun executeAction(command: String, log: (String) -> Unit) {
        val tokens = command.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return

        val normalized = tokens.joinToString(" ") { it.uppercase(Locale.US) }
        if (normalized == "ADB DEVICES") {
            log("> adb devices (not needed on-device, skipping)")
            return
        }

        if (tokens[0].equals("adb", ignoreCase = true)) {
            executeAdbEquivalent(tokens, log)
            return
        }

        when (tokens[0].uppercase(Locale.US)) {
            "TAP" -> {
                val x = tokens.getOrNull(1)?.toFloatOrNull()
                val y = tokens.getOrNull(2)?.toFloatOrNull()
                if (x == null || y == null) {
                    log("!! TAP usage: TAP <x> <y>")
                    return
                }
                performTap(x, y)
                log("> TAP $x $y")
            }

            "SWIPE" -> {
                val x1 = tokens.getOrNull(1)?.toFloatOrNull()
                val y1 = tokens.getOrNull(2)?.toFloatOrNull()
                val x2 = tokens.getOrNull(3)?.toFloatOrNull()
                val y2 = tokens.getOrNull(4)?.toFloatOrNull()
                val duration = tokens.getOrNull(5)?.toLongOrNull() ?: 300L
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
                log("!! Unsupported command: $command")
                throw IllegalArgumentException("Unsupported command: $command")
            }
        }
    }

    private suspend fun executeAdbEquivalent(tokens: List<String>, log: (String) -> Unit) {
        if (tokens.size < 4 || !tokens[1].equals("shell", ignoreCase = true)) {
            log("!! Unsupported adb command: ${tokens.joinToString(" ")}")
            throw IllegalArgumentException("Unsupported adb command")
        }

        if (tokens[2].equals("input", ignoreCase = true)) {
            when (tokens[3].lowercase(Locale.US)) {
                "tap" -> {
                    val x = tokens.getOrNull(4)?.toFloatOrNull()
                    val y = tokens.getOrNull(5)?.toFloatOrNull()
                    if (x == null || y == null) {
                        log("!! adb shell input tap usage: adb shell input tap <x> <y>")
                        return
                    }
                    performTap(x, y)
                    log("> adb shell input tap $x $y")
                }

                "swipe" -> {
                    val x1 = tokens.getOrNull(4)?.toFloatOrNull()
                    val y1 = tokens.getOrNull(5)?.toFloatOrNull()
                    val x2 = tokens.getOrNull(6)?.toFloatOrNull()
                    val y2 = tokens.getOrNull(7)?.toFloatOrNull()
                    val duration = tokens.getOrNull(8)?.toLongOrNull() ?: 300L
                    if (x1 == null || y1 == null || x2 == null || y2 == null) {
                        log("!! adb shell input swipe usage: adb shell input swipe <x1> <y1> <x2> <y2> [duration]")
                        return
                    }
                    performSwipe(x1, y1, x2, y2, duration)
                    log("> adb shell input swipe $x1 $y1 $x2 $y2 $duration")
                }

                "keyevent" -> {
                    val keyCode = tokens.getOrNull(4)?.uppercase(Locale.US)
                    when (keyCode) {
                        "4", "KEYCODE_BACK" -> {
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            log("> BACK")
                        }

                        "3", "KEYCODE_HOME" -> {
                            performGlobalAction(GLOBAL_ACTION_HOME)
                            log("> HOME")
                        }

                        "KEYCODE_SLEEP", "223" -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                                log("> LOCK SCREEN")
                            } else {
                                log("!! Screen lock requires Android 9+")
                            }
                        }

                        else -> {
                            log("!! Unsupported keyevent: $keyCode")
                            throw IllegalArgumentException("Unsupported keyevent: $keyCode")
                        }
                    }
                }

                else -> {
                    log("!! Unsupported adb input action: ${tokens[3]}")
                    throw IllegalArgumentException("Unsupported adb input action")
                }
            }
            return
        }

        log("!! Unsupported adb shell command: ${tokens.joinToString(" ")}")
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            log("!! CHECK_COLOR requires Android 11+")
            return null
        }

        val bitmap = try {
            captureScreenshot()
        } catch (e: Exception) {
            log("!! Failed to capture screenshot: ${e.message}")
            return null
        }

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

    private suspend fun captureScreenshot(): Bitmap {
        return suspendCancellableCoroutine { continuation ->
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val hwBuffer = screenshot.hardwareBuffer
                        val bitmap = Bitmap.wrapHardwareBuffer(hwBuffer, screenshot.colorSpace)
                        hwBuffer.close()
                        val copy = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                        if (copy == null) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(IllegalStateException("Screenshot conversion failed"))
                            }
                            return
                        }
                        if (continuation.isActive) continuation.resume(copy)
                    }

                    override fun onFailure(errorCode: Int) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(IllegalStateException("Screenshot error code $errorCode"))
                        }
                    }
                },
            )
        }
    }

    fun sendHomeAndSleep(log: (String) -> Unit) {
        performGlobalAction(GLOBAL_ACTION_HOME)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        }
        log("> Sent HOME and LOCK_SCREEN")
    }
}
