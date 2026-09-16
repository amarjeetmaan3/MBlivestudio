package com.mblivestudio

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.text.TextUtils
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import java.util.Calendar

internal fun MainActivity.showGoLiveDialog() {
    val activity = this
    pendingScheduleTimeMs = 0L 
    val padding = (16 * resources.displayMetrics.density).toInt()
    val container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(padding, padding, padding, padding) }
    
    val etTitle = EditText(activity).apply { hint = "Broadcast Title"; setText(pendingTitle) }
    val etDesc = EditText(activity).apply { hint = "Description"; setText(pendingDesc) }
    val privacyOptions = arrayOf("Public", "Unlisted", "Private")
    val spinner = Spinner(activity).apply { adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, privacyOptions); setSelection(privacyOptions.indexOfFirst { it.equals(pendingPrivacy, ignoreCase = true) }.coerceAtLeast(1)) }
    
    val sectionTitle = TextView(activity).apply { 
        text = "\nSTREAM QUALITY"
        setTextColor(Color.parseColor("#03A9F4"))
        setTypeface(null, Typeface.BOLD)
        setPadding(0, 0, 0, 10)
    }
    
    val resOptions = arrayOf("720p (HD)", "1080p (Full HD)")
    val resSpinner = Spinner(activity).apply { adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, resOptions) }
    resSpinner.setSelection(if (streamWidth == 1920 || streamHeight == 1920) 1 else 0)

    val fpsOptions = arrayOf("24 FPS", "30 FPS", "60 FPS")
    val fpsSpinner = Spinner(activity).apply { adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, fpsOptions) }
    fpsSpinner.setSelection(when(streamFps) { 24 -> 0; 60 -> 2; else -> 1 })

    val bitOptions = arrayOf("2 Mbps", "3 Mbps", "4 Mbps", "6 Mbps", "8 Mbps", "10 Mbps")
    val bitValues = intArrayOf(2_000_000, 3_000_000, 4_000_000, 6_000_000, 8_000_000, 10_000_000)
    val bitSpinner = Spinner(activity).apply { adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, bitOptions) }
    val currentBitIndex = bitValues.indexOf(streamBitrate).let { if (it == -1) 1 else it }
    bitSpinner.setSelection(currentBitIndex)

    val btnTime = Button(activity).apply { text = "SCHEDULE (OPTIONAL)" }
    val thumbPreview = ImageView(activity).apply { layoutParams = LinearLayout.LayoutParams((140 * resources.displayMetrics.density).toInt(), (90 * resources.displayMetrics.density).toInt()).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL }; scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.parseColor("#333333")); pendingThumbnailUri?.let { setImageURI(it) } }
    thumbnailPreviewImageView = thumbPreview
    val thumbWrapper = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER; addView(thumbPreview) }
    val btnThumbnail = Button(activity).apply { text = "CHOOSE THUMBNAIL" }
    val btnConfirmLive = Button(activity).apply { text = "CREATE STREAM"; setBackgroundColor(Color.parseColor("#D32F2F")); setTextColor(Color.WHITE) }

    btnTime.setOnClickListener {
        val c = Calendar.getInstance()
        DatePickerDialog(activity, { _, y, m, d ->
            TimePickerDialog(activity, { _, h, min ->
                val sel = Calendar.getInstance().apply { set(y, m, d, h, min, 0) }
                pendingScheduleTimeMs = sel.timeInMillis
                btnTime.text = "Scheduled: ${java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.US).format(sel.time)}"
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false).show()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    listOf(etTitle, etDesc, spinner, sectionTitle, TextView(activity).apply { text = "Resolution:" }, resSpinner, TextView(activity).apply { text = "Frame Rate:" }, fpsSpinner, TextView(activity).apply { text = "Bitrate:" }, bitSpinner, btnTime, thumbWrapper, btnThumbnail, btnConfirmLive).forEach { 
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = (8 * resources.displayMetrics.density).toInt()
        it.layoutParams = lp
        container.addView(it) 
    }

    val dialog = AlertDialog.Builder(activity).setTitle("Setup Broadcast").setView(ScrollView(activity).apply { addView(container) }).setNegativeButton("Cancel", null).create()

    btnThumbnail.setOnClickListener { val intent = Intent(Intent.ACTION_GET_CONTENT); intent.type = "image/*"; activity.startActivityForResult(intent, PICK_THUMBNAIL_REQUEST) }
    
    btnConfirmLive.setOnClickListener {
        pendingTitle = etTitle.text.toString(); pendingDesc = etDesc.text.toString(); pendingPrivacy = spinner.selectedItem.toString().lowercase()
        val isPortrait = streamHeight > streamWidth
        if (resSpinner.selectedItemPosition == 0) {
            streamWidth = if (isPortrait) 720 else 1280
            streamHeight = if (isPortrait) 1280 else 720
        } else {
            streamWidth = if (isPortrait) 1080 else 1920
            streamHeight = if (isPortrait) 1920 else 1080
        }
        streamFps = fpsOptions[fpsSpinner.selectedItemPosition].split(" ")[0].toInt()
        streamBitrate = bitValues[bitSpinner.selectedItemPosition]
        dialog.dismiss()
        retryCount = 0
        if (rtmpCamera.isOnPreview) { rtmpCamera.stopPreview() }
        surfaceReady = true 
        tryStartCameraPreview()
        createYouTubeBroadcast()
    }
    dialog.show()
}

internal fun MainActivity.showStreamReadyDialog(title: String, link: String, rtmpUrl: String, broadcastId: String, chatId: String) {
    val activity = this
    val container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(40,40,40,40) }
    container.addView(TextView(activity).apply { text = "Stream Created Successfully!\n\nShare this link with your viewers:"; textSize = 16f; setPadding(0,0,0,20) })
    container.addView(TextView(activity).apply { text = link; textSize = 18f; setTextColor(Color.parseColor("#2196F3")); setTypeface(null, Typeface.BOLD); setPadding(0,0,0,40) })
    
    val btnCopy = Button(activity).apply { text = "COPY LINK"; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) }
    val btnShare = Button(activity).apply { text = "SHARE LINK"; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) }
    val btnStart = Button(activity).apply { text = "▶ START CAMERA"; setBackgroundColor(Color.parseColor("#4CAF50")); setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin=40 } }
    container.addView(btnCopy); container.addView(btnShare); container.addView(btnStart)

    val scrollContainer = ScrollView(activity).apply { addView(container) }
    val dialog = AlertDialog.Builder(activity).setTitle(title).setView(scrollContainer).setNegativeButton("SAVE FOR LATER", null).create()
    
    btnCopy.setOnClickListener { val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager; clipboard.setPrimaryClip(ClipData.newPlainText("Live Stream Link", link)); Toast.makeText(activity, "Copied!", Toast.LENGTH_SHORT).show() }
    btnShare.setOnClickListener { val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, title); putExtra(Intent.EXTRA_TEXT, "Join my live stream: $link") }; activity.startActivity(Intent.createChooser(intent, "Share via")) }
    btnStart.setOnClickListener {
        dialog.dismiss()
        currentBroadcastId = broadcastId
        generatedRtmpUrl = rtmpUrl
        btnGoLive.text = "CONNECTING..."
        try { StreamingService.start(activity); rtmpCamera.startStream(rtmpUrl); startChatPolling(chatId) } catch (e: Exception) { StreamingService.stop(activity); Toast.makeText(activity, "Stream Error", Toast.LENGTH_LONG).show(); btnGoLive.text = "GO LIVE" }
    }
    dialog.show()
}

