package com.example.adbflow.engine

import com.example.adbflow.service.AutomationAccessibilityService
import kotlinx.coroutines.delay
import java.util.Locale

private data class JumpDirective(
    val label: String? = null,
    val isCall: Boolean = false,
    val isReturn: Boolean = false,
    val shouldExit: Boolean = false,
)

private data class BranchAction(
    val label: String? = null,
    val isCall: Boolean = false,
    val shouldExit: Boolean = false,
)

private data class CallFrame(
    val returnIndex: Int,
)

class CommandEngine(
    private val service: AutomationAccessibilityService,
    private val log: (String) -> Unit,
) {
    private val variablePattern = Regex("\\$\\{([^}]+)\\}")
    private val variableNamePattern = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    private val scriptVariables = mutableMapOf<String, String>()

    suspend fun runScript(script: String) {
        val lines = script.lines()
        val labels = mutableMapOf<String, Int>()
        buildLabelMap(lines, labels)

        var lineIndex = 0
        val callStack = mutableListOf<CallFrame>()

        try {
            while (lineIndex < lines.size) {
                val currentIndex = lineIndex
                val directive = runLine(lines[lineIndex])
                delay(300)

                if (directive == null) {
                    lineIndex += 1
                    continue
                }

                if (directive.isReturn) {
                    if (callStack.isEmpty()) {
                        log("!! RETURN called with an empty call stack")
                        lineIndex += 1
                        continue
                    }
                    val frame = callStack.removeAt(callStack.lastIndex)
                    lineIndex = frame.returnIndex
                    continue
                }

                if (directive.shouldExit) {
                    delay(500)
                    log("> EXIT received. Stopping command processing.")
                    break
                }

                if (directive.label == null) {
                    lineIndex += 1
                    continue
                }

                val target = labels[directive.label]
                if (target == null) {
                    log("!! Unknown label '${directive.label}'")
                    lineIndex += 1
                    continue
                }

                if (directive.isCall) {
                    callStack.add(
                        CallFrame(
                            returnIndex = currentIndex + 1,
                        )
                    )
                }

                lineIndex = target
            }
        } finally {
            service.sendHomeAndSleep(log)
        }
    }

    private fun buildLabelMap(
        lines: List<String>,
        labels: MutableMap<String, Int>,
    ) {
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
        }
    }

    private suspend fun runLine(raw: String): JumpDirective? {
        val rawCmd = raw.trim()
        val cmd = resolveVariables(rawCmd) ?: return null
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
                JumpDirective(label = tokens[1])
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
        if (normalized.startsWith("CHECK_COLOR_LINE")) return checkColorLine(cmd)
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
        val actual = runWithRetries("CHECK_COLOR") {
            service.readPixelColor(x, y, log)
        }
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

    private suspend fun checkColorLine(command: String): JumpDirective? {
        val tokens = tokenize(command)
        if (tokens.size < 5) {
            log("!! CHECK_COLOR_LINE usage: CHECK_COLOR_LINE [X|Y] <fixed> <start> <end> <#RRGGBB|R,G,B> [tolerance] [step] [minRun] [THEN ...] [ELSE ...]")
            return null
        }

        var offset = 1
        var axis = "X" // default legacy mode: fixed X, scan Y.
        val maybeAxis = tokens[1].uppercase(Locale.US)
        if (maybeAxis == "X" || maybeAxis == "Y") {
            axis = maybeAxis
            offset = 2
        }

        if (tokens.size < offset + 4) {
            log("!! CHECK_COLOR_LINE usage: CHECK_COLOR_LINE [X|Y] <fixed> <start> <end> <#RRGGBB|R,G,B> [tolerance] [step] [minRun] [THEN ...] [ELSE ...]")
            return null
        }

        val fixed = tokens[offset].toIntOrNull()
        val start = tokens[offset + 1].toIntOrNull()
        val end = tokens[offset + 2].toIntOrNull()
        if (fixed == null || start == null || end == null) {
            log("!! Invalid CHECK_COLOR_LINE coordinates")
            return null
        }

        val remainder = tokens.drop(offset + 3).toMutableList()
        if (remainder.isEmpty()) {
            log("!! CHECK_COLOR_LINE missing color argument")
            return null
        }

        val expected = try {
            parseRgb(remainder.removeAt(0))
        } catch (e: IllegalArgumentException) {
            log("!! Invalid CHECK_COLOR_LINE color: ${e.message}")
            return null
        }

        var tolerance = 0
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
        var step = 1
        if (remainder.isNotEmpty()) {
            val maybeStep = remainder.first().toIntOrNull()
            if (maybeStep != null) {
                if (maybeStep <= 0) {
                    log("!! step must be > 0")
                    return null
                }
                step = maybeStep
                remainder.removeAt(0)
            }
        }
        var minRun = 1
        if (remainder.isNotEmpty()) {
            val maybeMinRun = remainder.first().toIntOrNull()
            if (maybeMinRun != null) {
                if (maybeMinRun <= 0) {
                    log("!! minRun must be > 0")
                    return null
                }
                minRun = maybeMinRun
                remainder.removeAt(0)
            }
        }

        val (onMatch, onMismatch) = try {
            parseBranchTokens(remainder)
        } catch (e: IllegalArgumentException) {
            log("!! Invalid CHECK_COLOR_LINE branching syntax: ${e.message}")
            return null
        }

        val (hit, hitAxisName) = if (axis == "Y") {
            log("> Scanning y=$fixed from x=$start to x=$end for color $expected ±$tolerance step=$step minRun=$minRun")
            val hitX = runWithRetries("CHECK_COLOR_LINE") {
                service.findColorXOnHorizontalLine(fixed, start, end, expected, tolerance, step, minRun, log)
            }
                ?: run {
                    clearLineHitVariables()
                    return branchTargetToDirective(onMismatch)
                }
            setScriptVariable("LAST_X", hitX)
            setScriptVariable("LAST_Y", fixed)
            Pair(hitX, "x")
        } else {
            log("> Scanning x=$fixed from y=$start to y=$end for color $expected ±$tolerance step=$step minRun=$minRun")
            val hitY = runWithRetries("CHECK_COLOR_LINE") {
                service.findColorYOnVerticalLine(fixed, start, end, expected, tolerance, step, minRun, log)
            }
                ?: run {
                    clearLineHitVariables()
                    return branchTargetToDirective(onMismatch)
                }
            setScriptVariable("LAST_X", fixed)
            setScriptVariable("LAST_Y", hitY)
            Pair(hitY, "y")
        }

        log("✓ CHECK_COLOR_LINE hit at $hitAxisName=$hit")
        return branchTargetToDirective(onMatch)
    }

    private suspend fun checkOcr(command: String): JumpDirective? {
        val tokens = tokenize(command)
        if (tokens.size < 6) {
            log("!! CHECK_OCR usage: CHECK_OCR <x1> <y1> <x2> <y2> <text|re:regex> [LANG <language>] [THEN [CALL|GOTO] <label>] [ELSE [CALL|GOTO] <label>]")
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
        val recognizedText = runWithRetries("CHECK_OCR") {
            service.readTextInRegion(x1, y1, x2, y2, language, log)
        }
            ?: return branchTargetToDirective(onMismatch)

        val matches = matchesExpectedText(recognizedText, expectedText, log)
        return if (matches) {
            log("✓ OCR matched '$expectedText'")
            branchTargetToDirective(onMatch)
        } else {
            log("!! OCR mismatch. Expected '$expectedText', got '$recognizedText'")
            branchTargetToDirective(onMismatch)
        }
    }

    private fun matchesExpectedText(recognizedText: String, expectedText: String, log: (String) -> Unit): Boolean {
        val trimmed = expectedText.trim()
        if (trimmed.startsWith("re:", ignoreCase = true)) {
            val pattern = trimmed.substringAfter(":", "")
            if (pattern.isBlank()) {
                log("!! CHECK_OCR regex pattern is empty")
                return false
            }
            return try {
                Regex(pattern, setOf(RegexOption.IGNORE_CASE))
                    .containsMatchIn(recognizedText)
            } catch (e: Exception) {
                log("!! Invalid CHECK_OCR regex: ${e.message}")
                false
            }
        }
        return recognizedText.contains(trimmed, ignoreCase = true)
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

    private fun resolveVariables(command: String): String? {
        if (!command.contains("\${")) return command

        var hasError = false
        val resolved = variablePattern.replace(command) { match ->
            val expression = match.groupValues[1].trim()
            val replacement = resolveVariableExpression(expression)
            if (replacement == null) {
                hasError = true
                match.value
            } else {
                replacement
            }
        }
        if (hasError) {
            log("!! Variable resolution failed in line: $command")
            return null
        }
        return resolved
    }

    private fun resolveVariableExpression(expression: String): String? {
        val defaultSeparator = expression.indexOf(":-")
        val coreExpr: String
        var defaultValue: String? = null
        if (defaultSeparator >= 0) {
            coreExpr = expression.substring(0, defaultSeparator).trim()
            defaultValue = expression.substring(defaultSeparator + 2).trim()
            if (defaultValue.isNullOrEmpty()) defaultValue = null
        } else {
            coreExpr = expression
        }

        val (name, offset) = parseVariableCore(coreExpr) ?: run {
            log("!! Invalid variable expression: $expression")
            return null
        }
        val baseText = scriptVariables[name]
        if (baseText == null) {
            return defaultValue
        }
        if (offset == null) return baseText

        val base = baseText.toIntOrNull()
        if (base == null) {
            log("!! Variable '$name' is not numeric, cannot apply offset")
            return null
        }
        return (base + offset).toString()
    }

    private fun parseVariableCore(coreExpr: String): Pair<String, Int?>? {
        val plusIndex = coreExpr.indexOf('+')
        val minusIndex = coreExpr.indexOf('-', startIndex = 1)
        val opIndex = when {
            plusIndex >= 0 && minusIndex >= 0 -> kotlin.math.min(plusIndex, minusIndex)
            plusIndex >= 0 -> plusIndex
            minusIndex >= 0 -> minusIndex
            else -> -1
        }
        if (opIndex < 0) {
            if (!variableNamePattern.matches(coreExpr)) return null
            return Pair(coreExpr, null)
        }

        val name = coreExpr.substring(0, opIndex).trim()
        val offsetText = coreExpr.substring(opIndex).trim()
        if (!variableNamePattern.matches(name)) return null
        val offset = offsetText.toIntOrNull() ?: return null
        return Pair(name, offset)
    }

    private suspend fun <T> runWithRetries(label: String, attempts: Int = 2, block: suspend () -> T?): T? {
        var tryIndex = 0
        while (tryIndex < attempts) {
            val result = block()
            if (result != null) return result
            tryIndex++
            if (tryIndex < attempts) {
                log(".. $label retry $tryIndex/$attempts")
                delay(120)
            }
        }
        return null
    }

    private fun setScriptVariable(name: String, value: Int) {
        scriptVariables[name] = value.toString()
    }

    private fun clearLineHitVariables() {
        scriptVariables.remove("LAST_X")
        scriptVariables.remove("LAST_Y")
    }

}
