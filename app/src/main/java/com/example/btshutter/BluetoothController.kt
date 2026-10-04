package com.example.btshutter

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.btshutter.HidConstants.CONNECT_TIMEOUT_MS
import com.example.btshutter.HidConstants.HID_DESCRIPTOR
import com.example.btshutter.HidConstants.KEY_LAST_HOST
import com.example.btshutter.HidConstants.PREFS
import com.example.btshutter.HidConstants.REPORT_ID
import java.util.concurrent.Executor

@SuppressLint("MissingPermission")
class BluetoothController(
    private val context: Context,
    private val adapter: BluetoothAdapter?,
    private val mainExecutor: Executor,
    private val listener: Callback
) {
    interface Callback {
        fun onStatusChanged()
    }

    private val ui = Handler(Looper.getMainLooper())
    var hid: BluetoothHidDevice? = null
        private set
    var registered = false
        private set
    var host: BluetoothDevice? = null
        private set
    var searching = false
        private set
    var problem: String? = null
        private set

    private var proxyRequested = false
    private var candidates: List<BluetoothDevice> = emptyList()
    private var candidateIndex = 0
    private val nextCandidate = Runnable { tryNextCandidate() }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            val h = proxy as BluetoothHidDevice
            hid = h
            val sdp = BluetoothHidDeviceAppSdpSettings(
                "BT Shutter",
                "Bluetooth camera shutter remote",
                "BT Shutter",
                BluetoothHidDevice.SUBCLASS1_KEYBOARD,
                HID_DESCRIPTOR
            )
            h.registerApp(sdp, null, null, mainExecutor, hidCallback)
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hid = null
            registered = false
            host = null
            proxyRequested = false
            searching = false
            listener.onStatusChanged()
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@BluetoothController.registered = registered
            if (registered) {
                if (pluggedDevice != null) host = pluggedDevice
                startAutoConnect()
            } else {
                host = null
            }
            listener.onStatusChanged()
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            if (device == null) return
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    problem = null
                    searching = false
                    candidateIndex = candidates.size
                    ui.removeCallbacks(nextCandidate)
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putString(KEY_LAST_HOST, device.address).apply()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (host?.address == device.address) host = null
                    if (host == null && searching && candidateIndex < candidates.size) {
                        ui.postDelayed(nextCandidate, 300)
                    }
                }
            }
            listener.onStatusChanged()
        }
    }

    fun init() {
        if (hid == null && !proxyRequested && adapter != null) {
            proxyRequested = adapter.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
            if (!proxyRequested) {
                problem = context.getString(R.string.error_not_supported)
                listener.onStatusChanged()
            }
        }
    }

    fun release() {
        ui.removeCallbacksAndMessages(null)
        hid?.let { h ->
            try {
                host?.let { h.disconnect(it) }
                h.unregisterApp()
            } catch (e: Exception) {}
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, h)
        }
        hid = null
    }

    fun sendShutterPress() {
        val h = hid ?: return
        val d = host ?: return
        h.sendReport(d, REPORT_ID, byteArrayOf(0x01))
        ui.postDelayed({ hid?.sendReport(d, REPORT_ID, byteArrayOf(0x00)) }, 80)
    }

    fun startAutoConnect() {
        val bt = adapter ?: return
        val h = hid ?: return
        if (!registered || host != null) return

        val alreadyConnected = h.connectedDevices
        if (alreadyConnected.isNotEmpty()) {
            host = alreadyConnected[0]
            listener.onStatusChanged()
            return
        }

        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_HOST, null)
        candidates = (bt.bondedDevices ?: emptySet<BluetoothDevice>())
            .filter { d ->
                val major = d.bluetoothClass?.majorDeviceClass
                major == null ||
                        major == BluetoothClass.Device.Major.PHONE ||
                        major == BluetoothClass.Device.Major.COMPUTER
            }
            .sortedByDescending { it.address == last }
        candidateIndex = 0

        if (candidates.isEmpty()) {
            problem = context.getString(R.string.error_no_paired)
        }
        tryNextCandidate()
    }

    private fun tryNextCandidate() {
        ui.removeCallbacks(nextCandidate)
        if (host != null || candidateIndex >= candidates.size) {
            searching = false
            listener.onStatusChanged()
            return
        }
        val device = candidates[candidateIndex++]
        searching = true
        hid?.connect(device)
        ui.postDelayed(nextCandidate, CONNECT_TIMEOUT_MS)
        listener.onStatusChanged()
    }
}
