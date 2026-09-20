package com.example.btshutter

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView

/**
 * This phone acts as a Bluetooth "selfie remote" (a HID device that sends the Volume Up key).
 * The other phone needs no app: its camera treats Volume Up from a Bluetooth device as the shutter.
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class MainActivity : Activity() {

    private companion object {
        const val REQ_PERMISSION = 1
        const val REQ_ENABLE_BT = 2
        const val REPORT_ID = 1
        const val CONNECT_TIMEOUT_MS = 6000L
        const val PREFS = "btshutter"
        const val KEY_LAST_HOST = "last_host"

        val BG = Color.parseColor("#121212")
        val GREEN = Color.parseColor("#66BB6A")
        val AMBER = Color.parseColor("#FFB74D")

        // HID report descriptor: Consumer Control, report ID 1, one byte:
        // bit 0 = Volume Up, bit 1 = Volume Down, bits 2-7 = padding.
        val HID_DESCRIPTOR: ByteArray = intArrayOf(
            0x05, 0x0C,       // Usage Page (Consumer)
            0x09, 0x01,       // Usage (Consumer Control)
            0xA1, 0x01,       // Collection (Application)
            0x85, REPORT_ID,  //   Report ID
            0x15, 0x00,       //   Logical Minimum (0)
            0x25, 0x01,       //   Logical Maximum (1)
            0x75, 0x01,       //   Report Size (1 bit)
            0x95, 0x02,       //   Report Count (2)
            0x09, 0xE9,       //   Usage (Volume Increment)
            0x09, 0xEA,       //   Usage (Volume Decrement)
            0x81, 0x02,       //   Input (Data, Variable, Absolute)
            0x95, 0x06,       //   Report Count (6) - padding
            0x81, 0x03,       //   Input (Constant)
            0xC0              // End Collection
        ).map { it.toByte() }.toByteArray()
    }

    private val ui = Handler(Looper.getMainLooper())
    private var adapter: BluetoothAdapter? = null
    private var hid: BluetoothHidDevice? = null
    private var proxyRequested = false
    private var registered = false
    private var host: BluetoothDevice? = null
    private var problem: String? = null
    private var searching = false
    private var askedPermission = false
    private var askedEnable = false
    private var candidates: List<BluetoothDevice> = emptyList()
    private var candidateIndex = 0

    private lateinit var statusView: TextView
    private lateinit var hintView: TextView
    private lateinit var shutter: Button

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
            updateUi()
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@MainActivity.registered = registered
            if (registered) {
                if (pluggedDevice != null) host = pluggedDevice
                startAutoConnect()
            } else {
                host = null
            }
            updateUi()
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
                    getSharedPreferences(PREFS, MODE_PRIVATE)
                        .edit().putString(KEY_LAST_HOST, device.address).apply()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (host?.address == device.address) host = null
                    if (host == null && searching && candidateIndex < candidates.size) {
                        ui.postDelayed(nextCandidate, 300)
                    }
                }
            }
            updateUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        adapter = getSystemService(BluetoothManager::class.java)?.adapter
        buildUi()
        updateUi()
    }

    override fun onResume() {
        super.onResume()
        ensureReady()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        val h = hid
        if (h != null) {
            try {
                host?.let { h.disconnect(it) }
                h.unregisterApp()
            } catch (e: Exception) {
                // ignore: we're shutting down anyway
            }
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, h)
        }
        hid = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(BG)

        // Message at the top of the screen
        statusView = TextView(this).apply {
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        root.addView(
            statusView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )

        hintView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#9E9E9E"))
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(24), dp(28), dp(36))
        }
        root.addView(
            hintView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )

        // The one and only button: the shutter
        shutter = Button(this).apply {
            text = "SHUTTER"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            stateListAnimator = null
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E53935"))
                setStroke(dp(6), Color.WHITE)
            }
            setOnClickListener { onShutterPressed() }
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN ->
                        v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(60).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.animate().scaleX(1f).scaleY(1f).setDuration(60).start()
                }
                false
            }
        }
        root.addView(shutter, FrameLayout.LayoutParams(dp(220), dp(220), Gravity.CENTER))

        setContentView(root)
    }

    private fun nameOf(d: BluetoothDevice): String {
        val alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) d.alias else null
        return alias ?: d.name ?: d.address
    }

    private fun updateUi() {
        val d = host
        if (d != null) {
            statusView.text = "Device is connected with '${nameOf(d)}'"
            statusView.setTextColor(GREEN)
            hintView.text = "Open the camera on the other device, then tap the button."
            shutter.alpha = 1f
        } else {
            statusView.text = problem
                ?: if (searching) "Connecting to your other device…" else "Not connected to any device"
            statusView.setTextColor(AMBER)
            hintView.text = "Pair the two devices in Bluetooth settings first, then tap the button to reconnect."
            shutter.alpha = 0.45f
        }
    }

    // ---------------------------------------------------------------- Shutter

    private fun onShutterPressed() {
        val h = hid
        val d = host
        if (h == null || d == null) {
            // Not connected: the button doubles as "retry"
            askedPermission = false
            askedEnable = false
            ensureReady()
            return
        }
        shutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        h.sendReport(d, REPORT_ID, byteArrayOf(0x01))                          // key down
        ui.postDelayed({ hid?.sendReport(d, REPORT_ID, byteArrayOf(0x00)) }, 80) // key up
    }

    // ---------------------------------------------------------------- Bluetooth plumbing

    private fun ensureReady() {
        val bt = adapter
        if (bt == null) {
            problem = "This device has no Bluetooth"
            updateUi()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            problem = "Bluetooth permission is needed"
            if (!askedPermission) {
                askedPermission = true
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_PERMISSION)
            }
            updateUi()
            return
        }

        if (!bt.isEnabled) {
            problem = "Bluetooth is turned off"
            if (!askedEnable) {
                askedEnable = true
                startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE_BT)
            }
            updateUi()
            return
        }

        problem = null
        if (hid == null) {
            if (!proxyRequested) {
                proxyRequested = bt.getProfileProxy(this, serviceListener, BluetoothProfile.HID_DEVICE)
                if (!proxyRequested) {
                    problem = "This phone doesn't support acting as a Bluetooth remote"
                }
            }
        } else if (registered && host == null) {
            startAutoConnect()
        }
        updateUi()
    }

    private fun startAutoConnect() {
        val bt = adapter ?: return
        val h = hid ?: return
        if (!registered || host != null) return

        val alreadyConnected = h.connectedDevices
        if (alreadyConnected.isNotEmpty()) {
            host = alreadyConnected[0]
            updateUi()
            return
        }

        val last = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LAST_HOST, null)
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
            problem = "No paired device found"
        }
        tryNextCandidate()
    }

    private fun tryNextCandidate() {
        ui.removeCallbacks(nextCandidate)
        if (host != null || candidateIndex >= candidates.size) {
            searching = false
            updateUi()
            return
        }
        val device = candidates[candidateIndex++]
        searching = true
        hid?.connect(device)
        ui.postDelayed(nextCandidate, CONNECT_TIMEOUT_MS)
        updateUi()
    }
}

