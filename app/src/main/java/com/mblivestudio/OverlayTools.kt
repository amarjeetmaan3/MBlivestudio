package com.mblivestudio

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.EditText
import android.widget.ImageView
import android.widget.RelativeLayout
import com.google.android.gms.auth.api.signin.GoogleSignInAccount

internal fun MainActivity.addImageOverlayToScreen(bitmap: Bitmap) {
    val imageView = ImageView(this).apply { setImageBitmap(bitmap); layoutParams = RelativeLayout.LayoutParams(300, 300).apply { addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE) } }
    overlayContainer.addView(imageView); makeDraggableAndScalable(imageView); selectedOverlay = imageView; updateOverlayMenuButtonPosition(); updateSnapshot()
}

@SuppressLint("ClickableViewAccessibility")
internal fun MainActivity.makeDraggableAndScalable(view: View) {
    val scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() { override fun onScale(detector: ScaleGestureDetector): Boolean { if (view is WebView) return false; view.scaleX *= detector.scaleFactor; view.scaleY *= detector.scaleFactor; return true } })
    var localDX = 0f; var localDY = 0f
    view.setOnTouchListener { v, event ->
        if (currentMode != "DRAG") return@setOnTouchListener false
        scaleGestureDetector.onTouchEvent(event)
        if (!scaleGestureDetector.isInProgress) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { localDX = v.x - event.rawX; localDY = v.y - event.rawY; selectedOverlay = v; updateOverlayMenuButtonPosition() }
                MotionEvent.ACTION_MOVE -> { v.x = event.rawX + localDX; v.y = event.rawY + localDY; updateOverlayMenuButtonPosition(); updateSnapshot(50) }
                MotionEvent.ACTION_UP -> { updateSnapshot() }
            }
        }
        if (v is EditText) v.onTouchEvent(event)
        true
    }
}

internal fun MainActivity.updateOverlayMenuButtonPosition() {
    val target = selectedOverlay
    if (target == null || currentMode != "DRAG") { btnOverlayMenu.visibility = View.GONE; return }
    btnOverlayMenu.visibility = View.VISIBLE
    btnOverlayMenu.x = target.x + (target.width * target.scaleX) - (32 * resources.displayMetrics.density)
    btnOverlayMenu.y = target.y - (16 * resources.displayMetrics.density)
}

internal fun MainActivity.positionDoneButton(target: View) { btnOverlayDone.x = target.x; btnOverlayDone.y = target.y - (40 * resources.displayMetrics.density) }

@SuppressLint("ClickableViewAccessibility")
internal fun MainActivity.addResizeHandle(target: View, xAlign: Float, yAlign: Float, wMult: Int, hMult: Int, isEdge: Boolean) {
    val size = ((if (isEdge) 12 else 24) * resources.displayMetrics.density).toInt()
    val handle = View(this).apply { layoutParams = RelativeLayout.LayoutParams(size, size); setBackgroundColor(if (isEdge) Color.parseColor("#8800BCD4") else Color.parseColor("#00BCD4")) }
    val root = findViewById<RelativeLayout>(R.id.rootLayout); root.addView(handle); resizeHandles.add(handle)
    val updateHandlePos = { handle.x = target.x + (target.width * target.scaleX * xAlign) - size / 2; handle.y = target.y + (target.height * target.scaleY * yAlign) - size / 2 }
    updateHandlePos()
    var dX = 0f; var dY = 0f; var startW = 0; var startH = 0; var startX = 0f; var startY = 0f
    handle.setOnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dX = event.rawX; dY = event.rawY; startW = target.width; startH = target.height; startX = target.x; startY = target.y }
            MotionEvent.ACTION_MOVE -> {
                var newW = startW + ((event.rawX - dX).toInt() * wMult); var newH = startH + ((event.rawY - dY).toInt() * hMult)
                if (newW < 100) newW = 100; if (newH < 100) newH = 100
                target.layoutParams.width = newW; target.layoutParams.height = newH; target.requestLayout()
                if (wMult < 0) target.x = startX + (startW - newW); if (hMult < 0) target.y = startY + (startH - newH)
                updateHandlePos(); if (target == tvStreamChatOverlay) refreshChatOverlayText()
                root.post { resizeHandles.forEach { (it.tag as? ()->Unit)?.invoke() } }; updateSnapshot()
            }
            MotionEvent.ACTION_UP -> updateSnapshot()
        }
        true
    }
    handle.tag = updateHandlePos
}

internal fun MainActivity.showCropFrame(target: View) {
    val root = findViewById<RelativeLayout>(R.id.rootLayout); val density = resources.displayMetrics.density
    val border = View(this).apply { layoutParams = RelativeLayout.LayoutParams(target.width, target.height); background = android.graphics.drawable.GradientDrawable().apply { setStroke((2 * density).toInt(), Color.parseColor("#00BCD4")); setColor(Color.TRANSPARENT) }; x = target.x; y = target.y; isClickable = false }
    root.addView(border); cropFrameViews.add(border)
    listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f).forEach { (xAlign, yAlign) ->
        val dot = View(this).apply { layoutParams = RelativeLayout.LayoutParams((16 * density).toInt(), (16 * density).toInt()); setBackgroundColor(Color.parseColor("#00BCD4")); x = target.x + (target.width * xAlign) - (8 * density); y = target.y + (target.height * yAlign) - (8 * density); isClickable = false }
        root.addView(dot); cropFrameViews.add(dot)
    }
}