internal fun MainActivity.showSavedStreamsManager() {
    val activity = this
    val prefs = getSharedPreferences("MBLiveStreams", Context.MODE_PRIVATE)
    val arr = org.json.JSONArray(prefs.getString("streams", "[]"))
    if (arr.length() == 0) { Toast.makeText(activity, "No saved streams. Click GO LIVE to create one.", Toast.LENGTH_SHORT).show(); return }
    
    val items = Array(arr.length()) { i -> arr.getJSONObject(i).getString("title") }
    AlertDialog.Builder(activity).setTitle("Your Streams").setItems(items) { _, which ->
        val obj = arr.getJSONObject(which)
        val title = obj.getString("title")
        val bId = obj.getString("broadcastId")
        val link = "https://youtu.be/$bId"
        showStreamReadyDialog(title, link, obj.getString("rtmpUrl"), bId, obj.getString("chatId"))
    }.setNegativeButton("Close", null).show()
}

internal fun MainActivity.showAddTextDialog() {
    val activity = this
    val input = EditText(activity).apply { hint = "Enter text..."; inputType = InputType.TYPE_CLASS_TEXT }
    AlertDialog.Builder(activity).setTitle("Add Text Overlay").setView(input).setPositiveButton("Add") { _, _ -> 
        if (input.text.toString().trim().isNotEmpty()) {
            val textView = TextView(activity).apply { text = input.text.toString().trim(); setTextColor(Color.YELLOW); textSize = 30f; setTypeface(null, Typeface.BOLD); layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply { addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE) } }
            overlayContainer.addView(textView); makeDraggableAndScalable(textView); selectedOverlay = textView; updateOverlayMenuButtonPosition(); updateSnapshot()
        }
    }.setNegativeButton("Cancel", null).show()
}

