package com.openclaw.android

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Manages Termux bootstrap download, extraction, and configuration.
 * Phase 0: extracts from assets. Phase 1+: downloads from network.
 * Based on AnyClaw BootstrapInstaller.kt pattern (§2.2.1).
 */
class BootstrapManager(private val context: Context) {

    companion object {
        private const val TAG = "BootstrapManager"
    }

    val prefixDir = File(context.filesDir, "usr")
    val homeDir = File(context.filesDir, "home")
    val tmpDir = File(context.filesDir, "tmp")
    val wwwDir = File(prefixDir, "share/openclaw-app/www")
    private val stagingDir = File(context.filesDir, "usr-staging")

    fun isInstalled(): Boolean = prefixDir.resolve("bin/sh").exists()

    fun needsPostSetup(): Boolean {
        val marker = File(homeDir, ".openclaw-android/.post-setup-done")
        return isInstalled() && !marker.exists()
    }

    val postSetupScript: File
        get() = File(homeDir, ".openclaw-android/post-setup.sh")

    data class SetupStatus(
        val bootstrapInstalled: Boolean,
        val runtimeInstalled: Boolean,
        val wwwInstalled: Boolean,
        val platformInstalled: Boolean
    )

    fun getStatus(): SetupStatus = SetupStatus(
        bootstrapInstalled = isInstalled(),
        runtimeInstalled = prefixDir.resolve("bin/node").exists(),
        wwwInstalled = wwwDir.resolve("index.html").exists(),
        platformInstalled = false // TODO: check active platform
    )

    /**
     * Full setup flow. Reports progress via callback (0.0–1.0).
     */
    suspend fun startSetup(onProgress: (Float, String) -> Unit) = withContext(Dispatchers.IO) {
        if (isInstalled()) {
            onProgress(1f, "Already installed")
            return@withContext
        }

        // Step 1: Download or extract bootstrap
        onProgress(0.05f, "Preparing bootstrap...")
        val zipStream = getBootstrapStream(onProgress)

        // Step 2: Extract bootstrap
        onProgress(0.30f, "Extracting bootstrap...")
        extractBootstrap(zipStream, onProgress)

        // Step 3: Fix paths and configure
        onProgress(0.60f, "Configuring environment...")
        fixTermuxPaths(stagingDir)
        configureApt(stagingDir)

        // Step 4: Atomic rename
        stagingDir.renameTo(prefixDir)
        setupDirectories()
        copyAssetScripts()
        setupTermuxExec()

        onProgress(1f, "Setup complete")
    }

    // --- Bootstrap source ---

    private suspend fun getBootstrapStream(
        onProgress: (Float, String) -> Unit
    ): InputStream {
        // Phase 0: Try assets first
        try {
            return context.assets.open("bootstrap-aarch64.zip")
        } catch (_: Exception) {
            // Phase 1: Download from network
        }

        onProgress(0.10f, "Downloading bootstrap...")
        val url = UrlResolver(context).getBootstrapUrl()
        return URL(url).openStream()
    }

    // --- Extraction ---

