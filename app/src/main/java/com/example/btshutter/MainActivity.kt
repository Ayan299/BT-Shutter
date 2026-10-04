package com.example.btshutter

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.StateListAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import com.example.btshutter.HidConstants.AMBER
import com.example.btshutter.HidConstants.BG
import com.example.btshutter.HidConstants.GREEN

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class MainActivity : Activity() {

    private companion object {
        const val REQ_PERMISSION = 1
        const val REQ_ENABLE_BT = 2
    }

    private var adapter: BluetoothAdapter? = null
    private lateinit var controller: BluetoothController

    private var askedPermission = false
    private var askedEnable = false

    private lateinit var statusView: TextView
    private lateinit var hintView: TextView
    private lateinit var shutter: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        
        adapter = getSystemService(BluetoothManager::class.java)?.adapter
        controller = BluetoothController(this, adapter, mainExecutor, object : BluetoothController.Callback {
            override fun onStatusChanged() {
                updateUi()
            }
        })

        buildUi()
        updateUi()
    }

    override fun onResume() {
        super.onResume()
        ensureReady()
    }

    override fun onDestroy() {
        controller.release()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(BG)

        statusView = TextView(this).apply {
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        root.addView(statusView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        hintView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#9E9E9E"))
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(24), dp(28), dp(36))
        }
        root.addView(hintView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        shutter = Button(this).apply {
            text = getString(R.string.shutter_label)
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            
            // Modern animation using StateListAnimator
            stateListAnimator = StateListAnimator().apply {
                val down = ObjectAnimator.ofPropertyValuesHolder(
                    this@apply,
                    PropertyValuesHolder.ofFloat("scaleX", 0.92f),
                    PropertyValuesHolder.ofFloat("scaleY", 0.92f)
                ).setDuration(60)
                val up = ObjectAnimator.ofPropertyValuesHolder(
                    this@apply,
                    PropertyValuesHolder.ofFloat("scaleX", 1f),
                    PropertyValuesHolder.ofFloat("scaleY", 1f)
                ).setDuration(60)
                addState(intArrayOf(android.R.attr.state_pressed), down)
                addState(intArrayOf(), up)
            }
            
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E53935"))
                setStroke(dp(6), Color.WHITE)
            }
            setOnClickListener { onShutterPressed() }
        }
        root.addView(shutter, FrameLayout.LayoutParams(dp(220), dp(220), Gravity.CENTER))

        setContentView(root)
    }

    private fun nameOf(d: BluetoothDevice): String {
        val alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) d.alias else null
        return alias ?: d.name ?: d.address
    }

    private fun updateUi() {
        val d = controller.host
        if (d != null) {
            statusView.text = getString(R.string.status_connected, nameOf(d))
            statusView.setTextColor(GREEN)
            hintView.text = getString(R.string.hint_ready)
            shutter.alpha = 1f
        } else {
            statusView.text = controller.problem
                ?: if (controller.searching) getString(R.string.status_connecting) else getString(R.string.status_disconnected)
            statusView.setTextColor(AMBER)
            hintView.text = getString(R.string.hint_pair)
            shutter.alpha = 0.45f
        }
    }

    private fun onShutterPressed() {
        if (controller.hid == null || controller.host == null) {
            askedPermission = false
            askedEnable = false
            ensureReady()
            return
        }
        shutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        controller.sendShutterPress()
    }

    private fun ensureReady() {
        val bt = adapter
        if (bt == null) {
            // Problem managed locally for permission/enable state
            statusView.text = getString(R.string.error_no_bt)
            statusView.setTextColor(AMBER)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            statusView.text = getString(R.string.error_permission)
            statusView.setTextColor(AMBER)
            if (!askedPermission) {
                askedPermission = true
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_PERMISSION)
            }
            return
        }

        if (!bt.isEnabled) {
            statusView.text = getString(R.string.error_bt_off)
            statusView.setTextColor(AMBER)
            if (!askedEnable) {
                askedEnable = true
                startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE_BT)
            }
            return
        }

        controller.init()
        if (controller.registered && controller.host == null) {
            controller.startAutoConnect()
        }
        updateUi()
    }
}
