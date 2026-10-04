package com.mblivestudio

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the newest GitHub Release, and if its number is higher than this app's versionCode,
 * offers to download and install it.
 * (Android always needs one "Install" tap - fully silent updates are not possible outside Play Store.)
 */
internal object Updater {
    private var checked = false

    fun checkForUpdate(activity: MainActivity, manual: Boolean = false) {
        if (BuildConfig.GITHUB_REPO.startsWith("OWNER")) return
        if (checked && !manual) return
        checked = true
        Thread {
            try {
                val c = URL("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest").openConnection() as HttpURLConnection
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.connectTimeout = 8000; c.readTimeout = 8000
                if (c.responseCode != 200) {
                    if (manual) activity.runOnUiThread { Toast.makeText(activity, "Update check failed (${c.responseCode})", Toast.LENGTH_SHORT).show() }
                    return@Thread
                }
                val json = JSONObject(c.inputStream.bufferedReader().readText())
                val tag = json.getString("tag_name")                       // e.g. v2.0.15
                val remote = tag.substringAfterLast('.').toIntOrNull() ?: return@Thread
                if (remote <= currentVersionCode(activity)) {
                    if (manual) activity.runOnUiThread { Toast.makeText(activity, "You have the latest version", Toast.LENGTH_SHORT).show() }
                    return@Thread
                }
                val assets = json.getJSONArray("assets")
                var apkUrl: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.getString("name").endsWith(".apk")) { apkUrl = a.getString("browser_download_url"); break }
                }
                if (apkUrl == null) return@Thread
                val url: String = apkUrl
                activity.runOnUiThread {
                    AlertDialog.Builder(activity)
                        .setTitle("Update available")
                        .setMessage("New version ${tag.removePrefix("v")} is ready.\nYou have ${BuildConfig.VERSION_NAME}.")
                        .setPositiveButton("Update now") { _, _ -> download(activity, url) }
                        .setNegativeButton("Later", null)
                        .show()
                }
            } catch (e: Exception) {
                if (manual) activity.runOnUiThread { Toast.makeText(activity, "Update check failed", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private fun currentVersionCode(ctx: Context): Int {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
    }

    private fun download(activity: MainActivity, url: String) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activity, "Allow \"Install unknown apps\" for this app, then tap Update again.", Toast.LENGTH_LONG).show()
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.packageName)))
            return
        }
        val dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
        val file = File(dir, "MBLiveStudio-update.apk")
        if (file.exists()) file.delete()

        val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(
            DownloadManager.Request(Uri.parse(url))
                .setTitle("M.B. Live Studio update")
                .setDestinationUri(Uri.fromFile(file))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        )
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) == id) {
                    try { ctx.unregisterReceiver(this) } catch (e: Exception) { }
                    install(activity, file)
                }
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else activity.registerReceiver(receiver, filter)
        Toast.makeText(activity, "Downloading update...", Toast.LENGTH_SHORT).show()
    }

    private fun install(activity: MainActivity, file: File) {
        if (!file.exists()) { Toast.makeText(activity, "Update download failed", Toast.LENGTH_LONG).show(); return }
        val uri = FileProvider.getUriForFile(activity, activity.packageName + ".fileprovider", file)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(i)
    }
}
