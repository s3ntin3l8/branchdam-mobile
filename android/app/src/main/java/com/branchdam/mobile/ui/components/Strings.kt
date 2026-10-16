package com.branchdam.mobile.ui.components

import androidx.annotation.PluralsRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * Formatted quantity string (`<plurals>`) for Compose, re-read when the
 * configuration (locale) changes. Same lookup as the platform's
 * `Resources.getQuantityString`; kept here so screens don't depend on an
 * experimental Compose API for plurals.
 */
@Composable
@ReadOnlyComposable
fun quantityStringResource(@PluralsRes id: Int, count: Int, vararg formatArgs: Any): String {
    LocalConfiguration.current
    return LocalContext.current.resources.getQuantityString(id, count, *formatArgs)
}
