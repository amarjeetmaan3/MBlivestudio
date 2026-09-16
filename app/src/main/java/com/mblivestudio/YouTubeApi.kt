package com.mblivestudio

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.Toast
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.InputStreamContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.DateTime
import com.google.api.services.youtube.YouTube
import com.google.api.services.youtube.model.*

internal fun MainActivity.loadQuota() {
    val prefs = getSharedPreferences("MBLivePrefs", Context.MODE_PRIVATE)
    val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
    val savedDate = prefs.getString("QuotaDate", "")
    if (savedDate != today) { dailyQuotaUsed = 0; prefs.edit().putString("QuotaDate", today).putInt("QuotaUsed", 0).apply() } else { dailyQuotaUsed = prefs.getInt("QuotaUsed", 0) }
    updateQuotaUI()
}

internal fun MainActivity.addQuota(amount: Int) {
    dailyQuotaUsed += amount
    getSharedPreferences("MBLivePrefs", Context.MODE_PRIVATE).edit().putInt("QuotaUsed", dailyQuotaUsed).apply()
    runOnUiThread { updateQuotaUI() }
}

internal fun MainActivity.updateQuotaUI() { tvApiQuota.text = "API: $dailyQuotaUsed/10K" }

internal fun MainActivity.refreshChatOverlayText() {
    if (tvStreamChatOverlay.visibility != View.VISIBLE) return
    val maxLines = if (tvStreamChatOverlay.height > 0 && tvStreamChatOverlay.lineHeight > 0) tvStreamChatOverlay.height / tvStreamChatOverlay.lineHeight else 8
    tvStreamChatOverlay.text = streamChatHistory.takeLast(maxLines.coerceAtLeast(1)).joinToString("\n")
}

internal fun MainActivity.saveStreamLocally(title: String, broadcastId: String, chatId: String, rtmpUrl: String) {
    val prefs = getSharedPreferences("MBLiveStreams", Context.MODE_PRIVATE)
    val arr = org.json.JSONArray(prefs.getString("streams", "[]"))
    val obj = org.json.JSONObject().apply { put("title", title); put("broadcastId", broadcastId); put("chatId", chatId); put("rtmpUrl", rtmpUrl); put("time", System.currentTimeMillis()) }
    arr.put(obj); prefs.edit().putString("streams", arr.toString()).apply()
}

internal fun MainActivity.removeSavedStream(broadcastId: String) {
    val prefs = getSharedPreferences("MBLiveStreams", Context.MODE_PRIVATE)
    val arr = org.json.JSONArray(prefs.getString("streams", "[]")); val newArr = org.json.JSONArray()
    for (i in 0 until arr.length()) { val obj = arr.getJSONObject(i); if (obj.getString("broadcastId") != broadcastId) newArr.put(obj) }
    prefs.edit().putString("streams", newArr.toString()).apply()
}

internal fun MainActivity.createYouTubeBroadcast() {
    val activity = this
    btnGoLive.text = "1/3: API..."; btnGoLive.isEnabled = false
    val finalTitle = pendingTitle.trim().ifEmpty { "Live from M.B. Live Studio" }
    val finalDesc = pendingDesc.trim().ifEmpty { "Streaming via Android App" }
    val privacyInput = pendingPrivacy
    Thread {
        addQuota(150)
        try {
            val credential = GoogleAccountCredential.usingOAuth2(activity, listOf("https://www.googleapis.com/auth/youtube"))
            val signInAccount = GoogleSignIn.getLastSignedInAccount(activity)
            if (signInAccount?.account != null) credential.selectedAccount = signInAccount.account else credential.selectedAccountName = connectedAccountEmail
            val youtube = YouTube.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), HttpRequestInitializer { request -> credential.initialize(request); request.connectTimeout = 10000; request.readTimeout = 10000; request.numberOfRetries = 0 }).setApplicationName("MBLiveStudio").build()
            youtubeClient = youtube
            runOnUiThread { btnGoLive.text = "2/3: ROOM..." }

            val scheduleTime = if (pendingScheduleTimeMs > 0) DateTime(pendingScheduleTimeMs) else DateTime(System.currentTimeMillis())
            val broadcastSnippet = LiveBroadcastSnippet().apply { title = finalTitle; description = finalDesc; scheduledStartTime = scheduleTime }
            val broadcastStatus = LiveBroadcastStatus().apply { privacyStatus = privacyInput; selfDeclaredMadeForKids = false }
            val broadcastContentDetails = LiveBroadcastContentDetails().apply { enableAutoStart = true; latencyPreference = "ultraLow" }
            val broadcast = youtube.liveBroadcasts().insert("snippet,status,contentDetails", LiveBroadcast().apply { snippet = broadcastSnippet; status = broadcastStatus; contentDetails = broadcastContentDetails }).execute()

            val bId = broadcast.id
            val liveChatId = broadcast.snippet?.liveChatId ?: ""
            pendingThumbnailUri?.let { uri -> try { val stream = contentResolver.openInputStream(uri); if (stream != null) { youtube.thumbnails().set(bId, InputStreamContent("image/jpeg", stream)).execute() } } catch (e: Exception) { e.printStackTrace() } }
            runOnUiThread { btnGoLive.text = "3/3: KEY..." }

            val stream2 = youtube.liveStreams().insert("snippet,cdn", LiveStream().apply { snippet = LiveStreamSnippet().apply { title = "$finalTitle - Key" }; cdn = CdnSettings().apply { ingestionType = "rtmp"; resolution = "variable"; frameRate = "variable" } }).execute()
            youtube.liveBroadcasts().bind(bId, "id,contentDetails").apply { streamId = stream2.id }.execute()

            // FIX: Removed the IP resolution hack. We must use the exact URL YouTube gives us.
            val ingestionUrl = stream2.cdn.ingestionInfo.ingestionAddress
            val finalUrl = "$ingestionUrl/${stream2.cdn.ingestionInfo.streamName}"
            
            val shareLink = "https://youtu.be/$bId"
            saveStreamLocally(finalTitle, bId, liveChatId, finalUrl)

            runOnUiThread {
                btnGoLive.text = "GO LIVE"
                btnGoLive.isEnabled = true
                showStreamReadyDialog(finalTitle, shareLink, finalUrl, bId, liveChatId)
            }
        } catch (e: Exception) { e.printStackTrace(); runOnUiThread { btnGoLive.text = "GO LIVE"; btnGoLive.isEnabled = true; Toast.makeText(activity, "Timeout/API Error: ${e.message}", Toast.LENGTH_LONG).show() } }
    }.start()
}

