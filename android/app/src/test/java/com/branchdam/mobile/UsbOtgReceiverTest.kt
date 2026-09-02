package com.branchdam.mobile

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import com.branchdam.mobile.receiver.UsbOtgReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
/**
 * Tests for [UsbOtgReceiver]'s companion-object helpers:
 * isMassStorageDevice and getDeviceLabel. The full onReceive path
 * requires UsbManager + real Android runtime, so we test the pure
 * helpers instead.
 *
 * Requires mockito-kotlin on the test classpath (added by PR-2).
 */
class UsbOtgReceiverTest {

    @Test
    fun testIsMassStorageDevice_TrueForMassStorageClass() {
        val device = mock<UsbDevice>()
        whenever(device.deviceClass).thenReturn(UsbConstants.USB_CLASS_MASS_STORAGE)
        assertTrue(UsbOtgReceiver.isMassStorageDevice(device))
    }

    @Test
    fun testIsMassStorageDevice_TrueForMassStorageInterface() {
        val device = mock<UsbDevice>()
        val usbInterface = mock<UsbInterface>()
        whenever(device.deviceClass).thenReturn(UsbConstants.USB_CLASS_HUB)
        whenever(device.interfaceCount).thenReturn(1)
        whenever(device.getInterface(0)).thenReturn(usbInterface)
        whenever(usbInterface.interfaceClass).thenReturn(UsbConstants.USB_CLASS_MASS_STORAGE)
        assertTrue(UsbOtgReceiver.isMassStorageDevice(device))
    }

    @Test
    fun testGetDeviceLabel_PrefersProductAndManufacturer() {
        val device = mock<UsbDevice>()
        whenever(device.productName).thenReturn("Card Reader")
        whenever(device.manufacturerName).thenReturn("SanDisk")
        assertEquals("SanDisk Card Reader", UsbOtgReceiver.getDeviceLabel(device))
    }

    @Test
    fun testGetDeviceLabel_FallsBackToProductOnly() {
        val device = mock<UsbDevice>()
        whenever(device.productName).thenReturn("Card Reader")
        whenever(device.manufacturerName).thenReturn(null)
        assertEquals("Card Reader", UsbOtgReceiver.getDeviceLabel(device))
    }

    @Test
    fun testGetDeviceLabel_FallsBackToManufacturerCardReader() {
        val device = mock<UsbDevice>()
        whenever(device.productName).thenReturn(null)
        whenever(device.manufacturerName).thenReturn("SanDisk")
        assertEquals("SanDisk Card Reader", UsbOtgReceiver.getDeviceLabel(device))
    }

    @Test
    fun testGetDeviceLabel_FallsBackToUSBCSDCard() {
        val device = mock<UsbDevice>()
        whenever(device.productName).thenReturn(null)
        whenever(device.manufacturerName).thenReturn(null)
        assertEquals("USB-C SD Card", UsbOtgReceiver.getDeviceLabel(device))
    }

    @Test
    fun testGetDeviceLabel_TreatsBlankAsNull() {
        val device = mock<UsbDevice>()
        whenever(device.productName).thenReturn("  ")
        whenever(device.manufacturerName).thenReturn("SanDisk")
        assertEquals("SanDisk Card Reader", UsbOtgReceiver.getDeviceLabel(device))
    }
}
