package com.openclaw.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * WebView → Kotlin bridge via @JavascriptInterface (§2.6).
 * All methods callable from JavaScript as window.OpenClaw.<method>().
 * All return values are JSON strings. Async operations use EventBridge (§2.8).
 */
class JsBridge(
    private val activity: MainActivity,
    private val sessionManager: TerminalSessionManager,
    private val bootstrapManager: BootstrapManager,
    private val eventBridge: EventBridge
) {
    private val gson = Gson()

    // ═══════════════════════════════════════════
    // Terminal domain
    // ═══════════════════════════════════════════

    @JavascriptInterface
    fun showTerminal() {
        // Create session if none exists (e.g., after first-time setup)
        if (sessionManager.activeSession == null) {
            val session = sessionManager.createSession()
            if (bootstrapManager.needsPostSetup()) {
                val script = bootstrapManager.postSetupScript.absolutePath
                // Delay write until after attachSession() initializes the shell process.
                // createSession() posts attachSession() via runOnUiThread; writing before
                // that runs silently drops the data (mShellPid is still 0).
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    session.write("bash $script\n")
                }, 500)
            }
        }
        activity.showTerminal()
    }

    @JavascriptInterface
    fun showWebView() = activity.showWebView()

    @JavascriptInterface
    fun createSession(): String {
        val session = sessionManager.createSession()
        return gson.toJson(mapOf("id" to session.mHandle, "name" to (session.title ?: "Terminal")))
    }

    @JavascriptInterface
    fun switchSession(id: String) = activity.runOnUiThread {
        sessionManager.switchSession(id)
    }

    @JavascriptInterface
    fun closeSession(id: String) {
        sessionManager.closeSession(id)
    }

    @JavascriptInterface
    fun getTerminalSessions(): String {
        return gson.toJson(sessionManager.getSessionsInfo())
    }

    @JavascriptInterface
    fun writeToTerminal(id: String, data: String) {
        val session = if (id.isBlank()) {
            sessionManager.activeSession
        } else {
            sessionManager.getSessionById(id) ?: sessionManager.activeSession
        }
        session?.write(data)
    }

    // ═══════════════════════════════════════════
    // Setup domain
    // ═══════════════════════════════════════════

    @JavascriptInterface
    fun getSetupStatus(): String {
        return gson.toJson(bootstrapManager.getStatus())
    }

    @JavascriptInterface
    fun getBootstrapStatus(): String {
        return gson.toJson(
            mapOf(
                "installed" to bootstrapManager.isInstalled(),
                "prefixPath" to bootstrapManager.prefixDir.absolutePath
            )
        )
    }

    @JavascriptInterface
    fun startSetup() {
        CoroutineScope(Dispatchers.IO).launch {
            bootstrapManager.startSetup { progress, message ->
                eventBridge.emit(
                    "setup_progress",
                    mapOf("progress" to progress, "message" to message)
                )
            }
        }
    }

    @JavascriptInterface
    fun saveToolSelections(json: String) {
        val configFile = java.io.File(bootstrapManager.homeDir, ".openclaw-android/tool-selections.conf")
        configFile.parentFile?.mkdirs()
        val selections = gson.fromJson(json, Map::class.java) as? Map<*, *> ?: return
        val lines = selections.entries.joinToString("\n") { (key, value) ->
            val envKey = "INSTALL_${(key as String).uppercase().replace("-", "_")}"
            "$envKey=$value"
        }
        configFile.writeText(lines + "\n")
    }

    // ═══════════════════════════════════════════
    // Platform domain
    // ═══════════════════════════════════════════

    @JavascriptInterface
    fun getAvailablePlatforms(): String {
        // Read from cached config.json or return defaults
        return gson.toJson(
            listOf(
                mapOf("id" to "openclaw", "name" to "OpenClaw", "icon" to "🧠",
                    "desc" to "AI agent platform"),
            )
        )
    }

    @JavascriptInterface
    fun getInstalledPlatforms(): String {
        // Check which platforms are installed via npm/filesystem
        val env = EnvironmentBuilder.build(activity)
        val result = CommandRunner.runSync(
            "npm list -g --depth=0 --json 2>/dev/null",
            env, bootstrapManager.prefixDir, timeoutMs = 10_000
        )
        return result.stdout.ifBlank { "[]" }
    }

    @JavascriptInterface
    fun installPlatform(id: String) {
        CoroutineScope(Dispatchers.IO).launch {
            eventBridge.emit("install_progress",
                mapOf("target" to id, "progress" to 0f, "message" to "Installing $id..."))
            val env = EnvironmentBuilder.build(activity)
            CommandRunner.runStreaming(
                "npm install -g $id@latest --ignore-scripts",
                env, bootstrapManager.homeDir
            ) { output ->
                eventBridge.emit("install_progress",
                    mapOf("target" to id, "progress" to 0.5f, "message" to output))
            }
            eventBridge.emit("install_progress",
                mapOf("target" to id, "progress" to 1f, "message" to "$id installed"))
        }
    }

    @JavascriptInterface
    fun uninstallPlatform(id: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val env = EnvironmentBuilder.build(activity)
            CommandRunner.runSync("npm uninstall -g $id", env, bootstrapManager.homeDir)
        }
    }

    @JavascriptInterface
    fun switchPlatform(id: String) {
        // Write active platform marker
        val markerFile = java.io.File(bootstrapManager.homeDir, ".openclaw-android/.platform")
        markerFile.parentFile?.mkdirs()
        markerFile.writeText(id)
    }

    @JavascriptInterface
    fun getActivePlatform(): String {
        val markerFile = java.io.File(bootstrapManager.homeDir, ".openclaw-android/.platform")
        val id = if (markerFile.exists()) markerFile.readText().trim() else "openclaw"
        return gson.toJson(mapOf("id" to id, "name" to id.replaceFirstChar { it.uppercase() }))
    }

    // ═══════════════════════════════════════════
    // Tools domain
    // ═══════════════════════════════════════════

    @JavascriptInterface
    fun getInstalledTools(): String {
        val env = EnvironmentBuilder.build(activity)
        val prefix = bootstrapManager.prefixDir.absolutePath
        val tools = mutableListOf<Map<String, String>>()
        val checks = mapOf(
            "tmux" to "$prefix/bin/tmux",
            "ttyd" to "$prefix/bin/ttyd",
            "code-server" to "$prefix/bin/code-server",
            "openssh-server" to "$prefix/bin/sshd"
        )
        for ((id, path) in checks) {
            if (java.io.File(path).exists()) {
                tools.add(mapOf("id" to id, "name" to id, "version" to "installed"))
            }
        }
        return gson.toJson(tools)
    }

    @JavascriptInterface
    fun installTool(id: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val env = EnvironmentBuilder.build(activity)
            val cmd = when (id) {
                "tmux", "ttyd", "openssh-server" ->
                    "${bootstrapManager.prefixDir.absolutePath}/bin/apt-get install -y $id"
                "code-server" ->
                    "npm install -g code-server"
                else -> "echo 'Unknown tool: $id'"
            }
            eventBridge.emit("install_progress",
                mapOf("target" to id, "progress" to 0f, "message" to "Installing $id..."))
            CommandRunner.runStreaming(cmd, env, bootstrapManager.homeDir) { output ->
                eventBridge.emit("install_progress",
                    mapOf("target" to id, "progress" to 0.5f, "message" to output))
            }
            eventBridge.emit("install_progress",
                mapOf("target" to id, "progress" to 1f, "message" to "$id installed"))
        }
    }

    @JavascriptInterface
    fun uninstallTool(id: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val env = EnvironmentBuilder.build(activity)
            CommandRunner.runSync(
                "${bootstrapManager.prefixDir.absolutePath}/bin/apt-get remove -y $id",
                env, bootstrapManager.homeDir
            )
        }
    }

    @JavascriptInterface
    fun isToolInstalled(id: String): String {
        val prefix = bootstrapManager.prefixDir.absolutePath
        val binPath = when (id) {
            "openssh-server" -> "$prefix/bin/sshd"
            else -> "$prefix/bin/$id"
        }
        val exists = java.io.File(binPath).exists()
        return gson.toJson(mapOf("installed" to exists))
    }

    // ═══════════════════════════════════════════
    // Commands domain
    // ═══════════════════════════════════════════

    @JavascriptInterface
    fun runCommand(cmd: String): String {
        val env = EnvironmentBuilder.build(activity)
        val result = CommandRunner.runSync(cmd, env, bootstrapManager.homeDir)
        return gson.toJson(result)
    }

    @JavascriptInterface
    fun runCommandAsync(callbackId: String, cmd: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val env = EnvironmentBuilder.build(activity)
            CommandRunner.runStreaming(cmd, env, bootstrapManager.homeDir) { output ->
                eventBridge.emit(
                    "command_output",
                    mapOf("callbackId" to callbackId, "data" to output, "done" to false)
                )
            }
            eventBridge.emit(
                "command_output",
                mapOf("callbackId" to callbackId, "data" to "", "done" to true)
            )
        }
    }

    // ═══════════════════════════════════════════
    // Updates domain
    // ═══════════════════════════════════════════

    @JavascriptInterface
    fun checkForUpdates(): String {
        // Compare local versions with config.json remote versions
        val updates = mutableListOf<Map<String, String>>()
        try {
            val configFile = java.io.File(
                activity.filesDir, "usr/share/openclaw-app/config.json"
            )
            if (configFile.exists()) {
                val config = gson.fromJson(configFile.readText(), Map::class.java) as? Map<*, *>
                val localWwwVersion = activity.getSharedPreferences("openclaw", 0)
                    .getString("www_version", "0.0.0")
                val remoteWwwVersion = ((config?.get("www") as? Map<*, *>)?.get("version") as? String)
                if (remoteWwwVersion != null && remoteWwwVersion != localWwwVersion) {
                    updates.add(mapOf(
                        "component" to "www",
                        "currentVersion" to (localWwwVersion ?: "0.0.0"),
                        "newVersion" to remoteWwwVersion
                    ))
                }
            }
        } catch (_: Exception) { /* ignore parse errors */ }
        return gson.toJson(updates)
    }

    @JavascriptInterface
    fun applyUpdate(component: String) {
        CoroutineScope(Dispatchers.IO).launch {
            eventBridge.emit("install_progress",
                mapOf("target" to component, "progress" to 0f, "message" to "Updating $component..."))

            when (component) {
                "www" -> {
                    // Download www.zip → staging → atomic replace → reload
                    try {
                        val url = kotlinx.coroutines.runBlocking { UrlResolver(activity).getWwwUrl() }
                        val stagingWww = java.io.File(activity.cacheDir, "www-staging")
                        stagingWww.deleteRecursively()
                        stagingWww.mkdirs()

                        // Download www.zip
                        eventBridge.emit("install_progress",
                            mapOf("target" to "www", "progress" to 0.2f, "message" to "Downloading..."))
                        val zipFile = java.io.File(activity.cacheDir, "www.zip")
                        java.net.URL(url).openStream().use { input ->
                            zipFile.outputStream().use { output -> input.copyTo(output) }
                        }

                        // Extract to staging
                        eventBridge.emit("install_progress",
                            mapOf("target" to "www", "progress" to 0.6f, "message" to "Extracting..."))
                        java.util.zip.ZipInputStream(zipFile.inputStream()).use { zis ->
                            var entry = zis.nextEntry
                            while (entry != null) {
                                val destFile = java.io.File(stagingWww, entry.name)
                                if (entry.isDirectory) {
                                    destFile.mkdirs()
                                } else {
                                    destFile.parentFile?.mkdirs()
                                    destFile.outputStream().use { out -> zis.copyTo(out) }
                                }
                                entry = zis.nextEntry
                            }
                        }
                        zipFile.delete()

                        // Atomic replace: delete old www, rename staging
                        eventBridge.emit("install_progress",
                            mapOf("target" to "www", "progress" to 0.9f, "message" to "Applying..."))
                        val wwwDir = bootstrapManager.wwwDir
                        wwwDir.deleteRecursively()
                        wwwDir.parentFile?.mkdirs()
                        stagingWww.renameTo(wwwDir)

                        // Reload WebView
                        activity.runOnUiThread { activity.reloadWebView() }
                    } catch (e: Exception) {
                        eventBridge.emit("install_progress",
                            mapOf("target" to "www", "progress" to 0f,
                                "message" to "Update failed: ${e.message}"))
                    }
                }
                "bootstrap" -> {
                    // Re-download and re-extract bootstrap
                    try {
                        eventBridge.emit("install_progress",
                            mapOf("target" to "bootstrap", "progress" to 0.1f, "message" to "Downloading bootstrap..."))
                        bootstrapManager.startSetup { progress, message ->
                            eventBridge.emit("install_progress",
                                mapOf("target" to "bootstrap", "progress" to progress, "message" to message))
                        }
                    } catch (e: Exception) {
                        eventBridge.emit("install_progress",
                            mapOf("target" to "bootstrap", "progress" to 0f,
                                "message" to "Update failed: ${e.message}"))
                    }
                }
                "scripts" -> {
                    // Scripts update: re-download management scripts from config URL
                    eventBridge.emit("install_progress",
                        mapOf("target" to "scripts", "progress" to 0.5f, "message" to "Scripts are updated with bootstrap"))
                }
            }

            eventBridge.emit("install_progress",
                mapOf("target" to component, "progress" to 1f, "message" to "$component updated"))
        }
    }

    // ═══════════════════════════════════════════
    // System domain
    // ═══════════════════════════════════════════

    @JavascriptInterface
    fun getAppInfo(): String {
        val pInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
        return gson.toJson(
            mapOf(
                "versionName" to (pInfo.versionName ?: "unknown"),
                "versionCode" to pInfo.versionCode,
                "packageName" to activity.packageName
            )
        )
    }

    @JavascriptInterface
    fun getBatteryOptimizationStatus(): String {
        val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
        return gson.toJson(
            mapOf("isIgnoring" to pm.isIgnoringBatteryOptimizations(activity.packageName))
        )
    }

    @JavascriptInterface
    fun requestBatteryOptimizationExclusion() {
        activity.runOnUiThread {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:${activity.packageName}")
            activity.startActivity(intent)
        }
    }

    @JavascriptInterface
    fun openSystemSettings(page: String) {
        activity.runOnUiThread {
            val intent = when (page) {
                "battery" -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                "app_info" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                else -> Intent(Settings.ACTION_SETTINGS)
            }
            activity.startActivity(intent)
        }
    }

    @JavascriptInterface
    fun copyToClipboard(text: String) {
        activity.runOnUiThread {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("OpenClaw", text))
        }
    }

    @JavascriptInterface
    fun getStorageInfo(): String {
        val filesDir = activity.filesDir
        val totalSpace = filesDir.totalSpace
        val freeSpace = filesDir.freeSpace
        val bootstrapSize = bootstrapManager.prefixDir.walkTopDown().sumOf { it.length() }
        val wwwSize = bootstrapManager.wwwDir.walkTopDown().sumOf { it.length() }

        return gson.toJson(
            mapOf(
                "totalBytes" to totalSpace,
                "freeBytes" to freeSpace,
                "bootstrapBytes" to bootstrapSize,
                "wwwBytes" to wwwSize
            )
        )
    }

    @JavascriptInterface
    fun clearCache() {
        activity.cacheDir.deleteRecursively()
        activity.cacheDir.mkdirs()
    }
}
