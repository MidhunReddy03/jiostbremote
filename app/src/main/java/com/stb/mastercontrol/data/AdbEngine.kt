package com.stb.mastercontrol.data

import android.content.Context
import android.net.Uri
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Snapshot of the STB RAM usage.
 *
 * All values are in megabytes so the UI can render them directly.
 */
data class RamStats(
    val freeMb: Int,
    val totalMb: Int
) {
    /** Fraction of RAM currently in use, clamped to 0..1. */
    val usedFraction: Float
        get() = if (totalMb <= 0) 0f
        else ((totalMb - freeMb).toFloat() / totalMb.toFloat()).coerceIn(0f, 1f)
}

/** A launchable TV app installed on the STB. */
data class TvApp(
    val packageName: String,
    val label: String
)

/**
 * Direct ADB-over-TCP client for the Jio set-top box.
 *
 * Every network call is dispatched to [Dispatchers.IO] so callers may invoke these
 * suspend functions from `viewModelScope` without blocking the main thread.
 */
class AdbEngine(private val context: Context) {

    private val lock = Any()

    @Volatile
    private var dadb: Dadb? = null

    private val keyDir: File by lazy {
        File(context.filesDir, "adb").apply { mkdirs() }
    }
    private val privateKeyFile: File by lazy { File(keyDir, "adbkey") }
    private val publicKeyFile: File by lazy { File(keyDir, "adbkey.pub") }

    /**
     * ADB requires an RSA key pair. We keep ours in the app's private storage so the
     * STB only has to authorise this device once.
     */
    private fun keyPair(): AdbKeyPair = synchronized(lock) {
        if (!privateKeyFile.exists() || !publicKeyFile.exists()) {
            AdbKeyPair.generate(privateKeyFile, publicKeyFile)
        }
        AdbKeyPair.read(privateKeyFile, publicKeyFile)
    }

