package com.branchdam.mobile

import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowLayoutInfo
import com.branchdam.mobile.ui.DevicePosture
import com.branchdam.mobile.ui.toDevicePosture
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class DevicePostureTest {

    private fun foldingFeature(
        state: FoldingFeature.State,
        orientation: FoldingFeature.Orientation
    ): FoldingFeature {
        return mock<FoldingFeature> {
            on { this.state } doReturn state
            on { this.orientation } doReturn orientation
        }
    }

    private fun windowLayoutInfo(vararg features: FoldingFeature): WindowLayoutInfo {
        return WindowLayoutInfo(features.toList())
    }

    @Test
    fun noFoldingFeatureMapsToFlat() {
        val layoutInfo = windowLayoutInfo()
        assertEquals(DevicePosture.Flat, layoutInfo.toDevicePosture())
    }

    @Test
    fun halfOpenedHorizontalMapsToTabletop() {
        val feature = foldingFeature(
            FoldingFeature.State.HALF_OPENED,
            FoldingFeature.Orientation.HORIZONTAL
        )
        val layoutInfo = windowLayoutInfo(feature)
        assertEquals(DevicePosture.Tabletop, layoutInfo.toDevicePosture())
    }

    @Test
    fun halfOpenedVerticalMapsToBook() {
        val feature = foldingFeature(
            FoldingFeature.State.HALF_OPENED,
            FoldingFeature.Orientation.VERTICAL
        )
        val layoutInfo = windowLayoutInfo(feature)
        assertEquals(DevicePosture.Book, layoutInfo.toDevicePosture())
    }

    @Test
    fun flatStateMapsToFlat() {
        val feature = foldingFeature(
            FoldingFeature.State.FLAT,
            FoldingFeature.Orientation.HORIZONTAL
        )
        val layoutInfo = windowLayoutInfo(feature)
        assertEquals(DevicePosture.Flat, layoutInfo.toDevicePosture())
    }
}
