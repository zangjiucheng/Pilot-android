package com.example.adbflow.engine

import com.example.adbflow.service.AutomationAccessibilityService
import kotlinx.coroutines.delay
import java.util.Locale

private data class JumpDirective(
    val label: String? = null,
    val isCall: Boolean = false,
    val isReturn: Boolean = false,
    val autoReturn: Boolean = false,
    val shouldExit: Boolean = false,
)

private data class BranchAction(
    val label: String? = null,
    val isCall: Boolean = false,
    val shouldExit: Boolean = false,
)

private data class LabelBounds(
    val start: Int,
    var end: Int,
)

private data class CallFrame(
    val returnIndex: Int,
    val label: String,
    val autoReturn: Boolean,
)

class CommandEngine(
    private val service: AutomationAccessibilityService,
    private val log: (String) -> Unit,
) {
    suspend fun runScript(script: String) {
        val lines = script.lines()
        val labels = mutableMapOf<String, Int>()
        val labelBounds = mutableMapOf<String, LabelBounds>()
        buildLabelMap(lines, labels, labelBounds)

        var lineIndex = 0
        val callStack = mutableListOf<CallFrame>()

        try {
            while (lineIndex < lines.size) {
                val currentIndex = lineIndex
                val directive = runLine(lines[lineIndex])
                delay(300)

                if (directive == null) {
                    lineIndex = adjustForAutoReturn(lineIndex + 1, callStack, labelBounds)
                    continue
                }

                if (directive.isReturn) {
                    if (callStack.isEmpty()) {
                        log("!! RETURN called with an empty call stack")
                        lineIndex = adjustForAutoReturn(lineIndex + 1, callStack, labelBounds)
                        continue
                    }
                    val frame = callStack.removeAt(callStack.lastIndex)
                    lineIndex = adjustForAutoReturn(frame.returnIndex, callStack, labelBounds)
                    continue
                }

                if (directive.shouldExit) {
                    delay(500)
                    log("> EXIT received. Stopping command processing.")
                    break
                }

                if (directive.label == null) {
                    lineIndex = adjustForAutoReturn(lineIndex + 1, callStack, labelBounds)
                    continue
                }

                val target = labels[directive.label]
                if (target == null) {
                    log("!! Unknown label '${directive.label}'")
                    lineIndex = adjustForAutoReturn(lineIndex + 1, callStack, labelBounds)
                    continue
                }

                if (directive.isCall || directive.autoReturn) {
                    callStack.add(
                        CallFrame(
                            returnIndex = currentIndex + 1,
                            label = directive.label,
                            autoReturn = directive.autoReturn,
                        )
                    )
                } else {
                    popAutoFrameIfLeaving(callStack, labelBounds, currentIndex, target)
                }

                lineIndex = adjustForAutoReturn(target, callStack, labelBounds)
            }
        } finally {
            service.sendHomeAndSleep(log)
        }
    }

    private fun buildLabelMap(
        lines: List<String>,
        labels: MutableMap<String, Int>,
        bounds: MutableMap<String, LabelBounds>,
    ) {
        var currentLabel: String? = null
        for (index in lines.indices) {
            val stripped = lines[index].trim()
            if (!stripped.uppercase(Locale.US).startsWith("LABEL")) continue
            val tokens = tokenize(stripped)
            if (tokens.size < 2) {
                log("!! LABEL missing name on line ${index + 1}")
                continue
            }
            val labelName = tokens[1]
            if (labels.containsKey(labelName)) {
                log("!! Duplicate LABEL '$labelName' (keeping first definition)")
                continue
            }
            labels[labelName] = index
            bounds[labelName] = LabelBounds(start = index, end = lines.size)
            if (currentLabel != null) {
                bounds[currentLabel]?.end = index
            }
            currentLabel = labelName
        }
    }

    private suspend fun runLine(raw: String): JumpDirective? {
        val cmd = raw.trim()
        if (cmd.isEmpty() || cmd.startsWith("#")) return null

        val normalized = cmd.uppercase(Locale.US)
        if (normalized.startsWith("LABEL")) return null
        if (normalized.startsWith("GOTO")) {
            val tokens = tokenize(cmd)
            return if (tokens.size < 2) {
                log("!! GOTO usage: GOTO <label>")
                null
            } else {
                JumpDirective(label = tokens[1])
            }
        }
        if (normalized.startsWith("JUMP")) {
            val tokens = tokenize(cmd)
            return if (tokens.size < 2) {
                log("!! JUMP usage: JUMP <label>")
                null
            } else {
                JumpDirective(label = tokens[1], isCall = true, autoReturn = true)
            }
        }
        if (normalized.startsWith("CALL")) {
            val tokens = tokenize(cmd)
            return if (tokens.size < 2) {
                log("!! CALL usage: CALL <label>")
                null
            } else {
                JumpDirective(label = tokens[1], isCall = true)
            }
        }
        if (normalized.startsWith("RETURN")) return JumpDirective(isReturn = true)
        if (normalized.startsWith("EXIT")) return JumpDirective(shouldExit = true)
        if (normalized.startsWith("SLEEP")) {
            val tokens = tokenize(cmd)
            val seconds = tokens.getOrNull(1)?.toFloatOrNull() ?: 1f
            val clamped = if (seconds < 0f) 0f else seconds
            log("> Sleeping for $clamped seconds")
            delay((clamped * 1000).toLong())
            return null
        }
        if (normalized.startsWith("CHECK_COLOR")) return checkColor(cmd)
        if (normalized.startsWith("CHECK_OCR")) return checkOcr(cmd)

        service.executeAction(cmd, log)
        return null
    }

    private suspend fun checkColor(command: String): JumpDirective? {
        val tokens = tokenize(command)
        if (tokens.size < 4) {
            log("!! CHECK_COLOR usage: CHECK_COLOR <x> <y> <#RRGGBB|R,G,B> [tolerance] [THEN ...] [ELSE ...]")
            return null
        }

        val x = tokens[1].toIntOrNull()
        val y = tokens[2].toIntOrNull()
        if (x == null || y == null) {
            log("!! Invalid CHECK_COLOR coordinates")
            return null
        }

        val remainder = tokens.drop(3).toMutableList()
        if (remainder.isEmpty()) {
            log("!! CHECK_COLOR missing color argument")
            return null
        }

        var expected: Triple<Int, Int, Int>? = null
        var tolerance = 0
        try {
            expected = parseRgb(remainder.removeAt(0))
            if (remainder.isNotEmpty()) {
                val maybeTol = remainder.first().toIntOrNull()
                if (maybeTol != null) {
                    if (maybeTol < 0) {
                        log("!! tolerance must be >= 0")
                        return null
                    }
                    tolerance = maybeTol
                    remainder.removeAt(0)
                }
            }
        } catch (_: IllegalArgumentException) {
            val tol = remainder.removeAt(0).toIntOrNull()
            if (tol == null || tol < 0) {
                log("!! Invalid CHECK_COLOR arguments")
                return null
            }
            tolerance = tol
            if (remainder.isEmpty()) {
                log("!! CHECK_COLOR missing color after tolerance")
                return null
            }
            expected = try {
                parseRgb(remainder.removeAt(0))
            } catch (e: IllegalArgumentException) {
                log("!! Invalid CHECK_COLOR color: ${e.message}")
                return null
            }
        }

        val (onMatch, onMismatch) = try {
            parseBranchTokens(remainder)
        } catch (e: IllegalArgumentException) {
            log("!! Invalid CHECK_COLOR branching syntax: ${e.message}")
            return null
        }

        val expectedColor = expected ?: run {
            log("!! CHECK_COLOR expected color parse failed")
            return null
        }

        log("> Checking pixel ($x, $y) against $expectedColor ±$tolerance")
        val actual = service.readPixelColor(x, y, log)
            ?: return branchTargetToDirective(onMismatch)

        val matches = listOf(actual.first, actual.second, actual.third)
            .zip(listOf(expectedColor.first, expectedColor.second, expectedColor.third))
            .all { (a, b) -> kotlin.math.abs(a - b) <= tolerance }

        return if (matches) {
            log("✓ Pixel matches expected color. Actual=$actual")
            branchTargetToDirective(onMatch)
        } else {
            log("!! Pixel mismatch. Actual=$actual, Expected=$expectedColor ±$tolerance")
            branchTargetToDirective(onMismatch)
        }
    }

    private suspend fun checkOcr(command: String): JumpDirective? {
        val tokens = tokenize(command)
        if (tokens.size < 6) {
            log("!! CHECK_OCR usage: CHECK_OCR <x1> <y1> <x2> <y2> <text> [LANG <language>] [THEN [CALL|GOTO] <label>] [ELSE [CALL|GOTO] <label>]")
            return null
        }

        val x1 = tokens[1].toIntOrNull()
        val y1 = tokens[2].toIntOrNull()
        val x2 = tokens[3].toIntOrNull()
        val y2 = tokens[4].toIntOrNull()
        if (x1 == null || y1 == null || x2 == null || y2 == null) {
            log("!! Invalid CHECK_OCR coordinates")
            return null
        }

        val expectedText = tokens[5]
        if (expectedText.isBlank()) {
            log("!! CHECK_OCR text must not be empty")
            return null
        }

        val remainder = tokens.drop(6).toMutableList()
        var language: String? = null
        if (remainder.isNotEmpty() && remainder[0].equals("LANG", ignoreCase = true)) {
            if (remainder.size < 2) {
                log("!! CHECK_OCR LANG must be followed by language code, e.g. LANG en or LANG zh")
                return null
            }
            language = remainder[1]
            remainder.removeAt(0)
            remainder.removeAt(0)
        }

        val (onMatch, onMismatch) = try {
            parseBranchTokens(remainder)
        } catch (e: IllegalArgumentException) {
            log("!! Invalid CHECK_OCR branching syntax: ${e.message}")
            return null
        }

        log("> OCR region ($x1,$y1)-($x2,$y2), looking for '$expectedText'${if (language != null) " LANG=$language" else ""}")
        val recognizedText = service.readTextInRegion(x1, y1, x2, y2, language, log)
            ?: return branchTargetToDirective(onMismatch)

        val matches = recognizedText.contains(expectedText, ignoreCase = true)
        return if (matches) {
            log("✓ OCR matched '$expectedText'")
            branchTargetToDirective(onMatch)
        } else {
            log("!! OCR mismatch. Expected '$expectedText', got '$recognizedText'")
            branchTargetToDirective(onMismatch)
        }
    }

    private fun parseBranchTokens(tokens: List<String>): Pair<BranchAction?, BranchAction?> {
        var onMatch: BranchAction? = null
        var onMismatch: BranchAction? = null
        var i = 0

        while (i < tokens.size) {
            val keyword = tokens[i].uppercase(Locale.US)
            if (keyword != "THEN" && keyword != "ELSE") {
                throw IllegalArgumentException("Expected THEN or ELSE keyword, got '${tokens[i]}'")
            }
            i++
            if (i >= tokens.size) {
                throw IllegalArgumentException("$keyword must be followed by a label name")
            }

            var token = tokens[i]
            val tokenUpper = token.uppercase(Locale.US)
            val action = if (tokenUpper == "EXIT") {
                BranchAction(shouldExit = true)
            } else {
                var isCall = false
                if (tokenUpper == "CALL") {
                    isCall = true
                    i++
                    if (i >= tokens.size) {
                        throw IllegalArgumentException("$keyword CALL must be followed by a label name")
                    }
                    token = tokens[i]
                }

                val resolved = if (token.equals("GOTO", ignoreCase = true)) {
                    i++
                    if (i >= tokens.size) {
                        throw IllegalArgumentException("$keyword GOTO must be followed by a label name")
                    }
                    tokens[i]
                } else {
                    token
                }
                BranchAction(label = resolved, isCall = isCall)
            }

            if (keyword == "THEN") onMatch = action else onMismatch = action
            i++
        }
        return Pair(onMatch, onMismatch)
    }

    private fun parseRgb(value: String): Triple<Int, Int, Int> {
        val trimmed = value.trim()
        if (trimmed.startsWith("#")) {
            val hex = trimmed.removePrefix("#")
            if (hex.length != 6) throw IllegalArgumentException("hex colors must be provided as #RRGGBB")
            return try {
                Triple(
                    hex.substring(0, 2).toInt(16),
                    hex.substring(2, 4).toInt(16),
                    hex.substring(4, 6).toInt(16),
                )
            } catch (e: NumberFormatException) {
                throw IllegalArgumentException("invalid hex color", e)
            }
        }

        val parts = trimmed.split(",").map { it.trim() }
        if (parts.size != 3) throw IllegalArgumentException("expected #RRGGBB or R,G,B")
        val rgb = parts.map { it.toIntOrNull() ?: throw IllegalArgumentException("invalid channel") }
        if (rgb.any { it !in 0..255 }) throw IllegalArgumentException("RGB channel out of range")
        return Triple(rgb[0], rgb[1], rgb[2])
    }

    private fun branchTargetToDirective(target: BranchAction?): JumpDirective? {
        if (target == null) return null
        if (target.shouldExit) return JumpDirective(shouldExit = true)
        return JumpDirective(label = target.label, isCall = target.isCall)
    }

    private fun tokenize(command: String): List<String> {
        return command.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    }

    private fun isWithinBounds(index: Int, bounds: LabelBounds): Boolean {
        return index in bounds.start until bounds.end
    }

    private fun popAutoFrameIfLeaving(
        callStack: MutableList<CallFrame>,
        labelBounds: Map<String, LabelBounds>,
        currentIndex: Int,
        targetIndex: Int,
    ) {
        if (callStack.isEmpty()) return
        val frame = callStack.last()
        if (!frame.autoReturn) return
        val bounds = labelBounds[frame.label] ?: run {
            callStack.removeLastOrNull()
            return
        }

        if (isWithinBounds(currentIndex, bounds) && !isWithinBounds(targetIndex, bounds)) {
            callStack.removeLastOrNull()
        }
    }

    private fun adjustForAutoReturn(
        lineIndex: Int,
        callStack: MutableList<CallFrame>,
        labelBounds: Map<String, LabelBounds>,
    ): Int {
        var nextIndex = lineIndex
        while (callStack.isNotEmpty() && callStack.last().autoReturn) {
            val frame = callStack.last()
            val bounds = labelBounds[frame.label]
            if (bounds == null || !isWithinBounds(nextIndex, bounds)) {
                nextIndex = frame.returnIndex
                callStack.removeLastOrNull()
                continue
            }
            break
        }
        return nextIndex
    }
}
