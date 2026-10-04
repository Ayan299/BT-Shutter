package com.example.btshutter

import android.graphics.Color

object HidConstants {
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
