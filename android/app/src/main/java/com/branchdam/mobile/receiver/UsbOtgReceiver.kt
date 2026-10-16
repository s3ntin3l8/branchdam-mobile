package com.branchdam.mobile.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Environment
import com.branchdam.mobile.otg.OtgIngestManager
import androidx.core.content.IntentCompat
import java.io.File
import java.io.IOException

class UsbOtgReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        when (action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                if (device != null && isMassStorageDevice(device)) {
                    val label = getDeviceLabel(device)
                    val manager = OtgIngestManager.getInstance(context)
                    // Use the card's own mount when it is readable. Since
                    // scoped storage it usually is not; then the user picks
                    // the card in the system folder picker. Never fall back
                    // to the phone's own storage.
                    val otgMount = findOtgMountDirectory()
                    if (otgMount != null) {
                        manager.onCardDetected(label, otgMount)
                    } else {
                        manager.onCardNeedsAccess(label)
                    }
                }
            }

            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
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

        private val DEFAULT_MOUNT_PARENTS = listOf(
            "/mnt/media_rw",
            "/storage",
            "/mnt/usbhost",
        )

        /**
         * Finds a readable camera-card volume under [mountParents]: a
         * directory with a readable `DCIM` folder that is not the phone's
         * own shared storage ([primaryStorage], `/storage/emulated/...`,
         * `/storage/self/...`). Returns the volume root so non-DCIM camera
         * folders (e.g. `PRIVATE/M4ROOT` video) are scanned too, or null
         * when no such volume is readable.
         */
        fun findOtgMountDirectory(
            mountParents: List<File> = DEFAULT_MOUNT_PARENTS.map(::File),
            primaryStorage: File? = primaryStorageOrNull(),
        ): File? {
            val primary = primaryStorage?.let(::canonicalPathOrNull)
            for (parent in mountParents) {
                val volumes = parent.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: continue
                for (volume in volumes) {
                    if (volume.name.startsWith("emulated") || volume.name.startsWith("self")) continue
                    val path = canonicalPathOrNull(volume) ?: continue
                    if (primary != null && (path == primary || primary.startsWith("$path/") || path.startsWith("$primary/"))) {
                        continue
                    }
                    val dcim = File(volume, "DCIM")
                    if (dcim.isDirectory && dcim.listFiles() != null) return volume
                }
            }
            return null
        }

        private fun primaryStorageOrNull(): File? = try {
            Environment.getExternalStorageDirectory()
        } catch (e: RuntimeException) {
            null
        }

        private fun canonicalPathOrNull(file: File): String? = try {
            file.canonicalPath
        } catch (e: IOException) {
            null
        }
    }
}
