package com.penpal.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.penpal.core.ai.ModelStatus

@Composable
fun ModelStatusIndicator(
    isReady: Boolean,
    isLoading: Boolean = false,
    isUnloading: Boolean = false,
    modelStatus: ModelStatus,
    onToggleModel: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val (dotColor, statusText) = when {
        isReady -> Color(0xFF4CAF50) to "ON"
        isLoading -> Color(0xFFFFC107) to "Loading..."
        isUnloading -> Color(0xFFFFC107) to "Unloading..."
        modelStatus == ModelStatus.ERROR -> Color(0xFFF44336) to "ERR"
        else -> Color(0xFF9E9E9E) to "OFF"
    }

    val isClickable = !isLoading && !isUnloading && onToggleModel != null

    Row(
        modifier = modifier
            .then(
                if (isClickable) Modifier.clickable { onToggleModel() }
                else Modifier
            )
            .background(
                color = if (isReady) Color(0xFF4CAF50).copy(alpha = 0.2f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                shape = MaterialTheme.shapes.small
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelMedium,
            color = if (isReady) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ModelStatusIndicatorSmall(
    isReady: Boolean,
    isLoading: Boolean = false,
    isUnloading: Boolean = false,
    modifier: Modifier = Modifier
) {
    val dotColor = when {
        isReady -> Color(0xFF4CAF50)
        isLoading || isUnloading -> Color(0xFFFFC107)
        else -> Color(0xFF9E9E9E)
    }

    Box(
        modifier = modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(dotColor)
    )
}