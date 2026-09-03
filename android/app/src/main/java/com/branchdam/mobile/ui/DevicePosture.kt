package com.branchdam.mobile.ui

import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowLayoutInfo

sealed class DevicePosture {
    data object Flat : DevicePosture()
    data object Tabletop : DevicePosture()
    data object Book : DevicePosture()
}

fun WindowLayoutInfo.toDevicePosture(): DevicePosture {
    val foldingFeature = displayFeatures
        .filterIsInstance<FoldingFeature>()
        .firstOrNull() ?: return DevicePosture.Flat

    return when {
        foldingFeature.state == FoldingFeature.State.HALF_OPENED &&
            foldingFeature.orientation == FoldingFeature.Orientation.HORIZONTAL -> DevicePosture.Tabletop
        foldingFeature.state == FoldingFeature.State.HALF_OPENED &&
            foldingFeature.orientation == FoldingFeature.Orientation.VERTICAL -> DevicePosture.Book
        else -> DevicePosture.Flat
    }
}
