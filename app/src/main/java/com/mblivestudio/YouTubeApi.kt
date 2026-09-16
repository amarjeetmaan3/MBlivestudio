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
import java.net.Inet4Address
import java.net.InetAddress

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
            // फिक्स 3: enableAutoStart तभी भरोसेमंद तरीके से काम करता है जब monitor stream
            // (टेस्टिंग-ओनली प्रीव्यू) बंद हो। इसे बंद न करने पर broadcast अक्सर "Upcoming"/testing
            // पर अटका रह जाता है और असली दर्शकों तक कभी "Live" नहीं जाता।
            // नोट: enableAutoStop यहाँ जानबूझकर नहीं जोड़ा — यह प्रोजेक्ट google-api-services-youtube
            // के पुराने वर्शन (v3-rev222-1.25.0) पर बना है जिसमें वह फ़ील्ड मौजूद नहीं है
            // (compile error आता है)। स्ट्रीम को "Live" में लाने के असली फिक्स — यानी नीचे
            // ensureBroadcastGoesLive() वाला explicit transition — के लिए इसकी ज़रूरत नहीं है।
            // Match the configuration used by the last known-good YouTube pipeline.
            // Auto-start lets YouTube move the broadcast to live once valid RTMP media arrives.
            val broadcastContentDetails = LiveBroadcastContentDetails().apply {
                enableAutoStart = true
                latencyPreference = "ultraLow"
            }
            val broadcast = youtube.liveBroadcasts().insert("snippet,status,contentDetails", LiveBroadcast().apply { snippet = broadcastSnippet; status = broadcastStatus; contentDetails = broadcastContentDetails }).execute()

            val bId = broadcast.id
            val liveChatId = broadcast.snippet?.liveChatId ?: ""
            pendingThumbnailUri?.let { uri -> try { val stream = contentResolver.openInputStream(uri); if (stream != null) { youtube.thumbnails().set(bId, InputStreamContent("image/jpeg", stream)).execute() } } catch (e: Exception) { e.printStackTrace() } }
            runOnUiThread { btnGoLive.text = "3/3: KEY..." }

            val stream2 = youtube.liveStreams().insert("snippet,cdn", LiveStream().apply { snippet = LiveStreamSnippet().apply { title = "$finalTitle - Key" }; cdn = CdnSettings().apply { ingestionType = "rtmp"; resolution = "variable"; frameRate = "variable" } }).execute()
            youtube.liveBroadcasts().bind(bId, "id,contentDetails").apply { streamId = stream2.id }.execute()
            currentStreamId = stream2.id

            val ingestionUrl = stream2.cdn.ingestionInfo.ingestionAddress

            // Use the same resolved YouTube ingest endpoint that the known-working
            // RtmpCamera2 version used. This avoids device/network combinations where
            // the RTMP socket connects to the hostname but media packets never reach ingest.
            var resolvedIp: String? = null
            try {
                val host = if (ingestionUrl.contains("b.rtmp")) "b.rtmp.youtube.com" else "a.rtmp.youtube.com"
                resolvedIp = InetAddress.getAllByName(host)
                    .firstOrNull { it is Inet4Address }?.hostAddress
            } catch (e: Exception) {
                e.printStackTrace()
            }
            val finalUrl = if (resolvedIp != null && ingestionUrl.contains("a.rtmp.youtube.com")) {
                ingestionUrl.replace("a.rtmp.youtube.com", resolvedIp!!) + "/" + stream2.cdn.ingestionInfo.streamName
            } else {
                ingestionUrl.replace("a.rtmp", "b.rtmp") + "/" + stream2.cdn.ingestionInfo.streamName
            }

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
        currentStreamId = null
        try { rtmpCamera.stopStream() } catch (e: Exception) {}
        StreamingService.stop(activity)
        runOnUiThread {
            btnGoLive.text = "GO LIVE"; btnGoLive.isEnabled = true; btnGoLive.setBackgroundColor(Color.parseColor("#D32F2F"))
            Toast.makeText(activity, "Stream Ended Permanently.", Toast.LENGTH_SHORT).show()
            generatedRtmpUrl = null; stopChatPolling(); stopStudioTimer()
        }
    }.start()
}

