package com.pstranslator.tv

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object TvAppUpdater {
    private const val GITHUB_LATEST_RELEASE_API = "https://api.github.com/repos/teeradream/ps-translator/releases/latest"
    private const val DIRECT_TV_APK_URL = "https://github.com/teeradream/ps-translator/releases/latest/download/TV-PS-Translator-Android.apk"

    data class TvReleaseInfo(
        val tagName: String,
        val title: String,
        val apkUrl: String
    )

    fun isRemoteVersionNewer(remoteTag: String, currentVersion: String): Boolean {
        val remoteParts = remoteTag.trim().removePrefix("v").removePrefix("V").split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = currentVersion.trim().removePrefix("v").removePrefix("V").split(".").mapNotNull { it.toIntOrNull() }
        if (remoteParts.isEmpty() || currentParts.isEmpty()) return false
        val maxLen = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    suspend fun fetchLatestTvReleaseInfo(): TvReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(GITHUB_LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "PS-Translator-Android-TV")
                setRequestProperty("Accept", "application/vnd.github+json")
                connectTimeout = 6000
                readTimeout = 6000
            }
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return@withContext null
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val json = JSONObject(text)
            val tag = json.optString("tag_name", "").trim()
            val name = json.optString("name", tag).trim()
            var apkUrl = DIRECT_TV_APK_URL
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val aName = a.optString("name", "")
                    val url = a.optString("browser_download_url", "")
                    if (url.isEmpty() || !aName.endsWith(".apk", ignoreCase = true)) continue
                    if (aName.equals("TV-PS-Translator-Android.apk", ignoreCase = true) ||
                        aName.contains("tv", ignoreCase = true)
                    ) {
                        apkUrl = url
                        break
                    }
                }
            }
            if (tag.isEmpty()) null else TvReleaseInfo(tag, name, apkUrl)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun downloadAndInstallTvApk(
        activity: Activity,
        apkUrl: String = DIRECT_TV_APK_URL,
        onProgress: (Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val targetFile = File(activity.cacheDir, "ps_translator_tv_update.apk")
            if (targetFile.exists()) targetFile.delete()

            val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "PS-Translator-Android-TV")
                connectTimeout = 15000
                readTimeout = 30000
            }
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return@withContext false
            }

            val totalBytes = conn.contentLengthLong.coerceAtLeast(1L)
            var downloaded = 0L
            var lastPct = -1
            BufferedInputStream(conn.inputStream).use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buf = ByteArray(16384)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                        downloaded += read
                        val pct = ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                        if (pct != lastPct) {
                            lastPct = pct
                            withContext(Dispatchers.Main) { onProgress(pct) }
                        }
                    }
                    output.flush()
                }
            }
            conn.disconnect()

            if (!targetFile.exists() || targetFile.length() < 100_000L) {
                return@withContext false
            }

            withContext(Dispatchers.Main) {
                val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", targetFile)
                } else {
                    Uri.fromFile(targetFile)
                }
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activity.startActivity(installIntent)
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