internal fun MainActivity.stopLiveStream() {
    val activity = this
    btnGoLive.isEnabled = false; btnGoLive.text = "STOPPING..."
    Thread {
        currentBroadcastId?.let { broadcastId -> 
            try { youtubeClient?.liveBroadcasts()?.transition("complete", broadcastId, "status")?.execute() } catch (e: Exception) { e.printStackTrace() }
            removeSavedStream(broadcastId)
            currentBroadcastId = null 
        }
        try { rtmpCamera.stopStream() } catch (e: Exception) {}
        StreamingService.stop(activity)
        runOnUiThread {
            btnGoLive.text = "GO LIVE"; btnGoLive.isEnabled = true; btnGoLive.setBackgroundColor(Color.parseColor("#D32F2F"))
            Toast.makeText(activity, "Stream Ended Permanently.", Toast.LENGTH_SHORT).show()
            generatedRtmpUrl = null; stopChatPolling(); stopStudioTimer()
        }
    }.start()
}

internal fun MainActivity.startChatPolling(liveChatId: String) { currentLiveChatId = liveChatId; chatNextPageToken = null; chatPollingActive = true; pollChatOnce(); pollViewersOnce() }

internal fun MainActivity.stopChatPolling() { chatPollingActive = false; chatHandler.removeCallbacksAndMessages(null); currentLiveChatId = null; runOnUiThread { tvViewerCount.visibility = View.GONE } }

internal fun MainActivity.pollViewersOnce() {
    if (!chatPollingActive || currentBroadcastId == null) return
    if (!switchViewerSync.isChecked) { chatHandler.postDelayed({ pollViewersOnce() }, 5000L); return }
    val youtube = youtubeClient ?: return
    Thread {
        addQuota(1)
        try {
            val response = youtube.videos().list("liveStreamingDetails").setId(currentBroadcastId).execute()
            val details = response.items?.firstOrNull()?.liveStreamingDetails
            val viewers = details?.concurrentViewers?.toString() ?: "0"
            runOnUiThread { 
                tvViewerCount.text = "Viewers: $viewers"
                if (switchShowViewers.isChecked) tvViewerCount.visibility = View.VISIBLE 
            }
        } catch (e: Exception) { e.printStackTrace() }
        if (chatPollingActive) chatHandler.postDelayed({ pollViewersOnce() }, 5000L)
    }.start()
}

internal fun MainActivity.pollChatOnce() {
    if (!chatPollingActive) return
    if (!switchChatSync.isChecked) { chatHandler.postDelayed({ pollChatOnce() }, 5000L); return }
    val chatId = currentLiveChatId ?: return
    val youtube = youtubeClient ?: return
    Thread {
        addQuota(1)
        try {
            val request = youtube.liveChatMessages().list(chatId, "snippet,authorDetails")
            chatNextPageToken?.let { request.pageToken = it }
            val response = request.execute()
            chatNextPageToken = response.nextPageToken
            val newLines = response.items.orEmpty().mapNotNull { msg -> val author = msg.authorDetails?.displayName ?: "Viewer"; val text = msg.snippet?.displayMessage ?: return@mapNotNull null; "$author: $text" }
            if (newLines.isNotEmpty()) {
                runOnUiThread {
                    tvCommentsFeed.text = (tvCommentsFeed.text.toString().lines() + newLines).takeLast(30).joinToString("\n")
                    commentsScrollView.post { commentsScrollView.fullScroll(View.FOCUS_DOWN) }
                    streamChatHistory.addAll(newLines); if (streamChatHistory.size > 50) streamChatHistory.subList(0, streamChatHistory.size - 50).clear()
                    refreshChatOverlayText(); updateSnapshot(500)
                }
            }
            val youtubeDelay = response.pollingIntervalMillis ?: 5000L
            val finalDelay = if (youtubeDelay > 5000L) youtubeDelay else 5000L
            if (chatPollingActive) chatHandler.postDelayed({ pollChatOnce() }, finalDelay)
        } catch (e: Exception) {
            e.printStackTrace()
            if (chatPollingActive) chatHandler.postDelayed({ pollChatOnce() }, 10000L)
        }
    }.start()
}

internal fun MainActivity.startStudioTimer() { 
    liveStartTimeMillis = System.currentTimeMillis()
    timerRunning = true
    tvLiveTimer.visibility = View.VISIBLE
    timerHandler.post(timerRunnable) 
}

internal fun MainActivity.stopStudioTimer() { 
    timerRunning = false
    timerHandler.removeCallbacksAndMessages(null)
    tvLiveTimer.visibility = View.GONE
    tvLiveTimer.text = "00:00:00" 
}
