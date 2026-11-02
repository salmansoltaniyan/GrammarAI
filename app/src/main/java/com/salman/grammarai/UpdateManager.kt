package com.salman.grammarai

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val releaseTitle: String,
    val changelog: String,
    val apkDownloadUrl: String?,
    val htmlUrl: String,
    val isNewer: Boolean
)

class UpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "UpdateManager"
        private const val GITHUB_OWNER = "salmansoltaniyan"
        private const val GITHUB_REPO = "GrammarAI"
        private const val LATEST_RELEASE_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun checkForUpdates(): Result<ReleaseInfo> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(LATEST_RELEASE_URL)
                    .header("Accept", "application/vnd.github.v3+json")
                    .get()
                    .build()

                val response = httpClient.newCall(request).execute()
                val body = response.body?.string() ?: throw Exception("Empty response from GitHub API")

                if (!response.isSuccessful) {
                    if (response.code == 404) {
                        throw Exception("No releases found on GitHub yet.")
                    }
                    val msg = try {
                        JSONObject(body).optString("message", "HTTP ${response.code}")
                    } catch (e: Exception) {
                        "HTTP ${response.code}"
                    }
                    throw Exception("GitHub API error: $msg")
                }

                val json = JSONObject(body)
                val tagName = json.optString("tag_name", "").trim()
                val releaseTitle = json.optString("name", tagName).trim()
                val changelog = json.optString("body", "No changelog provided.").trim()
                val htmlUrl = json.optString("html_url", "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases")

                // Find APK asset
                var apkUrl: String? = null
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val assetName = asset.optString("name", "")
                        if (assetName.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = asset.optString("browser_download_url", null)
                            break
                        }
                    }
                }

                val normalizedRemote = cleanVersionString(tagName)
                val currentVersion = cleanVersionString(BuildConfig.VERSION_NAME)
                val isNewer = isVersionNewer(normalizedRemote, currentVersion)

                val info = ReleaseInfo(
                    tagName = tagName,
                    versionName = normalizedRemote,
                    releaseTitle = releaseTitle,
                    changelog = changelog,
                    apkDownloadUrl = apkUrl,
                    htmlUrl = htmlUrl,
                    isNewer = isNewer
                )

                Result.success(info)
            } catch (e: Exception) {
                Log.e(TAG, "Error checking for updates", e)
                Result.failure(e)
            }
        }
    }

    fun showUpdateDialog(release: ReleaseInfo) {
        val currentVer = BuildConfig.VERSION_NAME
        val message = StringBuilder().apply {
            append("A new version of Grammar AI is available!\n\n")
            append("• Current Version: v$currentVer\n")
            append("• Latest Version: ${release.tagName}\n\n")
            if (release.changelog.isNotBlank()) {
                append("What's New:\n")
                append(release.changelog.take(500))
                if (release.changelog.length > 500) append("...")
            }
        }.toString()

        val builder = MaterialAlertDialogBuilder(context)
            .setTitle("🚀 New Update Available")
            .setMessage(message)
            .setCancelable(true)
            .setNegativeButton("Later", null)
            .setNeutralButton("View on GitHub") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl))
                context.startActivity(intent)
            }

        if (!release.apkDownloadUrl.isNullOrBlank()) {
            builder.setPositiveButton("Download & Install") { _, _ ->
                startDownloadAndInstall(release.apkDownloadUrl, release.tagName)
            }
        } else {
            builder.setPositiveButton("Download from GitHub") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl))
                context.startActivity(intent)
            }
        }

        builder.show()
    }

    private fun startDownloadAndInstall(apkUrl: String, tagName: String) {
        // Check unknown sources permission on Android 8+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(context, "Please allow installing apps from Grammar AI", Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
                // Also open browser as immediate fallback
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl))
                context.startActivity(browserIntent)
                return
            }
        }

        try {
            val fileName = "GrammarAI-$tagName.apk"
            val destinationFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
            if (destinationFile.exists()) {
                destinationFile.delete()
            }

            val request = DownloadManager.Request(Uri.parse(apkUrl)).apply {
                setTitle("Downloading Grammar AI $tagName")
                setDescription("Downloading APK update...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationUri(Uri.fromFile(destinationFile))
                setMimeType("application/vnd.android.package-archive")
            }

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = downloadManager.enqueue(request)
            Toast.makeText(context, "Download started... You'll be prompted to install once complete.", Toast.LENGTH_LONG).show()

            // Listen for download completion
            val onCompleteReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctxt: Context?, intent: Intent?) {
                    val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
                    if (id == downloadId) {
                        try {
                            context.unregisterReceiver(this)
                        } catch (e: Exception) {
                            // Already unregistered
                        }
                        installApk(destinationFile)
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    onCompleteReceiver,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETED),
                    Context.RECEIVER_EXPORTED
                )
            } else {
                context.registerReceiver(
                    onCompleteReceiver,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETED)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading APK via DownloadManager, falling back to browser", e)
            Toast.makeText(context, "Opening browser for download...", Toast.LENGTH_SHORT).show()
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl))
            context.startActivity(browserIntent)
        }
    }

    private fun installApk(file: File) {
        if (!file.exists()) {
            Toast.makeText(context, "Downloaded file not found", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val apkUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error launching package installer", e)
            Toast.makeText(context, "Error launching installer: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun cleanVersionString(version: String): String {
        return version.trim().removePrefix("v").removePrefix("V").trim()
    }

    private fun isVersionNewer(remote: String, current: String): Boolean {
        if (remote.isBlank() || current.isBlank()) return false
        if (remote == current) return false

        try {
            val remoteParts = remote.split(".").map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
            val currentParts = current.split(".").map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }

            val maxLen = maxOf(remoteParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val r = remoteParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
            return false
        } catch (e: Exception) {
            return remote != current
        }
    }
}
