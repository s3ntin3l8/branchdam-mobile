package com.branchdam.mobile

import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowLayoutInfo
import com.branchdam.mobile.ui.DevicePosture
import com.branchdam.mobile.ui.toDevicePosture
import org.junit.Assert.assertEquals
import org.junit.Test

class DevicePostureTest {

    private fun foldingFeature(
        state: Int,
        orientation: Int
    ): FoldingFeature {
        val constructor = FoldingFeature::class.java.getDeclaredConstructor(
            android.graphics.Rect::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )
        constructor.isAccessible = true
        return constructor.newInstance(android.graphics.Rect(0, 0, 1000, 500), state, orientation)
    }

    private fun windowLayoutInfo(vararg features: Any): WindowLayoutInfo {
        val constructor = WindowLayoutInfo::class.java.getDeclaredConstructor(
            java.util.List::class.java
        )
        constructor.isAccessible = true
        val featuresList = features.filterIsInstance<FoldingFeature>()
        return constructor.newInstance(featuresList)
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
