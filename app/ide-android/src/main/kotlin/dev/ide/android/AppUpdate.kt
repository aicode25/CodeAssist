package dev.ide.android

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app update check against the project's GitHub Releases.
 *
 * It is deliberately small: the APK is the artifact the release workflow uploads, so a newer `versionCode`
 * in the release tag means a newer app. Nothing is downloaded or installed without the user tapping through
 * both the update prompt and Android's own installer confirmation; a release check never publishes anything.
 *
 * Dependency-free on purpose (HttpURLConnection + org.json are on every supported Android version), so the
 * checker can never keep the app from starting if GitHub is unreachable or changes its JSON shape.
 */
internal object AppUpdate {
    private const val REPO = "aicode25/CodeAssist"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val PREFS = "app-update"
    private const val KEY_LAST_CHECK = "lastCheck"
    private const val KEY_LAST_NOTIFIED = "lastNotified"
    private const val CHECK_INTERVAL_MS = 12L * 60L * 60L * 1000L
    private const val CHANNEL = "app-updates"
    private const val NOTIFICATION_ID = 0x0CA5

    const val EXTRA_VERSION_NAME = "dev.ide.android.update.versionName"
    const val EXTRA_VERSION_CODE = "dev.ide.android.update.versionCode"
    const val EXTRA_APK_URL = "dev.ide.android.update.apkUrl"
    const val EXTRA_HTML_URL = "dev.ide.android.update.htmlUrl"
    const val EXTRA_NOTES = "dev.ide.android.update.notes"

    data class Release(
        val versionName: String,
        val versionCode: Int,
        val apkUrl: String,
        val htmlUrl: String,
        val notes: String,
    )

