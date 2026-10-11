package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AppColors

/** Text with a light sweeping across it, like the "Thinking…" line of AI chat apps. */
@Composable
fun ShimmerText(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: Int = 14
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val shift by transition.animateFloat(
        initialValue = -300f,
        targetValue = 900f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1600, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer_shift"
    )
    val base = AppColors.textMuted
    val light = AppColors.textPrimary
    val brush = Brush.linearGradient(
        colors = listOf(base, light, base),
        start = Offset(shift, 0f),
        end = Offset(shift + 260f, 0f)
    )
    Text(
        text = text,
        modifier = modifier,
        style = TextStyle(brush = brush, fontSize = fontSize.sp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/** Spinner + shimmering status text. Generic statuses all read "Thinking…". */
@Composable
fun ThinkingRow(status: String, modifier: Modifier = Modifier) {
    val generic = status.isBlank() || status.startsWith("Starting") || status.startsWith("Thinking") || status.startsWith("Working")
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = AppColors.accent)
        Spacer(modifier = Modifier.width(10.dp))
        ShimmerText(text = if (generic) "Thinking…" else status, fontSize = 14)
    }
}