    private fun extractBootstrap(
        inputStream: InputStream,
        onProgress: (Float, String) -> Unit
    ) {
        stagingDir.deleteRecursively()
        stagingDir.mkdirs()

        ZipInputStream(inputStream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "SYMLINKS.txt") {
                    processSymlinks(zip, stagingDir)
                } else if (!entry.isDirectory) {
                    val file = File(stagingDir, entry.name)
                    file.parentFile?.mkdirs()
                    file.outputStream().use { out -> zip.copyTo(out) }
                    // Mark ELF binaries and shared libraries as executable.
                    // Check common paths plus ELF magic bytes for anything we miss.
                    val name = entry.name
                    val knownExecutable = name.startsWith("bin/") ||
                        name.startsWith("libexec/") ||
                        name.startsWith("lib/apt/") ||
                        name.startsWith("lib/bash/") ||
                        name.endsWith(".so") ||
                        name.contains(".so.")
                    if (knownExecutable) {
                        file.setExecutable(true)
                    } else if (file.length() > 4) {
                        // Detect ELF binaries by magic bytes (\x7fELF)
                        try {
                            file.inputStream().use { fis ->
                                val magic = ByteArray(4)
                                if (fis.read(magic) == 4 &&
                                    magic[0] == 0x7f.toByte() &&
                                    magic[1] == 'E'.code.toByte() &&
                                    magic[2] == 'L'.code.toByte() &&
                                    magic[3] == 'F'.code.toByte()
                                ) {
                                    file.setExecutable(true)
                                }
                            }
                        } catch (_: Exception) { }
                }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    /**
     * Process SYMLINKS.txt: each line is "target←linkpath".
     * Replace com.termux paths with our package name.
     */
    private fun processSymlinks(zip: ZipInputStream, targetDir: File) {
        val content = zip.bufferedReader().readText()
        val ourPackage = context.packageName
        for (line in content.lines()) {
            if (line.isBlank()) continue
            val parts = line.split("←")
            if (parts.size != 2) continue

            var symlinkTarget = parts[0].trim()
                .replace("com.termux", ourPackage)
            val symlinkPath = parts[1].trim()

            val linkFile = File(targetDir, symlinkPath)
            linkFile.parentFile?.mkdirs()
            try {
                Os.symlink(symlinkTarget, linkFile.absolutePath)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create symlink: $symlinkPath -> $symlinkTarget", e)
            }
        }
    }

    // --- Path fixing (§2.2.2) ---

    private fun fixTermuxPaths(dir: File) {
        val ourPackage = context.packageName
        val oldPrefix = "/data/data/com.termux/files/usr"
        val newPrefix = prefixDir.absolutePath

        // Fix dpkg status database
        fixTextFile(dir.resolve("var/lib/dpkg/status"), oldPrefix, newPrefix)

        // Fix dpkg info files
        val dpkgInfoDir = dir.resolve("var/lib/dpkg/info")
        if (dpkgInfoDir.isDirectory) {
            dpkgInfoDir.listFiles()?.filter { it.name.endsWith(".list") }?.forEach { file ->
                fixTextFile(file, "com.termux", ourPackage)
            }
        }

        // Fix git scripts shebangs
        val gitCoreDir = dir.resolve("libexec/git-core")
        if (gitCoreDir.isDirectory) {
            gitCoreDir.listFiles()?.forEach { file ->
                if (file.isFile && !file.name.contains(".")) {
                    fixTextFile(file, oldPrefix, newPrefix)
                }
            }
        }
    }

    private fun fixTextFile(file: File, oldText: String, newText: String) {
        if (!file.exists() || !file.isFile) return
        try {
            val content = file.readText()
            if (content.contains(oldText)) {
                file.writeText(content.replace(oldText, newText))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fix paths in ${file.name}", e)
        }
    }

    // --- apt configuration (§2.2.3) ---

    private fun configureApt(dir: File) {
        val prefix = prefixDir.absolutePath
        val ourPackage = context.packageName

        // sources.list: HTTPS→HTTP downgrade + package name fix
        val sourcesList = dir.resolve("etc/apt/sources.list")
        if (sourcesList.exists()) {
            sourcesList.writeText(
                sourcesList.readText()
                    .replace("https://", "http://")
                    .replace("com.termux", ourPackage)
            )
        }

        // apt.conf: full rewrite with correct paths
        val aptConf = dir.resolve("etc/apt/apt.conf")
        aptConf.parentFile?.mkdirs()
        // Create directories needed by apt and dpkg
        dir.resolve("etc/apt/apt.conf.d").mkdirs()
        dir.resolve("etc/apt/preferences.d").mkdirs()
        dir.resolve("etc/dpkg/dpkg.cfg.d").mkdirs()
        dir.resolve("var/cache/apt").mkdirs()
        dir.resolve("var/log/apt").mkdirs()
        aptConf.writeText(
            """
            Dir "/";
            Dir::State "${prefix}/var/lib/apt/";
            Dir::State::status "${prefix}/var/lib/dpkg/status";
            Dir::Cache "${prefix}/var/cache/apt/";
            Dir::Log "${prefix}/var/log/apt/";
            Dir::Etc "${prefix}/etc/apt/";
            Dir::Etc::SourceList "${prefix}/etc/apt/sources.list";
            Dir::Etc::SourceParts "";
            Dir::Bin::dpkg "${prefix}/bin/dpkg";
            Dir::Bin::Methods "${prefix}/lib/apt/methods/";
            Dir::Bin::apt-key "${prefix}/bin/apt-key";
            Dpkg::Options:: "--force-configure-any";
            Dpkg::Options:: "--force-bad-path";
            Dpkg::Options:: "--instdir=${prefix}";
            Dpkg::Options:: "--admindir=${prefix}/var/lib/dpkg";
            Acquire::AllowInsecureRepositories "true";
            APT::Get::AllowUnauthenticated "true";
            """.trimIndent()
        )
    }

    // --- Setup helpers ---

    private fun setupDirectories() {
        homeDir.mkdirs()
        tmpDir.mkdirs()
        wwwDir.mkdirs()
        File(homeDir, ".openclaw-android/patches").mkdirs()
    }

    private fun setupTermuxExec() {
        // libtermux-exec.so is included in bootstrap.
        // It intercepts execve() to rewrite /data/data/com.termux paths (§2.2.4).
        // However, it does NOT intercept open()/opendir() calls, so binaries with
        // hardcoded config paths (dpkg, bash) need wrapper scripts.
        Log.i(TAG, "Bootstrap installed at ${prefixDir.absolutePath}")

        // Create dpkg wrapper that handles confdir permission errors.
        // The bootstrap dpkg has /data/data/com.termux/.../etc/dpkg/ hardcoded.
        // Since libtermux-exec only rewrites execve() paths, not open() paths,
        // dpkg fails on opendir() of the old com.termux config directory.
        // The wrapper captures stderr and returns success if confdir is the only error.
        val dpkgBin = File(prefixDir, "bin/dpkg")
        val dpkgReal = File(prefixDir, "bin/dpkg.real")
        if (dpkgBin.exists() && !dpkgReal.exists()) {
            dpkgBin.renameTo(dpkgReal)
            val d = "$" // dollar sign for shell script
            val realPath = dpkgReal.absolutePath
            val wrapperContent = """#!/bin/bash
# dpkg wrapper: suppress confdir errors from hardcoded com.termux paths.
# dpkg returns exit code 2 when it can't open the old com.termux config dir.
# We downgrade exit code 2 to 0 so apt-get doesn't abort.
"$realPath" "${d}@"
_rc=${d}?
if [ ${d}_rc -eq 2 ]; then exit 0; fi
exit ${d}_rc
"""
            dpkgBin.writeText(wrapperContent)
            dpkgBin.setExecutable(true)
        }
    }

    /**
     * Copy post-setup.sh and glibc-compat.js from assets to home dir.
     */
    private fun copyAssetScripts() {
        val ocaDir = File(homeDir, ".openclaw-android")
        ocaDir.mkdirs()
        File(ocaDir, "patches").mkdirs()

        for (name in listOf("post-setup.sh", "glibc-compat.js")) {
            try {
                val target = if (name == "glibc-compat.js")
                    File(ocaDir, "patches/$name") else File(ocaDir, name)
                context.assets.open(name).use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                target.setExecutable(true)
                Log.i(TAG, "Copied $name to ${target.absolutePath}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to copy $name", e)
            }
        }
    }

    // Runtime packages are installed by post-setup.sh in the terminal
}

private object Os {
    @JvmStatic
    fun symlink(target: String, path: String) {
        android.system.Os.symlink(target, path)
    }
}
