package com.mblivestudio

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal data class YtChannel(val id: String, val name: String, val logoUrl: String?, val refreshToken: String)

/**
 * Multi-channel YouTube login.
 * Each channel is added once (Google shows its account / brand-channel chooser),
 * then you can switch between saved channels inside the app with one tap.
 */
internal object AuthManager {
    const val AUTH_REQUEST = 2001
    private const val PREFS = "MBLiveAuth"
    private const val SCOPE = "https://www.googleapis.com/auth/youtube"

    private lateinit var appCtx: Context
    private var authService: AuthorizationService? = null
    private var cachedToken: String? = null
    private var cachedExpiry = 0L
    private var cachedFor: String? = null

    fun init(ctx: Context) { appCtx = ctx.applicationContext }

    private fun prefs() = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- storage ----------
    fun channels(): List<YtChannel> {
        val arr = JSONArray(prefs().getString("channels", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            YtChannel(o.getString("id"), o.getString("name"), o.optString("logo").ifEmpty { null }, o.getString("refresh"))
        }
    }

    private fun saveChannels(list: List<YtChannel>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("logo", it.logoUrl ?: "").put("refresh", it.refreshToken)) }
        prefs().edit().putString("channels", arr.toString()).apply()
    }

    fun activeChannel(): YtChannel? {
        val id = prefs().getString("active", null)
        val list = channels()
        return list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }

    fun setActive(id: String) {
        prefs().edit().putString("active", id).apply()
        cachedToken = null
    }

    private fun removeChannel(id: String) {
        saveChannels(channels().filter { it.id != id })
        if (prefs().getString("active", null) == id) prefs().edit().remove("active").apply()
        cachedToken = null
    }

    // ---------- login (opens Google's account + channel chooser) ----------
    fun startLogin(activity: MainActivity) {
        if (BuildConfig.OAUTH_CLIENT_ID.startsWith("PASTE")) {
            Toast.makeText(activity, "First paste your OAuth client id in app/build.gradle.kts", Toast.LENGTH_LONG).show()
            return
        }
        val config = AuthorizationServiceConfiguration(
            Uri.parse("https://accounts.google.com/o/oauth2/v2/auth"),
            Uri.parse("https://oauth2.googleapis.com/token")
        )
        val request = AuthorizationRequest.Builder(
            config, BuildConfig.OAUTH_CLIENT_ID, ResponseTypeValues.CODE, Uri.parse(BuildConfig.REDIRECT_URI)
        ).setScope(SCOPE).setPrompt("select_account consent").build()

        authService?.dispose()
        val svc = AuthorizationService(activity)
        authService = svc
        activity.startActivityForResult(svc.getAuthorizationRequestIntent(request), AUTH_REQUEST)
    }

    fun handleAuthResult(activity: MainActivity, data: Intent) {
        val resp = AuthorizationResponse.fromIntent(data)
        val ex = AuthorizationException.fromIntent(data)
        val svc = authService
        if (resp == null || svc == null) {
            Toast.makeText(activity, "Login failed: " + (ex?.errorDescription ?: ex?.error ?: "unknown"), Toast.LENGTH_LONG).show()
            return
        }
        svc.performTokenRequest(resp.createTokenExchangeRequest()) { tokenResp, tokenEx ->
            val access = tokenResp?.accessToken
            val refresh = tokenResp?.refreshToken
            if (access == null || refresh == null) {
                Toast.makeText(activity, "Token error: " + (tokenEx?.errorDescription ?: "no refresh token"), Toast.LENGTH_LONG).show()
            } else {
                Thread {
                    try {
                        val ch = fetchChannel(access, refresh)
                        saveChannels(channels().filter { it.id != ch.id } + ch)
                        setActive(ch.id)
                        activity.runOnUiThread { activity.fetchYouTubeChannelProfile() }
                    } catch (e: Exception) {
                        activity.runOnUiThread { Toast.makeText(activity, "Could not read channel: ${e.message}", Toast.LENGTH_LONG).show() }
                    }
                }.start()
            }
        }
    }

    private fun fetchChannel(access: String, refresh: String): YtChannel {
        val c = URL("https://www.googleapis.com/youtube/v3/channels?part=snippet&mine=true").openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", "Bearer $access")
        c.connectTimeout = 10000; c.readTimeout = 10000
        val ok = c.responseCode in 200..299
        val body = (if (ok) c.inputStream else c.errorStream).bufferedReader().readText()
        if (!ok) throw Exception("YouTube API ${c.responseCode}")
        val items = JSONObject(body).optJSONArray("items")
        if (items == null || items.length() == 0) throw Exception("This account has no YouTube channel")
        val first = items.getJSONObject(0)
        val sn = first.getJSONObject("snippet")
        val logo = sn.optJSONObject("thumbnails")?.optJSONObject("default")?.optString("url")
        return YtChannel(first.getString("id"), sn.getString("title"), logo, refresh)
    }

    // ---------- fresh access token for the active channel (call from a background thread) ----------
    @Synchronized
    fun accessToken(): String {
        val ch = activeChannel() ?: throw IllegalStateException("No YouTube channel selected")
        val now = System.currentTimeMillis()
        val t = cachedToken
        if (t != null && cachedFor == ch.id && now < cachedExpiry - 60000) return t

        val c = URL("https://oauth2.googleapis.com/token").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 10000; c.readTimeout = 10000
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        val form = "client_id=" + enc(BuildConfig.OAUTH_CLIENT_ID) +
                "&refresh_token=" + enc(ch.refreshToken) + "&grant_type=refresh_token"
        c.outputStream.use { it.write(form.toByteArray()) }
        val ok = c.responseCode in 200..299
        val body = (if (ok) c.inputStream else c.errorStream).bufferedReader().readText()
        if (!ok) throw Exception("Login expired - add this channel again (" + body.take(80) + ")")
        val j = JSONObject(body)
        cachedToken = j.getString("access_token")
        cachedExpiry = now + j.optLong("expires_in", 3600) * 1000
        cachedFor = ch.id
        return cachedToken!!
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---------- in-app channel list ----------
    fun showChannelPicker(activity: MainActivity) {
        if (activity.rtmpCamera.isStreaming) {
            Toast.makeText(activity, "Stop the stream before switching channel", Toast.LENGTH_SHORT).show()
            return
        }
        val density = activity.resources.displayMetrics.density
        fun px(v: Int) = (v * density).toInt()
        var dialog: AlertDialog? = null
        val active = activeChannel()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), px(8), px(12), px(8))
        }

        for (ch in channels()) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(8), px(10), px(8), px(10))
            }
            val img = ImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(px(44), px(44))
                setImageResource(android.R.drawable.sym_def_app_icon)
            }
            val tv = TextView(activity).apply {
                text = ch.name + if (ch.id == active?.id) "   \u2713" else ""
                textSize = 16f
                setTextColor(Color.BLACK)
                setPadding(px(14), 0, 0, 0)
            }
            row.addView(img); row.addView(tv)
            ch.logoUrl?.let { u ->
                Thread {
                    try {
                        val stream = URL(u).openStream()
                        val bmp = BitmapFactory.decodeStream(stream)
                        stream.close()
                        if (bmp != null) { val circ = activity.cropToCircle(bmp); activity.runOnUiThread { img.setImageBitmap(circ) } }
                    } catch (e: Exception) { }
                }.start()
            }
            row.setOnClickListener {
                setActive(ch.id)
                dialog?.dismiss()
                activity.fetchYouTubeChannelProfile()
            }
            row.setOnLongClickListener {
                dialog?.dismiss()
                AlertDialog.Builder(activity).setTitle("Remove ${ch.name}?")
                    .setPositiveButton("Remove") { _, _ ->
                        removeChannel(ch.id)
                        if (activeChannel() == null) activity.ivProfilePhoto.setImageResource(android.R.drawable.sym_def_app_icon)
                        else activity.fetchYouTubeChannelProfile()
                    }.setNegativeButton("Cancel", null).show()
                true
            }
            root.addView(row)
        }

        val add = TextView(activity).apply {
            text = "+  Add YouTube channel"
            textSize = 16f
            setTextColor(Color.parseColor("#1565C0"))
            setPadding(px(8), px(16), px(8), px(10))
            setOnClickListener { dialog?.dismiss(); startLogin(activity) }
        }
        root.addView(add)
        if (channels().isNotEmpty()) {
            root.addView(TextView(activity).apply {
                text = "Tap = switch channel   |   Long-press = remove"
                textSize = 12f
                setTextColor(Color.GRAY)
                setPadding(px(8), px(6), px(8), px(4))
            })
        }

        val scroll = ScrollView(activity).apply { addView(root) }
        dialog = AlertDialog.Builder(activity).setTitle("YouTube Channels").setView(scroll).setNegativeButton("Close", null).create()
        dialog.show()
    }
}
