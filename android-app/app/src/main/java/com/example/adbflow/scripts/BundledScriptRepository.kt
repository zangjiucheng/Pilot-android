package com.example.adbflow.scripts

import android.content.Context
import java.util.Locale

class BundledScriptRepository(
    private val context: Context,
    private val assetsDir: String = "default-scripts",
) {
    private val scriptsByName: LinkedHashMap<String, String> by lazy { loadScripts() }

    fun allScripts(): Map<String, String> = LinkedHashMap(scriptsByName)

    fun listNames(): List<String> = scriptsByName.keys.toList()

    fun getByName(name: String): String? = scriptsByName[name]

    fun firstScriptOrNull(): String? = scriptsByName.values.firstOrNull()

    private fun loadScripts(): LinkedHashMap<String, String> {
        val files = try {
            context.assets.list(assetsDir).orEmpty()
        } catch (_: Exception) {
            emptyArray()
        }

        val sorted = files
            .filter { it.endsWith(".txt", ignoreCase = true) || it.endsWith(".script", ignoreCase = true) }
            .sorted()

        val map = linkedMapOf<String, String>()
        for (file in sorted) {
            val name = toDisplayName(file)
            val content = try {
                context.assets.open("$assetsDir/$file").bufferedReader().use { it.readText() }
            } catch (_: Exception) {
                continue
            }
            map[name] = content
        }
        return LinkedHashMap(map)
    }

    private fun toDisplayName(fileName: String): String {
        val base = fileName.substringBeforeLast(".")
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
        if (base.isEmpty()) return fileName
        return base.split(" ").joinToString(" ") { token ->
            token.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }
        }
    }
}