internal fun MainActivity.enterResizeMode(target: View) {
    currentMode = "RESIZE"; btnOverlayMenu.visibility = View.GONE; btnOverlayDone.visibility = View.VISIBLE; positionDoneButton(target); target.setOnTouchListener(null)
    if (target is ImageView) target.scaleType = ImageView.ScaleType.FIT_XY
    addResizeHandle(target, 0f, 0f, -1, -1, false); addResizeHandle(target, 1f, 0f, 1, -1, false); addResizeHandle(target, 0f, 1f, -1, 1, false); addResizeHandle(target, 1f, 1f, 1, 1, false)
    addResizeHandle(target, 0.5f, 0f, 0, -1, true); addResizeHandle(target, 0.5f, 1f, 0, 1, true); addResizeHandle(target, 0f, 0.5f, -1, 0, true); addResizeHandle(target, 1f, 0.5f, 1, 0, true)
}

@SuppressLint("ClickableViewAccessibility")
internal fun MainActivity.enterCropMode(target: View) {
    currentMode = "CROP"; btnOverlayMenu.visibility = View.GONE; btnOverlayDone.visibility = View.VISIBLE; positionDoneButton(target); showCropFrame(target)
    if (target is ImageView) {
        val bmpW = target.drawable?.intrinsicWidth?.toFloat() ?: target.width.toFloat(); val bmpH = target.drawable?.intrinsicHeight?.toFloat() ?: target.height.toFloat()
        val fillScale = maxOf(target.width.toFloat() / bmpW, target.height.toFloat() / bmpH)
        target.scaleType = ImageView.ScaleType.MATRIX
        var scale = fillScale; var transX = (target.width - bmpW * scale) / 2f; var transY = (target.height - bmpH * scale) / 2f
        fun clampAndApply() { transX = transX.coerceIn(target.width - bmpW * scale, 0f); transY = transY.coerceIn(target.height - bmpH * scale, 0f); val matrix = android.graphics.Matrix(); matrix.postScale(scale, scale); matrix.postTranslate(transX, transY); target.imageMatrix = matrix; updateSnapshot() }
        clampAndApply()
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() { override fun onScale(d: ScaleGestureDetector): Boolean { scale = (scale * d.scaleFactor).coerceIn(fillScale, fillScale * 4f); clampAndApply(); return true } })
        var dX = 0f; var dY = 0f
        target.setOnTouchListener { _, event ->
            if (currentMode != "CROP") return@setOnTouchListener false
            scaleDetector.onTouchEvent(event)
            if (!scaleDetector.isInProgress) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { dX = transX - event.rawX; dY = transY - event.rawY }
                    MotionEvent.ACTION_MOVE -> { transX = event.rawX + dX; transY = event.rawY + dY; clampAndApply() }
                    MotionEvent.ACTION_UP -> updateSnapshot()
                }
            }
            true
        }
    } else if (target is WebView) { target.settings.builtInZoomControls = true; target.settings.displayZoomControls = false; target.setOnTouchListener(null) }
}

internal fun MainActivity.addLiveTextOverlay() {
    val liveEditText = EditText(this).apply {
        hint = "Start typing..."
        setHintTextColor(Color.argb(128, 255, 255, 255))
        setTextColor(Color.YELLOW)
        textSize = 30f
        setTypeface(null, Typeface.BOLD)
        background = null
        setShadowLayer(5f, 2f, 2f, Color.BLACK)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply { addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE) }
        setOnFocusChangeListener { _, hasFocus -> if (!hasFocus && text.toString().trim().isEmpty()) { overlayContainer.removeView(this); if (selectedOverlay == this) { selectedOverlay = null; updateOverlayMenuButtonPosition() }; updateSnapshot() } }
        addTextChangedListener(object : TextWatcher { override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}; override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateSnapshot() }; override fun afterTextChanged(s: Editable?) {} })
    }
    overlayContainer.addView(liveEditText); makeDraggableAndScalable(liveEditText); selectedOverlay = liveEditText; updateOverlayMenuButtonPosition(); updateSnapshot(); liveEditText.requestFocus()
    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager; imm.showSoftInput(liveEditText, InputMethodManager.SHOW_IMPLICIT)
}

internal fun MainActivity.applyAccountToHeader(account: GoogleSignInAccount) {
    account.photoUrl?.let { url -> Thread { try { val input = java.net.URL(url.toString()).openStream(); val bmp = BitmapFactory.decodeStream(input); input.close(); val circular = cropToCircle(bmp); runOnUiThread { findViewById<ImageView>(R.id.ivProfilePhoto).setImageBitmap(circular) } } catch (e: Exception) { e.printStackTrace() } }.start() }
}

internal fun MainActivity.cropToCircle(bitmap: Bitmap): Bitmap {
    val size = minOf(bitmap.width, bitmap.height); val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output); val paint = Paint(Paint.ANTI_ALIAS_FLAG); paint.shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint); return output
}