    suspend fun connect(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            closeLocked()
            try {
                val instance = Dadb.create(
                    host = host,
                    port = port,
                    keyPair = keyPair(),
                    connectTimeout = 4000,
                    socketTimeout = 8000
                )
                val probe = instance.shell("echo ping").output.trim()
                if (probe == "ping") {
                    dadb = instance
                    true
                } else {
                    instance.closeQuietly()
                    false
                }
            } catch (_: Throwable) {
                false
            }
        }
    }

    /** Cheap liveness probe used by the connection watchdog. */
    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        withDadb { it.shell("echo ping").output.contains("ping") } ?: false
    }

    suspend fun sendKeyEvent(keyCode: Int): Boolean = withContext(Dispatchers.IO) {
        withDadb { it.shell("input keyevent $keyCode").exitCode == 0 } ?: false
    }

    /** Types [text] on the STB. Spaces are sent as `%s`, which `input text` expects. */
    suspend fun sendText(text: String) {
        if (text.isEmpty()) return
        withContext(Dispatchers.IO) {
            withDadb { it.shell("input text ${escapeInputText(text)}") }
        }
    }

    /** Sends a relative trackpad movement using `input roll dx dy`. */
    suspend fun sendTrackpadRoll(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        withContext(Dispatchers.IO) {
            withDadb { it.shell("input roll $dx $dy") }
        }
    }

    suspend fun fetchRamStats(): RamStats? = withContext(Dispatchers.IO) {
        withDadb {
            val meminfo = it.shell("cat /proc/meminfo").output
            val totalKb = Regex("MemTotal:\\s+(\\d+)")
                .find(meminfo)?.groupValues?.get(1)?.toLongOrNull()
            val freeKb = Regex("MemAvailable:\\s+(\\d+)")
                .find(meminfo)?.groupValues?.get(1)?.toLongOrNull()
                ?: Regex("MemFree:\\s+(\\d+)")
                    .find(meminfo)?.groupValues?.get(1)?.toLongOrNull()

            if (totalKb == null || freeKb == null) return@withDadb null
            RamStats(
                freeMb = (freeKb / 1024L).toInt(),
                totalMb = (totalKb / 1024L).toInt()
            )
        }
    }

    /**
     * Lists user-installed apps (`pm list packages -3`). Labels are derived from the
     * package name because querying real labels over ADB is prohibitively slow.
     */
    suspend fun fetchInstalledApps(): List<TvApp> = withContext(Dispatchers.IO) {
        withDadb {
            it.shell("pm list packages -3").output
                .lineSequence()
                .map { line -> line.trim() }
                .filter { line -> line.startsWith("package:") }
                .map { line -> line.removePrefix("package:").trim() }
                .filter { pkg -> pkg.isNotEmpty() }
                .distinct()
                .sorted()
                .map { pkg -> TvApp(packageName = pkg, label = prettyLabel(pkg)) }
                .toList()
        } ?: emptyList()
    }

    /**
     * Trims the STB's app caches. Requires the `CLEAR_APP_CACHE` permission, which the
     * `shell` user holds on stock Android TV builds.
     */
    suspend fun cleanSystemCache(): Boolean = withContext(Dispatchers.IO) {
        withDadb {
            val response = it.shell("pm trim-caches 1024M")
            val text = response.allOutput
            response.exitCode == 0 &&
                !text.contains("Error", ignoreCase = true) &&
                !text.contains("Exception", ignoreCase = true) &&
                !text.contains("Permission Denial", ignoreCase = true)
        } ?: false
    }

    suspend fun launchApp(packageName: String) {
        withContext(Dispatchers.IO) {
            withDadb {
                it.shell("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
            }
        }
    }

    /**
     * Streams the selected APK to the STB and installs it with `-r -g`
     * (replace existing + grant runtime permissions).
     *
     * [onProgress] is invoked from a background thread with values in the 0..1 range.
     */
    suspend fun sideloadApk(
        uri: Uri,
        onProgress: (Float, String) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val instance = dadb
            ?: return@withContext Result.failure(IOException("Not connected to STB"))

        var tempFile: File? = null
        try {
            onProgress(0.05f, "Copying APK...")
            val apk = File(context.cacheDir, "sideload-${System.currentTimeMillis()}.apk")
            tempFile = apk
            context.contentResolver.openInputStream(uri)?.use { input ->
                apk.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IOException("Unable to read the selected APK")

            if (!apk.exists() || apk.length() == 0L) {
                throw IOException("Selected APK is empty")
            }

            onProgress(0.35f, "Uploading APK (${apk.length() / 1024L} KB)...")
            onProgress(0.60f, "Installing on STB...")
            instance.install(apk, "-r", "-g")
            onProgress(1f, "Installed successfully")
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            runCatching { tempFile?.delete() }
        }
    }

    fun close() {
        synchronized(lock) { closeLocked() }
    }

    private fun closeLocked() {
        runCatching { dadb?.close() }
        dadb = null
    }

    /** Runs [block] with the live connection, returning `null` when disconnected. */
    private inline fun <T> withDadb(block: (Dadb) -> T): T? {
        val instance = dadb ?: return null
        return try {
            block(instance)
        } catch (_: Throwable) {
            null
        }
    }

    private fun Dadb.closeQuietly() {
        runCatching { close() }
    }

    private fun prettyLabel(packageName: String): String {
        val tail = packageName.substringAfterLast('.')
        return tail.split('_', '-')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase() else char.toString()
                }
            }
            .ifBlank { packageName }
    }

    private fun escapeInputText(text: String): String {
        val builder = StringBuilder(text.length + 8)
        builder.append('"')
        for (char in text) {
            when (char) {
                ' ' -> builder.append("%s")
                '\\' -> builder.append("\\\\")
                '"' -> builder.append("\\\"")
                '\'' -> builder.append("\\'")
                '$' -> builder.append("\\$")
                '`' -> builder.append("\\`")
                '\n', '\r', '\t' -> builder.append("%s")
                else -> builder.append(char)
            }
        }
        builder.append('"')
        return builder.toString()
    }
}
