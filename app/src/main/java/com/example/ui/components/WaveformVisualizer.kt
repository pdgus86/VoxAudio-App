package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.RecordingRed
import kotlin.math.sin

@Composable
fun LiveRecordingWaveform(
    amplitudes: List<Float>,
    isRecording: Boolean,
    isPaused: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = 100.dp
) {
    val barColor = when {
        isPaused -> Color(0xFFFFB74D)
        isRecording -> RecordingRed
        else -> NeonCyan
    }

    val glowGradient = Brush.verticalGradient(
        colors = listOf(
            barColor.copy(alpha = 0.9f),
            if (isRecording && !isPaused) NeonPurple.copy(alpha = 0.7f) else barColor.copy(alpha = 0.4f)
        )
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val width = size.width
        val canvasHeight = size.height
        val centerY = canvasHeight / 2f
        val barCount = 45
        val barWidth = (width / barCount) * 0.65f
        val spacing = (width / barCount) * 0.35f

        val dataSize = amplitudes.size
        for (i in 0 until barCount) {
            val amp = if (dataSize > 0) {
                val index = (dataSize - barCount + i).coerceIn(0, dataSize - 1)
                amplitudes.getOrNull(index) ?: 0.05f
            } else {
                if (isRecording && !isPaused) {
                    (0.1f + 0.08f * sin((i * 0.4f).toDouble()).toFloat())
                } else 0.05f
            }

            val barHeight = (amp * (canvasHeight * 0.85f)).coerceAtLeast(6f)
            val left = i * (barWidth + spacing)
            val top = centerY - barHeight / 2f

            drawRoundRect(
                brush = glowGradient,
                topLeft = Offset(left, top),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

@Composable
fun PlaybackWaveform(
    progressPercent: Float, // 0.0f to 1.0f
    modifier: Modifier = Modifier,
    height: Dp = 64.dp,
    seed: Int = 42,
    onSeek: ((Float) -> Unit)? = null
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .then(
                if (onSeek != null) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val percent = (offset.x / size.width).coerceIn(0f, 1f)
                            onSeek(percent)
                        }
                    }
                } else Modifier
            )
    ) {
        val width = size.width
        val canvasHeight = size.height
        val centerY = canvasHeight / 2f
        val barCount = 50
        val barWidth = (width / barCount) * 0.6f
        val spacing = (width / barCount) * 0.4f

        for (i in 0 until barCount) {
            val fraction = i.toFloat() / barCount.toFloat()
            // Deterministic waveform pattern derived from index and seed
            val pseudoRandom = (sin((i * 0.7f + seed).toDouble()) * 0.45f + 0.55f).toFloat()
            val barHeight = (pseudoRandom * (canvasHeight * 0.75f)).coerceAtLeast(8f)

            val left = i * (barWidth + spacing)
            val top = centerY - barHeight / 2f

            val isPlayed = fraction <= progressPercent
            val barBrush = if (isPlayed) {
                Brush.verticalGradient(
                    colors = listOf(NeonCyan, NeonPurple)
                )
            } else {
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF3B3759), Color(0xFF26233B))
                )
            }

            drawRoundRect(
                brush = barBrush,
                topLeft = Offset(left, top),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}
