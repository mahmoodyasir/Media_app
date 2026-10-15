package com.mediaapp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Scrolls option chips and fields only. Sliders stay outside this column: a
 * vertical scroll is a drag parent, and a horizontal slider yields to it unless
 * the finger stays within 30 degrees of horizontal.
 */
@Composable
fun ScrollingOptions(
    modifier: Modifier = Modifier,
    maxHeight: Dp = 240.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState()),
        content = content,
    )
}
