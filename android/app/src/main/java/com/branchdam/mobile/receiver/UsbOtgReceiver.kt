package com.branchdam.mobile.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Environment
import com.branchdam.mobile.otg.OtgIngestManager
import java.io.File

class UsbOtgReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        when (action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                if (device != null && isMassStorageDevice(device)) {
                    val label = getDeviceLabel(device)
                    // Check external storage or fallback to standard OTG / SD mount paths
                    val otgMount = findOtgMountDirectory() ?: File(Environment.getExternalStorageDirectory(), "DCIM")
                    OtgIngestManager.getInstance(context).onCardDetected(label, otgMount)
                }
            }

            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                if (device != null && isMassStorageDevice(device)) {
                    OtgIngestManager.getInstance(context).reset()
                }
            }
        }
    }

    companion object {
        fun isMassStorageDevice(device: UsbDevice): Boolean {
            if (device.deviceClass == UsbConstants.USB_CLASS_MASS_STORAGE) {
                return true
            }
            for (i in 0 until device.interfaceCount) {
                val usbInterface = device.getInterface(i)
                if (usbInterface.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE) {
                    return true
                }
            }
            return false
        }

        fun getDeviceLabel(device: UsbDevice): String {
            val productName = device.productName?.takeIf { it.isNotBlank() }
            val manufacturer = device.manufacturerName?.takeIf { it.isNotBlank() }
            return when {
                productName != null && manufacturer != null -> "$manufacturer $productName"
                productName != null -> productName
                manufacturer != null -> "$manufacturer Card Reader"
                else -> "USB-C SD Card"
            }
        }

        fun findOtgMountDirectory(): File? {
            val possiblePaths = listOf(
                "/mnt/media_rw",
                "/storage",
                "/mnt/usbhost"
            )
            for (path in possiblePaths) {
                val dir = File(path)
                if (dir.exists() && dir.isDirectory) {
                    val subDirs = dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith("emulated") && !it.name.startsWith("self") }
                    if (!subDirs.isNullOrEmpty()) {
                        return subDirs.first()
                    }
                }
            }
            return null
        }
    }
}