internal fun MainActivity.showAddLowerThirdDialog() {
    val activity = this
    val container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(40,20,40,20) }
    val titleInput = EditText(activity).apply { hint = "Title (e.g. BREAKING NEWS)" }
    val msgInput = EditText(activity).apply { hint = "Running Text (e.g. Subscribe to channel...)" }
    container.addView(titleInput); container.addView(msgInput)
    
    AlertDialog.Builder(activity).setTitle("Add Lower Third Ticker").setView(container)
        .setPositiveButton("Add") { _, _ -> 
            val wrapper = LinearLayout(activity).apply {
                tag = "LOWER_THIRD"
                orientation = LinearLayout.HORIZONTAL
                layoutParams = RelativeLayout.LayoutParams(1200, RelativeLayout.LayoutParams.WRAP_CONTENT).apply { addRule(RelativeLayout.ALIGN_PARENT_BOTTOM, RelativeLayout.TRUE); bottomMargin = 100; leftMargin = 50 }
            }
            val tvTitle = TextView(activity).apply {
                text = titleInput.text.toString(); setBackgroundColor(Color.parseColor("#1565C0")); setTextColor(Color.WHITE); textSize = 24f; setTypeface(null, Typeface.BOLD); setPadding(30, 20, 30, 20)
            }
            val tvMsg = TextView(activity).apply {
                text = msgInput.text.toString(); setBackgroundColor(Color.parseColor("#C62828")); setTextColor(Color.WHITE); textSize = 24f; setPadding(30, 20, 30, 20)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
                ellipsize = TextUtils.TruncateAt.MARQUEE; marqueeRepeatLimit = -1; isSingleLine = true; isSelected = true
            }
            wrapper.addView(tvTitle); wrapper.addView(tvMsg)
            overlayContainer.addView(wrapper); makeDraggableAndScalable(wrapper); selectedOverlay = wrapper
            tickerHandler.post(tickerRunnable); updateOverlayMenuButtonPosition(); updateSnapshot()
        }.setNegativeButton("Cancel", null).show()
}

