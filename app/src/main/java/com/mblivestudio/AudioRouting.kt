package com.mblivestudio

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.ImageButton
import android.widget.Toast
import com.pedro.encoder.input.sources.audio.MicrophoneSource

internal fun MainActivity.registerAudioDeviceMonitoring() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            val btAdded = addedDevices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET) }
            if (btAdded && !isBluetoothMicActive) { toggleBluetoothMic(findViewById(R.id.btnBluetoothMic)) }
            updateDetectedMicRoute(false)
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (removedDevices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET) } && isBluetoothMicActive) {
                isBluetoothMicActive = false; bluetoothCommunicationDevice = null; clearBluetoothRoute(); findViewById<ImageButton>(R.id.btnBluetoothMic).clearColorFilter(); restartCameraForAudioChange(250)
            }
            updateDetectedMicRoute(false)
        }
    }
    audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
}

internal fun MainActivity.updateDetectedMicRoute(showToast: Boolean) {
    val btInput = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET) } else null
    val wiredInput = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && it.type == AudioDeviceInfo.TYPE_USB_HEADSET) } else null
    val route = when {
        isBluetoothMicActive && btInput != null -> MicRoute.BLUETOOTH
        wiredInput != null -> MicRoute.WIRED
        else -> MicRoute.PHONE
    }
    val changed = route != detectedMicRoute
    detectedMicRoute = route
    if (showToast && changed) Toast.makeText(this, "Mic: $route", Toast.LENGTH_SHORT).show()
}

@SuppressLint("MissingPermission")
internal fun MainActivity.toggleBluetoothMic(button: ImageButton) {
    if (!isBluetoothMicActive) {
        val btInput = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET) } else null
        if (btInput == null) { Toast.makeText(this, "Bluetooth mic not found.", Toast.LENGTH_SHORT).show(); return }
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val commDevice = audioManager.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET } ?: return
                audioManager.setCommunicationDevice(commDevice)
                Handler(Looper.getMainLooper()).postDelayed({ updateDetectedMicRoute(false) }, 150)
            } else {
                val receiver = object : android.content.BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        when (intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)) {
                            AudioManager.SCO_AUDIO_STATE_CONNECTED -> { scoConnectTimeoutHandler.removeCallbacksAndMessages(null); try { unregisterReceiver(this) } catch (e: Exception) {}; scoStateReceiver = null; restartCameraForAudioChange(150) }
                            AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> { scoConnectTimeoutHandler.removeCallbacksAndMessages(null); try { unregisterReceiver(this) } catch (e: Exception) {}; scoStateReceiver = null; isBluetoothMicActive = false; bluetoothCommunicationDevice = null; updateDetectedMicRoute(false); findViewById<ImageButton>(R.id.btnBluetoothMic).clearColorFilter() }
                        }
                    }
                }
                scoStateReceiver = receiver
                registerReceiver(receiver, android.content.IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
                audioManager.startBluetoothSco(); audioManager.isBluetoothScoOn = true
                scoConnectTimeoutHandler.postDelayed({ scoStateReceiver?.let { try { unregisterReceiver(it) } catch (e: Exception) {} }; scoStateReceiver = null; isBluetoothMicActive = false; bluetoothCommunicationDevice = null; try { audioManager.stopBluetoothSco(); audioManager.isBluetoothScoOn = false; audioManager.mode = AudioManager.MODE_NORMAL } catch (e: Exception) {}; updateDetectedMicRoute(false); findViewById<ImageButton>(R.id.btnBluetoothMic).clearColorFilter() }, 8000)
            }
            isBluetoothMicActive = true; bluetoothCommunicationDevice = btInput; button.setColorFilter(android.graphics.Color.parseColor("#4CAF50"))
            Toast.makeText(this, "Bluetooth mic selected", Toast.LENGTH_SHORT).show(); restartCameraForAudioChange(300)
        } catch (e: Exception) { e.printStackTrace() }
    } else {
        clearBluetoothRoute(); isBluetoothMicActive = false; bluetoothCommunicationDevice = null; button.clearColorFilter()
        Toast.makeText(this, "Bluetooth mic off", Toast.LENGTH_SHORT).show(); restartCameraForAudioChange(250)
    }
}

@SuppressLint("MissingPermission")
internal fun MainActivity.clearBluetoothRoute() {
    scoStateReceiver?.let { try { unregisterReceiver(it) } catch (e: Exception) {} }; scoStateReceiver = null; scoConnectTimeoutHandler.removeCallbacksAndMessages(null)
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { audioManager.clearCommunicationDevice() } else { audioManager.stopBluetoothSco(); audioManager.isBluetoothScoOn = false }
        audioManager.mode = AudioManager.MODE_NORMAL
    } catch (e: Exception) {}
    findViewById<ImageButton>(R.id.btnBluetoothMic).clearColorFilter()
}

internal fun MainActivity.restartCameraForAudioChange(delayMs: Long) {
    if (rtmpCamera.isStreaming) { Handler(Looper.getMainLooper()).postDelayed({ switchAudioLive() }, delayMs); return }
    if (!rtmpCamera.isOnPreview) return
    try { rtmpCamera.stopPreview() } catch (e: Exception) {}
    Handler(Looper.getMainLooper()).postDelayed({ tryStartCameraPreview() }, delayMs)
}

@SuppressLint("MissingPermission")
internal fun MainActivity.findPhoneMic(): AudioDeviceInfo? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
    return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
}

@SuppressLint("MissingPermission")
internal fun MainActivity.findBluetoothMic(): AudioDeviceInfo? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
    return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET) }
}

@SuppressLint("MissingPermission")
internal fun MainActivity.applyCurrentMicrophoneDevice(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
    val source = rtmpCamera.audioSource as? MicrophoneSource ?: return false
    val preferred = if (isBluetoothMicActive) findBluetoothMic() else findPhoneMic()
    return try { source.setPreferredDevice(preferred) } catch (e: Exception) { e.printStackTrace(); false }
}

internal fun MainActivity.switchAudioLive() {
    if (!rtmpCamera.isStreaming) return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    if (applyCurrentMicrophoneDevice()) {
        runOnUiThread { Toast.makeText(this, if (isBluetoothMicActive) "Bluetooth mic active" else "Phone mic active", Toast.LENGTH_SHORT).show() }
    } else {
        runOnUiThread { Toast.makeText(this, "Mic route could not be changed.", Toast.LENGTH_SHORT).show() }
    }
}
