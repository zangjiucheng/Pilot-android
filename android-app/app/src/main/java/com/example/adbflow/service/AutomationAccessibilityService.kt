package com.example.adbflow.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Path
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import com.example.adbflow.engine.CommandEngine
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
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
    private var recordJob: Job? = null
    private var countdownOverlayView: TextView? = null
    private var recordTouchOverlayView: View? = null
    private var latinTextRecognizer: TextRecognizer? = null
    private var chineseTextRecognizer: TextRecognizer? = null
    @Volatile
    private var rootAccessCached: Boolean? = null
    private val recordedLines = mutableListOf<String>()
    private val recordedSwipePathPoints = mutableListOf<Pair<Int, Int>>()
    private val activeTouchPoints = mutableListOf<Pair<Int, Int>>()
    private var lastRecordedLine: String? = null
    private var lastRecordedAtMs: Long = 0L
    private var lastRecordedCommandAtMs: Long = 0L
    private var lastTouchSampleAtMs: Long = 0L
    private var lastScrollDeltaX: Int = 0
    private var lastScrollDeltaY: Int = 0
    @Volatile
    private var suppressTouchCaptureUntilMs: Long = 0L

    companion object {
        @Volatile
        var instance: AutomationAccessibilityService? = null
            private set

        private val mutableLogs = MutableStateFlow<List<String>>(emptyList())
        val logs: StateFlow<List<String>> = mutableLogs
        private val mutableRecording = MutableStateFlow(false)
        val isRecording: StateFlow<Boolean> = mutableRecording
        private val mutablePendingRecordedScript = MutableStateFlow<String?>(null)
        val pendingRecordedScript: StateFlow<String?> = mutablePendingRecordedScript

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

            if (containsRootRequiredCommands(script) && !service.hasRootAccess()) {
                appendLog("!! CHECK_COLOR/CHECK_OCR requires root. Script rejected.")
                return
            }
            service.runScriptInternal(script, countdownSeconds.coerceAtLeast(0))
        }

        fun stopScript() {
            instance?.removeCountdownOverlayNow()
            instance?.scriptJob?.cancel()
            appendLog("> Script stopped")
        }

        fun startOperationRecording(countdownSeconds: Int = 0) {
            val service = instance
            if (service == null) {
                appendLog("!! Accessibility service is not connected.")
                return
            }
            service.startOperationRecordingInternal(countdownSeconds.coerceAtLeast(0))
        }

        fun stopOperationRecordingAndExport(): String {
            val service = instance
            if (service == null) {
                appendLog("!! Accessibility service is not connected.")
                return ""
            }
            return service.stopOperationRecordingInternal()
        }

        fun stopOperationRecording() {
            val service = instance ?: return
            val recorded = service.stopOperationRecordingInternal()
            appendLog("> Recording stopped by volume key (${recorded.lineSequence().count()} lines)")
            if (recorded.isNotBlank()) {
                mutablePendingRecordedScript.value = recorded
            }
        }

        fun clearPendingRecordedScript() {
            mutablePendingRecordedScript.value = null
        }

        private fun containsRootRequiredCommands(script: String): Boolean {
            return script.lineSequence().any { line ->
                val trimmed = line.trim()
                val upper = trimmed.uppercase(Locale.US)
                trimmed.isNotEmpty() &&
                    !trimmed.startsWith("#") &&
                    (upper.startsWith("CHECK_COLOR") || upper.startsWith("CHECK_OCR"))
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val evt = event ?: return
        if (!mutableRecording.value) return
        if (System.currentTimeMillis() < suppressTouchCaptureUntilMs) return
        if (recordTouchOverlayView != null) return
        when (evt.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> recordTapFromNode(evt.source)
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> recordSwipePathPointFromScrollEvent(evt)
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (mutableRecording.value) {
                    stopOperationRecording()
                    return false
                }
                stopScript()
                return false
            }
            if (mutableRecording.value) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_BACK -> recordLine("BACK")
                    KeyEvent.KEYCODE_HOME -> recordLine("HOME")
                }
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
        rootAccessCached = null
        instance = this
        appendLog("> Accessibility service connected")
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        recordJob?.cancel()
        recordJob = null
        rootAccessCached = null
        mutableRecording.value = false
        synchronized(recordedLines) { recordedLines.clear() }
        synchronized(recordedSwipePathPoints) { recordedSwipePathPoints.clear() }
        synchronized(activeTouchPoints) { activeTouchPoints.clear() }
        lastRecordedLine = null
        lastRecordedAtMs = 0L
        lastRecordedCommandAtMs = 0L
        lastTouchSampleAtMs = 0L
        lastScrollDeltaX = 0
        lastScrollDeltaY = 0
        suppressTouchCaptureUntilMs = 0L
        removeRecordingTouchOverlayNow()
        latinTextRecognizer?.close()
        latinTextRecognizer = null
        chineseTextRecognizer?.close()
        chineseTextRecognizer = null
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
            try {
                val engine = CommandEngine(
                    service = this@AutomationAccessibilityService,
                    log = { appendLog(it) },
                )
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

    private fun startOperationRecordingInternal(countdownSeconds: Int) {
        recordJob?.cancel()
        recordJob = scope.launch {
            if (countdownSeconds > 0) {
                appendLog("> Recording starts in $countdownSeconds seconds")
                showGlobalCountdown(countdownSeconds)
            }
            beginRecordingNow()
        }
    }

    private fun beginRecordingNow() {
        synchronized(recordedLines) {
            recordedLines.clear()
        }
        synchronized(recordedSwipePathPoints) {
            recordedSwipePathPoints.clear()
        }
        synchronized(activeTouchPoints) {
            activeTouchPoints.clear()
        }
        lastRecordedLine = null
        lastRecordedAtMs = 0L
        lastRecordedCommandAtMs = 0L
        lastTouchSampleAtMs = 0L
        lastScrollDeltaX = 0
        lastScrollDeltaY = 0
        suppressTouchCaptureUntilMs = 0L
        mutableRecording.value = true
        recordJob = null
        showRecordingTouchOverlay()
        appendLog("> Operation recording started")
    }

    private fun stopOperationRecordingInternal(): String {
        recordJob?.cancel()
        recordJob = null
        mutableRecording.value = false
        removeRecordingTouchOverlayNow()
        val lines = synchronized(recordedLines) {
            recordedLines.toMutableList()
        }
        val swipePathLine = buildRecordedSwipePathCommand()
        if (swipePathLine != null) {
            maybeAppendSleepLine(lines, System.currentTimeMillis())
            lines.add(swipePathLine)
            appendLog("REC $swipePathLine")
            lastRecordedCommandAtMs = System.currentTimeMillis()
        }
        val script = lines.joinToString("\n")
        appendLog("> Operation recording stopped (${script.lineSequence().count()} lines)")
        return script
    }

    private fun recordTapFromNode(node: AccessibilityNodeInfo?) {
        if (node == null) return
        try {
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() <= 0 || rect.height() <= 0) return
            val x = rect.centerX()
            val y = rect.centerY()
            recordLine("TAP $x $y")
        } finally {
            node.recycle()
        }
    }

    private fun recordLine(line: String) {
        val now = System.currentTimeMillis()
        if (line == lastRecordedLine && (now - lastRecordedAtMs) < 220L) return
        synchronized(recordedLines) {
            maybeAppendSleepLine(recordedLines, now)
            recordedLines.add(line)
        }
        lastRecordedLine = line
        lastRecordedAtMs = now
        lastRecordedCommandAtMs = now
        appendLog("REC $line")
    }

    private fun maybeAppendSleepLine(lines: MutableList<String>, nowMs: Long) {
        if (lastRecordedCommandAtMs <= 0L) return
        val deltaMs = nowMs - lastRecordedCommandAtMs
        if (deltaMs < 250L) return
        val seconds = deltaMs / 1000.0
        lines.add(String.format(Locale.US, "SLEEP %.2f", seconds))
    }

    private fun showRecordingTouchOverlay() {
        mainExecutor.execute {
            if (!mutableRecording.value || recordTouchOverlayView != null) return@execute
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val overlay = View(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                setOnTouchListener { _, motionEvent ->
                    handleRecordingTouchEvent(motionEvent)
                    true
                }
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            )
            runCatching { wm.addView(overlay, params) }
                .onSuccess {
                    recordTouchOverlayView = overlay
                    appendLog("> Recorder precise mode enabled (touch is forwarded)")
                }
                .onFailure {
                    appendLog("!! Recorder overlay unavailable, fallback to accessibility events.")
                }
        }
    }

    private fun removeRecordingTouchOverlayNow() {
        val view = recordTouchOverlayView ?: return
        mainExecutor.execute {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            runCatching { wm.removeView(view) }
            recordTouchOverlayView = null
        }
    }

    private fun handleRecordingTouchEvent(event: MotionEvent) {
        if (!mutableRecording.value) return
        val now = System.currentTimeMillis()
        if (now < suppressTouchCaptureUntilMs) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                appendLog(".. recorder suppressing forwarded touch")
            }
            return
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                synchronized(activeTouchPoints) {
                    activeTouchPoints.clear()
                    activeTouchPoints.add(Pair(event.rawX.toInt(), event.rawY.toInt()))
                }
                lastTouchSampleAtMs = now
            }

            MotionEvent.ACTION_MOVE -> {
                if (now - lastTouchSampleAtMs >= 333L) {
                    synchronized(activeTouchPoints) {
                        activeTouchPoints.add(Pair(event.rawX.toInt(), event.rawY.toInt()))
                    }
                    lastTouchSampleAtMs = now
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                val points = synchronized(activeTouchPoints) {
                    val copy = activeTouchPoints.toMutableList()
                    copy.add(Pair(event.rawX.toInt(), event.rawY.toInt()))
                    activeTouchPoints.clear()
                    copy
                }
                recordAndForwardTouchGesture(points)
            }
        }
    }

    private fun recordAndForwardTouchGesture(pointsRaw: List<Pair<Int, Int>>) {
        if (pointsRaw.isEmpty()) return
        val points = pointsRaw.fold(mutableListOf<Pair<Int, Int>>()) { acc, p ->
            val last = acc.lastOrNull()
            if (last == null || kotlin.math.abs(last.first - p.first) > 2 || kotlin.math.abs(last.second - p.second) > 2) {
                acc.add(p)
            }
            acc
        }
        if (points.isEmpty()) return

        val start = points.first()
        val end = points.last()
        val distance = kotlin.math.hypot(
            (end.first - start.first).toDouble(),
            (end.second - start.second).toDouble(),
        )

        if (distance < 24.0 || points.size == 1) {
            recordLine("TAP ${end.first} ${end.second}")
            scope.launch {
                runCatching {
                    forwardWithUiPassThrough(550L) {
                        performTap(end.first.toFloat(), end.second.toFloat())
                    }
                }
            }
            return
        }

        if (points.size == 2) {
            recordLine("SWIPE ${start.first} ${start.second} ${end.first} ${end.second} 300")
            scope.launch {
                runCatching {
                    forwardWithUiPassThrough(900L) {
                        performSwipe(
                            start.first.toFloat(),
                            start.second.toFloat(),
                            end.first.toFloat(),
                            end.second.toFloat(),
                            300L,
                        )
                    }
                }
            }
            return
        }

        val payload = points.joinToString(" ") { "${it.first} ${it.second}" }
        val floatPoints = points.map { Pair(it.first.toFloat(), it.second.toFloat()) }
        val durationMs = calculateDurationFromPath(floatPoints)
        recordLine("SWIPEPATH $payload $durationMs")
        val estimatedDuration = durationMs + 450L
        scope.launch {
            runCatching {
                forwardWithUiPassThrough(estimatedDuration) {
                    performSwipePath(floatPoints, durationMs)
                }
            }
        }
    }

    private suspend fun forwardWithUiPassThrough(suppressDurationMs: Long, block: suspend () -> Unit) {
        val now = System.currentTimeMillis()
        suppressTouchCaptureUntilMs = now + suppressDurationMs + 250L
        removeRecordingTouchOverlayNow()
        delay(50L)
        try {
            block()
        } finally {
            delay(120L)
            if (mutableRecording.value) {
                showRecordingTouchOverlay()
            }
        }
    }

    private fun recordSwipePathPointFromScrollEvent(event: AccessibilityEvent) {
        val now = System.currentTimeMillis()
        if (now - lastTouchSampleAtMs < 333L) return

        val source = event.source
        val point = if (source != null) {
            try {
                val rect = android.graphics.Rect()
                source.getBoundsInScreen(rect)
                if (rect.width() <= 0 || rect.height() <= 0) return
                Pair(rect.centerX(), rect.centerY())
            } finally {
                source.recycle()
            }
        } else {
            Pair(resources.displayMetrics.widthPixels / 2, resources.displayMetrics.heightPixels / 2)
        }

        synchronized(recordedSwipePathPoints) {
            val last = recordedSwipePathPoints.lastOrNull()
            if (last == null || kotlin.math.abs(last.first - point.first) > 4 || kotlin.math.abs(last.second - point.second) > 4) {
                recordedSwipePathPoints.add(point)
                appendLog("REC SWIPEPATH_POINT ${point.first} ${point.second}")
            }
        }
        lastScrollDeltaX = event.scrollDeltaX
        lastScrollDeltaY = event.scrollDeltaY
        lastTouchSampleAtMs = now
    }

    private fun buildRecordedSwipePathCommand(): String? {
        val points = synchronized(recordedSwipePathPoints) { recordedSwipePathPoints.toList() }
        if (points.isEmpty()) return null
        if (points.size == 1) {
            val p = points.first()
            val width = resources.displayMetrics.widthPixels
            val height = resources.displayMetrics.heightPixels
            val dist = (kotlin.math.min(width, height) * 0.22f).toInt().coerceAtLeast(140)
            return if (kotlin.math.abs(lastScrollDeltaY) >= kotlin.math.abs(lastScrollDeltaX)) {
                if (lastScrollDeltaY > 0) {
                    "SWIPE ${p.first} ${p.second - dist} ${p.first} ${p.second + dist} 300"
                } else {
                    "SWIPE ${p.first} ${p.second + dist} ${p.first} ${p.second - dist} 300"
                }
            } else {
                if (lastScrollDeltaX > 0) {
                    "SWIPE ${p.first - dist} ${p.second} ${p.first + dist} ${p.second} 300"
                } else {
                    "SWIPE ${p.first + dist} ${p.second} ${p.first - dist} ${p.second} 300"
                }
            }
        }
        val payload = points.joinToString(" ") { "${it.first} ${it.second}" }
        val floatPoints = points.map { Pair(it.first.toFloat(), it.second.toFloat()) }
        val durationMs = calculateDurationFromPath(floatPoints)
        return "SWIPEPATH $payload $durationMs"
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
        val cached = rootAccessCached
        if (cached != null) return cached

        synchronized(this) {
            val secondRead = rootAccessCached
            if (secondRead != null) return secondRead

            val detected = detectRootAccess()
            rootAccessCached = detected
            return detected
        }
    }

    private fun detectRootAccess(): Boolean {
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

            "SWIPEPATH" -> {
                val args = normalizedTokens.drop(1)
                if (args.size < 4) {
                    log("!! SWIPEPATH usage: SWIPEPATH <x1> <y1> <x2> <y2> [<x3> <y3> ...] [durationMs]")
                    return
                }

                var durationMs = 260L
                var pointTokenCount = args.size
                val explicitDuration = (pointTokenCount % 2 == 1)
                if (pointTokenCount % 2 == 1) {
                    durationMs = args.last().toLongOrNull() ?: run {
                        log("!! SWIPEPATH invalid duration")
                        return
                    }
                    pointTokenCount -= 1
                }
                if (pointTokenCount < 4 || pointTokenCount % 2 != 0) {
                    log("!! SWIPEPATH requires at least 2 points: x1 y1 x2 y2 ...")
                    return
                }

                val maxX = (resources.displayMetrics.widthPixels - 1).coerceAtLeast(0).toFloat()
                val maxY = (resources.displayMetrics.heightPixels - 1).coerceAtLeast(0).toFloat()
                val points = mutableListOf<Pair<Float, Float>>()
                var i = 0
                while (i < pointTokenCount) {
                    val x = args[i].toFloatOrNull()
                    val y = args[i + 1].toFloatOrNull()
                    if (x == null || y == null) {
                        log("!! SWIPEPATH invalid coordinate at index $i")
                        return
                    }
                    points.add(Pair(x.coerceIn(0f, maxX), y.coerceIn(0f, maxY)))
                    i += 2
                }
                if (!explicitDuration) {
                    durationMs = calculateDurationFromPath(points)
                }

                performSwipePath(points, durationMs)
                log("> SWIPEPATH ${points.size} points duration=$durationMs (continuous hold)")
            }

            "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "LOCK", "SLEEP_DEVICE" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                }
            }

            else -> {
                log("!! Unsupported command: $command. Use TAP/SWIPE/SWIPEPATH/BACK/HOME/LOCK")
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

    private suspend fun performSwipePath(points: List<Pair<Float, Float>>, durationMs: Long) {
        if (points.size < 2) {
            throw IllegalArgumentException("SWIPEPATH needs at least 2 points")
        }
        suspendCancellableCoroutine { continuation ->
            val path = Path().apply {
                moveTo(points.first().first, points.first().second)
                for (index in 1 until points.size) {
                    lineTo(points[index].first, points[index].second)
                }
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
                            continuation.resumeWithException(IllegalStateException("Swipe path gesture cancelled"))
                        }
                    }
                },
                null,
            )

            if (!dispatched && continuation.isActive) {
                continuation.resumeWithException(IllegalStateException("Failed to dispatch swipe path gesture"))
            }
        }
    }

    private fun calculateDurationFromPath(
        points: List<Pair<Float, Float>>,
        targetVelocityPxPerSec: Float = 1800f,
    ): Long {
        if (points.size < 2) return 220L
        var distance = 0.0
        for (index in 0 until points.lastIndex) {
            val from = points[index]
            val to = points[index + 1]
            distance += kotlin.math.hypot(
                (to.first - from.first).toDouble(),
                (to.second - from.second).toDouble(),
            )
        }
        val ms = (distance / targetVelocityPxPerSec * 1000.0).toLong()
        return ms.coerceIn(220L, 2400L)
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

    suspend fun findColorYOnVerticalLine(
        x: Int,
        y1: Int,
        y2: Int,
        expected: Triple<Int, Int, Int>,
        tolerance: Int,
        step: Int = 1,
        minRun: Int = 1,
        log: (String) -> Unit,
    ): Int? {
        val bitmap = captureScreenshotViaRoot(log) ?: return null
        try {
            if (x < 0 || x >= bitmap.width) {
                log("!! x=$x is outside screenshot width ${bitmap.width}")
                return null
            }
            if (y1 < 0 || y1 >= bitmap.height || y2 < 0 || y2 >= bitmap.height) {
                log("!! y range [$y1, $y2] is outside screenshot height ${bitmap.height}")
                return null
            }

            val delta = step.coerceAtLeast(1) * if (y2 >= y1) 1 else -1
            val requiredRun = minRun.coerceAtLeast(1)
            var y = y1
            var runStartY: Int? = null
            while (true) {
                val pixel = bitmap.getPixel(x, y)
                val actual = Triple(
                    android.graphics.Color.red(pixel),
                    android.graphics.Color.green(pixel),
                    android.graphics.Color.blue(pixel),
                )
                val matches = listOf(actual.first, actual.second, actual.third)
                    .zip(listOf(expected.first, expected.second, expected.third))
                    .all { (a, b) -> kotlin.math.abs(a - b) <= tolerance }
                if (matches) {
                    if (runStartY == null) {
                        runStartY = y
                    }
                    val runLength = kotlin.math.abs(y - runStartY) + 1
                    if (runLength >= requiredRun) return runStartY
                } else {
                    runStartY = null
                }

                if (y == y2) break
                val next = y + delta
                y = if (delta > 0) kotlin.math.min(next, y2) else kotlin.math.max(next, y2)
            }
            return null
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    suspend fun findColorXOnHorizontalLine(
        y: Int,
        x1: Int,
        x2: Int,
        expected: Triple<Int, Int, Int>,
        tolerance: Int,
        step: Int = 1,
        minRun: Int = 1,
        log: (String) -> Unit,
    ): Int? {
        val bitmap = captureScreenshotViaRoot(log) ?: return null
        try {
            if (y < 0 || y >= bitmap.height) {
                log("!! y=$y is outside screenshot height ${bitmap.height}")
                return null
            }
            if (x1 < 0 || x1 >= bitmap.width || x2 < 0 || x2 >= bitmap.width) {
                log("!! x range [$x1, $x2] is outside screenshot width ${bitmap.width}")
                return null
            }

            val delta = step.coerceAtLeast(1) * if (x2 >= x1) 1 else -1
            val requiredRun = minRun.coerceAtLeast(1)
            var x = x1
            var runStartX: Int? = null
            while (true) {
                val pixel = bitmap.getPixel(x, y)
                val actual = Triple(
                    android.graphics.Color.red(pixel),
                    android.graphics.Color.green(pixel),
                    android.graphics.Color.blue(pixel),
                )
                val matches = listOf(actual.first, actual.second, actual.third)
                    .zip(listOf(expected.first, expected.second, expected.third))
                    .all { (a, b) -> kotlin.math.abs(a - b) <= tolerance }
                if (matches) {
                    if (runStartX == null) {
                        runStartX = x
                    }
                    val runLength = kotlin.math.abs(x - runStartX) + 1
                    if (runLength >= requiredRun) return runStartX
                } else {
                    runStartX = null
                }

                if (x == x2) break
                val next = x + delta
                x = if (delta > 0) kotlin.math.min(next, x2) else kotlin.math.max(next, x2)
            }
            return null
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    suspend fun readTextInRegion(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        language: String?,
        log: (String) -> Unit,
    ): String? {
        val bitmap = captureScreenshotViaRoot(log) ?: return null
        val left = kotlin.math.min(x1, x2)
        val rightInclusive = kotlin.math.max(x1, x2)
        val top = kotlin.math.min(y1, y2)
        val bottomInclusive = kotlin.math.max(y1, y2)

        if (left < 0 || top < 0 || rightInclusive >= bitmap.width || bottomInclusive >= bitmap.height) {
            log("!! OCR region ($left,$top)-($rightInclusive,$bottomInclusive) is outside screenshot bounds ${bitmap.width}x${bitmap.height}")
            return null
        }

        val regionWidth = rightInclusive - left + 1
        val regionHeight = bottomInclusive - top + 1
        val regionBitmap = Bitmap.createBitmap(bitmap, left, top, regionWidth, regionHeight)
        return try {
            recognizeText(regionBitmap, language, log)
        } finally {
            regionBitmap.recycle()
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private suspend fun recognizeText(bitmap: Bitmap, language: String?, log: (String) -> Unit): String? {
        val recognizer = getTextRecognizer(language, log)
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        return suspendCancellableCoroutine { continuation ->
            recognizer.process(inputImage)
                .addOnSuccessListener { result ->
                    if (continuation.isActive) {
                        continuation.resume(result.text.replace('\n', ' ').trim())
                    }
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) {
                        log("!! OCR failed: ${error.message}")
                        continuation.resume(null)
                    }
                }
        }
    }

    private fun getTextRecognizer(language: String?, log: (String) -> Unit): TextRecognizer {
        val lang = language?.trim()?.lowercase(Locale.US).orEmpty()
        return when {
            lang.isEmpty() || lang == "en" || lang == "latin" || lang.startsWith("en-") -> {
                latinTextRecognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also {
                    latinTextRecognizer = it
                }
            }

            lang == "zh" || lang.startsWith("zh-") || lang == "chinese" -> {
                chineseTextRecognizer ?: TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()).also {
                    chineseTextRecognizer = it
                }
            }

            else -> {
                log("!! OCR language '$language' is not supported yet. Falling back to Latin recognizer.")
                latinTextRecognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also {
                    latinTextRecognizer = it
                }
            }
        }
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

    fun returnToFlowPilotApp(log: (String) -> Unit) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        if (launchIntent == null) {
            log("!! Failed to return to FlowPilot: launch intent unavailable")
            return
        }
        try {
            startActivity(launchIntent)
            log("> Returned to FlowPilot")
        } catch (e: Exception) {
            log("!! Failed to return to FlowPilot: ${e.message}")
        }
    }
}
