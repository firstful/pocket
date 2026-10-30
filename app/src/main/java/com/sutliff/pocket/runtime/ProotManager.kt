package com.sutliff.pocket.runtime

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

class ProotManager(private val context: Context) {

    private val TAG = "ProotManager"
    private val rootDir = File(context.filesDir, "proot_env")
    private val prootBin = File(rootDir, "bin/proot")
    private val alpineRoot = File(rootDir, "alpine")
    private val client = OkHttpClient()

    fun isReady(): Boolean = prootBin.exists() && alpineRoot.exists() && File(alpineRoot, "bin/sh").exists()

    suspend fun ensureReady(): Boolean = withContext(Dispatchers.IO) {
        if (isReady()) return@withContext true
        rootDir.mkdirs()
        try {
            if (!prootBin.exists()) downloadProot()
            if (!alpineRoot.exists()) downloadAlpine()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set up proot environment", e)
            false
        }
    }

    private fun downloadProot() {
        // Download proot static binary for aarch64
        val binDir = File(rootDir, "bin")
        binDir.mkdirs()
        val url = "https://github.com/proot-me/proot/releases/download/v5.4.0/proot-v5.4.0-aarch64-static"
        downloadFile(url, prootBin)
        prootBin.setExecutable(true)
        Log.d(TAG, "proot downloaded")
    }

    private fun downloadAlpine() {
        alpineRoot.mkdirs()
        val url = "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.0-aarch64.tar.gz"
        val tarFile = File(rootDir, "alpine.tar.gz")
        downloadFile(url, tarFile)
        // Extract
        extractTarGz(tarFile, alpineRoot)
        tarFile.delete()
        Log.d(TAG, "Alpine extracted")
    }

    private fun downloadFile(url: String, dest: File) {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw Exception("Download failed: $url (${response.code})")
        response.body?.byteStream()?.use { input ->
            FileOutputStream(dest).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun extractTarGz(tarFile: File, destDir: File) {
        // Use tar command if available on Android, otherwise use Java
        // Android doesn't have tar by default, so use a simple extraction
        // For now, use the system tar if available
        val tar = File("/system/bin/tar")
        if (tar.exists()) {
            val proc = ProcessBuilder(tar.absolutePath, "-xzf", tarFile.absolutePath, "-C", destDir.absolutePath)
                .redirectErrorStream(true)
                .start()
            proc.waitFor()
            if (proc.exitValue() != 0) throw Exception("tar extraction failed: ${proc.inputStream.bufferedReader().readText()}")
        } else {
            // Fallback: use GZIP + basic tar parsing (limited)
            throw Exception("tar not available on this device")
        }
    }

    fun buildCommand(cmd: String): List<String> {
        return listOf(
            prootBin.absolutePath,
            "-r", alpineRoot.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "/bin/sh", "-c", cmd
        )
    }

    fun getRootPath(): String = alpineRoot.absolutePath
}