internal fun MainActivity.showCustomColorPickerDialog() {
    val activity = this
    val target = selectedOverlay ?: run { Toast.makeText(activity, "Select a text/overlay first.", Toast.LENGTH_SHORT).show(); return }
    val container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(40,20,40,20) }
    val previewBox = View(activity).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 150); setBackgroundColor(Color.WHITE) }
    var r = 255; var g = 255; var b = 255
    val rSeek = SeekBar(activity).apply { max = 255; progress = 255 }
    val gSeek = SeekBar(activity).apply { max = 255; progress = 255 }
    val bSeek = SeekBar(activity).apply { max = 255; progress = 255 }
    val updateColor = { r = rSeek.progress; g = gSeek.progress; b = bSeek.progress; previewBox.setBackgroundColor(Color.rgb(r, g, b)) }
    val listener = object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { updateColor() }; override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) {} }
    rSeek.setOnSeekBarChangeListener(listener); gSeek.setOnSeekBarChangeListener(listener); bSeek.setOnSeekBarChangeListener(listener)
    
    container.addView(previewBox)
    container.addView(TextView(activity).apply { text = "Red"; setPadding(0,20,0,0) }); container.addView(rSeek)
    container.addView(TextView(activity).apply { text = "Green"; setPadding(0,20,0,0) }); container.addView(gSeek)
    container.addView(TextView(activity).apply { text = "Blue"; setPadding(0,20,0,0) }); container.addView(bSeek)
    
    val btnRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0,30,0,0) }
    val dialog = AlertDialog.Builder(activity).setTitle("Custom Color Picker").setView(container).setNegativeButton("Close", null).create()

    if (target.tag == "LOWER_THIRD" && target is LinearLayout) {
        val btnTitleBg = Button(activity).apply { text = "Title BG"; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        val btnTickerBg = Button(activity).apply { text = "Ticker BG"; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        val btnTextColor = Button(activity).apply { text = "Text Color"; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        btnTitleBg.setOnClickListener { (target.getChildAt(0) as? TextView)?.setBackgroundColor(Color.rgb(r, g, b)); updateSnapshot(); dialog.dismiss() }
        btnTickerBg.setOnClickListener { (target.getChildAt(1) as? TextView)?.setBackgroundColor(Color.rgb(r, g, b)); updateSnapshot(); dialog.dismiss() }
        btnTextColor.setOnClickListener { (target.getChildAt(0) as? TextView)?.setTextColor(Color.rgb(r, g, b)); (target.getChildAt(1) as? TextView)?.setTextColor(Color.rgb(r, g, b)); updateSnapshot(); dialog.dismiss() }
        btnRow.addView(btnTitleBg); btnRow.addView(btnTickerBg); btnRow.addView(btnTextColor)
    } else {
        val btnTextColor = Button(activity).apply { text = "Text Color"; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        val btnBgColor = Button(activity).apply { text = "Bg Color"; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        val btnTransparentBg = Button(activity).apply { text = "No Bg"; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        btnTextColor.setOnClickListener { (target as? TextView)?.setTextColor(Color.rgb(r, g, b)); updateSnapshot(); dialog.dismiss() }
        btnBgColor.setOnClickListener { (target as? TextView)?.setBackgroundColor(Color.rgb(r, g, b)); updateSnapshot(); dialog.dismiss() }
        btnTransparentBg.setOnClickListener { (target as? TextView)?.setBackgroundColor(Color.TRANSPARENT); updateSnapshot(); dialog.dismiss() }
        btnRow.addView(btnTextColor); btnRow.addView(btnBgColor); btnRow.addView(btnTransparentBg)
    }
    container.addView(btnRow); dialog.show()
}

internal fun MainActivity.showScoreboardDialog() {
    val activity = this
    val layout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 20) }
    val mainInput = EditText(activity).apply { hint = "Main Score (IND 245/3)" }
    val subInput = EditText(activity).apply { hint = "Sub Score (Target: 312)" }
    layout.addView(mainInput); layout.addView(subInput)
    AlertDialog.Builder(activity).setTitle("Update Scoreboard").setView(layout).setPositiveButton("Show") { _, _ -> scoreMainText.text = mainInput.text.toString(); scoreSubText.text = subInput.text.toString(); dragScoreboard.visibility = View.VISIBLE; updateSnapshot() }.setNegativeButton("Cancel", null).show()
}

@SuppressLint("SetJavaScriptEnabled")
internal fun MainActivity.showAddWebDialog() {
    val activity = this
    val input = EditText(activity).apply { hint = "https://..." }
    AlertDialog.Builder(activity).setTitle("Add Web Overlay").setView(input).setPositiveButton("Add") { _, _ ->
        val url = input.text.toString().trim()
        if (url.isNotEmpty()) {
            val finalUrl = if (!url.startsWith("http")) "https://$url" else url
            val targetWebWidth = 1920; val targetWebHeight = 1080
            val webView = WebView(activity).apply {
                tag = "WEB_OVERLAY"
                layoutParams = RelativeLayout.LayoutParams(targetWebWidth, targetWebHeight).apply { addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE) }
                val containerW = overlayContainer.width.toFloat(); val containerH = overlayContainer.height.toFloat()
                val displayMetrics = resources.displayMetrics
                scaleX = if (containerW > 0f) containerW / targetWebWidth else displayMetrics.widthPixels.toFloat() / targetWebWidth
                scaleY = if (containerH > 0f) containerH / targetWebHeight else displayMetrics.heightPixels.toFloat() / targetWebHeight
                setBackgroundColor(Color.TRANSPARENT); setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
                settings.apply { javaScriptEnabled = true; domStorageEnabled = true; useWideViewPort = true; loadWithOverviewMode = true; textZoom = 100; setSupportZoom(false); builtInZoomControls = false; displayZoomControls = false }
                alpha = 0f; webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() { override fun onPageFinished(view: WebView?, url: String?) { super.onPageFinished(view, url); view?.animate()?.alpha(1f)?.setDuration(500)?.setUpdateListener { updateSnapshot(0) }?.start() } }
                loadUrl(finalUrl)
            }
            overlayContainer.addView(webView); makeDraggableAndScalable(webView); selectedOverlay = webView; updateOverlayMenuButtonPosition(); webSyncHandler.post(webSyncRunnable)
        }
    }.setNegativeButton("Cancel", null).show()
}