    /**
     * Check (at most every 12h) and, when a newer release exists, post one notification. If the user has
     * notifications turned off, [onUpdateAvailable] is invoked on the main thread instead, so the caller can
     * show the same update prompt in-app. Never throws.
     */
    fun checkAndNotify(context: Context, onUpdateAvailable: ((Release) -> Unit)? = null) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            val release = runCatching { fetchLatest() }.getOrNull() ?: return@launch
            if (!isNewer(release, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)) return@launch
            if (prefs.getInt(KEY_LAST_NOTIFIED, 0) >= release.versionCode) return@launch
            prefs.edit().putInt(KEY_LAST_NOTIFIED, release.versionCode).apply()
            withContext(Dispatchers.Main) {
                if (NotificationManagerCompat.from(app).areNotificationsEnabled()) {
                    notify(app, release)
                } else {
                    runCatching { onUpdateAvailable?.invoke(release) }
                }
            }
        }
    }

    /** The release carried by a tapped update notification, or null for an ordinary launch intent. */
    fun fromIntent(intent: Intent?): Release? {
        val version = intent?.getStringExtra(EXTRA_VERSION_NAME) ?: return null
        val apk = intent.getStringExtra(EXTRA_APK_URL) ?: return null
        return Release(
            versionName = version,
            versionCode = intent.getIntExtra(EXTRA_VERSION_CODE, 0),
            apkUrl = apk,
            htmlUrl = intent.getStringExtra(EXTRA_HTML_URL).orEmpty(),
            notes = intent.getStringExtra(EXTRA_NOTES).orEmpty(),
        )
    }

    private fun fetchLatest(): Release? {
        val connection = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "CodeAssist-Android")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name")
            val name = json.optString("name").ifBlank { tag }
            val html = json.optString("html_url")
            val body = json.optString("body").lineSequence().take(18).joinToString("\n").take(1_500)
            val assets = json.optJSONArray("assets") ?: return null
            var apkUrl: String? = null
            var fallback: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val assetName = asset.optString("name")
                val url = asset.optString("browser_download_url")
                if (!url.startsWith("https://") || !assetName.endsWith(".apk", ignoreCase = true)) continue
                if (fallback == null) fallback = url
                if (!assetName.contains("debug", ignoreCase = true) &&
                    !assetName.contains("unsigned", ignoreCase = true)
                ) {
                    apkUrl = url
                    break
                }
            }
            apkUrl = apkUrl ?: fallback ?: return null
            val (versionName, versionCode) = parseVersion(tag, name) ?: return null
            return Release(versionName, versionCode, apkUrl, html, body)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Parse `v3.45.1-134` (the release workflow's tag), or a plain `3.45.1`/`v3.45.1`. The trailing build
     * number is what ordinal comparison uses; without one, `versionCode` is derived from the semver so an
     * older release can never look newer than a current build.
     */
    private fun parseVersion(tag: String, name: String): Pair<String, Int>? {
        val text = tag.trim().ifEmpty { name.trim() }
        val semver = Regex("""
            ^[vV]?(\d+(?:\.\d+){0,3})(?:[-_ ](?:build[-_ ]?)?(\d+))?$
        """.trimIndent()).find(text)
        val match = semver ?: Regex("""[vV]?(\d+(?:\.\d+){0,3})(?:[-_ ](?:build[-_ ]?)?(\d+))?""").find(text)
            ?: return null
        val version = match.groupValues[1]
        val explicitCode = match.groupValues.getOrNull(2)?.toIntOrNull()
        val parts = version.split('.').map { it.toIntOrNull() ?: 0 }
        val derived = parts.fold(0) { acc, part -> acc * 1000 + part }
        return version to (explicitCode ?: derived)
    }

    private fun isNewer(release: Release, currentName: String, currentCode: Int): Boolean {
        if (release.versionCode > 0 && currentCode > 0) return release.versionCode > currentCode
        val a = release.versionName.split('.').map { it.toIntOrNull() ?: 0 }
        val b = currentName.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun notify(context: Context, release: Release) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "A new CodeAssist release is available"
                },
            )
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_VERSION_NAME, release.versionName)
            putExtra(EXTRA_VERSION_CODE, release.versionCode)
            putExtra(EXTRA_APK_URL, release.apkUrl)
            putExtra(EXTRA_HTML_URL, release.htmlUrl)
            putExtra(EXTRA_NOTES, release.notes)
        }
        val pending = PendingIntent.getActivity(
            context, NOTIFICATION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0),
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("CodeAssist MAX ${release.versionName} is available")
            .setContentText("Tap to download and install the update.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(release.notes.ifBlank { "A new release is available." }))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
        }
    }

    /** The update dialog. Downloading and installing continue only after the user accepts. */
    fun prompt(activity: Activity, release: Release) {
        if (activity.isFinishing || activity.isDestroyed) return
        val message = buildString {
            append("CodeAssist MAX ${release.versionName} is available. You are running ${BuildConfig.VERSION_NAME}.")
            if (release.notes.isNotBlank()) append("\n\n").append(release.notes)
        }
        AlertDialog.Builder(activity)
            .setTitle("Update CodeAssist MAX")
            .setMessage(message)
            .setPositiveButton("Download & install") { _, _ -> downloadAndInstall(activity, release) }
            .setNeutralButton("Release page") { _, _ ->
                if (release.htmlUrl.isNotBlank()) {
                    runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl))) }
                }
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun downloadAndInstall(activity: Activity, release: Release) {
        val pm = activity.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !pm.canRequestPackageInstalls()) {
            Toast.makeText(activity, "Allow CodeAssist to install apps, then tap the update again.", Toast.LENGTH_LONG).show()
            runCatching {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
                )
            }
            return
        }
        Toast.makeText(activity, "Downloading CodeAssist MAX ${release.versionName}…", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            val file = runCatching { download(activity, release) }.getOrNull()
            withContext(Dispatchers.Main) {
                if (file == null) {
                    Toast.makeText(activity, "Couldn't download the update.", Toast.LENGTH_LONG).show()
                    return@withContext
                }
                val uri = runCatching {
                    FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
                }.getOrNull()
                if (uri == null) {
                    Toast.makeText(activity, "Couldn't open the downloaded update.", Toast.LENGTH_LONG).show()
                    return@withContext
                }
                runCatching {
                    activity.startActivity(
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                    )
                }.onFailure {
                    Toast.makeText(activity, "No package installer available.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun download(activity: Activity, release: Release): File? {
        val dir = File(activity.externalCacheDir ?: activity.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "codeassist-${release.versionName}.apk")
        if (file.isFile && file.length() > MIN_APK_BYTES) return file
        val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "CodeAssist-Android")
        }
        try {
            if (connection.responseCode !in 200..299) return null
            val tmp = File(dir, "${file.name}.part")
            connection.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (tmp.length() <= MIN_APK_BYTES) { tmp.delete(); return null }
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
            return file
        } finally {
            connection.disconnect()
        }
    }

    private const val MIN_APK_BYTES = 1_000_000L
}