/**
 * फिक्स 3 (असली फिक्स — "Upcoming पर अटकी स्ट्रीम" वाली मेजर बग):
 *
 * पहले कोड सिर्फ यह मान लेता था कि RTMP कनेक्शन सफल होते ही (onConnectionSuccess)
 * YouTube अपने आप broadcast को "Upcoming" से "Live" में बदल देगा, क्योंकि
 * contentDetails.enableAutoStart = true सेट था। असलियत में एनकोडर सिर्फ इतना बता पाता
 * है कि वह YouTube के इनजेस्ट सर्वर से जुड़ गया — यह गारंटी नहीं देता कि YouTube का बैकएंड
 * उस वीडियो/ऑडियो डेटा को "स्वस्थ" (healthy) मान चुका है और असली दर्शकों को दिखाना शुरू
 * कर चुका है। जब भी किसी वजह से यह ऑटो-ट्रांज़िशन ट्रिगर नहीं होता, broadcast हमेशा के
 * लिए "Upcoming"/"testing" स्टेटस में अटका रह जाता है — बिना किसी एरर के — इसलिए दिखने में
 * लगता है कि "स्ट्रीम YouTube तक जा ही नहीं रही"।
 *
 * Google के आधिकारिक "Life of a Broadcast" डॉक्यूमेंटेशन के मुताबिक, सही/गारंटीशुदा तरीका
 * यह है: पहले लाइव स्ट्रीम के status.streamStatus के "active" होने का इंतज़ार करो
 * (इसका मतलब है YouTube को वाकई हेल्दी वीडियो डेटा मिलना शुरू हो गया है), फिर एक्सप्लिसिट
 * तरीके से liveBroadcasts().transition("live", ...) कॉल करो। यह enableAutoStart के साथ
 * काम करने के लिए पूरी तरह सुरक्षित है — अगर YouTube ने पहले ही खुद-ब-खुद "live" कर दिया
 * हो, तो यह कॉल बस एक (हानिरहित) redundant-transition एरर देगा जिसे हम पकड़ (catch) कर
 * के अनदेखा कर देते हैं।
 */
internal fun MainActivity.ensureBroadcastGoesLive() {
    val youtube = youtubeClient ?: return
    val broadcastId = currentBroadcastId ?: return
    val streamId = currentStreamId ?: return
    Thread {
        // ~90 सेकंड तक हर 3 सेकंड में चेक करेंगे — YouTube को स्ट्रीम को "active"/healthy
        // मानने में आमतौर पर कुछ ही सेकंड लगते हैं, लेकिन धीमे नेटवर्क पर थोड़ा वक़्त लग सकता है।
        val maxAttempts = 30
        var attempts = 0
        while (attempts < maxAttempts) {
            // अगर इस बीच यूज़र ने स्ट्रीम रोक दी, या कोई नई स्ट्रीम शुरू हो गई, तो यह पुरानी
            // पोलिंग लूप खुद को बंद कर ले — गलत broadcast को कभी टच न करे।
            if (!rtmpCamera.isStreaming || currentBroadcastId != broadcastId) return@Thread
            attempts++
            try {
                addQuota(1)
                val streamStatus = youtube.liveStreams().list("status").setId(streamId).execute()
                    .items?.firstOrNull()?.status?.streamStatus

                if (streamStatus == "active") {
                    addQuota(1)
                    val lifeCycleStatus = youtube.liveBroadcasts().list("status").setId(broadcastId).execute()
                        .items?.firstOrNull()?.status?.lifeCycleStatus

                    if (lifeCycleStatus != "live" && lifeCycleStatus != "complete" && lifeCycleStatus != "completeStarting") {
                        try {
                            addQuota(50)
                            youtube.liveBroadcasts().transition("live", broadcastId, "status").execute()
                        } catch (e: Exception) {
                            // अगर YouTube ने enableAutoStart से पहले ही खुद इसे live कर दिया था
                            // तो यह कॉल एक redundant-transition एरर देगा — यह सामान्य/सुरक्षित है।
                            e.printStackTrace()
                        }
                    }
                    return@Thread
                } else if (streamStatus == "error") {
                    // स्ट्रीम की सेहत खराब है (जैसे कोई डेटा नहीं मिल रहा) — दोबारा कोशिश करते रहो,
                    // हो सकता है यह शुरुआती कुछ सेकंड का उतार-चढ़ाव हो।
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            Thread.sleep(3000)
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
