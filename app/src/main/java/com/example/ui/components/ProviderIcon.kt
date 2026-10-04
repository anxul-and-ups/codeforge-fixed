package com.example.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R

/**
 * Picks the logo for a model / provider. The model name is checked first (so "anthropic/claude-…" on
 * OpenRouter still shows Claude), then the provider. Anything unknown (custom providers) uses the app icon.
 */
@DrawableRes
fun brandIconRes(providerId: String?, model: String?, providerName: String? = null): Int {
    val m = (model ?: "").lowercase()
    val id = (providerId ?: "").lowercase()
    val nm = (providerName ?: "").lowercase()
    return when {
        m.contains("claude") -> R.drawable.brand_claude
        m.contains("gpt") || Regex("(^|/)o\\d").containsMatchIn(m) -> R.drawable.brand_openai
        m.contains("gemini") -> R.drawable.brand_gemini
        m.contains("deepseek") -> R.drawable.brand_deepseek
        m.contains("kimi") || m.contains("moonshot") -> R.drawable.brand_kimi
        m.contains("grok") -> R.drawable.brand_grok
        m.contains("sonar") || m.contains("perplexity") -> R.drawable.brand_perplexity
        id == "anthropic" || nm.contains("anthropic") || nm.contains("claude") -> R.drawable.brand_claude
        id == "openai" || nm == "openai" -> R.drawable.brand_openai
        id == "gemini" || nm.contains("gemini") || nm.contains("google") -> R.drawable.brand_gemini
        id == "deepseek" || nm.contains("deepseek") -> R.drawable.brand_deepseek
        id == "kimi" || nm.contains("kimi") || nm.contains("moonshot") -> R.drawable.brand_kimi
        id == "xai" || nm.contains("grok") || nm.contains("xai") -> R.drawable.brand_grok
        id == "perplexity" || nm.contains("perplexity") -> R.drawable.brand_perplexity
        else -> R.drawable.brand_app
    }
}

@Composable
fun ProviderIcon(
    providerId: String?,
    model: String?,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    providerName: String? = null
) {
    Image(
        painter = painterResource(id = brandIconRes(providerId, model, providerName)),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.24f))
    )
}
